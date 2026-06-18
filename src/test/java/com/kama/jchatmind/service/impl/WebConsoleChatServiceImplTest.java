package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.agent.AgentEventPublisher;
import com.kama.jchatmind.agent.JChatMind;
import com.kama.jchatmind.agent.JChatMindFactory;
import com.kama.jchatmind.mapper.ChatSessionMapper;
import com.kama.jchatmind.mapper.CodeRepositoryMapper;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.entity.ChatSession;
import com.kama.jchatmind.model.entity.CodeRepository;
import com.kama.jchatmind.model.request.CreateChatMessageRequest;
import com.kama.jchatmind.model.request.WebConsoleChatSendRequest;
import com.kama.jchatmind.model.response.CreateChatMessageResponse;
import com.kama.jchatmind.model.response.WebConsoleChatSendResponse;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebConsoleChatServiceImplTest {

    @Test
    void sendCreatesPlainUserMessageAndRunsExistingAgentWithWebConsoleRepoContext() {
        ChatSessionMapper chatSessionMapper = mock(ChatSessionMapper.class);
        CodeRepositoryMapper codeRepositoryMapper = mock(CodeRepositoryMapper.class);
        ChatMessageFacadeService chatMessageFacadeService = mock(ChatMessageFacadeService.class);
        JChatMindFactory jChatMindFactory = mock(JChatMindFactory.class);
        AgentEventPublisher eventPublisher = mock(AgentEventPublisher.class);
        JChatMind agent = mock(JChatMind.class);
        Executor directExecutor = Runnable::run;

        when(chatSessionMapper.selectById("session-1")).thenReturn(ChatSession.builder()
                .id("session-1")
                .agentId("agent-1")
                .title("web")
                .build());
        when(codeRepositoryMapper.selectById("repo-1")).thenReturn(CodeRepository.builder()
                .id("repo-1")
                .name("hm-dianping")
                .status("READY")
                .build());
        when(chatMessageFacadeService.agentCreateChatMessage(isA(CreateChatMessageRequest.class)))
                .thenReturn(CreateChatMessageResponse.builder().chatMessageId("user-message-1").build());
        when(jChatMindFactory.create(eq("agent-1"), eq("session-1"), eq("user-message-1"),
                isA(String.class), isA(String.class))).thenReturn(agent);

        WebConsoleChatServiceImpl service = new WebConsoleChatServiceImpl(
                chatSessionMapper,
                codeRepositoryMapper,
                chatMessageFacadeService,
                jChatMindFactory,
                eventPublisher,
                directExecutor);
        WebConsoleChatSendRequest request = new WebConsoleChatSendRequest();
        request.setConversationId("session-1");
        request.setAgentId("agent-1");
        request.setRepoId("repo-1");
        request.setContent("分析秒杀下单链路是怎么走的");

        WebConsoleChatSendResponse response = service.send(request);

        ArgumentCaptor<CreateChatMessageRequest> messageCaptor =
                ArgumentCaptor.forClass(CreateChatMessageRequest.class);
        verify(chatMessageFacadeService).agentCreateChatMessage(messageCaptor.capture());
        assertEquals(ChatMessageDTO.RoleType.USER, messageCaptor.getValue().getRole());
        assertEquals("session-1", messageCaptor.getValue().getSessionId());
        assertEquals("分析秒杀下单链路是怎么走的", messageCaptor.getValue().getContent());
        assertFalse(messageCaptor.getValue().getContent().contains("Feishu context"));

        ArgumentCaptor<String> runtimeContextCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> traceIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(jChatMindFactory).create(eq("agent-1"), eq("session-1"), eq("user-message-1"),
                runtimeContextCaptor.capture(), traceIdCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(runtimeContextCaptor.getValue())
                .contains("channel: WEB_CONSOLE")
                .contains("selectedRepoId: repo-1")
                .contains("selectedRepoName: hm-dianping")
                .contains("selectedConversationId: session-1")
                .contains("Do not add or mention Feishu context");
        verify(agent).setMaxLoopSteps(12);
        verify(agent).run();

        assertEquals("user-message-1", response.getUserMessageId());
        assertEquals("session-1", response.getConversationId());
        assertEquals("/sse/connect/session-1", response.getSseUrl());
        assertEquals(traceIdCaptor.getValue(), response.getRunId());
        assertNotNull(response.getRunId());
    }

    @Test
    void sendKeepsChineseUserMessageReadable() {
        ChatSessionMapper chatSessionMapper = mock(ChatSessionMapper.class);
        CodeRepositoryMapper codeRepositoryMapper = mock(CodeRepositoryMapper.class);
        ChatMessageFacadeService chatMessageFacadeService = mock(ChatMessageFacadeService.class);
        JChatMindFactory jChatMindFactory = mock(JChatMindFactory.class);
        AgentEventPublisher eventPublisher = mock(AgentEventPublisher.class);
        JChatMind agent = mock(JChatMind.class);
        Executor directExecutor = Runnable::run;

        when(chatSessionMapper.selectById("session-cn")).thenReturn(ChatSession.builder()
                .id("session-cn")
                .agentId("agent-1")
                .title("Web Console 中文测试")
                .build());
        when(codeRepositoryMapper.selectById("repo-1")).thenReturn(CodeRepository.builder()
                .id("repo-1")
                .name("hm-dianping")
                .status("READY")
                .build());
        when(chatMessageFacadeService.agentCreateChatMessage(isA(CreateChatMessageRequest.class)))
                .thenReturn(CreateChatMessageResponse.builder().chatMessageId("user-message-cn").build());
        when(jChatMindFactory.create(eq("agent-1"), eq("session-cn"), eq("user-message-cn"),
                isA(String.class), isA(String.class))).thenReturn(agent);

        WebConsoleChatServiceImpl service = new WebConsoleChatServiceImpl(
                chatSessionMapper,
                codeRepositoryMapper,
                chatMessageFacadeService,
                jChatMindFactory,
                eventPublisher,
                directExecutor);
        WebConsoleChatSendRequest request = new WebConsoleChatSendRequest();
        request.setConversationId("session-cn");
        request.setAgentId("agent-1");
        request.setRepoId("repo-1");
        request.setContent("分析秒杀下单链路");

        service.send(request);

        ArgumentCaptor<CreateChatMessageRequest> messageCaptor =
                ArgumentCaptor.forClass(CreateChatMessageRequest.class);
        verify(chatMessageFacadeService).agentCreateChatMessage(messageCaptor.capture());
        assertEquals("分析秒杀下单链路", messageCaptor.getValue().getContent());
        assertFalse(messageCaptor.getValue().getContent().contains("????"));
    }
}
