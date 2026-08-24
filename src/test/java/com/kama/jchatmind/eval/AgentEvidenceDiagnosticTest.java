package com.kama.jchatmind.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.ChatClientRegistry;
import com.kama.jchatmind.config.CodeRagProperties;
import com.kama.jchatmind.mapper.AgentStepMapper;
import com.kama.jchatmind.mapper.AgentTaskMapper;
import com.kama.jchatmind.mapper.CodeRepositoryMapper;
import com.kama.jchatmind.mapper.ToolCallLogMapper;
import com.kama.jchatmind.model.common.ChatSessionChannel;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.dto.CodeSearchExecutionResult;
import com.kama.jchatmind.model.dto.CodeSearchResult;
import com.kama.jchatmind.model.entity.AgentTask;
import com.kama.jchatmind.model.entity.CodeRepository;
import com.kama.jchatmind.model.request.CreateChatSessionRequest;
import com.kama.jchatmind.model.request.WebConsoleChatSendRequest;
import com.kama.jchatmind.model.response.WebConsoleChatSendResponse;
import com.kama.jchatmind.service.AgentTaskLogService;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.ChatSessionFacadeService;
import com.kama.jchatmind.service.CodeSearchService;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Tag("agent-evidence-diagnostic")
@EnabledIf("diagnosticEnabled")
@SpringBootTest
@TestPropertySource(properties = {
        "jchatmind.agent.observability.recovery-enabled=false",
        "jchatmind.code-rag.embedding-warmup.enabled=false"
})
class AgentEvidenceDiagnosticTest {
    private static final String CASE_RESOURCE = "eval/agent_task_eval_cases.json";
    private static final String AGENT_ID = "733ff511-8435-499e-b583-9e2b9513cc64";
    private static final String REPOSITORY_NAME = "FlashDeal";
    private static final String EXPERIMENT = "evidence-presentation-v1";
    private static final int RUNS_PER_CASE = 3;
    private static final Duration POLL_INTERVAL = Duration.ofMillis(200);
    private static final Duration TASK_TIMEOUT = Duration.ofSeconds(300);
    private static final List<String> DIAGNOSTIC_CASES = List.of(
            "basic_code_cache_001",
            "hard_duplicate_order_001",
            "basic_code_lua_001",
            "basic_code_mq_001",
            "hard_cache_resilience_001");

    @Autowired private ObjectMapper objectMapper;
    @Autowired private WebConsoleChatService webConsoleChatService;
    @Autowired private ChatSessionFacadeService chatSessionFacadeService;
    @Autowired private ChatMessageFacadeService chatMessageFacadeService;
    @Autowired private AgentTaskMapper agentTaskMapper;
    @Autowired private AgentStepMapper agentStepMapper;
    @Autowired private ToolCallLogMapper toolCallLogMapper;
    @Autowired private CodeRepositoryMapper codeRepositoryMapper;
    @Autowired private CodeSearchService codeSearchService;
    @Autowired private CodeRagProperties codeRagProperties;
    @Autowired private ChatClientRegistry chatClientRegistry;
    @Autowired private RagService ragService;
    @Autowired @Qualifier("dataSource") private DataSource applicationDataSource;
    @Autowired @Qualifier("databaseToolJdbcTemplate") private JdbcTemplate databaseToolJdbcTemplate;

    @Test
    void diagnoseEvidenceStability() throws Exception {
        List<AgentTaskEvalCase> cases = selectedCases(loadCases());
        CodeRepository repository = resolveRepository();
        preflight(repository);
        List<RunResult> runs = new ArrayList<>();
        for (AgentTaskEvalCase evalCase : cases) {
            for (int runIndex = 1; runIndex <= RUNS_PER_CASE; runIndex++) {
                ExistingTask existing = findTerminalTask(evalCase.id, runIndex);
                runs.add(existing == null
                        ? run(evalCase, repository, runIndex)
                        : readTask(evalCase, repository, runIndex, existing.task(), existing.sessionId()));
            }
        }
        new DiagnosticReportWriter().write(outputDirectory(), cases, runs,
                repository, AGENT_ID, codeRagProperties.getAnswerEvidence().getRawTopK());
        System.out.printf("Agent Evidence Diagnostic completed: cases=%d runs=%d output=%s%n",
                cases.size(), runs.size(), outputDirectory().toAbsolutePath());
    }

    static boolean diagnosticEnabled() {
        return Boolean.getBoolean("agent.evidence.diagnostic.enabled");
    }

    private List<AgentTaskEvalCase> loadCases() throws IOException {
        try (var input = new ClassPathResource(CASE_RESOURCE).getInputStream()) {
            return objectMapper.readValue(input, new TypeReference<>() { });
        }
    }

    private List<AgentTaskEvalCase> selectedCases(List<AgentTaskEvalCase> all) {
        Map<String, AgentTaskEvalCase> byId = new LinkedHashMap<>();
        all.forEach(value -> byId.put(value.id, value));
        List<AgentTaskEvalCase> selected = DIAGNOSTIC_CASES.stream().map(byId::get).toList();
        if (selected.stream().anyMatch(Objects::isNull)) {
            throw new IllegalStateException("Diagnostic case is missing from frozen fixture");
        }
        return selected;
    }

    private CodeRepository resolveRepository() {
        return codeRepositoryMapper.selectAll().stream()
                .filter(repo -> REPOSITORY_NAME.equalsIgnoreCase(repo.getName()))
                .filter(repo -> "READY".equalsIgnoreCase(repo.getStatus()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No READY FlashDeal repository found"));
    }

    private void preflight(CodeRepository repository) {
        if (!"READY".equalsIgnoreCase(repository.getStatus())) {
            throw new IllegalStateException("Repository is not READY: " + repository.getStatus());
        }
        long files = count("SELECT COUNT(*) FROM code_file WHERE repo_id = CAST(? AS uuid)", repository.getId());
        long chunks = count("SELECT COUNT(*) FROM code_chunk WHERE repo_id = CAST(? AS uuid)", repository.getId());
        long embeddings = count("SELECT COUNT(*) FROM code_chunk WHERE repo_id = CAST(? AS uuid) AND embedding IS NOT NULL", repository.getId());
        if (files != 167 || chunks != 642 || embeddings != 642) {
            throw new IllegalStateException("Unexpected benchmark repository counts: files=" + files
                    + ", chunks=" + chunks + ", embeddings=" + embeddings);
        }
        Integer probe = databaseToolJdbcTemplate.queryForObject("SELECT 1", Integer.class);
        if (!Integer.valueOf(1).equals(probe)) {
            throw new IllegalStateException("Readonly datasource SELECT 1 failed");
        }
        float[] vector = ragService.embed("agent evidence diagnostic preflight");
        if (vector == null || vector.length == 0) {
            throw new IllegalStateException("Embedding preflight returned empty vector");
        }
        if (!chatClientRegistry.contains("gpt-5.5")) {
            throw new IllegalStateException("gpt-5.5 is not registered");
        }
    }

    private long count(String sql, String repoId) {
        Long value = new JdbcTemplate(applicationDataSource).queryForObject(sql, Long.class, repoId);
        return value == null ? 0 : value;
    }

    private RunResult run(AgentTaskEvalCase evalCase, CodeRepository repository, int runIndex)
            throws InterruptedException {
        CreateChatSessionRequest sessionRequest = new CreateChatSessionRequest();
        sessionRequest.setAgentId(AGENT_ID);
        sessionRequest.setTitle("Evidence Diagnostic " + evalCase.id + " run" + runIndex);
        sessionRequest.setChannel(ChatSessionChannel.WEB_CONSOLE.name());
        sessionRequest.setRepoId(repository.getId());
        sessionRequest.setModel("gpt-5.5");
        sessionRequest.setMetadata(new LinkedHashMap<>(Map.of(
                "agentEvidenceDiagnostic", true,
                "agentEvidenceExperiment", EXPERIMENT,
                "agentEvidenceCaseId", evalCase.id,
                "runIndex", runIndex)));
        String sessionId = chatSessionFacadeService.createChatSession(sessionRequest).getChatSessionId();

        WebConsoleChatSendRequest request = new WebConsoleChatSendRequest();
        request.setConversationId(sessionId);
        request.setAgentId(AGENT_ID);
        request.setModel("gpt-5.5");
        request.setRepoId(repository.getId());
        request.setContent(evalCase.query);
        WebConsoleChatSendResponse response = webConsoleChatService.send(request);
        AgentTask task = awaitTerminal(response);
        return readTask(evalCase, repository, runIndex, task, response.getConversationId());
    }

    private RunResult readTask(AgentTaskEvalCase evalCase, CodeRepository repository, int runIndex,
                               AgentTask task, String sessionId) {
        List<com.kama.jchatmind.model.entity.AgentStep> steps = agentStepMapper.selectByTaskId(task.getId());
        List<com.kama.jchatmind.model.entity.ToolCallLog> toolCalls = toolCallLogMapper.selectByTaskId(task.getId());
        String finalAnswer = finalAnswer(chatMessageFacadeService.getChatMessageDTOsBySessionId(sessionId));

        List<SearchDiagnostic> searches = new ArrayList<>();
        int searchIndex = 0;
        for (com.kama.jchatmind.model.entity.ToolCallLog call : toolCalls) {
            if (!"searchProjectCode".equals(call.getToolName())) {
                continue;
            }
            JsonNode arguments = parseArguments(call.getArgumentsJson());
            String query = arguments.path("query").asText("");
            String repoId = arguments.path("repoId").asText(repository.getId());
            CodeSearchExecutionResult execution = codeSearchService.searchWithTrace(
                    repoId, query, codeRagProperties.getAnswerEvidence().getRawTopK());
            String toolResult = call.getResultSummary() == null ? "" : call.getResultSummary();
            searches.add(SearchDiagnostic.from(++searchIndex, query, repoId, execution, toolResult,
                    evalCase, codeRagProperties.getAnswerEvidence().getRawTopK()));
        }
        String failureLayer = classify(evalCase, toolCalls, searches, finalAnswer);
        return new RunResult(evalCase.id, evalCase.category, evalCase.difficulty, runIndex,
                task, steps.size(), toolCalls.size(), searches.size(), finalAnswer != null,
                failureLayer, finalAnswer, searches);
    }

    private ExistingTask findTerminalTask(String caseId, int runIndex) {
        List<Map<String, Object>> rows = new JdbcTemplate(applicationDataSource).queryForList(
                "SELECT t.id AS task_id, t.status, t.finish_reason, s.id AS session_id "
                        + "FROM agent_task t JOIN chat_session s ON s.id=t.session_id "
                        + "WHERE s.metadata->>'agentEvidenceCaseId'=? AND s.metadata->>'runIndex'=? "
                        + "AND s.metadata->>'agentEvidenceExperiment'=? "
                        + "AND t.status <> 'RUNNING' ORDER BY t.started_at DESC LIMIT 1",
                caseId, Integer.toString(runIndex), EXPERIMENT);
        if (rows.isEmpty()) {
            return null;
        }
        String taskId = String.valueOf(rows.get(0).get("task_id"));
        return new ExistingTask(agentTaskMapper.selectById(taskId), String.valueOf(rows.get(0).get("session_id")));
    }

    private record ExistingTask(AgentTask task, String sessionId) {
    }

    private AgentTask awaitTerminal(WebConsoleChatSendResponse response) throws InterruptedException {
        long deadline = System.nanoTime() + TASK_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            AgentTask task = agentTaskMapper.selectRecentBySessionId(response.getConversationId(), 10).stream()
                    .filter(value -> response.getRunId().equals(value.getTraceId()))
                    .filter(value -> response.getUserMessageId().equals(value.getUserMessageId()))
                    .findFirst().orElse(null);
            if (task != null && !AgentTaskLogService.STATUS_RUNNING.equals(task.getStatus())) {
                return task;
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
        throw new IllegalStateException("Agent Task timeout: runId=" + response.getRunId());
    }

    private JsonNode parseArguments(String arguments) {
        try {
            return objectMapper.readTree(arguments == null ? "{}" : arguments);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private String classify(AgentTaskEvalCase evalCase,
                            List<com.kama.jchatmind.model.entity.ToolCallLog> calls,
                            List<SearchDiagnostic> searches,
                            String finalAnswer) {
        if (searches.isEmpty() && AgentTaskEvalCase.safe(evalCase.requiredTools).contains("searchProjectCode")) {
            return "TOOL_SELECTION";
        }
        if (!evalCase.expectedArgumentKeywords.isEmpty()
                && searches.stream().noneMatch(search -> containsAll(search.query(), evalCase.expectedArgumentKeywords))) {
            return "TOOL_ARGUMENT";
        }
        if (!searches.isEmpty() && searches.stream().noneMatch(SearchDiagnostic::rawGtHit)) {
            return "RETRIEVAL_MISS";
        }
        if (!searches.isEmpty() && searches.stream().anyMatch(SearchDiagnostic::rawGtHit)
                && searches.stream().noneMatch(SearchDiagnostic::selectedGtHit)) {
            return "SELECTOR_MISS";
        }
        boolean toolEvidenceHit = calls.stream().map(com.kama.jchatmind.model.entity.ToolCallLog::getResultSummary)
                .filter(Objects::nonNull).anyMatch(value -> evidenceMatches(evalCase, value));
        if (searches.stream().anyMatch(SearchDiagnostic::selectedGtHit) && !toolEvidenceHit) {
            return "EVIDENCE_PIPELINE_ERROR";
        }
        if (searches.stream().anyMatch(SearchDiagnostic::selectedGtHit)
                && !evidenceMatches(evalCase, finalAnswer == null ? "" : finalAnswer)) {
            return "AGENT_EVIDENCE_USAGE";
        }
        if (finalAnswer == null || finalAnswer.isBlank()) {
            return "FINAL_ANSWER_ERROR";
        }
        return "SUCCESS";
    }

    private String finalAnswer(List<ChatMessageDTO> messages) {
        return messages.stream()
                .filter(message -> message.getRole() == ChatMessageDTO.RoleType.ASSISTANT)
                .filter(message -> message.getMetadata() == null
                        || message.getMetadata().getToolCalls() == null
                        || message.getMetadata().getToolCalls().isEmpty())
                .filter(message -> StringUtils.hasText(message.getContent()))
                .max(Comparator.comparing(ChatMessageDTO::getCreatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(ChatMessageDTO::getContent).orElse(null);
    }

    private boolean evidenceMatches(AgentTaskEvalCase evalCase, String value) {
        return groupMatches(value, evalCase.expectedEvidenceFileKeywords)
                && groupMatches(value, evalCase.expectedEvidenceSymbolKeywords)
                && groupMatches(value, evalCase.expectedEvidenceKeywords);
    }

    private boolean groupMatches(String value, List<String> expected) {
        List<String> safe = AgentTaskEvalCase.safe(expected);
        String normalized = normalize(value);
        return safe.isEmpty() || safe.stream().anyMatch(keyword -> normalized.contains(normalize(keyword)));
    }

    private boolean containsAll(String value, List<String> expected) {
        String normalized = normalize(value);
        return expected.stream().allMatch(keyword -> normalized.contains(normalize(keyword)));
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private Path outputDirectory() {
        String configured = System.getProperty("agent.evidence.diagnostic.outputDir");
        return StringUtils.hasText(configured)
                ? Path.of(configured) : Path.of("target", "eval", "agent-evidence-presentation-v1");
    }

    record RunResult(String caseId, String category, String difficulty, int runIndex,
                     AgentTask task, int thinkSteps, int toolCalls, int searchCount,
                     boolean finalAnswerExists, String failureLayer, String finalAnswer,
                     List<SearchDiagnostic> searches) {
    }

    record SearchDiagnostic(int searchIndex, String query, String repositoryId,
                            int rawTopK, boolean rawGtHit, Integer rawGtRank,
                            boolean selectedGtHit, Integer selectedGtRank,
                            int rawCandidateCount, int selectedCandidateCount,
                            boolean selectorFallback, boolean selectorExecutionError,
                            boolean emptySelectorResult, String selectorReason,
                            String rawCandidateIds, String selectedCandidateIds,
                            int agentResultChars, String selectedEvidence) {
        static SearchDiagnostic from(int searchIndex, String query, String repositoryId,
        CodeSearchExecutionResult execution, String selectedToolResult,
        AgentTaskEvalCase evalCase,
                                     int rawTopK) {
            List<CodeSearchResult> raw = execution.getCandidates() == null
                    ? List.of() : execution.getCandidates();
            Integer rawRank = rank(raw, evalCase);
            Integer selectedRank = selectedRank(selectedToolResult, evalCase);
            boolean selectedHit = selectedRank != null;
            return new SearchDiagnostic(searchIndex, query, repositoryId, rawTopK,
                    rawRank != null, rawRank, selectedHit, selectedRank,
                    raw.size(), selectedHit ? 1 : 0, false, false, false,
                    "selected evidence read from ToolCallLog.resultSummary", ids(raw), "",
                    selectedToolResult.codePointCount(0, selectedToolResult.length()), selectedToolResult);
        }

        private static Integer rank(List<CodeSearchResult> values, AgentTaskEvalCase evalCase) {
            for (int i = 0; i < values.size(); i++) {
                if (matches(values.get(i), evalCase)) {
                    return i + 1;
                }
            }
            return null;
        }

        private static boolean matches(CodeSearchResult result, AgentTaskEvalCase evalCase) {
            String text = String.join(" ", safe(result.getFilePath()), safe(result.getSymbolName()),
                    safe(result.getApiPath()), safe(result.getChunkType()), safe(result.getContentPreview()),
                    safe(result.getMetadata()));
            return group(text, evalCase.expectedEvidenceFileKeywords)
                    && group(text, evalCase.expectedEvidenceSymbolKeywords)
                    && group(text, evalCase.expectedEvidenceKeywords);
        }

        private static boolean matchesText(String value, AgentTaskEvalCase evalCase) {
            return group(value, evalCase.expectedEvidenceFileKeywords)
                    && group(value, evalCase.expectedEvidenceSymbolKeywords)
                    && group(value, evalCase.expectedEvidenceKeywords);
        }

        private static Integer selectedRank(String value, AgentTaskEvalCase evalCase) {
            if (value == null || value.isBlank()) {
                return null;
            }
            String[] snippets = value.split("\\[code snippet\\]");
            int rank = 0;
            for (String snippet : snippets) {
                if (!snippet.isBlank()) {
                    rank++;
                    if (matchesText(snippet, evalCase)) {
                        return rank;
                    }
                }
            }
            return null;
        }

        private static boolean group(String value, List<String> expected) {
            String normalized = value.toLowerCase(Locale.ROOT);
            return AgentTaskEvalCase.safe(expected).isEmpty()
                    || AgentTaskEvalCase.safe(expected).stream()
                    .anyMatch(keyword -> normalized.contains(safe(keyword).toLowerCase(Locale.ROOT)));
        }

        private static String ids(List<CodeSearchResult> values) {
            return values.stream().map(value -> safe(value.getChunkId())).reduce((a, b) -> a + "|" + b).orElse("");
        }

        private static String safe(String value) {
            return value == null ? "" : value;
        }
    }

    static final class DiagnosticReportWriter {
        void write(Path directory, List<AgentTaskEvalCase> cases, List<RunResult> runs,
                   CodeRepository repository, String agentId, int rawTopK) throws IOException {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("agent-evidence-run-detail.csv"), detailCsv(runs), StandardCharsets.UTF_8);
            Files.writeString(directory.resolve("agent-evidence-case-summary.csv"), summaryCsv(cases, runs), StandardCharsets.UTF_8);
            Files.writeString(directory.resolve("agent-evidence-presentation-call-detail.csv"),
                    presentationCsv(runs), StandardCharsets.UTF_8);
            Files.writeString(directory.resolve("agent-evidence-diagnostic-report.md"),
                    markdown(cases, runs, repository, agentId, rawTopK), StandardCharsets.UTF_8);
        }

        String detailCsv(List<RunResult> runs) {
            List<String> rows = new ArrayList<>();
            rows.add("case_id,run,status,failure_layer,think_steps,tool_calls,search_count,final_answer_exists,task_id,trace_id,latency_ms");
            for (RunResult run : runs) {
                rows.add(csv(List.of(run.caseId(), Integer.toString(run.runIndex()), run.task().getStatus(),
                        run.failureLayer(), Integer.toString(run.thinkSteps()), Integer.toString(run.toolCalls()),
                        Integer.toString(run.searchCount()), Boolean.toString(run.finalAnswerExists()),
                        run.task().getId(), safe(run.task().getTraceId()), Long.toString(run.task().getLatencyMs() == null ? 0 : run.task().getLatencyMs()))));
            }
            return String.join("\n", rows) + "\n";
        }

        String summaryCsv(List<AgentTaskEvalCase> cases, List<RunResult> runs) {
            List<String> rows = new ArrayList<>();
            rows.add("case_id,success_count,tool_selection_count,tool_argument_count,retrieval_miss_count,selector_miss_count,evidence_pipeline_error_count,result_guard_evidence_loss_count,agent_evidence_usage_count,other_count");
            for (AgentTaskEvalCase evalCase : cases) {
                List<RunResult> group = runs.stream().filter(run -> evalCase.id.equals(run.caseId())).toList();
                rows.add(csv(List.of(evalCase.id,
                        count(group, "SUCCESS"), count(group, "TOOL_SELECTION"), count(group, "TOOL_ARGUMENT"),
                        count(group, "RETRIEVAL_MISS"), count(group, "SELECTOR_MISS"), count(group, "EVIDENCE_PIPELINE_ERROR"),
                        count(group, "RESULT_GUARD_EVIDENCE_LOSS"), count(group, "AGENT_EVIDENCE_USAGE"),
                        Integer.toString(group.size() - (int) group.stream().map(RunResult::failureLayer)
                                .filter(layer -> List.of("SUCCESS", "TOOL_SELECTION", "TOOL_ARGUMENT", "RETRIEVAL_MISS", "SELECTOR_MISS", "EVIDENCE_PIPELINE_ERROR", "RESULT_GUARD_EVIDENCE_LOSS", "AGENT_EVIDENCE_USAGE").contains(layer)).count()))));
            }
            return String.join("\n", rows) + "\n";
        }

        String presentationCsv(List<RunResult> runs) {
            List<String> rows = new ArrayList<>();
            rows.add("case_id,run,search_index,result_chars,query,selected_evidence");
            for (RunResult run : runs) {
                for (SearchDiagnostic search : run.searches()) {
                    rows.add(csv(List.of(run.caseId(), Integer.toString(run.runIndex()),
                            Integer.toString(search.searchIndex()), Integer.toString(search.agentResultChars()),
                            search.query(), search.selectedEvidence())));
                }
            }
            return String.join("\n", rows) + "\n";
        }

        String markdown(List<AgentTaskEvalCase> cases, List<RunResult> runs,
                        CodeRepository repository, String agentId, int rawTopK) {
            StringBuilder out = new StringBuilder();
            out.append("# Agent Evidence Diagnostic\n\n")
                    .append("- runs per case: 3\n- agent: ").append(agentId)
                    .append("\n- model: gpt-5.5\n- repository: ").append(repository.getName())
                    .append(" (").append(repository.getId()).append(")\n- rawTopK: ").append(rawTopK)
                    .append("\n- experiment: ").append(EXPERIMENT)
                    .append("\n- generated: ").append(OffsetDateTime.now()).append("\n\n")
                    .append("Raw/selector values are query-level replays using the actual searchProjectCode arguments; production runtime and fixture were not modified.\n\n");
            out.append("## Run Detail\n\n| case | run | status | failureLayer | thinkSteps | toolCalls | searchCount |\n| --- | ---: | --- | --- | ---: | ---: | ---: |\n");
            for (RunResult run : runs) {
                out.append('|').append(run.caseId()).append('|').append(run.runIndex()).append('|')
                        .append(run.task().getStatus()).append('|').append(run.failureLayer()).append('|')
                        .append(run.thinkSteps()).append('|').append(run.toolCalls()).append('|').append(run.searchCount()).append("|\n");
            }
            out.append("\n## Code Search Detail\n\n| case | run | search | query | rawGtHit | rawGtRank | selectedGtHit | selectedGtRank | selectorFallback | selectorError |\n| --- | ---: | ---: | --- | --- | ---: | --- | ---: | --- | --- |\n");
            for (RunResult run : runs) {
                for (SearchDiagnostic search : run.searches()) {
                    out.append('|').append(run.caseId()).append('|').append(run.runIndex()).append('|')
                            .append(search.searchIndex()).append('|').append(search.query().replace('|', ' ')).append('|')
                            .append(search.rawGtHit()).append('|').append(value(search.rawGtRank())).append('|')
                            .append(search.selectedGtHit()).append('|').append(value(search.selectedGtRank())).append('|')
                            .append("UNAVAILABLE|UNAVAILABLE|\n");
                }
            }
            List<Integer> resultChars = runs.stream().flatMap(run -> run.searches().stream())
                    .map(SearchDiagnostic::agentResultChars).sorted().toList();
            out.append("\n## Agent-facing Result Size\n\n")
                    .append("- calls: ").append(resultChars.size()).append('\n')
                    .append("- mean: ").append(mean(resultChars)).append('\n')
                    .append("- p50: ").append(percentile(resultChars, 0.50)).append('\n')
                    .append("- p95: ").append(percentile(resultChars, 0.95)).append('\n')
                    .append("- max: ").append(resultChars.isEmpty() ? 0 : resultChars.get(resultChars.size() - 1)).append('\n');
            out.append("\n## hard_duplicate_order_001 After Runs\n\n")
                    .append("| run | searchCount | matcherStatus | finalAnswer |\n")
                    .append("| ---: | ---: | --- | --- |\n");
            runs.stream().filter(run -> "hard_duplicate_order_001".equals(run.caseId())).forEach(run ->
                    out.append('|').append(run.runIndex()).append('|').append(run.searchCount()).append('|')
                            .append(run.failureLayer()).append('|')
                            .append(safe(run.finalAnswer()).replace('|', ' ').replace('\n', ' ')).append("|\n"));
            out.append("\n## Case Stability\n\n| case | success | retrievalMiss | selectorMiss | pipelineError | agentUsage | other |\n| --- | ---: | ---: | ---: | ---: | ---: | ---: |\n");
            for (AgentTaskEvalCase evalCase : cases) {
                List<RunResult> group = runs.stream().filter(run -> evalCase.id.equals(run.caseId())).toList();
                out.append('|').append(evalCase.id).append('|').append(count(group, "SUCCESS"))
                        .append('|').append(count(group, "RETRIEVAL_MISS")).append('|').append(count(group, "SELECTOR_MISS"))
                        .append('|').append(count(group, "EVIDENCE_PIPELINE_ERROR")).append('|').append(count(group, "AGENT_EVIDENCE_USAGE"))
                        .append('|').append(otherCount(group)).append("|\n");
            }
            out.append("\n## Signals\n\n")
                    .append("- Query Rewrite Signal: INSUFFICIENT_EVIDENCE (diagnostic evidence only; no optimization performed).\n")
                    .append("- Selector Signal: ").append(selectorSignal(runs)).append(".\n")
                    .append("- Agent Query Planning Signal: ").append(planningSignal(runs)).append(".\n")
                    .append("- Result Guard Signal: UNAVAILABLE; ToolCallLog resultTruncated is a mixed runtime/summary field and no production schema was changed.\n\n")
                    .append("## Failure Layer Distribution\n\n");
            Map<String, Long> counts = runs.stream().collect(java.util.stream.Collectors.groupingBy(RunResult::failureLayer, LinkedHashMap::new, java.util.stream.Collectors.counting()));
            counts.forEach((layer, count) -> out.append("- ").append(layer).append(": ").append(count).append('\n'));
            return out.toString();
        }

        private long mean(List<Integer> values) {
            return values.isEmpty() ? 0 : Math.round(values.stream().mapToInt(Integer::intValue).average().orElse(0));
        }

        private int percentile(List<Integer> sorted, double percentile) {
            if (sorted.isEmpty()) {
                return 0;
            }
            int index = (int) Math.ceil(percentile * sorted.size()) - 1;
            return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
        }

        private String selectorSignal(List<RunResult> runs) {
            long rawHit = runs.stream().flatMap(run -> run.searches().stream()).filter(SearchDiagnostic::rawGtHit).count();
            long selectorMiss = runs.stream().flatMap(run -> run.searches().stream())
                    .filter(SearchDiagnostic::rawGtHit).filter(search -> !search.selectedGtHit()).count();
            return rawHit > 0 && selectorMiss > 0 ? "YES" : "NO";
        }

        private String planningSignal(List<RunResult> runs) {
            long highSearchRuns = runs.stream().filter(run -> run.searchCount() >= 5).count();
            return highSearchRuns > 0 ? "INSUFFICIENT_EVIDENCE" : "NO";
        }

        private String count(List<RunResult> runs, String layer) {
            return Long.toString(runs.stream().filter(run -> layer.equals(run.failureLayer())).count());
        }

        private long otherCount(List<RunResult> runs) {
            return runs.stream().filter(run -> !List.of("SUCCESS", "RETRIEVAL_MISS", "SELECTOR_MISS", "EVIDENCE_PIPELINE_ERROR", "AGENT_EVIDENCE_USAGE").contains(run.failureLayer())).count();
        }

        private String value(Integer value) { return value == null ? "" : value.toString(); }

        private String csv(List<String> values) {
            return values.stream().map(this::escape).reduce((a, b) -> a + "," + b).orElse("");
        }

        private String escape(String value) {
            String safe = value == null ? "" : value;
            return safe.contains(",") || safe.contains("\"") || safe.contains("\n")
                    ? "\"" + safe.replace("\"", "\"\"") + "\"" : safe;
        }

        private String safe(String value) { return value == null ? "" : value; }
    }
}
