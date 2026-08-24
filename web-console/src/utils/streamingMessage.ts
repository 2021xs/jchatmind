import type {
  AgentSseEvent,
  AgentTaskTrace,
  ChatMessage,
  FinalStreamingSseEvent,
  StreamingMessageStatus,
} from "../types";

export interface StreamingMessageUpdate {
  messages: ChatMessage[];
  warning?: string;
}

export function asFinalStreamingEvent(
  event: AgentSseEvent,
): FinalStreamingSseEvent | null {
  const payload = event.payload;
  if (
    !payload ||
    typeof payload.streamId !== "string" ||
    typeof payload.stepId !== "string"
  ) {
    return null;
  }
  if (
    event.type === "final_message_start" &&
    payload.phase === "final_answer"
  ) {
    return event as FinalStreamingSseEvent;
  }
  if (
    event.type === "token" &&
    typeof payload.sequence === "number" &&
    typeof payload.delta === "string"
  ) {
    return event as FinalStreamingSseEvent;
  }
  if (
    event.type === "final_message_done" &&
    typeof payload.messageId === "string"
  ) {
    return event as FinalStreamingSseEvent;
  }
  if (
    event.type === "final_message_abort" &&
    typeof payload.reason === "string"
  ) {
    return event as FinalStreamingSseEvent;
  }
  return null;
}

export function applyFinalStreamingEvent(
  messages: ChatMessage[],
  event: FinalStreamingSseEvent,
  fallbackSessionId: string,
): StreamingMessageUpdate {
  switch (event.type) {
    case "final_message_start":
      return startFinalMessage(messages, event, fallbackSessionId);
    case "token":
      return appendFinalToken(messages, event);
    case "final_message_done":
      return completeFinalMessage(messages, event);
    case "final_message_abort":
      return updateByStreamId(messages, event.payload.streamId, "aborted");
  }
}

export function markActiveStreamingMessages(
  messages: ChatMessage[],
  sessionId: string,
  status: Extract<StreamingMessageStatus, "aborted" | "failed">,
): ChatMessage[] {
  return messages.map((message) =>
    message.sessionId === sessionId &&
    message.provisional &&
    message.status === "streaming"
      ? { ...message, status }
      : message,
  );
}

export function hasActiveStreamingMessage(
  messages: ChatMessage[],
  sessionId?: string,
): boolean {
  return messages.some(
    (message) =>
      message.provisional &&
      message.status === "streaming" &&
      (!sessionId || message.sessionId === sessionId),
  );
}

export function discardTerminalProvisionalMessages(
  messages: ChatMessage[],
): ChatMessage[] {
  return messages.filter(
    (message) =>
      !(
        message.provisional &&
        (message.status === "aborted" || message.status === "failed")
      ),
  );
}

export function reconcileLoadedMessages(
  current: ChatMessage[],
  persisted: ChatMessage[],
  sessionId: string,
  traces: AgentTaskTrace[] = [],
): ChatMessage[] {
  const reloadMatches = findReloadMatches(
    current,
    persisted,
    sessionId,
    traces,
  );
  const reconciled = persisted.map((message) =>
    mergeRuntimeState(current, message, reloadMatches.get(message.id ?? "")),
  );
  const matchedStreamIds = new Set(
    [...reloadMatches.values()]
      .map((message) => message.streamId)
      .filter((streamId): streamId is string => Boolean(streamId)),
  );
  const retainedProvisional = current.filter(
    (message) =>
      message.sessionId === sessionId &&
      message.provisional &&
      !message.id &&
      !matchedStreamIds.has(message.streamId ?? "") &&
      !reconciled.some((candidate) => candidate.streamId === message.streamId),
  );
  return [...reconciled, ...retainedProvisional];
}

export function upsertPersistedMessage(
  messages: ChatMessage[],
  incoming: ChatMessage,
): ChatMessage[] {
  if (!incoming.id) {
    return messages;
  }
  const existingIndex = messages.findIndex(
    (message) => message.id === incoming.id,
  );
  if (existingIndex < 0) {
    return [...messages, incoming];
  }
  return messages.map((message, index) =>
    index === existingIndex ? mergeRuntimeFields(message, incoming) : message,
  );
}

function startFinalMessage(
  messages: ChatMessage[],
  event: Extract<FinalStreamingSseEvent, { type: "final_message_start" }>,
  fallbackSessionId: string,
): StreamingMessageUpdate {
  const { streamId } = event.payload;
  if (messages.some((message) => message.streamId === streamId)) {
    return { messages };
  }
  return {
    messages: [
      ...messages,
      {
        sessionId: event.sessionId ?? fallbackSessionId,
        role: "assistant",
        content: "",
        streamId,
        taskId: event.taskId,
        status: "streaming",
        provisional: true,
        lastSequence: 0,
        createdAt: event.timestamp ?? new Date().toISOString(),
      },
    ],
  };
}

function appendFinalToken(
  messages: ChatMessage[],
  event: Extract<FinalStreamingSseEvent, { type: "token" }>,
): StreamingMessageUpdate {
  const { delta, sequence, streamId } = event.payload;
  if (!delta) {
    return { messages };
  }
  const index = messages.findIndex(
    (message) =>
      message.streamId === streamId && message.status === "streaming",
  );
  if (index < 0) {
    return {
      messages,
      warning: `Ignored TOKEN for unknown streamId ${streamId}`,
    };
  }
  const message = messages[index];
  const lastSequence = message.lastSequence ?? 0;
  if (sequence <= lastSequence) {
    return { messages };
  }
  const warning =
    sequence > lastSequence + 1
      ? `TOKEN sequence gap for ${streamId}: expected ${lastSequence + 1}, received ${sequence}`
      : undefined;
  return {
    messages: messages.map((candidate, candidateIndex) =>
      candidateIndex === index
        ? {
            ...candidate,
            content: candidate.content + delta,
            lastSequence: sequence,
          }
        : candidate,
    ),
    warning,
  };
}

function completeFinalMessage(
  messages: ChatMessage[],
  event: Extract<FinalStreamingSseEvent, { type: "final_message_done" }>,
): StreamingMessageUpdate {
  const { messageId, streamId } = event.payload;
  const streamIndex = messages.findIndex(
    (message) => message.streamId === streamId,
  );
  if (streamIndex < 0) {
    return {
      messages,
      warning: `Ignored FINAL_MESSAGE_DONE for unknown streamId ${streamId}`,
    };
  }
  const persistedIndex = messages.findIndex(
    (message) => message.id === messageId,
  );
  const streamMessage = messages[streamIndex];
  const completed: ChatMessage =
    persistedIndex >= 0
      ? mergeRuntimeFields(streamMessage, messages[persistedIndex], messageId)
      : {
          ...streamMessage,
          id: messageId,
          status: "complete",
          provisional: false,
        };
  const result: ChatMessage[] = [];
  messages.forEach((message, index) => {
    if (index === streamIndex) {
      result.push(completed);
    } else if (index !== persistedIndex) {
      result.push(message);
    }
  });
  return { messages: result };
}

function updateByStreamId(
  messages: ChatMessage[],
  streamId: string,
  status: StreamingMessageStatus,
): StreamingMessageUpdate {
  if (!messages.some((message) => message.streamId === streamId)) {
    return {
      messages,
      warning: `Ignored stream event for unknown streamId ${streamId}`,
    };
  }
  return {
    messages: messages.map((message) =>
      message.streamId === streamId ? { ...message, status } : message,
    ),
  };
}

function mergeRuntimeState(
  current: ChatMessage[],
  persisted: ChatMessage,
  reloadMatch?: ChatMessage,
): ChatMessage {
  const runtime =
    reloadMatch ??
    (persisted.id
      ? current.find((message) => message.id === persisted.id)
      : undefined);
  return runtime ? mergeRuntimeFields(runtime, persisted) : persisted;
}

function findReloadMatches(
  current: ChatMessage[],
  persisted: ChatMessage[],
  sessionId: string,
  traces: AgentTaskTrace[],
): Map<string, ChatMessage> {
  const unresolved = current.filter(
    (message) =>
      message.sessionId === sessionId &&
      message.role === "assistant" &&
      message.provisional === true &&
      message.status === "streaming" &&
      !message.id &&
      Boolean(message.streamId) &&
      Boolean(message.taskId),
  );
  const proposals = unresolved.flatMap((message) => {
    const matchingTraces = traces.filter(
      (trace) =>
        trace.id === message.taskId &&
        trace.sessionId === sessionId &&
        isSuccessfulTrace(trace) &&
        Boolean(trace.userMessageId),
    );
    if (matchingTraces.length !== 1) {
      return [];
    }
    const persistedFinal = findFinalForUserTurn(
      persisted,
      matchingTraces[0].userMessageId!,
      sessionId,
    );
    if (
      !persistedFinal?.id ||
      (message.content.length > 0 &&
        !persistedFinal.content.startsWith(message.content))
    ) {
      return [];
    }
    return [{ messageId: persistedFinal.id, provisional: message }];
  });

  const matchCounts = new Map<string, number>();
  proposals.forEach(({ messageId }) => {
    matchCounts.set(messageId, (matchCounts.get(messageId) ?? 0) + 1);
  });
  return new Map(
    proposals
      .filter(({ messageId }) => matchCounts.get(messageId) === 1)
      .map(({ messageId, provisional }) => [messageId, provisional]),
  );
}

function findFinalForUserTurn(
  persisted: ChatMessage[],
  userMessageId: string,
  sessionId: string,
): ChatMessage | undefined {
  const userIndex = persisted.findIndex(
    (message) =>
      message.id === userMessageId &&
      message.sessionId === sessionId &&
      message.role === "user",
  );
  if (userIndex < 0) {
    return undefined;
  }
  const nextUserIndex = persisted.findIndex(
    (message, index) => index > userIndex && message.role === "user",
  );
  const turnEnd = nextUserIndex < 0 ? persisted.length : nextUserIndex;
  const candidates = persisted
    .slice(userIndex + 1, turnEnd)
    .filter(
      (message) =>
        message.role === "assistant" &&
        message.sessionId === sessionId &&
        Boolean(message.id) &&
        !hasToolCalls(message),
    );
  return candidates.length === 1 ? candidates[0] : undefined;
}

function isSuccessfulTrace(trace: AgentTaskTrace): boolean {
  return trace.status === "SUCCESS" || trace.status === "COMPLETED";
}

function hasToolCalls(message: ChatMessage): boolean {
  return (
    Array.isArray(message.metadata?.toolCalls) &&
    message.metadata.toolCalls.length > 0
  );
}

function mergeRuntimeFields(
  runtime: ChatMessage,
  persisted: ChatMessage,
  messageId = persisted.id,
): ChatMessage {
  return {
    ...persisted,
    id: messageId,
    streamId: runtime.streamId,
    taskId: runtime.taskId,
    status: runtime.streamId ? "complete" : runtime.status,
    provisional: runtime.streamId ? false : runtime.provisional,
    lastSequence: runtime.lastSequence,
  };
}
