package com.kama.jchatmind.event.listener;

import com.kama.jchatmind.agent.JChatMind;
import com.kama.jchatmind.agent.JChatMindFactory;
import com.kama.jchatmind.event.ChatEvent;
import com.kama.jchatmind.model.request.CreateChatMessageRequest;
import com.kama.jchatmind.service.impl.ChatMessageFacadeServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatEventListenerTest {

    @Test
    void consumesChatEventAfterCommitOnTaskExecutor() throws Exception {
        Method handle = ChatEventListener.class.getMethod("handle", ChatEvent.class);

        TransactionalEventListener listener = handle.getAnnotation(TransactionalEventListener.class);
        Async async = handle.getAnnotation(Async.class);

        assertEquals(TransactionPhase.AFTER_COMMIT, listener.phase());
        assertFalse(listener.fallbackExecution());
        assertEquals("taskExecutor", async.value());
    }

    @Test
    void publishesUserChatEventInsideTransaction() throws Exception {
        Method createChatMessage = ChatMessageFacadeServiceImpl.class.getMethod(
                "createChatMessage", CreateChatMessageRequest.class);

        assertNotNull(createChatMessage.getAnnotation(Transactional.class));
    }

    @Test
    void delegatesCommittedEventToAgentRuntime() {
        JChatMindFactory factory = mock(JChatMindFactory.class);
        JChatMind agent = mock(JChatMind.class);
        ChatEvent event = new ChatEvent("agent-1", "session-1", "message-1", "question");
        when(factory.create("agent-1", "session-1", "message-1")).thenReturn(agent);

        new ChatEventListener(factory).handle(event);

        verify(agent).run();
    }
}
