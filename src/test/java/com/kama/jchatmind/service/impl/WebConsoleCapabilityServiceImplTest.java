package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.agent.tools.CodeSearchTools;
import com.kama.jchatmind.agent.tools.DataBaseTools;
import com.kama.jchatmind.agent.tools.KnowledgeTools;
import com.kama.jchatmind.agent.tools.TerminateTool;
import com.kama.jchatmind.mapper.CodeRepositoryMapper;
import com.kama.jchatmind.mapper.KnowledgeBaseMapper;
import com.kama.jchatmind.model.entity.CodeRepository;
import com.kama.jchatmind.mcp.config.ExternalMcpServerType;
import com.kama.jchatmind.mcp.registry.ExternalMcpToolRegistration;
import com.kama.jchatmind.mcp.registry.ExternalMcpToolRegistry;
import com.kama.jchatmind.mcp.safety.McpToolRiskLevel;
import com.kama.jchatmind.model.response.GetWebConsoleCapabilitiesResponse;
import com.kama.jchatmind.model.vo.WebConsoleCapabilityVO;
import com.kama.jchatmind.service.CodeRagAnswerEvidenceService;
import com.kama.jchatmind.service.RagService;
import com.kama.jchatmind.service.WebConsoleCapabilityService;
import com.kama.jchatmind.tool.InMemoryToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebConsoleCapabilityServiceImplTest {

    @Test
    void capabilitiesReflectLocalPolicyAndDisabledMcpWithoutDangerousTools() {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.initialize(List.of(
                new KnowledgeTools(mock(RagService.class), registry),
                new CodeSearchTools(mock(CodeRagAnswerEvidenceService.class)),
                new DataBaseTools(mock(JdbcTemplate.class)),
                new TerminateTool()
        ));
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        CodeRepositoryMapper codeRepositoryMapper = mock(CodeRepositoryMapper.class);
        when(knowledgeBaseMapper.selectAll()).thenReturn(List.of());
        String repoId = "11111111-1111-1111-1111-111111111111";
        when(codeRepositoryMapper.selectById(repoId)).thenReturn(CodeRepository.builder()
                .id(repoId)
                .name("hm-dianping")
                .status("READY")
                .build());

        WebConsoleCapabilityServiceImpl service = new WebConsoleCapabilityServiceImpl(
                registry,
                knowledgeBaseMapper,
                codeRepositoryMapper,
                emptyObjectProvider());

        GetWebConsoleCapabilitiesResponse response = service.getCapabilities(repoId, "gpt-5.5");

        assertThat(response.getAssistant()).isEqualTo("代码助手");
        assertThat(response.getProfile()).isEqualTo(WebConsoleCapabilityService.PROFILE);
        Map<String, WebConsoleCapabilityVO> byKey = response.getCapabilities().stream()
                .collect(Collectors.toMap(WebConsoleCapabilityVO::getKey, Function.identity()));
        assertThat(byKey.get("code_search").isEnabled()).isTrue();
        assertThat(byKey.get("code_search").getTools()).containsExactly("searchProjectCode");
        assertThat(byKey.get("database_readonly").isEnabled()).isTrue();
        assertThat(byKey.get("database_readonly").getTools()).containsExactly("databaseQuery");
        assertThat(byKey.get("knowledge_rag").isEnabled()).isTrue();
        assertThat(byKey.get("knowledge_rag").getReason()).contains("没有已配置知识库");
        assertThat(byKey.get("agent_control").getTools()).containsExactly("terminate");
        assertThat(byKey.get("mcp_docs").isEnabled()).isFalse();
        assertThat(byKey.get("mcp_github").isEnabled()).isFalse();
        assertThat(byKey.get("mcp_browser").isEnabled()).isFalse();
        assertThat(response.getNotSupported()).contains(
                "shell",
                "apply_patch",
                "write_file",
                "filesystem_mcp",
                "shell_mcp",
                "database_write",
                "browser_run_code_unsafe",
                "github_write");
    }

    @Test
    void invalidRepoIdDisablesCodeSearchWithoutCallingMapper() {
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.initialize(List.of(
                new KnowledgeTools(mock(RagService.class), registry),
                new CodeSearchTools(mock(CodeRagAnswerEvidenceService.class)),
                new DataBaseTools(mock(JdbcTemplate.class)),
                new TerminateTool()
        ));
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        CodeRepositoryMapper codeRepositoryMapper = mock(CodeRepositoryMapper.class);
        when(knowledgeBaseMapper.selectAll()).thenReturn(List.of());
        WebConsoleCapabilityServiceImpl service = new WebConsoleCapabilityServiceImpl(
                registry,
                knowledgeBaseMapper,
                codeRepositoryMapper,
                emptyObjectProvider());

        GetWebConsoleCapabilitiesResponse response = service.getCapabilities("repo-1", "gpt-5.5");

        WebConsoleCapabilityVO codeSearch = response.getCapabilities().stream()
                .filter(item -> "code_search".equals(item.getKey()))
                .findFirst()
                .orElseThrow();
        assertThat(codeSearch.isEnabled()).isFalse();
        assertThat(codeSearch.getReason()).contains("格式无效");
        org.mockito.Mockito.verify(codeRepositoryMapper, org.mockito.Mockito.never()).selectById(org.mockito.Mockito.anyString());
    }

    @Test
    void capabilitiesExposeAllPolicyAllowedMcpTools() {
        com.kama.jchatmind.tool.ToolRegistry localRegistry = mock(com.kama.jchatmind.tool.ToolRegistry.class);
        when(localRegistry.canExposeToAgent(anyString())).thenReturn(false);
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        CodeRepositoryMapper codeRepositoryMapper = mock(CodeRepositoryMapper.class);
        when(knowledgeBaseMapper.selectAll()).thenReturn(List.of());
        ExternalMcpToolRegistry mcpRegistry = mock(ExternalMcpToolRegistry.class);
        when(mcpRegistry.exposedTools()).thenReturn(List.of(
                mcpTool(ExternalMcpServerType.DOCS, "mcp_context7_resolve_library_id"),
                mcpTool(ExternalMcpServerType.DOCS, "mcp_context7_get_library_docs"),
                mcpTool(ExternalMcpServerType.BROWSER, "mcp_playwright_browser_navigate"),
                mcpTool(ExternalMcpServerType.GITHUB, "mcp_github_search_repositories")
        ));
        WebConsoleCapabilityServiceImpl service = new WebConsoleCapabilityServiceImpl(
                localRegistry,
                knowledgeBaseMapper,
                codeRepositoryMapper,
                objectProvider(mcpRegistry));

        GetWebConsoleCapabilitiesResponse response = service.getCapabilities(null, "gpt-5.5");

        Map<String, WebConsoleCapabilityVO> byKey = response.getCapabilities().stream()
                .collect(Collectors.toMap(WebConsoleCapabilityVO::getKey, Function.identity()));
        assertThat(byKey.get("mcp_docs").isEnabled()).isTrue();
        assertThat(byKey.get("mcp_docs").getTools())
                .containsExactly("mcp_context7_resolve_library_id", "mcp_context7_get_library_docs");
        assertThat(byKey.get("mcp_browser").isEnabled()).isTrue();
        assertThat(byKey.get("mcp_browser").getTools())
                .containsExactly("mcp_playwright_browser_navigate");
        assertThat(byKey.get("mcp_github").isEnabled()).isTrue();
        assertThat(byKey.get("mcp_github").getTools())
                .containsExactly("mcp_github_search_repositories");
        assertThat(response.getNotSupported()).contains("filesystem_mcp", "shell_mcp", "github_write");
    }

    @Test
    void mcpDiscoveryFailureLeavesCapabilityResponseAvailableAndMcpDisabled() {
        com.kama.jchatmind.tool.ToolRegistry localRegistry = mock(com.kama.jchatmind.tool.ToolRegistry.class);
        when(localRegistry.canExposeToAgent(anyString())).thenReturn(false);
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        CodeRepositoryMapper codeRepositoryMapper = mock(CodeRepositoryMapper.class);
        when(knowledgeBaseMapper.selectAll()).thenReturn(List.of());
        ExternalMcpToolRegistry mcpRegistry = mock(ExternalMcpToolRegistry.class);
        when(mcpRegistry.exposedTools()).thenThrow(new IllegalStateException("server unavailable"));

        WebConsoleCapabilityServiceImpl service = new WebConsoleCapabilityServiceImpl(
                localRegistry, knowledgeBaseMapper, codeRepositoryMapper, objectProvider(mcpRegistry));

        GetWebConsoleCapabilitiesResponse response = service.getCapabilities(null, "gpt-5.5");

        Map<String, WebConsoleCapabilityVO> byKey = response.getCapabilities().stream()
                .collect(Collectors.toMap(WebConsoleCapabilityVO::getKey, Function.identity()));
        assertThat(byKey.get("mcp_docs").isEnabled()).isFalse();
        assertThat(byKey.get("mcp_docs").getTools()).isEmpty();
        assertThat(byKey.get("mcp_docs").getReason()).contains("MCP server unavailable");
        assertThat(response.getNotSupported()).contains("shell");
    }

    @Test
    void runtimeCapabilityContextSeparatesEnabledUnavailableAndUnsupported() {
        WebConsoleCapabilityServiceImpl service = new WebConsoleCapabilityServiceImpl(
                mock(com.kama.jchatmind.tool.ToolRegistry.class),
                mock(KnowledgeBaseMapper.class),
                mock(CodeRepositoryMapper.class),
                emptyObjectProvider());
        GetWebConsoleCapabilitiesResponse response = GetWebConsoleCapabilitiesResponse.builder()
                .assistant("代码助手")
                .profile(WebConsoleCapabilityService.PROFILE)
                .capabilities(List.of(
                        WebConsoleCapabilityVO.builder()
                                .label("代码检索")
                                .enabled(true)
                                .tools(List.of("searchProjectCode"))
                                .build(),
                        WebConsoleCapabilityVO.builder()
                                .label("MCP GitHub 只读工具")
                                .enabled(false)
                                .tools(List.of())
                                .reason("MCP client 未启用")
                                .build()
                ))
                .notSupported(List.of("shell", "apply_patch", "write_file"))
                .build();

        String context = service.runtimeCapabilityContext(response);

        assertThat(context)
                .contains("Current enabled capabilities")
                .contains("searchProjectCode")
                .contains("Configured but currently disabled or unavailable")
                .contains("MCP client 未启用")
                .contains("Not supported in Web Console")
                .contains("shell")
                .contains("Do not claim you can execute shell commands");
    }

    private ObjectProvider<com.kama.jchatmind.mcp.registry.ExternalMcpToolRegistry> emptyObjectProvider() {
        return new ObjectProvider<>() {
            @Override
            public com.kama.jchatmind.mcp.registry.ExternalMcpToolRegistry getObject(Object... args) {
                throw new IllegalStateException("not available");
            }

            @Override
            public com.kama.jchatmind.mcp.registry.ExternalMcpToolRegistry getIfAvailable() {
                return null;
            }

            @Override
            public com.kama.jchatmind.mcp.registry.ExternalMcpToolRegistry getIfUnique() {
                return null;
            }

            @Override
            public com.kama.jchatmind.mcp.registry.ExternalMcpToolRegistry getObject() {
                throw new IllegalStateException("not available");
            }

            @Override
            public Iterator<com.kama.jchatmind.mcp.registry.ExternalMcpToolRegistry> iterator() {
                return List.<com.kama.jchatmind.mcp.registry.ExternalMcpToolRegistry>of().iterator();
            }
        };
    }

    private ExternalMcpToolRegistration mcpTool(ExternalMcpServerType type, String exposedName) {
        return ExternalMcpToolRegistration.builder()
                .serverName(type.name().toLowerCase())
                .serverType(type)
                .toolName(exposedName)
                .exposedName(exposedName)
                .riskLevel(McpToolRiskLevel.NETWORK_READ)
                .autoInvokeAllowed(true)
                .build();
    }

    private ObjectProvider<ExternalMcpToolRegistry> objectProvider(ExternalMcpToolRegistry registry) {
        return new ObjectProvider<>() {
            @Override
            public ExternalMcpToolRegistry getObject(Object... args) {
                return registry;
            }

            @Override
            public ExternalMcpToolRegistry getIfAvailable() {
                return registry;
            }

            @Override
            public ExternalMcpToolRegistry getIfUnique() {
                return registry;
            }

            @Override
            public ExternalMcpToolRegistry getObject() {
                return registry;
            }

            @Override
            public Iterator<ExternalMcpToolRegistry> iterator() {
                return List.of(registry).iterator();
            }
        };
    }
}
