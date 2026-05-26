package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.config.ChatClientRegistry;
import com.kama.jchatmind.service.ConversationSummaryClient;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

@Component
public class ChatClientConversationSummaryClient implements ConversationSummaryClient {
    private final ChatClientRegistry chatClientRegistry;

    public ChatClientConversationSummaryClient(ChatClientRegistry chatClientRegistry) {
        this.chatClientRegistry = chatClientRegistry;
    }

    @Override
    public String summarize(String model, String prompt) {
        ChatClient chatClient = chatClientRegistry.get(model);
        if (chatClient == null) {
            throw new IllegalStateException("ChatClient not found for model: " + model);
        }
        return chatClient.prompt()
                .user(prompt)
                .call()
                .content();
    }
}
