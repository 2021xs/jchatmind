package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.agent.AgentToolProtocolInspector;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.mapper.ChatMessageMapper;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.service.ChatMessageFacadeService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    private String sessionId;
    private String taskId;

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
        jdbc.execute("TRUNCATE TABLE chat_message");
        sessionId = UUID.randomUUID().toString();
        taskId = UUID.randomUUID().toString();
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

    private AssistantMessage assistantBatch() {
        return AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(
                        new AssistantMessage.ToolCall("call-a", "function", "toolA", "{}"),
                        new AssistantMessage.ToolCall("call-b", "function", "toolB", "{}")))
                .build();
    }

    private ToolResponseMessage responseBatch(String first, String second) {
        return ToolResponseMessage.builder()
                .responses(List.of(
                        new ToolResponseMessage.ToolResponse("call-a", "toolA", first),
                        new ToolResponseMessage.ToolResponse("call-b", "toolB", second)))
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
            factory.setMapperLocations(new ClassPathResource("mapper/ChatMessageMapper.xml"));
            return factory.getObject();
        }

        @Bean
        ChatMessageMapper chatMessageMapper(SqlSessionFactory factory) throws Exception {
            MapperFactoryBean<ChatMessageMapper> bean = new MapperFactoryBean<>(ChatMessageMapper.class);
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
    }
}
