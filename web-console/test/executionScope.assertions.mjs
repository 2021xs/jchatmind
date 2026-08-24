import {
  filterExecutionEvents,
  selectExecutionTrace,
} from "../target/deterministic-tests/utils/executionScope.js";

function assert(condition, label) {
  if (!condition) {
    throw new Error(`Assertion failed: ${label}`);
  }
}

const traceA = {
  id: "task-a",
  sessionId: "session-1",
  userMessageId: "user-a",
  traceId: "run-a",
  steps: [],
  toolCalls: [],
};
const traceB = {
  id: "task-b",
  sessionId: "session-1",
  userMessageId: "user-b",
  traceId: "run-b",
  steps: [],
  toolCalls: [],
};

assert(
  selectExecutionTrace({
    traces: [traceA],
    selectedTraceId: "task-a",
    taskPending: true,
  }) === undefined,
  "starting a new question immediately hides the old selected task",
);

assert(
  selectExecutionTrace({
    traces: [traceB, traceA],
    activeTaskId: "task-b",
    selectedTraceId: "task-a",
  })?.id === "task-b",
  "active task wins over an old historical selection",
);

assert(
  selectExecutionTrace({
    traces: [traceA],
    activeTaskId: "task-b",
    selectedTraceId: "task-a",
  }) === undefined,
  "an active task that is not loaded does not fall back to an old task",
);

assert(
  selectExecutionTrace({
    traces: [traceA],
    selectedTraceId: "task-b",
  }) === undefined,
  "a selected task that is still loading does not fall back to an old task",
);

assert(
  selectExecutionTrace({
    traces: [traceB, traceA],
    selectedTraceId: "task-a",
  })?.id === "task-a",
  "historical task remains selectable after the active run",
);

const events = [
  { eventId: "a-1", taskId: "task-a", sessionId: "session-1", type: "step_done" },
  { eventId: "a-2", taskId: "task-a", sessionId: "session-1", type: "done" },
  { eventId: "b-1", taskId: "task-b", sessionId: "session-1", type: "step_done" },
  { eventId: "b-2", taskId: "task-b", sessionId: "session-1", type: "done" },
  { eventId: "other-session", taskId: "task-b", sessionId: "session-2", type: "done" },
];

const taskBEvents = filterExecutionEvents(events, "task-b", "session-1");
assert(taskBEvents.length === 2, "only selected task events are visible");
assert(taskBEvents.every((event) => event.taskId === "task-b"), "task events do not mix");
assert(taskBEvents.every((event) => event.sessionId === "session-1"), "session events do not mix");
assert(
  filterExecutionEvents(events, "task-b", "session-2").length === 1,
  "switching sessions excludes the prior session event buffer",
);

console.log("execution scope assertions: PASS");
