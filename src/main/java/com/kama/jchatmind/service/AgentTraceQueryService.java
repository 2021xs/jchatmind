package com.kama.jchatmind.service;

import com.kama.jchatmind.model.response.GetAgentTracesResponse;

public interface AgentTraceQueryService {
    GetAgentTracesResponse getTraces(String sessionId, int limit);
}
