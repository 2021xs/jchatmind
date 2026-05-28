package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.agent.AgentEventPublisher;
import com.kama.jchatmind.service.AgentTaskLogService;
import com.kama.jchatmind.tool.ToolExecutionContext;
import com.kama.jchatmind.tool.ToolExecutionRecord;
import com.kama.jchatmind.tool.ToolFailureClassifier;
import com.kama.jchatmind.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolExecutionServiceImplTest {

    @Mock
    private ToolRegistry toolRegistry;

    @Mock
    private AgentTaskLogService agentTaskLogService;

    @Mock
    private AgentEventPublisher agentEventPublisher;

    @Test
    void policyRejectedDatabaseResultIsRecordedAsFailedToolCall() {
        ToolExecutionServiceImpl service = new ToolExecutionServiceImpl(
                toolRegistry,
                agentTaskLogService,
                agentEventPublisher,
                new ToolFailureClassifier()
        );
        ToolExecutionContext context = ToolExecutionContext.builder()
                .taskId("task-1")
                .stepId("step-1")
                .sessionId("session-1")
                .build();
        ToolExecutionRecord record = ToolExecutionRecord.builder()
                .toolCallId("call-1")
                .toolCallLogId("log-1")
                .canonicalToolName("databaseQuery")
                .actualToolName("databaseQuery")
                .startedAtMillis(System.currentTimeMillis())
                .build();
        String rejected = "[REJECTED_BY_POLICY] rejected=true reason=Only SELECT is allowed";
        when(toolRegistry.truncateResult("databaseQuery", rejected)).thenReturn(rejected);

        service.afterToolSuccess(context, record, rejected);

        verify(agentTaskLogService).failToolCall(
                eq("log-1"),
                eq(rejected),
                anyLong(),
                eq(AgentTaskLogService.ERROR_TYPE_POLICY_REJECTED),
                eq(true)
        );
        verify(agentTaskLogService, never()).finishToolCall(eq("log-1"), eq(rejected), anyLong(), anyBoolean());
    }
}
