package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.converter.ChatSessionConverter;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.AgentStepMapper;
import com.kama.jchatmind.mapper.AgentTaskMapper;
import com.kama.jchatmind.mapper.ChatMessageMapper;
import com.kama.jchatmind.mapper.ChatSessionMapper;
import com.kama.jchatmind.mapper.CodeRepositoryMapper;
import com.kama.jchatmind.mapper.ToolCallLogMapper;
import com.kama.jchatmind.model.entity.ChatSession;
import com.kama.jchatmind.model.entity.CodeRepository;
import com.kama.jchatmind.model.request.CreateChatSessionRequest;
import com.kama.jchatmind.model.request.UpdateChatSessionRequest;
import com.kama.jchatmind.model.response.GetChatSessionsResponse;
import com.kama.jchatmind.model.vo.ChatSessionVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatSessionFacadeServiceImplTest {

    private ChatSessionMapper chatSessionMapper;
    private ChatMessageMapper chatMessageMapper;
    private AgentTaskMapper agentTaskMapper;
    private AgentStepMapper agentStepMapper;
    private ToolCallLogMapper toolCallLogMapper;
    private CodeRepositoryMapper codeRepositoryMapper;
    private ChatSessionFacadeServiceImpl service;

    @BeforeEach
    void setUp() {
        chatSessionMapper = mock(ChatSessionMapper.class);
        chatMessageMapper = mock(ChatMessageMapper.class);
        agentTaskMapper = mock(AgentTaskMapper.class);
        agentStepMapper = mock(AgentStepMapper.class);
        toolCallLogMapper = mock(ToolCallLogMapper.class);
        codeRepositoryMapper = mock(CodeRepositoryMapper.class);
        when(codeRepositoryMapper.selectById("repo-1")).thenReturn(CodeRepository.builder()
                .id("repo-1").name("demo").status("READY").build());
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        service = new ChatSessionFacadeServiceImpl(
                chatSessionMapper,
                new ChatSessionConverter(objectMapper),
                chatMessageMapper,
                agentTaskMapper,
                agentStepMapper,
                toolCallLogMapper,
                codeRepositoryMapper);
    }

    @Test
    void getChatSessionsWithoutChannelKeepsExistingAllSessionBehavior() {
        when(chatSessionMapper.selectAll()).thenReturn(List.of(
                chatSession("legacy-1", "agent-1", null),
                chatSession("web-1", "agent-1", "{\"channel\":\"WEB_CONSOLE\",\"repoId\":\"repo-1\"}")
        ));

        GetChatSessionsResponse response = service.getChatSessions(null);

        verify(chatSessionMapper).selectAll();
        assertThat(response.getChatSessions()).extracting(ChatSessionVO::getChannel)
                .containsExactly("LEGACY", "WEB_CONSOLE");
    }

    @Test
    void getChatSessionsWithChannelUsesMetadataFilter() {
        when(chatSessionMapper.selectByChannel("WEB_CONSOLE")).thenReturn(List.of(
                chatSession("web-1", "agent-1",
                        "{\"channel\":\"WEB_CONSOLE\",\"repoId\":\"repo-1\",\"model\":\"gpt-5.5\",\"source\":\"web-console\"}")
        ));

        GetChatSessionsResponse response = service.getChatSessions("web_console");

        verify(chatSessionMapper).selectByChannel("WEB_CONSOLE");
        assertThat(response.getChatSessions()).hasSize(1);
        assertThat(response.getChatSessions()[0].getChannel()).isEqualTo("WEB_CONSOLE");
        assertThat(response.getChatSessions()[0].getRepoId()).isEqualTo("repo-1");
        assertThat(response.getChatSessions()[0].getModel()).isEqualTo("gpt-5.5");
    }

    @Test
    void createWebConsoleSessionWritesChannelRepoIdAndSourceToMetadata() {
        CreateChatSessionRequest request = new CreateChatSessionRequest();
        request.setAgentId("agent-1");
        request.setTitle("Web Console 会话");
        request.setChannel("WEB_CONSOLE");
        request.setRepoId("repo-1");
        request.setModel("deepseek-chat");
        request.setMetadata(Map.of("custom", "kept"));
        when(chatSessionMapper.insert(isA(ChatSession.class))).thenAnswer(invocation -> {
            invocation.<ChatSession>getArgument(0).setId("session-1");
            return 1;
        });

        service.createChatSession(request);

        ArgumentCaptor<ChatSession> captor = ArgumentCaptor.forClass(ChatSession.class);
        verify(chatSessionMapper).insert(captor.capture());
        assertThat(captor.getValue().getMetadata())
                .contains("\"channel\":\"WEB_CONSOLE\"")
                .contains("\"repoId\":\"repo-1\"")
                .contains("\"model\":\"deepseek-chat\"")
                .contains("\"source\":\"web-console\"")
                .contains("\"custom\":\"kept\"");
    }

    @Test
    void createWebConsoleSessionKeepsChineseTitleReadable() throws Exception {
        CreateChatSessionRequest request = new CreateChatSessionRequest();
        request.setAgentId("agent-1");
        request.setTitle("Web Console 中文测试");
        request.setChannel("WEB_CONSOLE");
        request.setRepoId("repo-1");
        when(chatSessionMapper.insert(isA(ChatSession.class))).thenAnswer(invocation -> {
            invocation.<ChatSession>getArgument(0).setId("session-cn");
            return 1;
        });

        service.createChatSession(request);

        ArgumentCaptor<ChatSession> captor = ArgumentCaptor.forClass(ChatSession.class);
        verify(chatSessionMapper).insert(captor.capture());
        ChatSession persisted = captor.getValue();
        assertThat(persisted.getTitle()).isEqualTo("Web Console 中文测试");
        assertThat(persisted.getTitle()).doesNotContain("????");

        ChatSessionVO vo = new ChatSessionConverter(new ObjectMapper().findAndRegisterModules())
                .toVO(persisted);
        assertThat(vo.getTitle()).isEqualTo("Web Console 中文测试");
        assertThat(vo.getTitle()).doesNotContain("????");
    }

    @Test
    void createWebConsoleSessionRequiresExplicitTitle() {
        CreateChatSessionRequest request = new CreateChatSessionRequest();
        request.setAgentId("agent-1");
        request.setTitle(" ");
        request.setChannel("WEB_CONSOLE");
        request.setRepoId("repo-1");

        assertThatThrownBy(() -> service.createChatSession(request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Web Console 会话名称不能为空");
    }

    @Test
    void createWebConsoleSessionRequiresRepositoryBinding() {
        CreateChatSessionRequest request = new CreateChatSessionRequest();
        request.setAgentId("agent-1");
        request.setTitle("Unbound session");
        request.setChannel("WEB_CONSOLE");

        assertThatThrownBy(() -> service.createChatSession(request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("SESSION_REPOSITORY_UNBOUND");
    }

    @Test
    void createWebConsoleSessionRejectsNonReadyRepository() {
        when(codeRepositoryMapper.selectById("repo-importing")).thenReturn(CodeRepository.builder()
                .id("repo-importing").status("IMPORTING").build());
        CreateChatSessionRequest request = new CreateChatSessionRequest();
        request.setAgentId("agent-1");
        request.setTitle("Importing session");
        request.setChannel("WEB_CONSOLE");
        request.setRepoId("repo-importing");

        assertThatThrownBy(() -> service.createChatSession(request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("REPOSITORY_NOT_READY");
    }

    @Test
    void updatingSessionTitlePreservesImmutableRepositoryBinding() {
        when(chatSessionMapper.selectById("session-a")).thenReturn(
                chatSession("session-a", "agent-1", "{\"channel\":\"WEB_CONSOLE\",\"repoId\":\"repo-a\"}"));
        when(chatSessionMapper.updateById(isA(ChatSession.class))).thenReturn(1);
        UpdateChatSessionRequest request = new UpdateChatSessionRequest();
        request.setTitle("renamed");

        service.updateChatSession("session-a", request);

        ArgumentCaptor<ChatSession> captor = ArgumentCaptor.forClass(ChatSession.class);
        verify(chatSessionMapper).updateById(captor.capture());
        assertThat(captor.getValue().getMetadata()).contains("\"repoId\":\"repo-a\"");
    }

    @Test
    void deleteChatSessionCleansMessagesTraceStepsAndToolLogsBeforeDeletingSession() {
        when(chatSessionMapper.selectById("session-1")).thenReturn(
                chatSession("session-1", "agent-1", "{\"channel\":\"WEB_CONSOLE\"}"));
        when(chatSessionMapper.deleteById("session-1")).thenReturn(1);

        service.deleteChatSession("session-1");

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(
                toolCallLogMapper,
                agentStepMapper,
                agentTaskMapper,
                chatMessageMapper,
                chatSessionMapper);
        inOrder.verify(toolCallLogMapper).deleteBySessionId("session-1");
        inOrder.verify(agentStepMapper).deleteBySessionId("session-1");
        inOrder.verify(agentTaskMapper).deleteBySessionId("session-1");
        inOrder.verify(chatMessageMapper).deleteBySessionId("session-1");
        inOrder.verify(chatSessionMapper).deleteById("session-1");
    }

    private ChatSession chatSession(String id, String agentId, String metadata) {
        LocalDateTime now = LocalDateTime.of(2026, 6, 18, 10, 0);
        return ChatSession.builder()
                .id(id)
                .agentId(agentId)
                .title(id)
                .metadata(metadata)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
