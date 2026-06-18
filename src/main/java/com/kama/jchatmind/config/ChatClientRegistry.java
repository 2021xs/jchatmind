package com.kama.jchatmind.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ChatClientRegistry {

    private final Map<String, ChatClient> chatClients;

    @Autowired
    public ChatClientRegistry(Map<String, ChatClient> chatClients,
                              @Value("${jchatmind.ai.deepseek.official.model:}") String deepSeekOfficialModel,
                              @Value("${jchatmind.ai.gpt.compatible.model:}") String gptCompatibleModel) {
        this(chatClients, deepSeekOfficialModel, gptCompatibleModel, true);
    }

    public ChatClientRegistry(Map<String, ChatClient> chatClients) {
        this(chatClients, null, null, true);
    }

    ChatClientRegistry(Map<String, ChatClient> chatClients,
                       String deepSeekOfficialModel,
                       String gptCompatibleModel,
                       boolean registerAliases) {
        this.chatClients = new LinkedHashMap<>(chatClients);
        if (registerAliases) {
            registerCompatibleAlias("deepseek-chat", "deepseek-official-chat");
            if (StringUtils.hasText(deepSeekOfficialModel)) {
                registerCompatibleAlias(deepSeekOfficialModel.trim(), "deepseek-official-chat");
            }
            if (StringUtils.hasText(gptCompatibleModel)) {
                registerCompatibleAlias(gptCompatibleModel.trim(), "gpt-compatible-chat");
            }
            registerCompatibleAlias("gpt-5.5", "gpt-compatible-chat");
        }
    }

    public ChatClient get(String key) {
        return chatClients.get(key);
    }

    public boolean contains(String key) {
        return chatClients.containsKey(key);
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
