package com.kama.jchatmind.message;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentSseEvent {
    private String eventId;
    private String taskId;
    private String sessionId;
    private Type type;
    private LocalDateTime timestamp;
    private Map<String, Object> payload;

    public static AgentSseEvent of(String taskId, String sessionId, Type type, Map<String, Object> payload) {
        return AgentSseEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .taskId(taskId)
                .sessionId(sessionId)
                .type(type)
                .timestamp(LocalDateTime.now())
                .payload(payload)
                .build();
    }

    public enum Type {
        MESSAGE_START("message_start"),
        TOKEN("token"),
        RETRIEVAL_RESULT("retrieval_result"),
        TOOL_CALL_START("tool_call_start"),
        TOOL_CALL_RESULT("tool_call_result"),
        STEP_DONE("step_done"),
        ERROR("error"),
        DONE("done");

        private final String value;

        Type(String value) {
            this.value = value;
        }

        @JsonValue
        public String getValue() {
            return value;
        }
    }
}
