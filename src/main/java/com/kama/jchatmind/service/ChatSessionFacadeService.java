package com.kama.jchatmind.service;

import com.kama.jchatmind.model.request.CreateChatSessionRequest;
import com.kama.jchatmind.model.request.UpdateChatSessionRequest;
import com.kama.jchatmind.model.response.CreateChatSessionResponse;
import com.kama.jchatmind.model.response.GetChatSessionResponse;
import com.kama.jchatmind.model.response.GetChatSessionsResponse;

public interface ChatSessionFacadeService {
    GetChatSessionsResponse getChatSessions();

    GetChatSessionsResponse getChatSessions(String channel);

    GetChatSessionResponse getChatSession(String chatSessionId);

    GetChatSessionsResponse getChatSessionsByAgentId(String agentId);

    CreateChatSessionResponse createChatSession(CreateChatSessionRequest request);

    void deleteChatSession(String chatSessionId);

    void updateChatSession(String chatSessionId, UpdateChatSessionRequest request);
}
