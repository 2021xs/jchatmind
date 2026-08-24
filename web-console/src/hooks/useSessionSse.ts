import { useEffect } from "react";
import { createSseConnection } from "../api/client";
import type { FinalStreamingSseEvent, LegacySseMessage, RuntimeState } from "../types";
import { asAgentSseEvent, hasToolCalls, upsertMessage } from "../utils/messageDisplay";
import {
  applyFinalStreamingEvent,
  asFinalStreamingEvent,
  hasActiveStreamingMessage,
  markActiveStreamingMessages,
} from "../utils/streamingMessage";

export function useSessionSse({
  sessionId,
  setState,
  refreshSessionData,
}: {
  sessionId?: string;
  setState: React.Dispatch<React.SetStateAction<RuntimeState>>;
  refreshSessionData: (sessionId: string) => Promise<void>;
}) {
  useEffect(() => {
    if (!sessionId) {
      setState((previous) => ({
        ...previous,
        messages: [],
        traces: [],
        sseEvents: [],
        selectedTraceId: undefined,
        sseStatus: "disconnected",
        messageStatus: "idle",
        activeRunId: undefined,
        activeTaskId: undefined,
        activeUserMessageId: undefined,
      }));
      return undefined;
    }

    const activeSessionId = sessionId;
    setState((previous) => ({
      ...previous,
      sseStatus: "connecting",
      sessionError: undefined,
      traces: [],
      sseEvents: [],
      selectedTraceId: undefined,
      activeRunId: undefined,
      activeTaskId: undefined,
      activeUserMessageId: undefined,
    }));
    void refreshSessionData(activeSessionId);

    const source = createSseConnection(activeSessionId);
    let pendingTokenEvents: FinalStreamingSseEvent[] = [];
    let tokenFlushTimer: ReturnType<typeof setTimeout> | undefined;
    let traceRefreshTimer: ReturnType<typeof setTimeout> | undefined;

    const scheduleTraceRefresh = () => {
      if (traceRefreshTimer) {
        clearTimeout(traceRefreshTimer);
      }
      traceRefreshTimer = setTimeout(() => {
        traceRefreshTimer = undefined;
        void refreshSessionData(activeSessionId);
      }, 150);
    };

    const refreshTraceNow = () => {
      if (traceRefreshTimer) {
        clearTimeout(traceRefreshTimer);
        traceRefreshTimer = undefined;
      }
      void refreshSessionData(activeSessionId);
    };

    const applyStreamingEventsToMessages = (
      messages: RuntimeState["messages"],
      events: FinalStreamingSseEvent[],
    ) => events.reduce((current, streamingEvent) => {
      const update = applyFinalStreamingEvent(current, streamingEvent, activeSessionId);
      if (update.warning) {
        console.warn(update.warning);
      }
      return update.messages;
    }, messages);

    const takePendingTokenEvents = () => {
      if (tokenFlushTimer) {
        clearTimeout(tokenFlushTimer);
        tokenFlushTimer = undefined;
      }
      const pending = pendingTokenEvents;
      pendingTokenEvents = [];
      return pending;
    };

    const flushPendingTokenEvents = () => {
      const pending = takePendingTokenEvents();
      if (pending.length === 0) {
        return;
      }
      setState((previous) => ({
        ...previous,
        messages: applyStreamingEventsToMessages(previous.messages, pending),
        sseStatus: "connected",
      }));
    };

    const queueTokenEvent = (event: FinalStreamingSseEvent) => {
      pendingTokenEvents.push(event);
      if (!tokenFlushTimer) {
        tokenFlushTimer = setTimeout(flushPendingTokenEvents, 30);
      }
    };

    const appendAgentEvent = (event: Event) => {
      const parsed = asAgentSseEvent(eventData(event));
      if (!parsed) {
        return;
      }
      if (parsed.type === "done" || parsed.type === "error" || parsed.type === "cancelled") {
        flushPendingTokenEvents();
      }
      setState((previous) => {
        let messages = previous.messages;
        if (parsed.type === "error") {
          messages = markActiveStreamingMessages(messages, activeSessionId, "failed");
        } else if (parsed.type === "cancelled") {
          messages = markActiveStreamingMessages(messages, activeSessionId, "aborted");
        } else if (parsed.type === "done" && hasActiveStreamingMessage(messages, activeSessionId)) {
          console.warn("DONE received while a final assistant message is still streaming");
          messages = markActiveStreamingMessages(messages, activeSessionId, "failed");
        }
        return {
          ...previous,
          messages,
          sseStatus: "connected",
          sseEvents: [parsed, ...previous.sseEvents].slice(0, 80),
          messageStatus:
            parsed.type === "cancelled"
              ? "cancelled"
              : parsed.type === "done"
                ? "completed"
                : parsed.type === "error"
                  ? "failed"
                  : parsed.type === "message_start" ||
                      parsed.type === "tool_call_start" ||
                      parsed.type === "tool_call_result" ||
                      parsed.type === "step_done"
                    ? "generating"
                    : previous.messageStatus,
          sending:
            parsed.type === "done" || parsed.type === "error" || parsed.type === "cancelled"
              ? false
              : previous.sending,
          activeRunId:
            typeof parsed.payload?.traceId === "string"
              ? parsed.payload.traceId
              : previous.activeRunId,
          selectedTraceId:
            parsed.type === "message_start" && typeof parsed.taskId === "string"
              ? parsed.taskId
              : previous.selectedTraceId,
          activeTaskId:
            parsed.type === "done" || parsed.type === "error" || parsed.type === "cancelled"
              ? undefined
              : typeof parsed.taskId === "string"
                ? parsed.taskId
                : previous.activeTaskId,
        };
      });
      if (parsed.type === "done" || parsed.type === "error" || parsed.type === "cancelled") {
        refreshTraceNow();
      } else if (parsed.type === "step_done") {
        scheduleTraceRefresh();
      }
    };

    const appendLegacyMessage = (event: Event) => {
      const parsed = parseLegacyMessage(eventData(event));
      const messagePayload = parsed?.payload?.message;
      if (!messagePayload) {
        return;
      }
      setState((previous) => ({
        ...previous,
        sseStatus: "connected",
        messages: upsertMessage(previous.messages, messagePayload),
        messageStatus:
          messagePayload.role === "assistant" &&
          !hasToolCalls(messagePayload) &&
          !hasActiveStreamingMessage(previous.messages, activeSessionId)
            ? "completed"
            : previous.messageStatus,
        sending:
          messagePayload.role === "assistant" &&
          !hasToolCalls(messagePayload) &&
          !hasActiveStreamingMessage(previous.messages, activeSessionId)
            ? false
            : previous.sending,
      }));
    };

    const applyStreamingMessageEvent = (event: Event) => {
      const parsed = asAgentSseEvent(eventData(event));
      const streamingEvent = parsed ? asFinalStreamingEvent(parsed) : null;
      if (!parsed || !streamingEvent) {
        console.warn("Ignored invalid final streaming SSE event");
        return;
      }
      if (streamingEvent.type === "token") {
        queueTokenEvent(streamingEvent);
        return;
      }
      const events = [...takePendingTokenEvents(), streamingEvent];
      setState((previous) => {
        return {
          ...previous,
          messages: applyStreamingEventsToMessages(previous.messages, events),
          sseStatus: "connected",
          sseEvents: [parsed, ...previous.sseEvents].slice(0, 80),
          messageStatus:
            streamingEvent.type === "final_message_start"
              ? "generating"
              : previous.messageStatus,
          activeTaskId:
            typeof streamingEvent.taskId === "string"
              ? streamingEvent.taskId
              : previous.activeTaskId,
        };
      });
    };

    source.addEventListener("init", () => {
      setState((previous) => ({ ...previous, sseStatus: "connected" }));
    });
    source.addEventListener("message_start", appendAgentEvent);
    source.addEventListener("final_message_start", applyStreamingMessageEvent);
    source.addEventListener("token", applyStreamingMessageEvent);
    source.addEventListener("final_message_done", applyStreamingMessageEvent);
    source.addEventListener("final_message_abort", applyStreamingMessageEvent);
    source.addEventListener("tool_call_start", appendAgentEvent);
    source.addEventListener("tool_call_result", appendAgentEvent);
    source.addEventListener("retrieval_result", appendAgentEvent);
    source.addEventListener("step_done", appendAgentEvent);
    source.addEventListener("done", appendAgentEvent);
    source.addEventListener("cancelled", appendAgentEvent);
    source.addEventListener("error", appendAgentEvent);
    source.addEventListener("message", appendLegacyMessage);
    source.onerror = () => {
      setState((previous) => {
        const activeRun =
          previous.sending ||
          previous.messageStatus === "sending" ||
          previous.messageStatus === "generating";
        if (!activeRun) {
          return {
            ...previous,
            sseStatus: "disconnected",
          };
        }
        return {
          ...previous,
          sseStatus: "error",
          sseEvents: [
            {
              taskId: previous.activeTaskId,
              sessionId: activeSessionId,
              type: "error",
              timestamp: new Date().toISOString(),
              payload: { errorMessage: "SSE connection interrupted during active run" },
            },
            ...previous.sseEvents,
          ].slice(0, 80),
        };
      });
    };

    return () => {
      if (tokenFlushTimer) {
        clearTimeout(tokenFlushTimer);
      }
      if (traceRefreshTimer) {
        clearTimeout(traceRefreshTimer);
      }
      pendingTokenEvents = [];
      source.close();
    };
  }, [refreshSessionData, sessionId, setState]);
}

function parseLegacyMessage(data: string): LegacySseMessage | null {
  try {
    return JSON.parse(data) as LegacySseMessage;
  } catch {
    return null;
  }
}

function eventData(event: Event): string {
  return "data" in event ? String((event as MessageEvent).data) : "";
}
