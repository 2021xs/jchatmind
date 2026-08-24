package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.AgentObservabilityProperties;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.mapper.AgentStepMapper;
import com.kama.jchatmind.mapper.AgentTaskMapper;
import com.kama.jchatmind.mapper.ChatMessageMapper;
import com.kama.jchatmind.mapper.ChatSessionMapper;
import com.kama.jchatmind.mapper.ToolCallLogMapper;
import com.kama.jchatmind.service.AgentTaskLogService;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.FinalCompletionService;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.postgresql.ds.PGSimpleDataSource;
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
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@Testcontainers(disabledWithoutDocker = true)
@SpringJUnitConfig(FinalCompletionTransactionIntegrationTest.Config.class)
class FinalCompletionTransactionIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @org.springframework.beans.factory.annotation.Autowired
    private FinalCompletionService completionService;

    @org.springframework.beans.factory.annotation.Autowired
    private AgentTaskLogService taskLogService;

    @org.springframework.beans.factory.annotation.Autowired
    private JdbcTemplate jdbc;

    private String sessionId;
    private String taskId;
    private String finalStepId;

    @BeforeEach
    void setUpSchemaAndRunningLifecycle() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS chat_message (
                    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                    session_id uuid NOT NULL,
                    role varchar(32) NOT NULL,
                    content text,
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
                    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
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
        finalStepId = UUID.randomUUID().toString();
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
                        VALUES (CAST(? AS uuid), CAST(? AS uuid), 3, 'FINAL_SYNTHESIS', 'RUNNING', 'final', ?, ?)
                        """,
                finalStepId, taskId, Timestamp.valueOf(startedAt), Timestamp.valueOf(startedAt));
    }

    @Test
    void coordinatorAndMandatoryLifecycleAreSpringProxiesAndCommitTogether() {
        assertThat(AopUtils.isAopProxy(completionService)).isTrue();
        assertThat(AopUtils.isAopProxy(taskLogService)).isTrue();

        FinalCompletionService.FinalCompletionResult result = completionService.complete(command(finalStepId));

        assertThat(result.messageId()).isNotBlank();
        assertThat(count("chat_message")).isEqualTo(1);
        assertThat(status("agent_task", taskId)).isEqualTo("SUCCESS");
        assertThat(status("agent_step", finalStepId)).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_step WHERE task_id = CAST(? AS uuid) AND step_type = 'FINISH' AND status = 'SUCCESS'",
                Integer.class, taskId)).isEqualTo(1);
    }

    @Test
    void stepFailureAfterMessageInsertRollsBackMessageAndEveryLifecycleMutation() {
        String missingStepId = UUID.randomUUID().toString();

        assertThatThrownBy(() -> completionService.complete(command(missingStepId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Final synthesis step is missing");

        assertThat(count("chat_message")).isZero();
        assertThat(status("agent_task", taskId)).isEqualTo("RUNNING");
        assertThat(status("agent_step", finalStepId)).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_step WHERE task_id = CAST(? AS uuid) AND step_type = 'FINISH'",
                Integer.class, taskId)).isZero();
    }

    @Test
    void taskCompletionFailureRollsBackMessageFinalStepAndFinishStep() {
        jdbc.update("UPDATE agent_task SET status = 'FAILED' WHERE id = CAST(? AS uuid)", taskId);

        assertThatThrownBy(() -> completionService.complete(command(finalStepId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unable to complete Agent Task atomically");

        assertThat(count("chat_message")).isZero();
        assertThat(status("agent_task", taskId)).isEqualTo("FAILED");
        assertThat(status("agent_step", finalStepId)).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_step WHERE task_id = CAST(? AS uuid) AND step_type = 'FINISH'",
                Integer.class, taskId)).isZero();
    }

    private FinalCompletionService.FinalCompletionCommand command(String stepId) {
        return new FinalCompletionService.FinalCompletionCommand(
                sessionId, taskId, "durable answer", stepId, 3, "validated Final", 15L,
                4, AgentTaskLogService.FINISH_REASON_NO_TOOL_CALLS, "test-model", 4, 2);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private String status(String table, String id) {
        return jdbc.queryForObject("SELECT status FROM " + table + " WHERE id = CAST(? AS uuid)",
                String.class, id);
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
                    new ClassPathResource("mapper/AgentStepMapper.xml"),
                    new ClassPathResource("mapper/AgentTaskMapper.xml"));
            return factory.getObject();
        }

        @Bean
        ChatMessageMapper chatMessageMapper(SqlSessionFactory factory) throws Exception {
            return mapper(ChatMessageMapper.class, factory);
        }

        @Bean
        AgentStepMapper agentStepMapper(SqlSessionFactory factory) throws Exception {
            return mapper(AgentStepMapper.class, factory);
        }

        @Bean
        AgentTaskMapper agentTaskMapper(SqlSessionFactory factory) throws Exception {
            return mapper(AgentTaskMapper.class, factory);
        }

        private static <T> T mapper(Class<T> type, SqlSessionFactory factory) throws Exception {
            MapperFactoryBean<T> bean = new MapperFactoryBean<>(type);
            bean.setSqlSessionFactory(factory);
            bean.afterPropertiesSet();
            return bean.getObject();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
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

        @Bean
        FinalCompletionService finalCompletionService(ChatMessageFacadeService messageService,
                                                       AgentTaskLogService taskLogService) {
            return new FinalCompletionServiceImpl(messageService, taskLogService);
        }
    }
}
