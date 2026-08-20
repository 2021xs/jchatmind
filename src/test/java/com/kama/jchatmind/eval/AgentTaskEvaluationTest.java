package com.kama.jchatmind.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.ChatClientRegistry;
import com.kama.jchatmind.mapper.AgentMapper;
import com.kama.jchatmind.mapper.AgentStepMapper;
import com.kama.jchatmind.mapper.AgentTaskMapper;
import com.kama.jchatmind.mapper.CodeRepositoryMapper;
import com.kama.jchatmind.mapper.ToolCallLogMapper;
import com.kama.jchatmind.model.common.ChatSessionChannel;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Tag("agent-eval")
@EnabledIf("agentEvalEnabled")
@SpringBootTest
@TestPropertySource(properties = {
        "jchatmind.agent.observability.recovery-enabled=false",
        "jchatmind.code-rag.embedding-warmup.enabled=false"
})
class AgentTaskEvaluationTest {
    private static final String CASE_RESOURCE = "eval/agent_task_eval_cases.json";
    private static final Path DEFAULT_OUTPUT_DIRECTORY = Path.of("target", "eval", "agent-task");
    private static final String DEFAULT_REPOSITORY_NAME = "FlashDeal";
    private static final String DEFAULT_MODEL = "gpt-5.5";
    private static final int DEFAULT_TASK_TIMEOUT_SECONDS = 300;
    private static final Duration POLL_INTERVAL = Duration.ofMillis(200);

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WebConsoleChatService webConsoleChatService;

    @Autowired
    private ChatSessionFacadeService chatSessionFacadeService;

    @Autowired
    private ChatMessageFacadeService chatMessageFacadeService;

    @Autowired
    private AgentTaskMapper agentTaskMapper;

    @Autowired
    private AgentStepMapper agentStepMapper;

    @Autowired
    private ToolCallLogMapper toolCallLogMapper;

    @Autowired
    private CodeRepositoryMapper codeRepositoryMapper;

    @Autowired
    private AgentMapper agentMapper;

    @Autowired
    private ChatClientRegistry chatClientRegistry;

    @Autowired
    private RagService ragService;

    @Autowired
    @Qualifier("dataSource")
    private DataSource applicationDataSource;

    @Autowired
    @Qualifier("databaseToolJdbcTemplate")
    private JdbcTemplate databaseToolJdbcTemplate;

    private final AgentTaskTrajectoryEvaluator evaluator = new AgentTaskTrajectoryEvaluator();
    private final AgentTaskEvaluationReportWriter reportWriter = new AgentTaskEvaluationReportWriter();

    @Test
    void evaluateRealAgentTasks() throws Exception {
        List<AgentTaskEvalCase> cases = selectedCases(loadCases());
        validateCases(cases);
        CodeRepository repository = resolveRepository();
        Agent agent = resolveAgent();
        String model = resolveModel();
        RepositoryCounts repositoryCounts = preflight(repository, model);

        List<AgentTaskEvalResult> results = new ArrayList<>();
        for (AgentTaskEvalCase evalCase : cases) {
            AgentTaskTrajectory trajectory = execute(evalCase, repository, agent, model);
            AgentTaskEvalResult result = evaluator.evaluate(evalCase, trajectory);
            results.add(result);
            System.out.printf(
                    "Agent Task Eval case=%s status=%s failure=%s steps=%d requested=%d executed=%d latencyMs=%d%n",
                    evalCase.id, result.taskStatus(), result.failureType(), result.thinkSteps(),
                    result.requestedToolCalls(), result.executedToolCalls(), result.totalLatencyMs());
        }

        Path outputDirectory = outputDirectory();
        reportWriter.write(outputDirectory, results, new AgentTaskEvaluationReportWriter.Environment(
                OffsetDateTime.now(), System.getProperty("java.version", "unknown"),
                repository.getId(), repository.getName(), repositoryCounts.files(), repositoryCounts.chunks(),
                repositoryCounts.embeddings(), agent.getId(), model));
        System.out.println("Agent Task Eval completed: cases=" + results.size()
                + ", outputDirectory=" + outputDirectory.toAbsolutePath());
    }

    private AgentTaskTrajectory execute(AgentTaskEvalCase evalCase, CodeRepository repository,
                                        Agent agent, String model) throws InterruptedException {
        CreateChatSessionRequest sessionRequest = new CreateChatSessionRequest();
        sessionRequest.setAgentId(agent.getId());
        sessionRequest.setTitle("Agent Eval " + evalCase.id);
        sessionRequest.setChannel(ChatSessionChannel.WEB_CONSOLE.name());
        sessionRequest.setRepoId(repository.getId());
        sessionRequest.setModel(model);
        sessionRequest.setMetadata(new LinkedHashMap<>(Map.of(
                "agentEval", true,
                "agentEvalCaseId", evalCase.id
        )));
        String sessionId = chatSessionFacadeService.createChatSession(sessionRequest).getChatSessionId();

        WebConsoleChatSendRequest sendRequest = new WebConsoleChatSendRequest();
        sendRequest.setConversationId(sessionId);
        sendRequest.setAgentId(agent.getId());
        sendRequest.setModel(model);
        sendRequest.setRepoId(repository.getId());
        sendRequest.setContent(evalCase.query);
        WebConsoleChatSendResponse sendResponse = webConsoleChatService.send(sendRequest);

        AgentTask task = awaitTerminalTask(sendResponse, taskTimeout());
        return new AgentTaskTrajectory(
                task,
                agentStepMapper.selectByTaskId(task.getId()),
                toolCallLogMapper.selectByTaskId(task.getId()),
                finalAnswer(chatMessageFacadeService.getChatMessageDTOsBySessionId(sessionId))
        );
    }

    private AgentTask awaitTerminalTask(WebConsoleChatSendResponse response, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        AgentTask matched = null;
        while (System.nanoTime() < deadline) {
            matched = matchingTask(response);
            if (matched != null && !AgentTaskLogService.STATUS_RUNNING.equals(matched.getStatus())) {
                return matched;
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
        String state = matched == null ? "not-created" : matched.getStatus();
        throw new IllegalStateException("Agent Task did not reach a terminal state within "
                + timeout.toSeconds() + "s: runId=" + response.getRunId()
                + ", userMessageId=" + response.getUserMessageId() + ", lastState=" + state);
    }

    private AgentTask matchingTask(WebConsoleChatSendResponse response) {
        return agentTaskMapper.selectRecentBySessionId(response.getConversationId(), 10).stream()
                .filter(task -> response.getRunId().equals(task.getTraceId()))
                .filter(task -> response.getUserMessageId().equals(task.getUserMessageId()))
                .findFirst()
                .orElse(null);
    }

    static String finalAnswer(List<ChatMessageDTO> messages) {
        if (messages == null) {
            return null;
        }
        return messages.stream()
                .filter(message -> message.getRole() == ChatMessageDTO.RoleType.ASSISTANT)
                .filter(AgentTaskEvaluationTest::isUserVisibleAssistant)
                .filter(message -> StringUtils.hasText(message.getContent()))
                .max(Comparator.comparing(ChatMessageDTO::getCreatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(ChatMessageDTO::getContent)
                .orElse(null);
    }

    private static boolean isUserVisibleAssistant(ChatMessageDTO message) {
        return message.getMetadata() == null
                || message.getMetadata().getToolCalls() == null
                || message.getMetadata().getToolCalls().isEmpty();
    }

    private RepositoryCounts preflight(CodeRepository repository, String model) {
        if (!"READY".equalsIgnoreCase(repository.getStatus())) {
            throw new IllegalStateException("Agent Eval repository is not READY: id=" + repository.getId()
                    + ", name=" + repository.getName() + ", status=" + repository.getStatus());
        }
        long files = count("SELECT COUNT(*) FROM code_file WHERE repo_id = CAST(? AS uuid)", repository.getId());
        long chunks = count("SELECT COUNT(*) FROM code_chunk WHERE repo_id = CAST(? AS uuid)", repository.getId());
        long embeddings = count("SELECT COUNT(*) FROM code_chunk WHERE repo_id = CAST(? AS uuid) "
                + "AND embedding IS NOT NULL", repository.getId());
        if (files <= 0 || chunks <= 0 || embeddings != chunks) {
            throw new IllegalStateException("FlashDeal benchmark data is incomplete: files=" + files
                    + ", chunks=" + chunks + ", embeddings=" + embeddings);
        }
        Integer databaseProbe = databaseToolJdbcTemplate.queryForObject("SELECT 1", Integer.class);
        if (databaseProbe == null || databaseProbe != 1) {
            throw new IllegalStateException("databaseToolJdbcTemplate SELECT 1 preflight returned " + databaseProbe);
        }
        float[] embedding = ragService.embed("agent task eval preflight");
        if (embedding == null || embedding.length == 0) {
            throw new IllegalStateException("Embedding preflight returned an empty vector");
        }
        if (!chatClientRegistry.contains(model)) {
            throw new IllegalStateException("Agent Eval model is not registered: " + model);
        }
        return new RepositoryCounts(files, chunks, embeddings);
    }

    private long count(String sql, String repositoryId) {
        Long value = new JdbcTemplate(applicationDataSource).queryForObject(sql, Long.class, repositoryId);
        return value == null ? 0 : value;
    }

    private CodeRepository resolveRepository() {
        String configuredId = configured("agent.eval.repoId", "AGENT_TASK_EVAL_REPO_ID");
        if (StringUtils.hasText(configuredId)) {
            CodeRepository repository = codeRepositoryMapper.selectById(configuredId);
            if (repository == null) {
                throw new IllegalArgumentException("Agent Eval repository does not exist: " + configuredId);
            }
            return repository;
        }
        return codeRepositoryMapper.selectAll().stream()
                .filter(repository -> DEFAULT_REPOSITORY_NAME.equalsIgnoreCase(repository.getName()))
                .filter(repository -> "READY".equalsIgnoreCase(repository.getStatus()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No READY FlashDeal repository found; configure -Dagent.eval.repoId=<repoId>"));
    }

    private Agent resolveAgent() {
        String configuredId = configured("agent.eval.agentId", "AGENT_TASK_EVAL_AGENT_ID");
        if (StringUtils.hasText(configuredId)) {
            Agent agent = agentMapper.selectById(configuredId);
            if (agent == null) {
                throw new IllegalArgumentException("Agent Eval agent does not exist: " + configuredId);
            }
            return agent;
        }
        return agentMapper.selectAll().stream()
                .filter(this::supportsBenchmarkTools)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No Agent exposes searchProjectCode and databaseQuery; configure -Dagent.eval.agentId=<agentId>"));
    }

    private boolean supportsBenchmarkTools(Agent agent) {
        String allowedTools = agent.getAllowedTools();
        return StringUtils.hasText(allowedTools)
                && allowedTools.contains("searchProjectCode")
                && allowedTools.contains("databaseQuery");
    }

    private String resolveModel() {
        String model = configured("agent.eval.model", "AGENT_TASK_EVAL_MODEL");
        if (!StringUtils.hasText(model)) {
            model = DEFAULT_MODEL;
        }
        if (!chatClientRegistry.contains(model)) {
            throw new IllegalStateException("Agent Eval model is not registered: " + model);
        }
        return model;
    }

    private List<AgentTaskEvalCase> loadCases() throws IOException {
        ClassPathResource resource = new ClassPathResource(CASE_RESOURCE);
        return objectMapper.readValue(resource.getInputStream(), new TypeReference<>() {
        });
    }

    private List<AgentTaskEvalCase> selectedCases(List<AgentTaskEvalCase> cases) {
        String caseId = System.getProperty("agent.eval.caseId");
        if (StringUtils.hasText(caseId)) {
            return cases.stream().filter(evalCase -> caseId.equals(evalCase.id)).toList();
        }
        int limit = Integer.getInteger("agent.eval.limit", cases.size());
        return cases.subList(0, Math.min(Math.max(limit, 0), cases.size()));
    }

    private void validateCases(List<AgentTaskEvalCase> cases) {
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("No Agent Task Eval cases selected");
        }
        for (AgentTaskEvalCase evalCase : cases) {
            if (!evalCase.valid()) {
                throw new IllegalArgumentException("Invalid Agent Task Eval fixture: caseId=" + evalCase.id);
            }
        }
    }

    private Duration taskTimeout() {
        int seconds = Integer.getInteger("agent.eval.taskTimeoutSeconds", DEFAULT_TASK_TIMEOUT_SECONDS);
        if (seconds <= 0) {
            throw new IllegalArgumentException("agent.eval.taskTimeoutSeconds must be positive");
        }
        return Duration.ofSeconds(seconds);
    }

    private Path outputDirectory() {
        String configured = System.getProperty("agent.eval.outputDir");
        return StringUtils.hasText(configured) ? Path.of(configured) : DEFAULT_OUTPUT_DIRECTORY;
    }

    private String configured(String systemProperty, String environmentVariable) {
        String value = System.getProperty(systemProperty);
        return StringUtils.hasText(value) ? value.trim() : System.getenv(environmentVariable);
    }

    static boolean agentEvalEnabled() {
        return Boolean.getBoolean("agent.eval.enabled");
    }

    private record RepositoryCounts(long files, long chunks, long embeddings) {
    }
}
