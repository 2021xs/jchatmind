package com.kama.jchatmind.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ChatClientRegistry {

    private final Map<String, ChatClient> chatClients;

    public ChatClientRegistry(Map<String, ChatClient> chatClients) {
        this.chatClients = new LinkedHashMap<>(chatClients);
        registerCompatibleAlias("deepseek-chat", "deepseek-official-chat");
    }

    public ChatClient get(String key) {
        return chatClients.get(key);
    }

    private void registerCompatibleAlias(String legacyName, String canonicalName) {
        ChatClient client = chatClients.get(canonicalName);
        if (client == null) {
            client = chatClients.get(legacyName);
        }
        if (client != null) {
            chatClients.putIfAbsent(canonicalName, client);
            chatClients.putIfAbsent(legacyName, client);
        }
    }
}
