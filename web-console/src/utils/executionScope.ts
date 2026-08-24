import type { AgentSseEvent, AgentTaskTrace } from "../types";

export interface ExecutionTraceSelection {
  traces: AgentTaskTrace[];
  activeTaskId?: string;
  selectedTraceId?: string;
  activeUserMessageId?: string;
  activeRunId?: string;
  taskPending?: boolean;
}

export function selectExecutionTrace({
  traces,
  activeTaskId,
  selectedTraceId,
  activeUserMessageId,
  activeRunId,
  taskPending,
}: ExecutionTraceSelection): AgentTaskTrace | undefined {
  if (taskPending && !activeTaskId) {
    return undefined;
  }

  if (activeTaskId) {
    return traces.find((trace) => trace.id === activeTaskId);
  }

  if (selectedTraceId) {
    return traces.find((trace) => trace.id === selectedTraceId);
  }

  return (
    traces.find((trace) => trace.userMessageId === activeUserMessageId) ??
    traces.find((trace) => trace.traceId === activeRunId) ??
    traces[0]
  );
}

export function filterExecutionEvents(
  events: AgentSseEvent[],
  taskId?: string,
  sessionId?: string,
): AgentSseEvent[] {
  if (!taskId || !sessionId) {
    return [];
  }
  return events.filter(
    (event) => event.taskId === taskId && event.sessionId === sessionId,
  );
}
