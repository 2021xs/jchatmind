package com.kama.jchatmind.controller;

import com.kama.jchatmind.model.common.ApiResponse;
import com.kama.jchatmind.model.request.WebConsoleChatSendRequest;
import com.kama.jchatmind.model.response.WebConsoleChatSendResponse;
import com.kama.jchatmind.service.WebConsoleChatService;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/web-console/chat")
@AllArgsConstructor
public class WebConsoleChatController {
    private final WebConsoleChatService webConsoleChatService;

    @PostMapping("/send")
    public ApiResponse<WebConsoleChatSendResponse> send(@RequestBody WebConsoleChatSendRequest request) {
        return ApiResponse.success(webConsoleChatService.send(request));
    }
}
