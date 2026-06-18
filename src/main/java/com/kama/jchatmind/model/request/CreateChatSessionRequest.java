package com.kama.jchatmind.model.request;

import lombok.Data;

import java.util.Map;

@Data
public class CreateChatSessionRequest {
    private String agentId;
    private String title;
    private String channel;
    private String repoId;
    private Map<String, Object> metadata;
}
