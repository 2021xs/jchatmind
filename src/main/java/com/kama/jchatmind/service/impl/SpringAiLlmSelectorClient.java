package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.config.ChatClientRegistry;
import com.kama.jchatmind.config.CodeRagProperties;
import com.kama.jchatmind.model.dto.SelectorModelResponse;
import com.kama.jchatmind.service.LlmSelectorClient;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "jchatmind.code-rag.llm-selector", name = "client-type",
        havingValue = "SPRING_AI")
public class SpringAiLlmSelectorClient implements LlmSelectorClient {
    private final ChatClientRegistry chatClientRegistry;
    private final CodeRagProperties properties;

    public SpringAiLlmSelectorClient(ChatClientRegistry chatClientRegistry, CodeRagProperties properties) {
        this.chatClientRegistry = chatClientRegistry;
        this.properties = properties;
    }

    @Override
    public SelectorModelResponse call(String prompt) {
        ChatClient chatClient = chatClientRegistry.get(properties.getLlmSelector().getModel());
        if (chatClient == null) {
            throw new IllegalStateException(
                    "ChatClient not found for model: " + properties.getLlmSelector().getModel());
        }
        return toSelectorModelResponse(chatClient.prompt().user(prompt).call().chatResponse());
    }

    static SelectorModelResponse toSelectorModelResponse(ChatResponse response) {
        AssistantMessage output = response == null || response.getResult() == null
                ? null : response.getResult().getOutput();
        String content = output == null ? null : output.getText();
        boolean deepSeekOutputAvailable = output instanceof DeepSeekAssistantMessage;
        String reasoningContent = deepSeekOutputAvailable
                ? ((DeepSeekAssistantMessage) output).getReasoningContent() : null;
        Integer reasoningContentChars = deepSeekOutputAvailable
                ? reasoningContent == null ? 0 : reasoningContent.length() : null;
        Boolean reasoningContentPresent = deepSeekOutputAvailable
                ? reasoningContent != null && !reasoningContent.isEmpty() : null;
        String finishReason = response == null || response.getResult() == null
                || response.getResult().getMetadata() == null
                ? null : response.getResult().getMetadata().getFinishReason();
        Usage usage = response == null || response.getMetadata() == null
                ? null : response.getMetadata().getUsage();
        return new SelectorModelResponse(
                content,
                reasoningContentChars,
                reasoningContentPresent,
                usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens(),
                usage == null ? null : usage.getTotalTokens(),
                finishReason);
    }
}
