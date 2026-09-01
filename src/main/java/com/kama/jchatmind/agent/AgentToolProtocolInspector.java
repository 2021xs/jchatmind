package com.kama.jchatmind.agent;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/** Read-only benchmark facade over the runtime memory's production protocol parser. */
public final class AgentToolProtocolInspector {

    private AgentToolProtocolInspector() {
    }

    public static Inspection inspect(List<Message> messages) {
        ProtocolAwareChatMemory.ProtocolValidation validation =
                ProtocolAwareChatMemory.inspectProtocol(messages);
        return new Inspection(validation.orphanToolProtocolCount(),
                validation.protocolValidationFailureCount(), validation.diagnostic());
    }

    public record Inspection(int orphanToolProtocolCount,
                             int protocolValidationFailureCount,
                             String diagnostic) {
        public boolean valid() {
            return protocolValidationFailureCount == 0;
        }
    }
}
