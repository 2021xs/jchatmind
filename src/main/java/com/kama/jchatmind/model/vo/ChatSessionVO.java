package com.kama.jchatmind.model.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ChatSessionVO {
    private String id;
    private String agentId;
    private String title;
    private String channel;
    private String repoId;
    private String model;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
