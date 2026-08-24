package com.kama.jchatmind.controller;

import com.kama.jchatmind.model.common.ApiResponse;
import com.kama.jchatmind.model.request.WebConsoleChatSendRequest;
import com.kama.jchatmind.model.response.GetWebConsoleCapabilitiesResponse;
import com.kama.jchatmind.model.response.CancelAgentTaskResponse;
import com.kama.jchatmind.service.AgentTaskLifecycleService;
import com.kama.jchatmind.model.response.WebConsoleChatSendResponse;
import com.kama.jchatmind.service.WebConsoleCapabilityService;
import com.kama.jchatmind.service.WebConsoleChatService;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/web-console")
@AllArgsConstructor
public class WebConsoleChatController {
    private final WebConsoleChatService webConsoleChatService;
    private final WebConsoleCapabilityService webConsoleCapabilityService;
    private final AgentTaskLifecycleService agentTaskLifecycleService;

    @PostMapping("/chat/send")
    public ApiResponse<WebConsoleChatSendResponse> send(@RequestBody WebConsoleChatSendRequest request) {
        return ApiResponse.success(webConsoleChatService.send(request));
    }

    @PostMapping("/tasks/{taskId}/cancel")
    public ApiResponse<CancelAgentTaskResponse> cancel(
            @org.springframework.web.bind.annotation.PathVariable String taskId,
            @RequestParam String conversationId) {
        return ApiResponse.success(agentTaskLifecycleService.cancel(taskId, conversationId));
    }

    @GetMapping("/capabilities")
    public ApiResponse<GetWebConsoleCapabilitiesResponse> capabilities(
            @RequestParam(required = false) String repoId,
            @RequestParam(required = false) String model) {
        return ApiResponse.success(webConsoleCapabilityService.getCapabilities(repoId, model));
    }
}
