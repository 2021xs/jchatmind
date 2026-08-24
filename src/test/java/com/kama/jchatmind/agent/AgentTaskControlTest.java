package com.kama.jchatmind.agent;

import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import java.util.concurrent.FutureTask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AgentTaskControlTest {
    @Test
    void cancellationIsIdempotentAndCancelsCurrentToolFuture() {
        AgentTaskControl control = new AgentTaskControl("task-1", "session-1");
        FutureTask<Void> future = new FutureTask<>(() -> null);
        control.attachToolFuture(future);

        assertEquals(AgentTaskControl.CancelResult.REQUESTED, control.requestCancellation());
        assertEquals(AgentTaskControl.CancelResult.ALREADY_REQUESTED, control.requestCancellation());
        assertFalse(control.completeIfActive(() -> { }));
        assertEquals(true, future.isCancelled());
    }

    @Test
    void cancellationDisposesActiveFinalStream() {
        AgentTaskControl control = new AgentTaskControl("task-1", "session-1");
        Disposable stream = mock(Disposable.class);
        control.attachActiveStream(stream);

        assertEquals(AgentTaskControl.CancelResult.REQUESTED, control.requestCancellation());

        verify(stream).dispose();
    }
}
