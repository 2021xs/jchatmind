package com.kama.jchatmind.controller;

import com.kama.jchatmind.model.common.ApiResponse;
import com.kama.jchatmind.model.response.GetAgentTracesResponse;
import com.kama.jchatmind.service.AgentTraceQueryService;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@AllArgsConstructor
public class AgentTraceController {
    private final AgentTraceQueryService agentTraceQueryService;

    @GetMapping("/agent-traces")
    public ApiResponse<GetAgentTracesResponse> getTraces(
            @RequestParam(required = false) String sessionId,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(agentTraceQueryService.getTraces(sessionId, limit));
    }
}
