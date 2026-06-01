package com.kama.jchatmind.integration.feishu;

import com.kama.jchatmind.mapper.ChatSessionMapper;
import com.kama.jchatmind.mapper.FeishuAgentSessionBindingMapper;
import com.kama.jchatmind.model.entity.ChatSession;
import com.kama.jchatmind.model.entity.FeishuAgentSessionBinding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeishuAgentSessionBindingServiceTest {

    private static final String AGENT_ID = "11111111-1111-1111-1111-111111111111";

    private FeishuAgentSessionBindingMapper bindingMapper;
    private ChatSessionMapper chatSessionMapper;
    private FeishuAgentSessionBindingService service;

    @BeforeEach
    void setUp() {
        bindingMapper = mock(FeishuAgentSessionBindingMapper.class);
        chatSessionMapper = mock(ChatSessionMapper.class);
        service = new FeishuAgentSessionBindingService(bindingMapper, chatSessionMapper);
    }

    @Test
    void getOrCreateActiveSessionCreatesChatSessionAndBindingWhenMissing() {
        when(bindingMapper.selectByFeishuChatId("oc_test")).thenReturn(null);

        String sessionId = service.getOrCreateActiveSession("oc_test", "p2p", "ou_test", AGENT_ID);

        assertThat(sessionId).isNotBlank();
        ArgumentCaptor<ChatSession> chatSessionCaptor = ArgumentCaptor.forClass(ChatSession.class);
        ArgumentCaptor<FeishuAgentSessionBinding> bindingCaptor =
                ArgumentCaptor.forClass(FeishuAgentSessionBinding.class);
        verify(chatSessionMapper).insertWithId(chatSessionCaptor.capture());
        verify(bindingMapper).upsertActiveSession(bindingCaptor.capture());
        assertThat(chatSessionCaptor.getValue().getId()).isEqualTo(sessionId);
        assertThat(bindingCaptor.getValue().getFeishuChatId()).isEqualTo("oc_test");
        assertThat(bindingCaptor.getValue().getSessionId()).isEqualTo(sessionId);
    }

    @Test
    void getOrCreateActiveSessionReturnsExistingBindingSession() {
        when(bindingMapper.selectByFeishuChatId("oc_test"))
                .thenReturn(FeishuAgentSessionBinding.builder()
                        .feishuChatId("oc_test")
                        .sessionId("22222222-2222-2222-2222-222222222222")
                        .build());

        String sessionId = service.getOrCreateActiveSession("oc_test", "p2p", "ou_test", AGENT_ID);

        assertThat(sessionId).isEqualTo("22222222-2222-2222-2222-222222222222");
        verify(chatSessionMapper, never()).insertWithId(isA(ChatSession.class));
        verify(bindingMapper, never()).upsertActiveSession(isA(FeishuAgentSessionBinding.class));
    }

    @Test
    void createNewSessionOverwritesBindingWithFreshSession() {
        String first = service.createNewSession("oc_test", "p2p", "ou_test", AGENT_ID);
        String second = service.createNewSession("oc_test", "p2p", "ou_test", AGENT_ID);

        assertThat(second).isNotEqualTo(first);
        verify(chatSessionMapper, org.mockito.Mockito.times(2)).insertWithId(isA(ChatSession.class));
        verify(bindingMapper, org.mockito.Mockito.times(2)).upsertActiveSession(isA(FeishuAgentSessionBinding.class));
    }

    @Test
    void differentChatIdsCreateDifferentSessionIds() {
        String first = service.getOrCreateActiveSession("oc_first", "p2p", "ou_test", AGENT_ID);
        String second = service.getOrCreateActiveSession("oc_second", "p2p", "ou_test", AGENT_ID);

        assertThat(second).isNotEqualTo(first);
    }
}
