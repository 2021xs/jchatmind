package com.kama.jchatmind.agent;

import com.kama.jchatmind.service.FinalCompletionService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public final class JChatMindSafeFinalTestSupport {

    private JChatMindSafeFinalTestSupport() {
    }

    public static FinalCompletionService configure(JChatMind agent,
                                                   ChatClient.ChatClientRequestSpec requestSpec,
                                                   String finalText) {
        return configure(agent, requestSpec, Flux.just(ChatResponse.builder()
                .generations(java.util.List.of(new Generation(
                        AssistantMessage.builder().content(finalText).build(),
                        ChatGenerationMetadata.builder().finishReason("STOP").build())))
                .build()));
    }

    public static FinalCompletionService configure(JChatMind agent,
                                                   ChatClient.ChatClientRequestSpec requestSpec,
                                                   Flux<ChatResponse> finalResponses) {
        configureStream(requestSpec, finalResponses);

        FinalCompletionService completionService = mock(FinalCompletionService.class);
        when(completionService.complete(any())).thenAnswer(invocation -> {
            FinalCompletionService.FinalCompletionCommand command = invocation.getArgument(0);
            return new FinalCompletionService.FinalCompletionResult(
                    "final-message-1", command.finalStepId(), command.finalStepNo(),
                    "finish-step-1", command.finishStepNo());
        });
        agent.setFinalCompletionService(completionService);
        return completionService;
    }

    public static void configureStream(ChatClient.ChatClientRequestSpec requestSpec,
                                       Flux<ChatResponse> finalResponses) {
        ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.chatResponse()).thenReturn(finalResponses);
    }
}
