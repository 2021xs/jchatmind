package com.kama.jchatmind.model.request;

import lombok.Data;

@Data
public class WebConsoleChatSendRequest {
    private String conversationId;
    private String agentId;
    private String model;
    private String repoId;
    private String content;
}
