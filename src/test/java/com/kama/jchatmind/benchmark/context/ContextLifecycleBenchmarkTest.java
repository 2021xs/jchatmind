package com.kama.jchatmind.benchmark.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.ChatClientRegistry;
import com.kama.jchatmind.config.CodeRagProperties;
import com.kama.jchatmind.config.ContextCompressionProperties;
import com.kama.jchatmind.config.FinalSynthesisProperties;
import com.kama.jchatmind.converter.AgentConverter;
import com.kama.jchatmind.mapper.AgentMapper;
import com.kama.jchatmind.mapper.AgentStepMapper;
import com.kama.jchatmind.mapper.AgentTaskMapper;
import com.kama.jchatmind.mapper.CodeRepositoryMapper;
import com.kama.jchatmind.mapper.ToolCallLogMapper;
import com.kama.jchatmind.model.common.ChatSessionChannel;
import com.kama.jchatmind.model.dto.AgentDTO;
import com.kama.jchatmind.model.entity.Agent;
import com.kama.jchatmind.model.entity.AgentTask;
import com.kama.jchatmind.model.entity.CodeRepository;
import com.kama.jchatmind.model.request.CreateChatSessionRequest;
import com.kama.jchatmind.model.request.WebConsoleChatSendRequest;
import com.kama.jchatmind.model.response.WebConsoleChatSendResponse;
import com.kama.jchatmind.service.AgentTaskLogService;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.ChatSessionFacadeService;
import com.kama.jchatmind.service.RagService;
import com.kama.jchatmind.service.WebConsoleChatService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;

@Tag("context-lifecycle-benchmark")
@EnabledIf("enabled")
@SpringBootTest
@ActiveProfiles("benchmark")
class ContextLifecycleBenchmarkTest {
    static final String SUITE_RESOURCE = "benchmark/context_lifecycle_benchmark_suite.json";
    private static final Duration DEFAULT_TASK_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(200);

    @Autowired private ObjectMapper objectMapper;
    @Autowired private WebConsoleChatService webConsoleChatService;
    @Autowired private ChatSessionFacadeService chatSessionFacadeService;
    @Autowired private ChatMessageFacadeService chatMessageFacadeService;
    @Autowired private AgentTaskMapper agentTaskMapper;
    @Autowired private AgentStepMapper agentStepMapper;
    @Autowired private ToolCallLogMapper toolCallLogMapper;
    @Autowired private CodeRepositoryMapper codeRepositoryMapper;
    @Autowired private AgentMapper agentMapper;
    @Autowired private AgentConverter agentConverter;
    @Autowired private ChatClientRegistry chatClientRegistry;
    @Autowired private RagService ragService;
    @Autowired private CodeRagProperties codeRagProperties;
    @Autowired private ContextCompressionProperties compressionProperties;
    @Autowired private FinalSynthesisProperties finalSynthesisProperties;
    @Autowired @Qualifier("dataSource") private DataSource applicationDataSource;
    @Autowired @Qualifier("databaseToolJdbcTemplate") private JdbcTemplate databaseToolJdbcTemplate;

    @Test
    void runContextLifecycleBenchmark() throws Exception {
        OffsetDateTime startedAt = OffsetDateTime.now();
        ContextLifecycleBenchmarkSuite suite = loadSuite();
        suite.validate();
        ContextLifecycleBenchmarkResult.ExecutionArchitecture executionArchitecture =
                ContextLifecycleBenchmarkResult.ExecutionArchitecture.configured();
        List<ContextLifecycleBenchmarkCase> cases = selectedCases(suite.cases);
        assertFalse(cases.isEmpty(), "No active context lifecycle benchmark cases selected");
        int repeats = positiveProperty("context.benchmark.repeats", 1);
        CodeRepository repository = requireRepository(suite.repositorySnapshot.repositoryId);
        Agent agent = resolveAgent();
        AgentDTO agentConfig = agentConverter.toDTO(agent);
        String model = configured("context.benchmark.model", "CONTEXT_BENCHMARK_MODEL", agent.getModel());
        if (!chatClientRegistry.contains(model)) {
            throw new IllegalStateException("Benchmark model is not registered: " + model);
        }

        ContextLifecycleBenchmarkPreflight.Snapshot snapshot = preflight(suite, repository, model);
        String runId = "context-lifecycle-" + startedAt.toString().replace(':', '-')
                + "-" + UUID.randomUUID().toString().substring(0, 8);
        printPreflight(runId, suite, executionArchitecture, cases, repeats,
                repository, model, agentConfig, snapshot);

        List<ContextLifecycleCaseExecution> executions = new ArrayList<>();
        Map<String, String> groupedSessions = new HashMap<>();
        try (ContextLifecycleObservationCollector collector = new ContextLifecycleObservationCollector()) {
            for (int repeat = 1; repeat <= repeats; repeat++) {
                for (ContextLifecycleBenchmarkCase benchmarkCase : ordered(cases)) {
                    String sessionId = sessionFor(benchmarkCase, repeat, groupedSessions, agent, repository, model);
                    executions.add(executeCase(benchmarkCase, repeat, sessionId, agent, repository, model, collector));
                }
            }
        }

        assertFalse(executions.isEmpty());
        long modelCalls = executions.stream().mapToLong(value -> value.capture().modelCalls.size()).sum();
        System.out.printf("Context Lifecycle capture completed: runId=%s cases=%d modelCalls=%d failures=%d%n",
                runId, executions.size(), modelCalls,
                executions.stream().filter(value -> value.executionFailure() != null).count());
        ContextLifecycleResultAssembler assembler = new ContextLifecycleResultAssembler(
                compressionProperties.getCharsPerToken(), finalSynthesisProperties, executionArchitecture);
        List<ContextLifecycleBenchmarkResult.CaseResult> caseResults = executions.stream()
                .map(assembler::assemble).toList();
        ContextLifecycleBenchmarkResult result = new ContextLifecycleBenchmarkResult(
                metadata(runId, suite, executionArchitecture, repository, model, agentConfig, snapshot,
                        startedAt, OffsetDateTime.now(), repeats), caseResults);
        Path outputDirectory = Path.of("target", "benchmark", "context-lifecycle", runId);
        ContextLifecycleBenchmarkOutput.Artifacts artifacts =
                new ContextLifecycleBenchmarkOutput(objectMapper).write(outputDirectory, result);
        System.out.println("Raw JSON: " + artifacts.rawJson().toAbsolutePath());
        System.out.println("Case CSV: " + artifacts.caseCsv().toAbsolutePath());
        System.out.println("Markdown report: " + artifacts.markdownReport().toAbsolutePath());
        System.out.println("Anomalies CSV: " + artifacts.anomaliesCsv().toAbsolutePath());
    }

    private ContextLifecycleBenchmarkResult.RunMetadata metadata(
            String runId,
            ContextLifecycleBenchmarkSuite suite,
            ContextLifecycleBenchmarkResult.ExecutionArchitecture executionArchitecture,
            CodeRepository repository,
            String model,
            AgentDTO agent,
            ContextLifecycleBenchmarkPreflight.Snapshot snapshot,
            OffsetDateTime startedAt,
            OffsetDateTime endedAt,
            int repeats) {
        ContextCompressionProperties.TokenThreshold threshold = compressionProperties.thresholdFor(model);
        AgentDTO.ChatOptions options = agent.getChatOptions();
        Map<String, Object> modelParameters = new LinkedHashMap<>();
        modelParameters.put("topP", options == null || options.getTopP() == null
                ? "unavailable" : options.getTopP());
        modelParameters.put("messageLength", options == null || options.getMessageLength() == null
                ? "unavailable" : options.getMessageLength());
        modelParameters.put("seed", "unavailable");
        Map<String, Object> selector = new LinkedHashMap<>();
        selector.put("enabled", codeRagProperties.getLlmSelector().isEnabled());
        selector.put("clientType", codeRagProperties.getLlmSelector().getClientType().name());
        selector.put("model", codeRagProperties.getLlmSelector().getModel());
        selector.put("maxCandidateChars", codeRagProperties.getLlmSelector().getMaxCandidateChars());
        selector.put("maxSelected", codeRagProperties.getLlmSelector().getMaxSelected());
        selector.put("timeoutMs", codeRagProperties.getLlmSelector().getTimeoutMs());
        Map<String, Object> compression = new LinkedHashMap<>();
        compression.put("enabled", compressionProperties.isEnabled());
        compression.put("compressionTriggerTokens", threshold.getCompressionTriggerTokens());
        compression.put("workingContextHardLimitTokens", threshold.getWorkingContextHardLimitTokens());
        compression.put("maxSingleToolResultTokens", threshold.getMaxSingleToolResultTokens());
        compression.put("keepRecentRounds", compressionProperties.getKeepRecentRounds());
        compression.put("maxHistoryMessages", compressionProperties.getMaxHistoryMessages());
        compression.put("charsPerToken", compressionProperties.getCharsPerToken());
        compression.put("model", "same registered model client; dedicated compression model field unavailable");
        return new ContextLifecycleBenchmarkResult.RunMetadata(
                runId, suite.benchmarkSuiteVersion, suite.architectureLabel, executionArchitecture,
                snapshot.mainGit().commit(),
                snapshot.mainGit().status(), model,
                options == null ? null : options.getTemperature(), null, modelParameters,
                repository.getId(), repository.getName(), snapshot.repositoryGit().commit(),
                snapshot.repositoryGit().status(), snapshot.fileManifestMd5(), snapshot.chunkManifestMd5(),
                snapshot.fileCount(), snapshot.chunkCount(), snapshot.embeddingCount(),
                20, codeRagProperties.getAnswerEvidence().getRawTopK(), selector, compression,
                startedAt, endedAt, 3, repeats,
                "Provider usage when exposed; ESTIMATED_MESSAGE_CHARS_V1 otherwise, stored separately",
                "Deterministic structured critical facts, exact values, and forbidden claims; no LLM judge");
    }

    private ContextLifecycleBenchmarkPreflight.Snapshot preflight(
            ContextLifecycleBenchmarkSuite suite, CodeRepository repository, String model) {
        Integer probe = databaseToolJdbcTemplate.queryForObject("SELECT 1", Integer.class);
        if (probe == null || probe != 1) {
            throw new IllegalStateException("Read-only database preflight failed");
        }
        float[] embedding = ragService.embed("context lifecycle benchmark preflight");
        if (embedding == null || embedding.length == 0) {
            throw new IllegalStateException("Embedding preflight returned an empty vector");
        }
        boolean requireClean = !Boolean.getBoolean("context.benchmark.allowDirtyMainTree");
        return new ContextLifecycleBenchmarkPreflight(new JdbcTemplate(applicationDataSource))
                .verify(repository, suite.repositorySnapshot, requireClean);
    }

    private ContextLifecycleCaseExecution executeCase(
            ContextLifecycleBenchmarkCase benchmarkCase,
            int repeat,
            String sessionId,
            Agent agent,
            CodeRepository repository,
            String model,
            ContextLifecycleObservationCollector collector) {
        ContextLifecycleObservationCollector.CaseCapture started =
                collector.begin(benchmarkCase.caseId, repeat, sessionId);
        AgentTask task = null;
        String failure = null;
        try {
            WebConsoleChatSendRequest request = new WebConsoleChatSendRequest();
            request.setConversationId(sessionId);
            request.setAgentId(agent.getId());
            request.setModel(model);
            request.setRepoId(repository.getId());
            request.setContent(benchmarkCase.question);
            WebConsoleChatSendResponse response = webConsoleChatService.send(request);
            task = awaitTerminalTask(response, taskTimeout());
            collector.bindTask(task.getId());
        } catch (Exception e) {
            failure = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        ContextLifecycleObservationCollector.CaseCapture capture = collector.end();
        List<com.kama.jchatmind.model.entity.AgentStep> steps = task == null
                ? List.of() : agentStepMapper.selectByTaskId(task.getId());
        List<com.kama.jchatmind.model.entity.ToolCallLog> tools = task == null
                ? List.of() : toolCallLogMapper.selectByTaskId(task.getId());
        return new ContextLifecycleCaseExecution(
                benchmarkCase, repeat, sessionId, task, steps, tools,
                chatMessageFacadeService.getChatMessageDTOsBySessionId(sessionId), capture, failure);
    }

    private AgentTask awaitTerminalTask(WebConsoleChatSendResponse response, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        AgentTask matched = null;
        while (System.nanoTime() < deadline) {
            matched = agentTaskMapper.selectRecentBySessionId(response.getConversationId(), 10).stream()
                    .filter(task -> response.getRunId().equals(task.getTraceId()))
                    .filter(task -> response.getUserMessageId().equals(task.getUserMessageId()))
                    .findFirst().orElse(null);
            if (matched != null && !AgentTaskLogService.STATUS_RUNNING.equals(matched.getStatus())) {
                return matched;
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
        throw new IllegalStateException("Benchmark task timeout: lastStatus="
                + (matched == null ? "NOT_CREATED" : matched.getStatus()));
    }

    private String sessionFor(ContextLifecycleBenchmarkCase benchmarkCase,
                              int repeat,
                              Map<String, String> groupedSessions,
                              Agent agent,
                              CodeRepository repository,
                              String model) {
        if (StringUtils.hasText(benchmarkCase.sessionGroupId)) {
            String key = repeat + ":" + benchmarkCase.sessionGroupId;
            return groupedSessions.computeIfAbsent(key,
                    ignored -> createSession(benchmarkCase.sessionGroupId, repeat, agent, repository, model));
        }
        return createSession(benchmarkCase.caseId, repeat, agent, repository, model);
    }

    private String createSession(String label, int repeat, Agent agent,
                                 CodeRepository repository, String model) {
        CreateChatSessionRequest request = new CreateChatSessionRequest();
        request.setAgentId(agent.getId());
        request.setTitle("Context Benchmark " + label + " #" + repeat);
        request.setChannel(ChatSessionChannel.WEB_CONSOLE.name());
        request.setRepoId(repository.getId());
        request.setModel(model);
        request.setMetadata(new LinkedHashMap<>(Map.of(
                "contextLifecycleBenchmark", true,
                "benchmarkSuiteVersion", "context-lifecycle-v1",
                "repeatIndex", repeat)));
        return chatSessionFacadeService.createChatSession(request).getChatSessionId();
    }

    private List<ContextLifecycleBenchmarkCase> selectedCases(List<ContextLifecycleBenchmarkCase> all) {
        List<ContextLifecycleBenchmarkCase> active = all.stream()
                .filter(value -> value.active() || Boolean.getBoolean("context.benchmark.includeFixtures"))
                .toList();
        String caseId = System.getProperty("context.benchmark.caseId");
        if (StringUtils.hasText(caseId)) {
            return active.stream().filter(value -> caseId.equals(value.caseId)).toList();
        }
        String category = System.getProperty("context.benchmark.category");
        if (StringUtils.hasText(category)) {
            active = active.stream().filter(value -> category.equals(value.category)).toList();
        }
        int limit = Integer.getInteger("context.benchmark.limit", active.size());
        return active.subList(0, Math.min(Math.max(0, limit), active.size()));
    }

    private List<ContextLifecycleBenchmarkCase> ordered(List<ContextLifecycleBenchmarkCase> cases) {
        return cases.stream().sorted(Comparator
                .comparing((ContextLifecycleBenchmarkCase value) ->
                        StringUtils.hasText(value.sessionGroupId) ? value.sessionGroupId : value.caseId)
                .thenComparingInt(value -> value.taskOrder)).toList();
    }

    private ContextLifecycleBenchmarkSuite loadSuite() throws Exception {
        return objectMapper.readValue(new ClassPathResource(SUITE_RESOURCE).getInputStream(),
                ContextLifecycleBenchmarkSuite.class);
    }

    private CodeRepository requireRepository(String id) {
        CodeRepository repository = codeRepositoryMapper.selectById(id);
        if (repository == null || !"READY".equalsIgnoreCase(repository.getStatus())) {
            throw new IllegalStateException("Fixed benchmark repository is not READY: " + id);
        }
        return repository;
    }

    private Agent resolveAgent() {
        String id = configured("context.benchmark.agentId", "CONTEXT_BENCHMARK_AGENT_ID", null);
        if (StringUtils.hasText(id)) {
            Agent agent = agentMapper.selectById(id);
            if (agent == null) {
                throw new IllegalStateException("Benchmark agent not found: " + id);
            }
            return agent;
        }
        return agentMapper.selectAll().stream()
                .filter(value -> value.getAllowedTools() != null
                        && value.getAllowedTools().contains("searchProjectCode")
                        && value.getAllowedTools().contains("databaseQuery"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No benchmark Agent exposes searchProjectCode and databaseQuery"));
    }

    private void printPreflight(String runId,
                                ContextLifecycleBenchmarkSuite suite,
                                ContextLifecycleBenchmarkResult.ExecutionArchitecture executionArchitecture,
                                List<ContextLifecycleBenchmarkCase> cases,
                                int repeats,
                                CodeRepository repository,
                                String model,
                                AgentDTO agent,
                                ContextLifecycleBenchmarkPreflight.Snapshot snapshot) {
        ContextCompressionProperties.TokenThreshold threshold = compressionProperties.thresholdFor(model);
        System.out.println("Context Lifecycle Formal Preflight:");
        System.out.println("benchmark run id: " + runId);
        System.out.println("git commit: " + snapshot.mainGit().commit());
        System.out.println("working tree: " + snapshot.mainGit().status().replace('\n', ' '));
        System.out.println("suite architecture provenance: " + suite.architectureLabel);
        System.out.println("execution architecture: " + executionArchitecture);
        System.out.println("model: " + model);
        System.out.println("temperature: " + (agent.getChatOptions() == null
                ? "unavailable" : agent.getChatOptions().getTemperature()));
        System.out.println("seed: unavailable");
        System.out.println("repo: " + repository.getName() + "/" + repository.getId());
        System.out.println("repo snapshot: " + snapshot.fileManifestMd5() + "/" + snapshot.chunkManifestMd5());
        System.out.println("repo external commit: " + snapshot.repositoryGit().commit());
        System.out.println("repo external working tree: " + snapshot.repositoryGit().status().replace('\n', ' '));
        System.out.println("benchmark suite version: " + suite.benchmarkSuiteVersion);
        System.out.println("number of cases: " + cases.size());
        System.out.println("repeats: " + repeats);
        System.out.println("context config: trigger=" + threshold.getCompressionTriggerTokens()
                + ", hardLimit=" + threshold.getWorkingContextHardLimitTokens()
                + ", maxTool=" + threshold.getMaxSingleToolResultTokens()
                + ", keepRecentRounds=" + compressionProperties.getKeepRecentRounds()
                + ", maxHistoryMessages=" + compressionProperties.getMaxHistoryMessages());
        System.out.println("retrieval config: rawTopK=" + codeRagProperties.getAnswerEvidence().getRawTopK()
                + ", finalTopK=" + codeRagProperties.getAnswerEvidence().getFinalTopK()
                + ", selector=" + codeRagProperties.getLlmSelector().isEnabled());
        System.out.println("agent budget: maxSteps=20");
        System.out.println("token measurement: provider actual + ESTIMATED_MESSAGE_CHARS_V1 (separate)");
        System.out.println("correctness scoring: deterministic critical facts/exact values/forbidden claims");
        System.out.println("output directory: target/benchmark/context-lifecycle/" + runId);
    }

    private Duration taskTimeout() {
        return Duration.ofSeconds(positiveProperty("context.benchmark.taskTimeoutSeconds",
                (int) DEFAULT_TASK_TIMEOUT.toSeconds()));
    }

    private int positiveProperty(String name, int defaultValue) {
        int value = Integer.getInteger(name, defaultValue);
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private String configured(String property, String environment, String defaultValue) {
        String value = System.getProperty(property);
        if (!StringUtils.hasText(value)) {
            value = System.getenv(environment);
        }
        return StringUtils.hasText(value) ? value.trim() : defaultValue;
    }

    static boolean enabled() {
        return Boolean.getBoolean("context.benchmark.enabled");
    }
}
