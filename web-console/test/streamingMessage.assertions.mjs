import {
  applyFinalStreamingEvent,
  reconcileLoadedMessages,
  upsertPersistedMessage,
} from "../target/deterministic-tests/utils/streamingMessage.js";

const SESSION_ID = "session-1";
const USER_ID = "user-1";
const TASK_ID = "task-1";
const STREAM_ID = "stream-1";
const MESSAGE_ID = "message-1";

function message(overrides) {
  return {
    sessionId: SESSION_ID,
    role: "assistant",
    content: "",
    ...overrides,
  };
}

function unresolved(overrides = {}) {
  return message({
    streamId: STREAM_ID,
    taskId: TASK_ID,
    status: "streaming",
    provisional: true,
    lastSequence: 1,
    ...overrides,
  });
}

function successfulTrace(overrides = {}) {
  return {
    id: TASK_ID,
    sessionId: SESSION_ID,
    userMessageId: USER_ID,
    status: "SUCCESS",
    steps: [],
    toolCalls: [],
    ...overrides,
  };
}

function assert(condition, label) {
  if (!condition) {
    throw new Error(`Assertion failed: ${label}`);
  }
}

function assertPersistedFinal(result, expectedContent) {
  const assistants = result.filter((item) => item.role === "assistant");
  assert(assistants.length === 1, "one assistant bubble");
  assert(assistants[0].id === MESSAGE_ID, "DB message identity wins");
  assert(assistants[0].content === expectedContent, "DB full content wins");
  assert(assistants[0].status === "complete", "status is complete");
  assert(assistants[0].provisional === false, "message is not provisional");
  assert(assistants[0].streamId === STREAM_ID, "stream metadata is retained");
}

function testDisconnectReload() {
  const current = [
    message({ id: USER_ID, role: "user", content: "question" }),
    unresolved({ content: "ABC" }),
  ];
  const persisted = [
    message({ id: USER_ID, role: "user", content: "question" }),
    message({ id: MESSAGE_ID, content: "ABCDEFG" }),
  ];
  const result = reconcileLoadedMessages(current, persisted, SESSION_ID, [
    successfulTrace(),
  ]);
  assertPersistedFinal(result, "ABCDEFG");
}

function testPrefixContent() {
  const current = [
    message({ id: USER_ID, role: "user", content: "question" }),
    unresolved({ content: "Redis + Lua" }),
  ];
  const persisted = [
    message({ id: USER_ID, role: "user", content: "question" }),
    message({ id: MESSAGE_ID, content: "Redis + Lua ...complete answer" }),
  ];
  assertPersistedFinal(
    reconcileLoadedMessages(current, persisted, SESSION_ID, [
      successfulTrace(),
    ]),
    "Redis + Lua ...complete answer",
  );
}

function testNormalAndRepeatedReload() {
  const persisted = [
    message({ id: USER_ID, role: "user", content: "question" }),
    message({ id: MESSAGE_ID, content: "complete answer" }),
  ];
  const complete = message({
    id: MESSAGE_ID,
    content: "complete answer",
    streamId: STREAM_ID,
    taskId: TASK_ID,
    status: "complete",
    provisional: false,
  });
  const first = reconcileLoadedMessages(
    [persisted[0], complete],
    persisted,
    SESSION_ID,
    [successfulTrace()],
  );
  const second = reconcileLoadedMessages(first, persisted, SESSION_ID, [
    successfulTrace(),
  ]);
  assertPersistedFinal(first, "complete answer");
  assertPersistedFinal(second, "complete answer");
}

function testTerminalProvisionalIsNotUpgraded(status) {
  const history = message({
    id: "history-assistant",
    content: "historical answer",
  });
  const terminal = unresolved({
    streamId: `stream-${status}`,
    status,
    content: "partial",
  });
  const result = reconcileLoadedMessages(
    [history, terminal],
    [history],
    SESSION_ID,
    [successfulTrace()],
  );
  assert(
    result.length === 2,
    `${status} partial is retained by existing reload semantics`,
  );
  assert(
    result.some(
      (item) => item.id === history.id && item.content === history.content,
    ),
    `${status} history remains`,
  );
  assert(
    result.some(
      (item) => item.streamId === terminal.streamId && item.status === status,
    ),
    `${status} is not upgraded`,
  );
}

function testAssistantHistoryIsPreserved() {
  const oldUser = message({
    id: "old-user",
    role: "user",
    content: "old question",
  });
  const oldAssistant = message({ id: "old-assistant", content: "old answer" });
  const user = message({ id: USER_ID, role: "user", content: "question" });
  const current = [oldUser, oldAssistant, user, unresolved({ content: "new" })];
  const persisted = [
    oldUser,
    oldAssistant,
    user,
    message({ id: MESSAGE_ID, content: "new complete answer" }),
  ];
  const result = reconcileLoadedMessages(current, persisted, SESSION_ID, [
    successfulTrace(),
  ]);
  const assistants = result.filter((item) => item.role === "assistant");
  assert(assistants.length === 2, "history plus one final assistant remain");
  assert(
    assistants[0].id === "old-assistant",
    "history assistant order is preserved",
  );
  assert(assistants[1].id === MESSAGE_ID, "persisted final keeps reload order");
}

function testMultipleProvisionalAmbiguity() {
  const user = message({ id: USER_ID, role: "user", content: "question" });
  const first = unresolved({ streamId: "stream-a", content: "ABC" });
  const second = unresolved({ streamId: "stream-b", content: "ABC" });
  const persisted = [user, message({ id: MESSAGE_ID, content: "ABCDEFG" })];
  const result = reconcileLoadedMessages(
    [user, first, second],
    persisted,
    SESSION_ID,
    [successfulTrace()],
  );
  assert(
    result.filter((item) => item.provisional).length === 2,
    "ambiguous provisionals are retained",
  );
  assert(
    result.filter((item) => item.id === MESSAGE_ID).length === 1,
    "persisted message is not duplicated",
  );
}

function testEmptyPartialNeedsStrongIdentity() {
  const user = message({ id: USER_ID, role: "user", content: "question" });
  const empty = unresolved({ content: "" });
  const persisted = [
    user,
    message({ id: MESSAGE_ID, content: "complete answer" }),
  ];
  assertPersistedFinal(
    reconcileLoadedMessages([user, empty], persisted, SESSION_ID, [
      successfulTrace(),
    ]),
    "complete answer",
  );
  const withoutTrace = reconcileLoadedMessages(
    [user, empty],
    persisted,
    SESSION_ID,
    [],
  );
  assert(
    withoutTrace.filter((item) => item.role === "assistant").length === 2,
    "empty partial is not prefix-matched without task identity",
  );
}

function testConservativeCandidateChecks() {
  const user = message({ id: USER_ID, role: "user", content: "question" });
  const partial = unresolved({ content: "expected prefix" });
  const wrongPrefix = [
    user,
    message({ id: MESSAGE_ID, content: "different answer" }),
  ];
  assert(
    reconcileLoadedMessages([user, partial], wrongPrefix, SESSION_ID, [
      successfulTrace(),
    ]).filter((item) => item.role === "assistant").length === 2,
    "non-prefix content is not merged",
  );
  const twoFinalCandidates = [
    user,
    message({ id: "candidate-1", content: "expected prefix one" }),
    message({ id: "candidate-2", content: "expected prefix two" }),
  ];
  assert(
    reconcileLoadedMessages([user, partial], twoFinalCandidates, SESSION_ID, [
      successfulTrace(),
    ]).filter((item) => item.provisional).length === 1,
    "multiple final candidates are not merged",
  );
}

function startEvent() {
  return {
    type: "final_message_start",
    taskId: TASK_ID,
    sessionId: SESSION_ID,
    payload: { streamId: STREAM_ID, stepId: "step-1", phase: "final_answer" },
  };
}

function tokenEvent(sequence, delta) {
  return {
    type: "token",
    taskId: TASK_ID,
    sessionId: SESSION_ID,
    payload: { streamId: STREAM_ID, stepId: "step-1", sequence, delta },
  };
}

function doneEvent() {
  return {
    type: "final_message_done",
    taskId: TASK_ID,
    sessionId: SESSION_ID,
    payload: { streamId: STREAM_ID, stepId: "step-1", messageId: MESSAGE_ID },
  };
}

function streamedMessages() {
  let messages = applyFinalStreamingEvent(
    [],
    startEvent(),
    SESSION_ID,
  ).messages;
  messages = applyFinalStreamingEvent(
    messages,
    tokenEvent(1, "ABC"),
    SESSION_ID,
  ).messages;
  messages = applyFinalStreamingEvent(
    messages,
    tokenEvent(1, "duplicate"),
    SESSION_ID,
  ).messages;
  messages = applyFinalStreamingEvent(
    messages,
    tokenEvent(2, "DEF"),
    SESSION_ID,
  ).messages;
  assert(
    messages.length === 1 && messages[0].content === "ABCDEF",
    "TOKEN sequence remains idempotent",
  );
  return messages;
}

function testLegacyBeforeDone() {
  let messages = streamedMessages();
  messages = upsertPersistedMessage(
    messages,
    message({ id: MESSAGE_ID, content: "ABCDEF" }),
  );
  messages = applyFinalStreamingEvent(
    messages,
    doneEvent(),
    SESSION_ID,
  ).messages;
  assertPersistedFinal(messages, "ABCDEF");
}

function testDoneBeforeLegacy() {
  let messages = streamedMessages();
  messages = applyFinalStreamingEvent(
    messages,
    doneEvent(),
    SESSION_ID,
  ).messages;
  messages = upsertPersistedMessage(
    messages,
    message({ id: MESSAGE_ID, content: "ABCDEF" }),
  );
  assertPersistedFinal(messages, "ABCDEF");
}

testDisconnectReload();
testPrefixContent();
testNormalAndRepeatedReload();
testTerminalProvisionalIsNotUpgraded("aborted");
testTerminalProvisionalIsNotUpgraded("failed");
testAssistantHistoryIsPreserved();
testMultipleProvisionalAmbiguity();
testEmptyPartialNeedsStrongIdentity();
testConservativeCandidateChecks();
testLegacyBeforeDone();
testDoneBeforeLegacy();

globalThis.console.log("streamingMessage reconciliation assertions: PASS");
