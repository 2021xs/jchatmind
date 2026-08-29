# Agent Context Lifecycle Benchmark

## 目标与边界

本 Benchmark 用同一套 runner、suite 和 metrics definition 比较：

```text
LEGACY vs TASK_AWARE
```

本轮只测量当前真实架构。Instrumentation 是 fail-safe observation，不改变 message 顺序、Tool Result、truncate、compression 触发、TaskToolTranscript 或 Final synthesis 输入。

## 当前可观测链路

- `JChatMind`：THINK、TOOL_CALL、CONTEXT_COMPRESSION、FINAL_SYNTHESIS、FINISH。
- `ProtocolAwareMessageWindowChatMemory.logicalMessageGroups()`：生产协议分组和 orphan 检查的唯一规则来源。
- `ConversationContextCompressorImpl`：compression prompt、前后 token、summary、latency、failure。
- `ToolCallBatchExecutor` / `tool_call_log`：Tool Result 和持久化工具调用。
- `FinalSynthesisRequestFactory` / `FinalContextCompiler`：Final request 与 TaskToolTranscript 投影。
- `CodeLlmEvidenceSelector`：独立 SELECTOR usage 和 latency。

THINK 与 SELECTOR 当前可以取得 provider usage。流式 FINAL 和 `ConversationSummaryClient` 当前不能稳定取得 provider usage，因此必须记录为 unavailable；统一估算值不能回填 actual 字段。

## Suite

Suite 文件：`src/test/resources/benchmark/context_lifecycle_benchmark_suite.json`

- version：`context-lifecycle-v1`
- architecture：`LEGACY`
- 26 个定义：24 active，2 个 oversized fixture-only。
- active 分类：A=3、B=5、C=3、D=6、E=3、F=2、G=2。
- Code RAG repo：FlashDeal，repoId `bf4ef891-330b-4ce8-9002-ba4c43ffe210`。
- snapshot：167 files、642 chunks、642 embeddings，并校验 file/chunk manifest digest。

## 核心指标定义

### Token

- actual：只来自 provider usage。
- estimated：`ESTIMATED_MESSAGE_CHARS_V1`，统计 message text、Assistant tool-call protocol 字段和 ToolResponse protocol 字段，以 benchmark profile 的 `charsPerToken` 换算。
- 聚合 actual：只有聚合范围内每次 model call 都有 usage 才输出数值；部分可用时输出 `null`，source 标记 `UNAVAILABLE_INCOMPLETE_PROVIDER_USAGE_n_OF_m`。
- task total 包含 THINK、SELECTOR、COMPRESSION、FINAL；各 phase 同时独立保存。

### Context attribution

每个真正发送给模型的 request messages 使用同一 estimator，基于 message role、tool call id、持久化 message metadata 的真实 `taskId` 归因：

```text
CURRENT_USER
CURRENT_TASK_PLANNING
CURRENT_TASK_TOOL
COMPLETED_TASK_USER_FINAL
COMPLETED_TASK_TOOL
SESSION_SUMMARY
UNKNOWN
```

- `maxWorkingContextTokensObserved`：所有 THINK request 的 estimated token 最大值。
- `contextTokensBeforeEachThink`：每次 THINK request 的 estimated token。
- `finalContextTokens`：实际发送的 Final request messages estimated token。
- `crossTaskToolResultTokens`：Task2 首轮 THINK 中归因到 completed-task tool protocol 的 estimated token。
- UNKNOWN 主要包含固定 system prompt；不将无法证明来源的内容强行归类。

### Tool Result

- produced：工具执行产生的 raw result estimated token。
- injected：所有 THINK request 中 current/completed task tool protocol estimated token 的累计注入量，加可归因的 Final transcript merge contribution。
- largest：单次 raw result estimated token 最大值。

### TaskToolTranscript

- entry count / estimated tokens：Final projection 中 current-task transcript 的条目数和 estimated token。
- before merge：用同一个 `FinalSynthesisRequestFactory` 从 execution transcript 重建不附加 transcript 的 Final request，再经同一 `FinalContextCompiler` 估算。
- after merge：真实 Final request estimated token。
- contribution：`max(0, after - before)`；如果 Legacy execution transcript 已含同一 protocol，去重后 contribution 可以为 0。

### Compression

- `summaryDepth`：同一 task 内按发生顺序累计的 compression generation。
- before/after：生产 `TokenCounter` 在 compression observation 中给出的值及来源。
- compression input/output：prompt/summary 的统一估算；当前 provider actual unavailable。
- ratio：`tokensAfterCompression / tokensBeforeCompression`。

### Protocol

使用生产 `logicalMessageGroups()` 检查 AssistantMessage(tool_calls) 与 ToolResponseMessage 是否成组完整。指标统计每个实际模型 request 和 Final 投影中观察到的 orphan / validation failure；不另造 parser。

### Correctness

- `CriticalFactRecall`：matched critical facts / critical facts。
- `ExactValueAccuracy`：边界感知 exact-value match。
- `ForbiddenClaimCount`：回答中出现的 forbidden deterministic phrase 数。
- expected refs 作为 supporting fact 单独保存。
- 当前不使用 LLM judge，`judgeStatus=NOT_USED_DETERMINISTIC_ONLY`。

## Benchmark profile

`src/test/resources/application-benchmark.yaml` 与生产默认配置分离：

- primary model：`gpt-5.5`
- temperature：来自真实 Agent 配置，本次为 `0.7`
- seed：unavailable
- max steps：20
- rawTopK / finalTopK：20 / 5
- selector：enabled，独立 model/config 写入 metadata
- max context / max single Tool Result：3500 / 1500
- keep recent rounds / max history messages：2 / 8

未来 A/B 必须复用完全相同的 profile 和 suite。不能为了改善结果修改这些值。

## 运行

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"

# 单 case smoke
.\mvnw.cmd "-Dtest=ContextLifecycleBenchmarkTest" `
  "-Dcontext.benchmark.enabled=true" `
  "-Dcontext.benchmark.caseId=a_no_tool_hashmap" `
  "-Dcontext.benchmark.repeats=1" test

# 完整 smoke：24 active cases x 1
.\mvnw.cmd "-Dtest=ContextLifecycleBenchmarkTest" `
  "-Dcontext.benchmark.enabled=true" `
  "-Dcontext.benchmark.repeats=1" test

# Formal：24 active cases x 3
.\mvnw.cmd "-Dtest=ContextLifecycleBenchmarkTest" `
  "-Dcontext.benchmark.enabled=true" `
  "-Dcontext.benchmark.repeats=3" test
```

可选过滤：`context.benchmark.caseId`、`context.benchmark.category`、`context.benchmark.limit`。Fixture-only case 仅在显式 `context.benchmark.includeFixtures=true` 时启用。

## 2026-08-29 Legacy Smoke Baseline

- git commit：`6f5502df216ce9c74a5bee08797b1d795868e165`
- main working tree at preflight：CLEAN
- run id：`context-lifecycle-2026-08-29T07-59-51.088690200+08-00-5a5dde7b`
- recommended repeats：3
- actual repeats：1
- result：24/24 task success，Maven BUILD SUCCESS，总耗时 39:44。
- 未执行 Formal 3 repeats：按 smoke 推算约 2 小时、约 639 次模型调用，属于明显额外 API 成本；没有静默降采样。

固化产物：`docs/eval/context_lifecycle_legacy_baseline_20260829/`。

该数据是首份真实 Smoke Baseline。它足以验证 runner、指标和 Legacy 行为，但单次重复不足以作为最终统计稳定的 Formal A/B baseline。
