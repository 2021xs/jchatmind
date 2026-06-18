package com.kama.jchatmind.model.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class WebConsoleChatSendResponse {
    private String userMessageId;
    private String assistantMessageId;
    private String runId;
    private String conversationId;
    private String sseUrl;
}
