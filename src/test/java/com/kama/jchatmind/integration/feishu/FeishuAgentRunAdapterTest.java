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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeishuAgentRunAdapterTest {

    private JChatMindFactory jChatMindFactory;
    private ChatMessageFacadeService chatMessageFacadeService;
    private FeishuAgentSessionBindingService sessionBindingService;
    private JChatMind jChatMind;
    private FeishuAgentRunAdapter adapter;

    @BeforeEach
    void setUp() {
        jChatMindFactory = mock(JChatMindFactory.class);
        chatMessageFacadeService = mock(ChatMessageFacadeService.class);
        sessionBindingService = mock(FeishuAgentSessionBindingService.class);
        jChatMind = mock(JChatMind.class);
        FeishuProperties properties = new FeishuProperties();
        properties.setRepoAliases(new LinkedHashMap<>(Map.of("hmdp", "repo-hmdp-id")));
        adapter = new FeishuAgentRunAdapter(jChatMindFactory, chatMessageFacadeService, sessionBindingService, properties);
    }

    @Test
    void runUsesActiveFeishuSessionCreatesUserMessageRunsExistingAgentAndReturnsLatestAssistantAnswer() {
        String agentId = "11111111-1111-1111-1111-111111111111";
        when(sessionBindingService.getOrCreateActiveSession(eq("oc_test"), eq("p2p"), eq("ou_test"), eq(agentId)))
                .thenReturn("22222222-2222-2222-2222-222222222222");
        when(chatMessageFacadeService.agentCreateChatMessage(isA(CreateChatMessageRequest.class)))
                .thenReturn(CreateChatMessageResponse.builder().chatMessageId("user-message-id").build());
        when(jChatMindFactory.create(eq(agentId), eq("22222222-2222-2222-2222-222222222222"), eq("user-message-id")))
                .thenReturn(jChatMind);
        when(chatMessageFacadeService.getChatMessagesBySessionIdRecently(
                eq("22222222-2222-2222-2222-222222222222"), eq(30)))
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

        FeishuAgentRunAdapter.AgentRunResult result =
                adapter.run(agentId, "oc_test", "p2p", "ou_test", "analyze seckill order flow");

        ArgumentCaptor<CreateChatMessageRequest> requestCaptor = ArgumentCaptor.forClass(CreateChatMessageRequest.class);
        verify(chatMessageFacadeService).agentCreateChatMessage(requestCaptor.capture());
        assertEquals(ChatMessageDTO.RoleType.USER, requestCaptor.getValue().getRole());
        assertEquals("22222222-2222-2222-2222-222222222222", requestCaptor.getValue().getSessionId());
        org.assertj.core.api.Assertions.assertThat(requestCaptor.getValue().getContent())
                .contains("analyze seckill order flow")
                .contains("repo-hmdp-id");
        verify(jChatMind).run();
        assertEquals("22222222-2222-2222-2222-222222222222", result.sessionId());
        assertEquals("user-message-id", result.userMessageId());
        assertEquals("final answer", result.answer());
    }

    @Test
    void stableChatKeyUsesStableUuidDerivedFromChatId() {
        String first = adapter.stableChatKey("oc_test");
        String second = adapter.stableChatKey("oc_test");
        String different = adapter.stableChatKey("oc_other");

        assertEquals(first, second);
        assertNotEquals(first, different);
    }

    @Test
    void withFeishuContextLeavesQuestionUnchangedWhenNoRepoAliasConfigured() {
        FeishuProperties properties = new FeishuProperties();
        properties.setRepoAliases(new LinkedHashMap<>());
        FeishuAgentRunAdapter adapterWithoutRepoAlias =
                new FeishuAgentRunAdapter(jChatMindFactory, chatMessageFacadeService, sessionBindingService, properties);

        assertEquals("question", adapterWithoutRepoAlias.withFeishuContext("question"));
    }
}
