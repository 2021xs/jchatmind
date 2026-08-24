import {
  compactIdentifier,
  eventDisplay,
  parseToolInvocations,
  stepDisplay,
  toolCallDiagnostics,
} from "../target/deterministic-tests/utils/executionDisplay.js";
import {
  normalizeToolContent,
  parseCodeEvidence,
} from "../target/deterministic-tests/utils/evidence.js";

function assert(condition, label) {
  if (!condition) {
    throw new Error(`Assertion failed: ${label}`);
  }
}

const selectedEvidence = JSON.stringify(`Selected code evidence:

[1]
file: src/main/resources/seckill.lua
symbol: seckill.lua
type: LUA_SCRIPT
lines: 1-12

snippet:
local stockKey = 'seckill:stock:' .. voucherId
redis.call('decr', stockKey)

[2]
file: src/main/java/com/example/OrderService.java
symbol: OrderService#create
type: JAVA_METHOD
lines: 20-42

snippet:
public void create() {
    // create order
}`);

const evidence = parseCodeEvidence(selectedEvidence);
assert(evidence.length === 2, "selected evidence count");
assert(evidence[0]?.filePath === "src/main/resources/seckill.lua", "file parsed");
assert(evidence[0]?.symbolName === "seckill.lua", "symbol parsed");
assert(evidence[0]?.chunkType === "LUA_SCRIPT", "type parsed");
assert(evidence[0]?.lineRange === "1-12", "lines parsed");
assert(evidence[0]?.snippet?.includes("redis.call('decr'"), "multiline snippet parsed");
assert(evidence[1]?.index === 2, "evidence index parsed");

const escapedWithoutClosingQuote =
  '"Selected code evidence:\\n\\n[1]\\nfile: src/Test.java\\ntype: JAVA_METHOD\\nlines: 1-2\\n\\nsnippet:\\nvoid test() {}';
assert(
  normalizeToolContent(escapedWithoutClosingQuote).includes("\nfile: src/Test.java\n"),
  "truncated JSON string newline normalized",
);
assert(parseCodeEvidence(escapedWithoutClosingQuote).length === 1, "truncated evidence parsed");

const invocations = parseToolInvocations(
  'searchProjectCode({"repoId":"repo-1","query":"seckill.lua 库存 原子"})\n' +
    'searchProjectCode({"repoId":"repo-1","query":"OrderService create"})',
);
assert(invocations.length === 2, "tool invocation count");
assert(invocations[0]?.toolName === "searchProjectCode", "tool name parsed");
assert(invocations[0]?.query === "seckill.lua 库存 原子", "query parsed");

const toolStep = stepDisplay({
  id: "step-1",
  taskId: "task-1",
  stepType: "TOOL_CALL",
  status: "SUCCESS",
  inputSummary:
    'searchProjectCode({"repoId":"repo-1","query":"seckill.lua 库存 原子"})',
});
assert(toolStep.title === "代码检索", "tool step has product title");
assert(toolStep.description.includes("1 个查询主题"), "tool step summarizes work");
assert(!toolStep.description.includes("repo-1"), "raw repository id hidden from summary");

const thinkStep = stepDisplay({
  id: "step-2",
  taskId: "task-1",
  stepType: "THINK",
  inputSummary: "think with current conversation memory",
  outputSummary:
    'searchProjectCode({"repoId":"repo-1","query":"OrderService create"})',
});
assert(thinkStep.title === "分析问题", "think step has natural title");
assert(thinkStep.description.includes("1 个后续检索主题"), "think summary is meaningful");
assert(!thinkStep.description.includes("think with current"), "internal think text hidden");

const finalDone = eventDisplay({
  type: "final_message_done",
  payload: { messageId: "message-123" },
});
assert(finalDone.label === "回答已完成", "final done event label");
assert(finalDone.description.includes("已落库"), "final done event explanation");
assert(compactIdentifier("12345678-1234-1234-1234-123456789abc") === "12345678…789abc", "long id compacted");

const successfulAuthEvidence = {
  id: "tool-1",
  taskId: "task-1",
  status: "SUCCESS",
  resultSummary:
    "file: ruoyi-auth/src/main/java/com/ruoyi/auth/controller/TokenController.java\n" +
    "symbol: AuthenticationManager\n" +
    "fallback=true\nselectorJsonParseOk: false\n401 403 unauthorized forbidden",
  blockedByPolicy: false,
};
assert(
  toolCallDiagnostics(successfulAuthEvidence).length === 0,
  "normal repository evidence never becomes an authentication or selector diagnostic",
);

const failedTool = {
  ...successfulAuthEvidence,
  status: "FAILED",
  errorType: "TOOL_EXCEPTION",
  errorMessage: "HTTP 401 from selector provider\nrequest rejected",
};
const failedDiagnostics = toolCallDiagnostics(failedTool);
assert(failedDiagnostics.length === 1, "failed tool uses only its explicit error channel");
assert(
  failedDiagnostics[0] === "工具失败：HTTP 401 from selector provider",
  "failed tool preserves the real first-line error without guessing its cause",
);

const blockedTool = {
  ...successfulAuthEvidence,
  status: "FAILED",
  blockedByPolicy: true,
  errorMessage: "Tool is not allowed for this agent",
};
assert(
  toolCallDiagnostics(blockedTool).includes("安全策略：已拦截本次工具调用"),
  "structured policy rejection is displayed",
);

console.log("execution display and evidence assertions: PASS");
