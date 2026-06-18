import { useEffect } from "react";
import { createSseConnection } from "../api/client";
import type { LegacySseMessage, RuntimeState } from "../types";
import { asAgentSseEvent, hasToolCalls, upsertMessage } from "../utils/messageDisplay";

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
        activeUserMessageId: undefined,
      }));
      return undefined;
    }

    const activeSessionId = sessionId;
    setState((previous) => ({
      ...previous,
      sseStatus: "connecting",
      sessionError: undefined,
    }));
    void refreshSessionData(activeSessionId);

    const source = createSseConnection(activeSessionId);

    const appendAgentEvent = (event: Event) => {
      const parsed = asAgentSseEvent(eventData(event));
      if (!parsed) {
        return;
      }
      setState((previous) => ({
        ...previous,
        sseStatus: "connected",
        sseEvents: [parsed, ...previous.sseEvents].slice(0, 80),
        messageStatus:
          parsed.type === "done"
            ? "completed"
            : parsed.type === "error"
              ? "failed"
              : parsed.type === "message_start" ||
                  parsed.type === "tool_call_start" ||
                  parsed.type === "tool_call_result" ||
                  parsed.type === "step_done"
                ? "generating"
                : previous.messageStatus,
        sending: parsed.type === "done" || parsed.type === "error" ? false : previous.sending,
        activeRunId:
          typeof parsed.payload?.traceId === "string"
            ? parsed.payload.traceId
            : previous.activeRunId,
        detailMode:
          parsed.type === "tool_call_start" || parsed.type === "tool_call_result"
            ? "tools"
            : previous.detailMode,
      }));
      if (parsed.type === "done" || parsed.type === "error") {
        void refreshSessionData(activeSessionId);
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
          messagePayload.role === "assistant" && !hasToolCalls(messagePayload)
            ? "completed"
            : previous.messageStatus,
        sending:
          messagePayload.role === "assistant" && !hasToolCalls(messagePayload)
            ? false
            : previous.sending,
      }));
    };

    source.addEventListener("init", () => {
      setState((previous) => ({ ...previous, sseStatus: "connected" }));
    });
    source.addEventListener("message_start", appendAgentEvent);
    source.addEventListener("token", appendAgentEvent);
    source.addEventListener("tool_call_start", appendAgentEvent);
    source.addEventListener("tool_call_result", appendAgentEvent);
    source.addEventListener("retrieval_result", appendAgentEvent);
    source.addEventListener("step_done", appendAgentEvent);
    source.addEventListener("done", appendAgentEvent);
    source.addEventListener("error", appendAgentEvent);
    source.addEventListener("message", appendLegacyMessage);
    source.onerror = () => {
      setState((previous) => ({
        ...previous,
        sseStatus: "error",
        sseEvents: [
          {
            type: "error",
            timestamp: new Date().toISOString(),
            payload: { errorMessage: "SSE connection interrupted" },
          },
          ...previous.sseEvents,
        ].slice(0, 80),
      }));
    };

    return () => {
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
