package com.kama.jchatmind.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.agent.AgentEventPublisher;
import com.kama.jchatmind.mcp.adapter.McpToolCallbackAdapter;
import com.kama.jchatmind.mcp.adapter.SpringAiExternalMcpClientAdapter;
import com.kama.jchatmind.mcp.audit.McpToolAuditLogger;
import com.kama.jchatmind.mcp.config.ExternalMcpServerProperties;
import com.kama.jchatmind.mcp.config.ExternalMcpServerType;
import com.kama.jchatmind.mcp.config.ExternalMcpToolProperties;
import com.kama.jchatmind.mcp.config.McpClientProperties;
import com.kama.jchatmind.mcp.registry.ExternalMcpDiscoveredTool;
import com.kama.jchatmind.mcp.registry.ExternalMcpServerRegistration;
import com.kama.jchatmind.mcp.registry.ExternalMcpServerRegistry;
import com.kama.jchatmind.mcp.registry.ExternalMcpToolRegistration;
import com.kama.jchatmind.mcp.registry.ExternalMcpToolRegistry;
import com.kama.jchatmind.mcp.safety.McpExternalToolPolicy;
import com.kama.jchatmind.mcp.safety.McpToolRiskLevel;
import com.kama.jchatmind.model.entity.ToolCallLog;
import com.kama.jchatmind.service.AgentTaskLogService;
import com.kama.jchatmind.service.impl.ToolExecutionServiceImpl;
import com.kama.jchatmind.tool.ToolDefinition;
import com.kama.jchatmind.tool.ToolExecutionContext;
import com.kama.jchatmind.tool.ToolExecutionRecord;
import com.kama.jchatmind.tool.ToolFailureClassifier;
import com.kama.jchatmind.tool.ToolPolicyRejectedException;
import com.kama.jchatmind.tool.ToolRegistry;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class ExternalMcpRealServerTestSupport {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ExternalMcpRealServerTestSupport() {
    }

    static RealMcpResult run(RealMcpCase defaults) {
        RealMcpCase testCase = defaults.withEnvironmentOverrides();
        assertTrue(testCase.riskLevel() == McpToolRiskLevel.READ_ONLY
                        || testCase.riskLevel() == McpToolRiskLevel.NETWORK_READ,
                "real MCP manual tests only allow READ_ONLY or NETWORK_READ tools");

        ServerParameters parameters = ServerParameters.builder(testCase.command())
                .args(testCase.args())
                .env(testCase.childEnv())
                .build();

        try (McpSyncClient client = McpClient.sync(new StdioClientTransport(
                        parameters, new JacksonMcpJsonMapper(OBJECT_MAPPER)))
                .clientInfo(new McpSchema.Implementation("jchatmind-mcp-manual-test", "1.0.0"))
                .requestTimeout(Duration.ofSeconds(45))
                .initializationTimeout(Duration.ofSeconds(45))
                .build()) {
            client.initialize();

            SpringAiExternalMcpClientAdapter springAiAdapter = new SpringAiExternalMcpClientAdapter(
                    List.of(client), OBJECT_MAPPER);
            ExternalMcpServerRegistration discoveryServer = ExternalMcpServerRegistration.builder()
                    .name(testCase.serverName())
                    .type(testCase.serverType())
                    .transport("stdio")
                    .command(testCase.command())
                    .build();
            List<ExternalMcpDiscoveredTool> discovered = springAiAdapter.discoverTools(discoveryServer);
            List<String> discoveredToolNames = discovered.stream()
                    .map(ExternalMcpDiscoveredTool::getName)
                    .toList();
            System.out.println("real MCP discovered tools: serverType=" + testCase.serverType()
                    + ", serverName=" + testCase.serverName()
                    + ", tools=" + discoveredToolNames);

            assertFalse(discoveredToolNames.isEmpty(), "real MCP server did not return tools");
            assertTrue(discoveredToolNames.contains(testCase.toolName()),
                    "allowed tool was not discovered: " + testCase.toolName());

            String deniedToolName = chooseDeniedTool(testCase.deniedToolName(), testCase.toolName(), discoveredToolNames);
            McpClientProperties properties = propertiesFor(testCase, deniedToolName);
            ExternalMcpToolRegistry registry = new ExternalMcpToolRegistry(
                    new ExternalMcpServerRegistry(properties),
                    springAiAdapter,
                    new McpExternalToolPolicy());
            McpToolAuditLogger auditLogger = new RecordingAuditLogger();
            McpToolCallbackAdapter callbackAdapter = new McpToolCallbackAdapter(
                    registry, springAiAdapter, auditLogger);

            String exposedName = exposedName(testCase.serverName(), testCase.toolName());
            String deniedExposedName = deniedToolName == null ? null : exposedName(testCase.serverName(), deniedToolName);

            List<ExternalMcpToolRegistration> registeredTools = registry.registeredTools();
            List<ExternalMcpToolRegistration> exposedTools = registry.exposedTools();
            assertTrue(registeredTools.stream().anyMatch(tool -> tool.getToolName().equals(testCase.toolName())
                    && tool.getRiskLevel() == testCase.riskLevel()
                    && tool.isAutoInvokeAllowed()));
            assertEquals(List.of(exposedName), callbackAdapter.exposedToolNames());
            assertEquals(1, exposedTools.size());
            if (deniedToolName != null) {
                assertTrue(registeredTools.stream().anyMatch(tool -> tool.getToolName().equals(deniedToolName)
                        && tool.getRiskLevel() == McpToolRiskLevel.WRITE_OPERATION));
                assertFalse(callbackAdapter.exposedToolNames().contains(deniedExposedName));
            }

            List<ToolCallback> callbacks = callbackAdapter.toolCallbacks();
            assertEquals(1, callbacks.size());
            assertEquals(exposedName, callbacks.get(0).getToolDefinition().name());

            AgentTaskLogService logService = mock(AgentTaskLogService.class);
            when(logService.startToolCall(
                    eq("task-real"), eq("step-real"), eq(exposedName), eq(exposedName),
                    eq("call-real"), eq(testCase.argumentsJson()), eq(false)))
                    .thenReturn(ToolCallLog.builder().id("log-real").build());
            ToolExecutionServiceImpl executionService = new ToolExecutionServiceImpl(
                    new NoLocalToolRegistry(),
                    logService,
                    mock(AgentEventPublisher.class),
                    new ToolFailureClassifier(),
                    provider(registry),
                    provider(auditLogger));

            ToolExecutionContext context = ToolExecutionContext.builder()
                    .taskId("task-real")
                    .stepId("step-real")
                    .sessionId("session-real")
                    .runtimeToolNames(callbackAdapter.exposedToolNames())
                    .build();
            ToolExecutionRecord record = executionService.beforeToolCall(
                    context, new AssistantMessage.ToolCall("call-real", "function", exposedName,
                            testCase.argumentsJson()));
            String result = callbacks.get(0).call(testCase.argumentsJson());
            executionService.afterToolSuccess(context, record, result);

            assertNotNull(result);
            assertFalse(result.isBlank(), "real MCP tool returned an empty result");
            verify(logService).finishToolCall(eq("log-real"), eq(result), anyLong(), anyBoolean());

            if (deniedToolName != null) {
                when(logService.startAndFailToolCall(
                        eq("task-real"), eq("step-real"), eq(deniedExposedName), eq(deniedExposedName),
                        eq("call-denied"), eq("{}"), eq(false),
                        eq("External MCP tool is not allowed in current agent runtime: " + deniedExposedName),
                        eq(0L), eq(AgentTaskLogService.ERROR_TYPE_POLICY_REJECTED), eq(true)))
                        .thenReturn(ToolCallLog.builder().id("log-denied").build());
                ToolExecutionContext deniedContext = ToolExecutionContext.builder()
                        .taskId("task-real")
                        .stepId("step-real")
                        .sessionId("session-real")
                        .runtimeToolNames(List.of(exposedName, deniedExposedName))
                        .build();
                assertThrows(ToolPolicyRejectedException.class,
                        () -> executionService.beforeToolCall(deniedContext,
                                new AssistantMessage.ToolCall("call-denied", "function", deniedExposedName, "{}")));
            }

            RecordingAuditLogger recordingAuditLogger = (RecordingAuditLogger) auditLogger;
            assertTrue(recordingAuditLogger.events().stream().anyMatch(event -> event.equals("start:" + testCase.toolName())));
            assertTrue(recordingAuditLogger.events().stream().anyMatch(event -> event.startsWith("success:" + testCase.toolName())));
            if (deniedToolName != null) {
                assertTrue(recordingAuditLogger.events().stream()
                        .anyMatch(event -> event.equals("denied:" + deniedToolName + ":MCP_TOOL_POLICY_REJECTED")));
            }

            System.out.println("real MCP invocation ok: serverType=" + testCase.serverType()
                    + ", serverName=" + testCase.serverName()
                    + ", allowedTool=" + testCase.toolName()
                    + ", deniedTool=" + deniedToolName
                    + ", resultLength=" + result.length());
            return new RealMcpResult(testCase.serverType(), testCase.serverName(), discoveredToolNames,
                    testCase.toolName(), deniedToolName, result.length(), recordingAuditLogger.events());
        }
    }

    private static McpClientProperties propertiesFor(RealMcpCase testCase, String deniedToolName) {
        McpClientProperties properties = new McpClientProperties();
        properties.setMaxResultLength(testCase.maxResultLength());
        properties.setAuditEnabled(true);
        properties.setServers(List.of(server(testCase, deniedToolName)));
        return properties;
    }

    private static ExternalMcpServerProperties server(RealMcpCase testCase, String deniedToolName) {
        List<ExternalMcpToolProperties> tools = new ArrayList<>();
        tools.add(tool(testCase.toolName(), testCase.riskLevel(), true));
        if (deniedToolName != null) {
            tools.add(tool(deniedToolName, McpToolRiskLevel.WRITE_OPERATION, true));
        }
        ExternalMcpServerProperties server = new ExternalMcpServerProperties();
        server.setName(testCase.serverName());
        server.setType(testCase.serverType());
        server.setTransport("stdio");
        server.setCommand(testCase.command());
        server.setEnabled(true);
        server.setAllowedTools(tools);
        return server;
    }

    private static ExternalMcpToolProperties tool(String name, McpToolRiskLevel riskLevel, boolean autoInvokeAllowed) {
        ExternalMcpToolProperties tool = new ExternalMcpToolProperties();
        tool.setName(name);
        tool.setRiskLevel(riskLevel);
        tool.setAutoInvokeAllowed(autoInvokeAllowed);
        return tool;
    }

    private static String chooseDeniedTool(String preferred, String allowedToolName, List<String> discoveredToolNames) {
        if (preferred != null && discoveredToolNames.contains(preferred) && !preferred.equals(allowedToolName)) {
            return preferred;
        }
        return discoveredToolNames.stream()
                .filter(name -> !name.equals(allowedToolName))
                .findFirst()
                .orElse(null);
    }

    private static String exposedName(String serverName, String toolName) {
        return "mcp_" + sanitize(serverName) + "_" + sanitize(toolName);
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sanitized = new StringBuilder();
        for (char ch : value.trim().toLowerCase(Locale.ROOT).toCharArray()) {
            sanitized.append((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '_' ? ch : '_');
        }
        return sanitized.toString();
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }
        };
    }

    private static List<String> stringListEnv(String jsonName, String plainName, List<String> defaultValue) {
        String json = System.getenv(jsonName);
        if (json != null && !json.isBlank()) {
            try {
                return OBJECT_MAPPER.readValue(json, STRING_LIST);
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid JSON string list environment variable: " + jsonName, e);
            }
        }
        String plain = System.getenv(plainName);
        if (plain == null || plain.isBlank()) {
            return defaultValue;
        }
        return Arrays.stream(plain.split(" "))
                .filter(part -> !part.isBlank())
                .toList();
    }

    private static String stringEnv(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static int intEnv(String name, int defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value);
    }

    private static McpToolRiskLevel riskEnv(String name, McpToolRiskLevel defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank()
                ? defaultValue
                : McpToolRiskLevel.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    private static Map<String, String> childEnv(String prefix, List<String> defaultKeys) {
        List<String> keys = stringListEnv(prefix + "_REAL_SERVER_ENV_KEYS_JSON",
                prefix + "_REAL_SERVER_ENV_KEYS", defaultKeys);
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : keys) {
            String value = System.getenv(key);
            if (value != null && !value.isBlank()) {
                result.put(key, value);
            }
        }
        return result;
    }

    record RealMcpCase(String prefix,
                       ExternalMcpServerType serverType,
                       String serverName,
                       String command,
                       List<String> args,
                       List<String> childEnvKeys,
                       String toolName,
                       String deniedToolName,
                       McpToolRiskLevel riskLevel,
                       String argumentsJson,
                       int maxResultLength,
                       Map<String, String> childEnv) {
        RealMcpCase withEnvironmentOverrides() {
            return new RealMcpCase(
                    prefix,
                    serverType,
                    stringEnv(prefix + "_REAL_SERVER_NAME", serverName),
                    stringEnv(prefix + "_REAL_SERVER_COMMAND", command),
                    stringListEnv(prefix + "_REAL_SERVER_ARGS_JSON", prefix + "_REAL_SERVER_ARGS", args),
                    childEnvKeys,
                    stringEnv(prefix + "_REAL_TOOL_NAME", toolName),
                    stringEnv(prefix + "_REAL_DENIED_TOOL_NAME", deniedToolName),
                    riskEnv(prefix + "_REAL_TOOL_RISK_LEVEL", riskLevel),
                    stringEnv(prefix + "_REAL_TOOL_ARGUMENTS_JSON", argumentsJson),
                    intEnv(prefix + "_REAL_MAX_RESULT_LENGTH", maxResultLength),
                    ExternalMcpRealServerTestSupport.childEnv(prefix, childEnvKeys)
            );
        }
    }

    record RealMcpResult(ExternalMcpServerType serverType,
                         String serverName,
                         List<String> discoveredToolNames,
                         String allowedToolName,
                         String deniedToolName,
                         int resultLength,
                         List<String> auditEvents) {
    }

    private static class NoLocalToolRegistry implements ToolRegistry {
        @Override
        public Optional<ToolDefinition> find(String toolNameOrAlias) {
            return Optional.empty();
        }

        @Override
        public String canonicalName(String toolNameOrAlias) {
            return toolNameOrAlias;
        }

        @Override
        public boolean canExposeToAgent(String toolNameOrAlias) {
            return false;
        }

        @Override
        public boolean isAllowedForRuntime(String toolNameOrAlias, Collection<String> runtimeToolNames) {
            return false;
        }

        @Override
        public int maxResultLength(String toolNameOrAlias) {
            return 6000;
        }

        @Override
        public String truncateResult(String toolNameOrAlias, String result) {
            return result;
        }
    }

    private static class RecordingAuditLogger implements McpToolAuditLogger {
        private final List<String> events = new ArrayList<>();

        @Override
        public void start(String traceId, ExternalMcpToolRegistration tool, String argumentsJson) {
            events.add("start:" + tool.getToolName());
        }

        @Override
        public void success(String traceId, ExternalMcpToolRegistration tool, String resultSummary,
                            long latencyMs, boolean truncated) {
            events.add("success:" + tool.getToolName() + ":" + truncated);
        }

        @Override
        public void failure(String traceId, ExternalMcpToolRegistration tool, String errorMessage,
                            long latencyMs, String errorCode) {
            events.add("failure:" + tool.getToolName() + ":" + errorCode);
        }

        @Override
        public void denied(String traceId, ExternalMcpToolRegistration tool, String argumentsJson,
                           long latencyMs, String errorCode) {
            events.add("denied:" + tool.getToolName() + ":" + errorCode);
        }

        private List<String> events() {
            return List.copyOf(events);
        }
    }
}
