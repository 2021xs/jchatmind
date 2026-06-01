package com.kama.jchatmind.model.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class FeishuAgentSessionBinding {
    private String id;

    private String feishuChatId;

    private String feishuChatType;

    private String feishuSenderOpenId;

    private String agentId;

    private String sessionId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime lastMessageAt;
}
