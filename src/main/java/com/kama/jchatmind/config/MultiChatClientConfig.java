package com.kama.jchatmind.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.ai.zhipuai.ZhiPuAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.micrometer.observation.ObservationRegistry;

@Configuration
public class MultiChatClientConfig {
    // DeepSeek relay channel. Keep deepseek-chat for backward compatibility.
    @Bean("deepseek-chat")
    public ChatClient deepSeekChatClient(DeepSeekChatModel deepSeekChatModel) {
        return ChatClient.create(deepSeekChatModel);
    }

    @Bean("deepseek-relay-chat")
    public ChatClient deepSeekRelayChatClient(DeepSeekChatModel deepSeekChatModel) {
        return ChatClient.create(deepSeekChatModel);
    }

    @Bean("deepseek-official-chat")
    public ChatClient deepSeekOfficialChatClient(
            @Value("${jchatmind.ai.deepseek.official.api-key}") String apiKey,
            @Value("${jchatmind.ai.deepseek.official.base-url}") String baseUrl,
            @Value("${jchatmind.ai.deepseek.official.model}") String model) {
        DeepSeekApi deepSeekApi = DeepSeekApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();
        DeepSeekChatOptions options = DeepSeekChatOptions.builder()
                .model(model)
                .build();
        DeepSeekChatModel chatModel = DeepSeekChatModel.builder()
                .deepSeekApi(deepSeekApi)
                .defaultOptions(options)
                .retryTemplate(RetryUtils.DEFAULT_RETRY_TEMPLATE)
                .observationRegistry(ObservationRegistry.NOOP)
                .build();
        return ChatClient.create(chatModel);
    }

    // zhipuai
    @Bean("glm-4.6")
    public ChatClient zhiPuAiChatClient(ZhiPuAiChatModel zhiPuAiChatModel) {
        return ChatClient.create(zhiPuAiChatModel);
    }
}
