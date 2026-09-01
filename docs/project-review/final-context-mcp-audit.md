# JChatMind Final Context + MCP Audit Result

## 1. Baseline

```text
HEAD:
07ac3db444042fec4c647100b1763eecc498ce3c

working tree:
CLEAN
```

审计方式：只读源码、配置、历史 benchmark evidence 与本地 mock/fake 测试。未运行 Agent benchmark、真实 MCP server、外部模型或网络调用。

## 2. Audit Scope

```text
Context residual risk
MCP architecture
MCP security
MCP failure handling
MCP fault isolation
MCP timeout
MCP auditability
MCP transport
Context/MCP integration
```

## 3. Context Residual Audit

```text
Legacy production residue:
NONE

Final single path:
HEALTHY

Canonical persistence:
HEALTHY

Stable reread:
HEALTHY

Budget:
HEALTHY (soft trigger 200000; hard limit 256000)

Cross-task isolation:
HEALTHY
```

全局 Legacy 关键词搜索只命中 `src/test/.../benchmark/context` 的历史报告兼容字段和测试局部变量，分类为 `BENCHMARK_COMPATIBILITY_ONLY`。production 中不存在 `TaskToolTranscript`、Legacy Final recovery、shadow Final 或第二 evidence merge 路径。

Final production path 是单一来源：`ProtocolAwareChatMemory` 的 Working Context 经 `FinalSynthesisRequestFactory.create(...)`、`FinalContextCompiler`、单次 provider Final、`FinalCompletionService` 后持久化并推送 SSE。

Tool 路径先在 `JChatMind.handleToolCalls` 调用 `ChatMessageFacadeService.createToolProtocolBatch(...)` 持久化 canonical response，再由 `ToolCallBatchExecutor.projectForContext(...)` 生成 model view。`getCodeChunk` 使用 UUID 校验、trusted repo scope、READY repository 校验和 `selectByRepoIdAndChunkId` 精确读取，不经过 embedding、search 或 fallback。

Compression soft trigger 与 hard fail boundary 已拆分；失败状态以 `previous_failure` 抑制同一上下文上的立即重试。completed-task projection 仅保留历史 User/Final，排除历史 Planning、Tool、Continuation State；production benchmark 的 Historical raw Tool leakage 为 0。

Decision：

```text
CONTEXT_RESIDUAL_AUDIT_PASS
```

## 4. MCP Actual Architecture

```text
External MCP Server
  ↓
Spring AI McpSyncClient
  ↓ listTools
SpringAiExternalMcpClientAdapter
  ↓
ExternalMcpToolRegistry
  ↓ exact configured allow-list + risk/default-deny policy
McpToolCallbackAdapter
  ↓
JChatMindFactory merges callback with local ToolCallbacks
  ↓
ToolExecutionServiceImpl preflight
  ↓
ToolCallBatchExecutor
  ├ duplicate detector
  ├ step/tool budget
  └ 60-second default invocation timeout + cancellation
  ↓
McpSyncClient.callTool
  ↓
canonical ToolResponse persistence
  ↓
shared ToolResultGuard / model-view projection
  ↓
Working Context
  ↓
ToolCallLog + SSE + MCP-specific audit
```

MCP 由 `jchatmind.mcp.client.enabled` 条件启用，默认 `false`；committed `application-mcp.yaml` 的 `servers` 是空列表。本地工具注册不依赖 MCP registry，MCP callback 进入 Agent 后复用统一 preflight、duplicate detection、timeout、step budget、ToolCallLog、canonical persistence、projection 和 SSE 路径。

## 5. MCP Capability Matrix

| Capability | Actual Status | Security Boundary | Production Verified |
| --- | --- | --- | --- |
| Tool discovery | IMPLEMENTED_AND_TESTED | Per-server isolation; exact allow-list; default deny | Local fake/mock verified |
| navigate | IMPLEMENTED_AND_TESTED | Manual exact allow-list; `NETWORK_READ`; no project URL filter | Manual STDIO only |
| snapshot | CONFIG_PRESENT_ONLY | Generic discovery supports schema; absent from committed allow-list | No |
| click | NOT_SUPPORTED in active config | Not allow-listed; dangerous/write risk denied | Policy test only |
| arbitrary JS | NOT_SUPPORTED | Exact allow-list excludes it; real browser test denies `browser_run_code_unsafe` | Manual deny-path only |
| file read/write | NOT_SUPPORTED | `FILESYSTEM` server type rejected; default deny | Policy test only |
| shell | NOT_SUPPORTED | `SHELL` server type rejected; default deny | Policy test only |
| STDIO | IMPLEMENTED_AND_TESTED | Explicit opt-in config and manual environment gate | Yes, manual integration |
| SSE transport | NOT_SUPPORTED by project config | No project-owned config/test boundary | No |
| Streamable HTTP | NOT_SUPPORTED by project config | No project-owned config/test boundary | No |

当前 committed production 配置中没有 enabled MCP server，因此当前实际 exposed tool set 是空集。历史手工验证 profile 仅证明：Context7 `resolve-library-id`、Playwright `browser_navigate`、GitHub `search_repositories` 可经 STDIO 链路运行；对应测试分别拒绝未授权的 `query-docs`、`browser_run_code_unsafe`、`create_issue`。不要把 SDK transport 能力描述为项目能力。

## 6. Discovery & Failure Isolation

```text
listTools behavior:
ExternalMcpToolRegistry.registeredTools() 按 enabled server 调用 discovery；无 registry cache，factory/preflight 可重复 discovery。

server unavailable:
RuntimeException 在单 server 边界被捕获，该 server 返回 0 tools。

impact on local tools:
Local ToolRegistry 独立；失败 MCP server 不移除本地工具，也不阻断其他健康 MCP server。
```

`ExternalMcpToolRegistry.exposedTools(server)` 在 `listTools` 异常或 null response 时记录 UNAVAILABLE 并返回空列表。`ExternalMcpRegistryAndPolicyTest.discoveryFailureIsolatedToUnavailableServer`、capability failure test 和 `McpLocalToolRegistryIsolationTest` 覆盖 fault containment。

Verdict：

```text
PASS
```

## 7. Allow-list / Authorization

```text
discovery:
Dynamic listTools

authorization:
Exact configured tool name + explicit risk + explicit autoInvokeAllowed

default:
DENY
```

`allowList.containsKey(tool.getName())` 是精确匹配，不使用 prefix/contains/宽泛 regex。未配置 risk 时解析为 `DANGEROUS`；只有 `READ_ONLY` 和 `NETWORK_READ` 可自动调用，`WRITE_OPERATION`/`DANGEROUS` 被拒绝；`FILESYSTEM`、`DATABASE`、`SHELL` server type 不受支持。

这体现了核心边界：`Discovery != Authorization`。Server 后续新增 tool 不会自动暴露给 Agent。

Verdict：

```text
PASS
```

## 8. Failure Status Consistency

```text
MCP client call throws RuntimeException
→ McpToolCallbackAdapter writes MCP audit FAILED/MCP_TOOL_CALL_FAILED
→ adapter throws typed McpToolCallException
→ ToolCallBatchExecutor returns FAILED (not normal String success)
→ ToolExecutionService/AgentTaskLog marks ToolCallLog FAILED
→ protocol-safe failure becomes Planner-visible tool failure
→ SSE reports failure state
```

Adapter 不把 exception 转为 `"Error: ..."` 正常 String，也不把失败伪装为空结果。`McpToolCallbackAdapterTest` 和 `McpFakeEndToEndIntegrationTest` 覆盖 MCP audit 与 unified trace 同为失败；现有 `ToolCallBatchExecutorTest.mcpInvocationFailureRemainsFailureThroughUnifiedRuntime` 进一步锁定 typed classification。

正常空 result 与异常路径可区分：正常空仍是 callback success；异常必抛 typed exception。空成功是否满足具体业务语义由上层 tool contract 负责，不与 MCP transport failure 混淆。

Verdict：

```text
PASS
```

## 9. Timeout

```text
discovery timeout:
UNKNOWN at JChatMind ownership boundary; delegated to supplied Spring AI McpSyncClient.

tool timeout:
60 seconds by unified ToolTimeoutProperties default; per-tool override supported.

cancellation:
ToolCallBatchExecutor cancels the Future on timeout; AgentTaskControl also propagates task cancellation.
```

Manual real-client tests explicitly set request/initialization timeout to 45-60 seconds, but committed `McpClientProperties` does not own connect/discovery timeout. Therefore tool invocation timeout is code-proven healthy, while connect/discovery timeout cannot be declared project-controlled from current artifacts. There is no evidence of an infinite Agent tool call because MCP callbacks traverse the unified 60-second executor boundary.

Verdict：

```text
UNKNOWN
```

## 10. Network / URL Security

`browser_navigate` is exact-name allow-listed only in an opt-in manual profile and classified `NETWORK_READ`. The project layer does not validate URL scheme, host, localhost/private IP, `file:`, `data:` or `javascript:`. The manual Playwright process is headless and isolated, and MCP is disabled by default, which lowers current exposure but does not create an SSRF/navigation boundary.

Classification: `P2` under the current disabled/default-empty deployment. If browser MCP becomes production-enabled against untrusted prompts or privileged networks, this threat model must be revisited before enablement. The accurate claim is “non-mutating web observation tools”, not absolute browser read-only behavior.

## 11. Prompt Injection Boundary

MCP/browser output enters the Agent as ordinary `ToolResponse` evidence. It is not concatenated into SYSTEM or developer instructions, so external page text is not promoted to a higher message role. Normal indirect prompt-injection exposure remains because the model reads untrusted tool data; exact authorization and non-mutating tool scope contain possible actions.

Verdict: no role-escalation issue found. Maintaining an explicit allow-list is still necessary because content trust and tool authorization are separate controls.

## 12. Canonical Persistence / Context

```text
canonical result:
Full MCP Tool result returned by adapter and persisted in the normal protocol batch.

projection:
Shared ToolResultGuard creates a bounded model-facing view after persistence.

legacy truncation residue:
NONE on Agent MCP Tool path.
```

`McpFakeEndToEndIntegrationTest` verifies a 9000+ character MCP canonical result remains complete in the terminal response/persistence path while the model view is bounded to 8000 characters. `ToolProtocolPersistenceTransactionIntegrationTest` separately verifies the persisted MCP response equals the full canonical content and retains its tail marker/fingerprint.

MCP resource/prompt access services apply their configured `maxResultLength` before returning content. Those are explicit resource/prompt access boundaries, not the Agent Tool canonical persistence path. No evidence shows projected model view overwriting persisted MCP Tool canonical data.

Verdict：

```text
PASS
```

## 13. Audit / Logging

```text
ToolCallLog:
Unified taskId/stepId/toolCallId/status/latency logging is present.

MCP Audit:
Independent traceId/serverName/serverType/toolName/risk/status/latency logging is present.

shared identity:
NO direct shared taskId/toolCallId; correlation is incomplete.
```

A high-confidence log-content issue exists. `Slf4jMcpToolAuditLogger` writes up to 2000 characters of arguments and result; resource/prompt audit loggers write up to 800 characters of remote content; adapter/registry warnings include throwable stack traces; generic `JChatMind` logs full tool arguments and up to 4000 characters of model-facing result. A focused synthetic failure containing sensitive sentinel text printed that text through discovery/invocation exception stack traces even though the Agent-facing error was sanitized.

No committed credentials were found in `application-mcp.yaml`; examples use environment variables and MCP is disabled by default. The issue is runtime content/exception disclosure, not a committed-secret finding.

Verdict for correlation: `ISSUE` (P2). Verdict for content-safe logging: `ISSUE` (P1).

## 14. Transport

```text
STDIO:
IMPLEMENTED_AND_TESTED (manual environment-gated real integrations)

SSE:
NOT PROJECT-SUPPORTED/VALIDATED

Streamable HTTP:
NOT PROJECT-SUPPORTED/VALIDATED
```

Project-validated transport: STDIO only. Spring AI/MCP dependency capabilities are not counted as JChatMind implementation evidence. Manual tests explicitly close clients; no project-owned automatic restart policy for crashed STDIO servers was found, but there is also no evidence of leaked child processes or cross-task corruption.

Verdict：

```text
PASS
```

## 15. Tests

Focused local/mock test result:

```text
Tests run: 36
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
```

Covered classes:

```text
ExternalMcpRegistryAndPolicyTest
McpResourcePromptAccessTest
McpToolCallbackAdapterTest
McpClientIntegrationConfigTest
McpFakeEndToEndIntegrationTest
McpLocalToolRegistryIsolationTest
JChatMindFactoryMcpToolIntegrationTest
ToolExecutionServiceImplTest
```

Covered boundaries: disabled-by-default config, discovery isolation, exact allow-list, risk policy, callback success/failure, preflight, local-tool isolation, canonical/model-view split, ToolCallLog and fake end-to-end failure lineage. No real MCP server/network was invoked in this audit.

Existing environment-gated manual tests cover STDIO Context7, Playwright and GitHub read-only profiles, but were not rerun. Existing `ToolCallBatchExecutorTest` covers MCP typed failure, timeout/cancellation, duplicate detection, canonical persistence projection and protocol-safe terminal response.

Highest-value missing test: a log-capture regression test with sensitive sentinels asserting that MCP arguments, results, remote resource/prompt bodies and exception messages never appear in ordinary logs. Secondary gaps are explicit discovery/connect timeout ownership and shared audit identity.

## 16. Findings

| ID | Area | Severity | Evidence | User Impact | Root Cause | Recommended Action |
| --- | --- | --- | --- | --- | --- | --- |
| P1-1 | MCP logging/secrets | P1 | `Slf4jMcp*AuditLogger`, `McpToolCallbackAdapter`, `ExternalMcpToolRegistry`, `JChatMind`; sensitive sentinel reproduced in focused test output | Remote content, arguments or exception details can enter retained logs | Audit and diagnostic paths log body previews and throwable causes instead of metadata only | One targeted metadata-only logging/redaction fix plus sentinel log-capture tests |
| P2-1 | Audit correlation | P2 | Adapter creates independent UUID; MCP audit has no taskId/toolCallId shared with ToolCallLog | Cross-layer incident attribution requires timestamp/name matching | MCP audit API is detached from Agent execution identity | Add shared ID only when auditability work is scheduled |
| P2-2 | Browser URL boundary | P2 | No project URL scheme/host/private-network validation for `browser_navigate` | Opt-in browser could reach undesired endpoints | Authorization is tool-name/risk based, not argument/network policy based | Add URL policy before production browser enablement |
| P2-3 | Discovery repetition | P2 | `toolCallbacks`, `exposedToolNames`, and preflight registry lookup rediscover; no cache | Extra startup/request latency and repeated failure logs | Registry builds registrations on every accessor | Consider bounded/cache-on-build discovery snapshot only with real exposure evidence |
| P2-4 | Discovery/connect timeout | P2 | No timeout fields in `McpClientProperties`; only manual clients set 45-60 seconds | Bootstrap/discovery latency ownership is unclear | Relies on supplied Spring AI client defaults/config | Define and test timeout at enablement boundary if MCP is production-enabled |
| C-1 | Context residue | NO_ISSUE | No production Legacy transcript/recovery hits; canonical-first persistence and single Final path | None | Frozen architecture is internally consistent | Keep frozen |
| M-1 | Discovery isolation | NO_ISSUE | Per-server exception containment and local registry isolation tests | Local tools remain usable when MCP fails | Fault containment is implemented | Preserve behavior |
| M-2 | Authorization | NO_ISSUE | Exact `containsKey`; missing risk = DANGEROUS; explicit auto-invoke required | Newly discovered tools are not automatically exposed | Discovery and authorization are separated | Preserve behavior |
| M-3 | Failure status | NO_ISSUE | Typed exception and fake end-to-end failure tests | Planner/telemetry do not treat failure as success | Adapter propagates failure structurally | Preserve behavior |
| M-4 | Canonical persistence | NO_ISSUE | Full MCP body persisted before 8000-char model projection | Context bounding does not overwrite evidence | Shared Tool lifecycle is reused | Preserve behavior |
| M-5 | Transport claims | NO_ISSUE | Only STDIO has project manual integration evidence | Prevents capability overstatement | Report distinguishes SDK from project support | Claim STDIO only |

## 17. P0

```text
count:
0
```

No correctness blocker, authorization bypass, protocol corruption, data corruption or proven credential disclosure was found.

## 18. P1

```text
count:
1
```

### P1-1 MCP/external content and exception details can leak into logs

Root cause: body-preview audit design and throwable logging at the remote boundary. Specific symbols are `Slf4jMcpToolAuditLogger.start/success/denied`, `Slf4jMcpResourceAuditLogger.success`, `Slf4jMcpPromptAuditLogger.success`, `McpToolCallbackAdapter.ExternalMcpToolCallback.call`, `ExternalMcpToolRegistry.exposedTools`, `JChatMind.logToolCalls`, and `JChatMind` tool-result logging.

Impact: MCP arguments may contain tokens/URLs/paths; remote results and prompt/resource bodies may contain private content; exception messages/stacks can echo credentials or command arguments into centralized logs. The Agent-facing exception is sanitized, but ordinary logs are not consistently content-safe.

Minimal fix: keep metadata-only structured logs (`task/tool identity`, server/tool, status, latency, size/count, blank/truncated flags); do not log raw arguments, results, resource/prompt bodies or remote throwable messages/stacks. Keep internal cause association without serializing its message to ordinary logs. Review the generic `JChatMind` Tool logging because MCP traverses that shared path.

Test: log-capture tests inject unique sensitive sentinels into arguments, successful content, resource/prompt bodies, discovery exception and invocation exception, then assert absence across MCP and Agent logger output while status/code/latency remain present.

Risk of fix: low-to-moderate diagnostic detail loss; mitigate with metadata, hashes/counts, typed error codes and controlled debug-only local diagnostics rather than production body logging.

## 19. P2

| ID | Item | Context Lifecycle related |
| --- | --- | --- |
| P2-1 | MCP audit and ToolCallLog lack a direct shared `taskId/toolCallId` | NO |
| P2-2 | Browser navigate has no project-owned URL/network boundary | NO |
| P2-3 | MCP tool discovery is repeated and uncached | NO |
| P2-4 | Discovery/connect timeout ownership is not explicit in JChatMind config | NO |

These are backlog items under the current default-disabled, empty-server configuration. Do not introduce new managers or broad abstractions for them without production exposure evidence.

## 20. Required Verdicts

```text
Context residual architecture:
PASS

MCP discovery failure isolation:
PASS

MCP failure status consistency:
PASS

MCP allow-list:
PASS

MCP timeout:
UNKNOWN

MCP audit correlation:
ISSUE

MCP canonical persistence:
PASS

MCP transport claim accuracy:
PASS
```

## 21. Interview-value Findings

| Design point | Relevance | Evidence-safe interpretation |
| --- | --- | --- |
| Discovery != Authorization | INTERVIEW_HIGH_VALUE | Dynamic `listTools` is filtered by exact, explicit, default-deny allow-list and risk policy |
| Failure isolation | INTERVIEW_HIGH_VALUE | One MCP server discovery failure exposes zero tools without disabling local or healthy MCP tools |
| Unified Tool abstraction | INTERVIEW_HIGH_VALUE | MCP callbacks reuse preflight, duplicate detection, timeout, step budget, ToolCallLog, persistence, projection and SSE |
| Failure semantics | INTERVIEW_HIGH_VALUE | Exceptions remain typed failures across MCP audit and Agent runtime, not success strings |
| Canonical/model-view split | INTERVIEW_HIGH_VALUE | Full MCP result persists before bounded model projection |
| Transport choice | INTERVIEW_MEDIUM_VALUE | Only STDIO is project-validated; HTTP transports are not claimed |
| Audit correlation gap | INTERVIEW_MEDIUM_VALUE | Unified status exists, but MCP-specific audit lacks direct Agent call identity |
| Logging P1 | INTERVIEW_HIGH_VALUE | Security audit found a concrete disclosure path and scoped it to a small boundary |

## 22. Tencent Interview MCP Summary

项目通过 Spring AI 的 `McpSyncClient` 接外部 STDIO MCP Server，先 `listTools` 做动态发现，但发现不等于授权：只有配置中精确 allow-list 命中、显式标成 `READ_ONLY/NETWORK_READ` 且允许 auto-invoke 的 tool 才会转成 Agent callback，新增 tool 默认不会暴露。MCP callback 进入后和本地 Tool 共用 preflight、step/duplicate/timeout、ToolCallLog、SSE、canonical persistence 和 Context projection；Server discovery 失败按 server 隔离，本地工具和其他健康 MCP server 仍可用；调用异常以 typed failure 贯穿 MCP audit 和 Agent trace。当前项目真实验证的是 STDIO，SSE/Streamable HTTP 不做能力声明。最终审计还发现日志会记录外部正文和异常 cause，这是一个边界小、值得单独修的 P1。

## 23. Final Project Decision

```text
PROJECT_FINAL_AUDIT_REQUIRES_TARGETED_P1_FIXES
```

理由：P0 为 0；Context residual audit 已通过且保持 freeze。存在一个 code-path-provable、影响明确、修复边界小的 P1 日志披露问题，因此尚不建议直接 `FREEZE PROJECT`。P2 不构成当前阻塞。

## 24. Recommended Next Step

只执行一个 targeted step：将 MCP 及共享 Agent Tool 普通日志改为 metadata-only/content-safe logging，并增加 sensitive-sentinel log-capture tests。完成且验证后重新做最小范围 P1 closure review；不要重新打开 Context Lifecycle，也不要顺带处理 P2 backlog。

## 25. Production Changes

```text
production:
0

tests:
0

benchmark semantics:
0
```

## 26. Git Status

```text
HEAD:
07ac3db444042fec4c647100b1763eecc498ce3c

working tree:
CLEAN
```
