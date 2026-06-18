package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.converter.ChatSessionConverter;
import com.kama.jchatmind.mapper.ChatSessionMapper;
import com.kama.jchatmind.model.entity.ChatSession;
import com.kama.jchatmind.model.request.CreateChatSessionRequest;
import com.kama.jchatmind.model.response.GetChatSessionsResponse;
import com.kama.jchatmind.model.vo.ChatSessionVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatSessionFacadeServiceImplTest {

    private ChatSessionMapper chatSessionMapper;
    private ChatSessionFacadeServiceImpl service;

    @BeforeEach
    void setUp() {
        chatSessionMapper = mock(ChatSessionMapper.class);
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        service = new ChatSessionFacadeServiceImpl(chatSessionMapper, new ChatSessionConverter(objectMapper));
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
                        "{\"channel\":\"WEB_CONSOLE\",\"repoId\":\"repo-1\",\"source\":\"web-console\"}")
        ));

        GetChatSessionsResponse response = service.getChatSessions("web_console");

        verify(chatSessionMapper).selectByChannel("WEB_CONSOLE");
        assertThat(response.getChatSessions()).hasSize(1);
        assertThat(response.getChatSessions()[0].getChannel()).isEqualTo("WEB_CONSOLE");
        assertThat(response.getChatSessions()[0].getRepoId()).isEqualTo("repo-1");
    }

    @Test
    void createWebConsoleSessionWritesChannelRepoIdAndSourceToMetadata() {
        CreateChatSessionRequest request = new CreateChatSessionRequest();
        request.setAgentId("agent-1");
        request.setTitle("Web Console 会话");
        request.setChannel("WEB_CONSOLE");
        request.setRepoId("repo-1");
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
                .contains("\"source\":\"web-console\"")
                .contains("\"custom\":\"kept\"");
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
