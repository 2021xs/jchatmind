package com.kama.jchatmind.integration.feishu;

import com.kama.jchatmind.agent.JChatMind;
import com.kama.jchatmind.agent.JChatMindFactory;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.request.CreateChatMessageRequest;
import com.kama.jchatmind.model.response.CreateChatMessageResponse;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeishuAgentRunAdapterTest {

    private JChatMindFactory jChatMindFactory;
    private ChatMessageFacadeService chatMessageFacadeService;
    private JChatMind jChatMind;
    private FeishuAgentRunAdapter adapter;

    @BeforeEach
    void setUp() {
        jChatMindFactory = mock(JChatMindFactory.class);
        chatMessageFacadeService = mock(ChatMessageFacadeService.class);
        jChatMind = mock(JChatMind.class);
        adapter = new FeishuAgentRunAdapter(jChatMindFactory, chatMessageFacadeService);
    }

    @Test
    void runCreatesUserMessageRunsExistingAgentAndReturnsLatestAssistantAnswer() {
        String agentId = "11111111-1111-1111-1111-111111111111";
        when(chatMessageFacadeService.agentCreateChatMessage(org.mockito.ArgumentMatchers.isA(CreateChatMessageRequest.class)))
                .thenReturn(CreateChatMessageResponse.builder().chatMessageId("user-message-id").build());
        when(jChatMindFactory.create(eq(agentId), anyString(), eq("user-message-id")))
                .thenReturn(jChatMind);
        when(chatMessageFacadeService.getChatMessagesBySessionIdRecently(anyString(), eq(30)))
                .thenReturn(List.of(
                        ChatMessageDTO.builder()
                                .role(ChatMessageDTO.RoleType.ASSISTANT)
                                .content("older")
                                .createdAt(LocalDateTime.parse("2026-06-01T11:00:00"))
                                .build(),
                        ChatMessageDTO.builder()
                                .role(ChatMessageDTO.RoleType.ASSISTANT)
                                .content("final answer")
                                .createdAt(LocalDateTime.parse("2026-06-01T11:00:02"))
                                .build()));

        FeishuAgentRunAdapter.AgentRunResult result = adapter.run(agentId, "oc_test", "分析秒杀订单链路");

        ArgumentCaptor<CreateChatMessageRequest> requestCaptor = ArgumentCaptor.forClass(CreateChatMessageRequest.class);
        verify(chatMessageFacadeService).agentCreateChatMessage(requestCaptor.capture());
        assertEquals(ChatMessageDTO.RoleType.USER, requestCaptor.getValue().getRole());
        assertEquals("分析秒杀订单链路", requestCaptor.getValue().getContent());
        verify(jChatMind).run();
        assertEquals("user-message-id", result.userMessageId());
        assertEquals("final answer", result.answer());
    }

    @Test
    void toSessionIdUsesStableUuidDerivedFromChatId() {
        String first = adapter.toSessionId("oc_test");
        String second = adapter.toSessionId("oc_test");
        String different = adapter.toSessionId("oc_other");

        assertEquals(first, second);
        assertNotEquals(first, different);
    }
}
