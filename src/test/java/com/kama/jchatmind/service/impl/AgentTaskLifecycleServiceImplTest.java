package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.agent.AgentTaskControl;
import com.kama.jchatmind.agent.AgentTaskRuntimeRegistry;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.entity.AgentTask;
import com.kama.jchatmind.model.request.CreateChatMessageRequest;
import com.kama.jchatmind.model.response.CreateChatMessageResponse;
import com.kama.jchatmind.service.AgentTaskLifecycleService;
import com.kama.jchatmind.service.AgentTaskLogService;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentTaskLifecycleServiceImplTest {
    @Test
    void reservationBindsMessageToTaskBeforeReturning() {
        AgentTaskLogService logService = mock(AgentTaskLogService.class);
        ChatMessageFacadeService messageService = mock(ChatMessageFacadeService.class);
        AgentTask task = AgentTask.builder().id("task-1").status(AgentTaskLogService.STATUS_RUNNING).build();
        when(logService.startTask(eq("session-1"), eq("agent-1"), eq(null), any(),
                eq("model"), eq(12), eq("trace-1"))).thenReturn(task);
        when(messageService.agentCreateChatMessage(any()))
                .thenReturn(CreateChatMessageResponse.builder().chatMessageId("message-1").build());

        AgentTaskLifecycleService service = new AgentTaskLifecycleServiceImpl(
                logService, messageService, new AgentTaskRuntimeRegistry());
        CreateChatMessageRequest userMessageRequest = CreateChatMessageRequest.builder()
                .sessionId("session-1")
                .role(ChatMessageDTO.RoleType.USER)
                .metadata(ChatMessageDTO.MetaData.builder().model("model").build())
                .build();
        AgentTaskLifecycleService.ReservedTask reserved = service.reserve(
                "session-1", "agent-1", "model", 12, "trace-1",
                userMessageRequest);

        assertEquals("task-1", reserved.task().getId());
        assertEquals("message-1", reserved.userMessageId());
        assertEquals("message-1", reserved.task().getUserMessageId());
        verify(logService).bindUserMessage("task-1", "message-1");
        ArgumentCaptor<CreateChatMessageRequest> persistedUser =
                ArgumentCaptor.forClass(CreateChatMessageRequest.class);
        verify(messageService).agentCreateChatMessage(persistedUser.capture());
        assertNull(persistedUser.getValue().getMetadata().getTaskId());
    }

    @Test
    void cancelIsIdempotentAndDoesNotRewriteFinishedTask() {
        AgentTaskLogService logService = mock(AgentTaskLogService.class);
        ChatMessageFacadeService messageService = mock(ChatMessageFacadeService.class);
        AgentTaskRuntimeRegistry registry = new AgentTaskRuntimeRegistry();
        AgentTaskLifecycleService service = new AgentTaskLifecycleServiceImpl(logService, messageService, registry);
        when(logService.getTask("task-1")).thenReturn(AgentTask.builder()
                .id("task-1").sessionId("session-1").status(AgentTaskLogService.STATUS_RUNNING).build());
        registry.register("task-1", "session-1");

        assertEquals("CANCELLATION_REQUESTED", service.cancel("task-1", "session-1").getStatus());
        assertEquals("ALREADY_CANCELLING", service.cancel("task-1", "session-1").getStatus());

        when(logService.getTask("task-2")).thenReturn(AgentTask.builder()
                .id("task-2").sessionId("session-1").status(AgentTaskLogService.STATUS_SUCCESS).build());
        assertEquals("TASK_ALREADY_FINISHED", service.cancel("task-2", "session-1").getStatus());
    }
}
