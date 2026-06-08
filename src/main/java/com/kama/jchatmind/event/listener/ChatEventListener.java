package com.kama.jchatmind.event.listener;

import com.kama.jchatmind.agent.JChatMind;
import com.kama.jchatmind.agent.JChatMindFactory;
import com.kama.jchatmind.event.ChatEvent;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@AllArgsConstructor
public class ChatEventListener {

    private final JChatMindFactory jChatMindFactory;

    @Async("taskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(ChatEvent event) {
        try {
            JChatMind jChatMind = jChatMindFactory.create(
                    event.getAgentId(), event.getSessionId(), event.getUserMessageId());
            jChatMind.run();
        } catch (Exception e) {
            log.error("Async chat event handling failed: sessionId={}, agentId={}, userMessageId={}",
                    event.getSessionId(), event.getAgentId(), event.getUserMessageId(), e);
        }
    }
}
