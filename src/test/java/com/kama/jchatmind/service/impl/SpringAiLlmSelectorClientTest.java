package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.config.ChatClientRegistry;
import com.kama.jchatmind.config.CodeRagProperties;
import com.kama.jchatmind.model.dto.SelectorModelResponse;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpringAiLlmSelectorClientTest {

    @Test
    void callsConfiguredChatClientAndMapsExistingSpringAiResponse() {
        String visibleContent = "{\"selectedCandidateIds\":[\"C01\"]}";
        String reasoningContent = "private reasoning that must not be retained";
        DeepSeekAssistantMessage output = DeepSeekAssistantMessage
                .prefixAssistantMessage(visibleContent, reasoningContent);
        Generation generation = new Generation(output,
                ChatGenerationMetadata.builder().finishReason("STOP").build());
        ChatResponse chatResponse = new ChatResponse(List.of(generation), ChatResponseMetadata.builder()
                .usage(new DefaultUsage(120, 300, 420)).build());
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        ChatClientRegistry registry = mock(ChatClientRegistry.class);
        CodeRagProperties properties = new CodeRagProperties();
        properties.getLlmSelector().setModel("deepseek-chat");
        when(registry.get("deepseek-chat")).thenReturn(chatClient);
        when(chatClient.prompt().user("prompt").call().chatResponse()).thenReturn(chatResponse);

        SelectorModelResponse response = new SpringAiLlmSelectorClient(registry, properties).call("prompt");

        assertEquals(visibleContent, response.getContent());
        assertEquals(reasoningContent.length(), response.getReasoningContentChars());
        assertTrue(response.getReasoningContentPresent());
        assertEquals(120, response.getPromptTokens());
        assertEquals(300, response.getCompletionTokens());
        assertEquals(420, response.getTotalTokens());
        assertEquals("STOP", response.getFinishReason());
    }
}
