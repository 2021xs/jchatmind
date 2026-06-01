package com.kama.jchatmind.integration.feishu;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class FeishuAgentCardSnapshot {
    private String taskId;
    private String question;
    private String status;
    private String stage;
    private String result;
    private String updatedAt;
}
