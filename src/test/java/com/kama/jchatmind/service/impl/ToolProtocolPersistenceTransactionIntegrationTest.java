package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.agent.AgentToolProtocolInspector;
import com.kama.jchatmind.agent.tools.CodeSearchTools;
import com.kama.jchatmind.config.AgentObservabilityProperties;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.mapper.AgentStepMapper;
import com.kama.jchatmind.mapper.AgentTaskMapper;
import com.kama.jchatmind.mapper.ChatMessageMapper;
import com.kama.jchatmind.mapper.ChatSessionMapper;
import com.kama.jchatmind.mapper.ToolCallLogMapper;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.dto.CodeAnswerEvidenceResult;
import com.kama.jchatmind.model.dto.CodeSearchResult;
import com.kama.jchatmind.service.AgentTaskLogService;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.CodeRagAnswerEvidenceService;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
@SpringJUnitConfig(ToolProtocolPersistenceTransactionIntegrationTest.Config.class)
class ToolProtocolPersistenceTransactionIntegrationTest {
    private static final String FAIL_SECOND_INSERT = "__FAIL_SECOND_TOOL_INSERT__";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @org.springframework.beans.factory.annotation.Autowired
    private ChatMessageFacadeService messageService;

    @org.springframework.beans.factory.annotation.Autowired
    private JdbcTemplate jdbc;

    @org.springframework.beans.factory.annotation.Autowired
    private AgentTaskLogService taskLogService;

    private String sessionId;
    private String taskId;
    private String stepId;

    @BeforeEach
    void setUpSchema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS chat_message (
                    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                    session_id uuid NOT NULL,
                    role varchar(32) NOT NULL,
                    content text CHECK (content IS DISTINCT FROM '__FAIL_SECOND_TOOL_INSERT__'),
                    metadata jsonb,
                    created_at timestamp,
                    updated_at timestamp
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agent_task (
                    id uuid PRIMARY KEY,
                    session_id uuid NOT NULL,
                    agent_id uuid,
                    user_message_id uuid,
                    status varchar(32) NOT NULL,
                    goal text,
                    finish_reason varchar(128),
                    model_name varchar(128),
                    max_steps integer,
                    actual_steps integer,
                    tool_call_count integer,
                    latency_ms bigint,
                    trace_id varchar(128),
                    heartbeat_at timestamp,
                    started_at timestamp,
                    finished_at timestamp,
                    updated_at timestamp,
                    error_message text
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agent_step (
                    id uuid PRIMARY KEY,
                    task_id uuid NOT NULL,
                    step_no integer NOT NULL,
                    step_type varchar(64) NOT NULL,
                    status varchar(32) NOT NULL,
                    input_summary text,
                    output_summary text,
                    latency_ms bigint,
                    model_name varchar(128),
                    llm_latency_ms bigint,
                    input_tokens integer,
                    output_tokens integer,
                    finish_reason varchar(128),
                    started_at timestamp,
                    finished_at timestamp,
                    updated_at timestamp,
                    error_message text
                )
                """);
        jdbc.execute("TRUNCATE TABLE chat_message, agent_step, agent_task");
        sessionId = UUID.randomUUID().toString();
        taskId = UUID.randomUUID().toString();
        stepId = UUID.randomUUID().toString();
        LocalDateTime startedAt = LocalDateTime.now().minusSeconds(1);
        jdbc.update("""
                        INSERT INTO agent_task
                        (id, session_id, status, goal, started_at, heartbeat_at, updated_at)
                        VALUES (CAST(? AS uuid), CAST(? AS uuid), 'RUNNING', 'test', ?, ?, ?)
                        """,
                taskId, sessionId, Timestamp.valueOf(startedAt), Timestamp.valueOf(startedAt),
                Timestamp.valueOf(startedAt));
        jdbc.update("""
                        INSERT INTO agent_step
                        (id, task_id, step_no, step_type, status, input_summary, started_at, updated_at)
                        VALUES (CAST(? AS uuid), CAST(? AS uuid), 2, 'TOOL_CALL', 'RUNNING', 'tool', ?, ?)
                        """,
                stepId, taskId, Timestamp.valueOf(startedAt), Timestamp.valueOf(startedAt));
    }

    @Test
    void secondToolInsertFailureRollsBackAssistantAndEveryToolResponse() {
        assertThat(AopUtils.isAopProxy(messageService)).isTrue();

        assertThatThrownBy(() -> messageService.createToolProtocolBatch(
                sessionId, taskId, assistantBatch(), responseBatch("A-ok", FAIL_SECOND_INSERT)))
                .isInstanceOf(RuntimeException.class);

        assertThat(messageCount()).isZero();
    }

    @Test
    void committedBatchReloadsThroughProductionProtocolGrouping() {
        messageService.createToolProtocolBatch(
                sessionId, taskId, assistantBatch(), responseBatch("A-ok", "B-ok"));

        List<ChatMessageDTO> persisted = messageService.getChatMessageDTOsBySessionId(sessionId);
        assertThat(persisted).hasSize(3);
        assertThat(persisted).extracting(ChatMessageDTO::getRole)
                .containsExactly(ChatMessageDTO.RoleType.ASSISTANT,
                        ChatMessageDTO.RoleType.TOOL, ChatMessageDTO.RoleType.TOOL);
        AgentToolProtocolInspector.Inspection inspection = AgentToolProtocolInspector.inspect(
                toProtocolMessages(persisted));
        assertThat(inspection.valid()).isTrue();
        assertThat(inspection.orphanToolProtocolCount()).isZero();
        assertThat(inspection.protocolValidationFailureCount()).isZero();
    }

    @Test
    void oversizedCodeCanonicalResultRetainsExactFingerprintAfterReload() throws Exception {
        String tailMarker = "-CODE-CANONICAL-TAIL";
        CodeRagAnswerEvidenceService evidenceService = mock(CodeRagAnswerEvidenceService.class);
        when(evidenceService.retrieve("repo-1", "large query")).thenReturn(CodeAnswerEvidenceResult.builder()
                .selectedEvidence(List.of(CodeSearchResult.builder()
                        .repoId("repo-1")
                        .chunkId("chunk-large")
                        .filePath("LargeService.java")
                        .symbolName("LargeService#run")
                        .chunkType("SERVICE_METHOD")
                        .startLine(1)
                        .endLine(500)
                        .contentPreview("x".repeat(12_000) + tailMarker)
                        .build()))
                .build());
        String canonical = new CodeSearchTools(evidenceService)
                .searchProjectCode("repo-1", "large query");
        String expectedFingerprint = sha256(canonical);
        assertThat(expectedFingerprint)
                .isEqualTo("af305f51ba23fe83f2b71e6d25da80440e62dbd8e063808fc3ba070e9b59c0ef");
        assertThat(canonical).hasSizeGreaterThan(7_000).endsWith(tailMarker + "\n");
        assertThat(canonical).contains("repoId: repo-1", "chunkId: chunk-large");

        messageService.createToolProtocolBatch(
                sessionId, taskId, assistantBatch(), responseBatch(canonical, "B-ok"));

        List<ChatMessageDTO> persisted = messageService.getChatMessageDTOsBySessionId(sessionId);
        ChatMessageDTO firstToolResponse = persisted.stream()
                .filter(message -> message.getRole() == ChatMessageDTO.RoleType.TOOL)
                .findFirst()
                .orElseThrow();
        assertThat(firstToolResponse.getContent()).isEqualTo(canonical);
        assertThat(firstToolResponse.getContent()).endsWith(tailMarker + "\n");
        assertThat(sha256(firstToolResponse.getContent())).isEqualTo(expectedFingerprint);
        AgentToolProtocolInspector.Inspection inspection = AgentToolProtocolInspector.inspect(
                toProtocolMessages(persisted));
        assertThat(inspection.valid()).isTrue();
        assertThat(inspection.orphanToolProtocolCount()).isZero();
    }

    @Test
    void cancellationRetainsMultipleCommittedBatchesAndKeepsCancelledAuditState() {
        messageService.createToolProtocolBatch(
                sessionId, taskId, assistantBatch("1"), responseBatch("1", "A1-ok", "B1-ok"));
        messageService.createToolProtocolBatch(
                sessionId, taskId, assistantBatch("2"), responseBatch("2", "A2-ok", "B2-ok"));
        int rowsBeforeCancellation = messageCount();

        boolean cancelled = taskLogService.cancelStepAndTask(stepId, taskId, 2, 4);

        assertThat(cancelled).isTrue();
        assertThat(rowsBeforeCancellation).isEqualTo(6);
        assertThat(messageCount()).isEqualTo(rowsBeforeCancellation);
        assertThat(status("agent_task", taskId)).isEqualTo(AgentTaskLogService.STATUS_CANCELLED);
        assertThat(status("agent_step", stepId)).isEqualTo(AgentTaskLogService.STATUS_CANCELLED);
        AgentToolProtocolInspector.Inspection inspection = AgentToolProtocolInspector.inspect(
                toProtocolMessages(messageService.getChatMessageDTOsBySessionId(sessionId)));
        assertThat(inspection.valid()).isTrue();
        assertThat(inspection.orphanToolProtocolCount()).isZero();
        assertThat(inspection.protocolValidationFailureCount()).isZero();
    }

    private AssistantMessage assistantBatch() {
        return assistantBatch("");
    }

    private AssistantMessage assistantBatch(String suffix) {
        return AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(
                        new AssistantMessage.ToolCall("call-a" + suffix, "function", "toolA", "{}"),
                        new AssistantMessage.ToolCall("call-b" + suffix, "function", "toolB", "{}")))
                .build();
    }

    private ToolResponseMessage responseBatch(String first, String second) {
        return responseBatch("", first, second);
    }

    private ToolResponseMessage responseBatch(String suffix, String first, String second) {
        return ToolResponseMessage.builder()
                .responses(List.of(
                        new ToolResponseMessage.ToolResponse("call-a" + suffix, "toolA", first),
                        new ToolResponseMessage.ToolResponse("call-b" + suffix, "toolB", second)))
                .build();
    }

    private List<Message> toProtocolMessages(List<ChatMessageDTO> persisted) {
        List<Message> messages = new ArrayList<>();
        for (ChatMessageDTO dto : persisted) {
            if (dto.getRole() == ChatMessageDTO.RoleType.ASSISTANT) {
                messages.add(AssistantMessage.builder()
                        .content(dto.getContent() == null ? "" : dto.getContent())
                        .toolCalls(dto.getMetadata().getToolCalls())
                        .build());
            } else if (dto.getRole() == ChatMessageDTO.RoleType.TOOL) {
                messages.add(ToolResponseMessage.builder()
                        .responses(List.of(dto.getMetadata().getToolResponse()))
                        .build());
            }
        }
        return messages;
    }

    private int messageCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM chat_message", Integer.class);
    }

    private String status(String table, String id) {
        return jdbc.queryForObject("SELECT status FROM " + table + " WHERE id = CAST(? AS uuid)",
                String.class, id);
    }

    private String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    @Configuration
    @EnableTransactionManagement
    static class Config {
        @Bean
        DataSource dataSource() {
            PGSimpleDataSource dataSource = new PGSimpleDataSource();
            dataSource.setURL(POSTGRES.getJdbcUrl());
            dataSource.setUser(POSTGRES.getUsername());
            dataSource.setPassword(POSTGRES.getPassword());
            return dataSource;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setMapperLocations(
                    new ClassPathResource("mapper/ChatMessageMapper.xml"),
                    new ClassPathResource("mapper/AgentTaskMapper.xml"),
                    new ClassPathResource("mapper/AgentStepMapper.xml"));
            return factory.getObject();
        }

        @Bean
        ChatMessageMapper chatMessageMapper(SqlSessionFactory factory) throws Exception {
            return mapper(ChatMessageMapper.class, factory);
        }

        @Bean
        AgentTaskMapper agentTaskMapper(SqlSessionFactory factory) throws Exception {
            return mapper(AgentTaskMapper.class, factory);
        }

        @Bean
        AgentStepMapper agentStepMapper(SqlSessionFactory factory) throws Exception {
            return mapper(AgentStepMapper.class, factory);
        }

        private static <T> T mapper(Class<T> type, SqlSessionFactory factory) throws Exception {
            MapperFactoryBean<T> bean = new MapperFactoryBean<>(type);
            bean.setSqlSessionFactory(factory);
            bean.afterPropertiesSet();
            return bean.getObject();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        ChatMessageConverter chatMessageConverter(ObjectMapper objectMapper) {
            return new ChatMessageConverter(objectMapper);
        }

        @Bean
        ChatMessageFacadeService chatMessageFacadeService(ChatMessageMapper mapper,
                                                           ChatMessageConverter converter,
                                                           ApplicationEventPublisher publisher) {
            return new ChatMessageFacadeServiceImpl(mapper, converter, publisher);
        }

        @Bean
        AgentTaskLogService agentTaskLogService(AgentTaskMapper taskMapper,
                                                AgentStepMapper stepMapper,
                                                ObjectMapper objectMapper) {
            return new AgentTaskLogServiceImpl(taskMapper, stepMapper,
                    mock(ToolCallLogMapper.class), mock(ChatSessionMapper.class), objectMapper,
                    new AgentObservabilityProperties());
        }
    }
}
