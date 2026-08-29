# JChatMind Context Lifecycle Legacy Baseline Report

> 本报告由固定 Benchmark Runner 从真实执行结果生成，不包含手工填写的指标。

## Environment

| Field | Value |
| --- | --- |
| benchmark run | context-lifecycle-2026-08-29T12-28-15.129505200+08-00-79805e7c |
| architecture | LEGACY |
| git commit | `6f5502df216ce9c74a5bee08797b1d795868e165` |
| working tree | CLEAN |
| suite version | context-lifecycle-v1 |
| model | gpt-5.5 |
| temperature / seed | 0.7 / unavailable |
| repo | FlashDeal / `bf4ef891-330b-4ce8-9002-ba4c43ffe210` |
| repo snapshot | `7baa292e917e30d3c81384e2b5cf76ca` / `988c5abbe64e95bb4bf1ee1daf169a7c` |
| repo external HEAD / tree | `52d0baf1de5fc273efdb47f070b1187fbdafd2cf` / M src/main/java/com/flashdeal/config/RabbitMqConfig.java  M src/main/resources/application-dev.yaml  M src/main/resources/application.yaml  M src/test/java/com/flashdeal/config/RabbitMqConfigTest.java ?? src/test/java/com/flashdeal/config/SchedulingConfigurationTest.java |
| started / ended | 2026-08-29T12:28:15.129505200+08:00 / 2026-08-29T14:22:31.386660+08:00 |
| repeats | recommended 3, actual 3 |
| token measurement | Provider usage when exposed; ESTIMATED_MESSAGE_CHARS_V1 otherwise, stored separately |

## Suite

总执行数：72。

| Category | Executions |
| --- | ---: |
| A_SMALL_NO_COMPRESSION | 9 |
| B_CURRENT_TASK_MULTI_TOOL_RAG | 15 |
| C_CONTEXT_PRESSURE | 9 |
| D_CROSS_TASK_POLLUTION | 18 |
| E_EXACT_DETAIL | 9 |
| F_LONG_PLANNING | 6 |
| G_FAILURE_PROTOCOL | 6 |

## Correctness

| Metric | Value |
| --- | ---: |
| task success | 72/72 |
| critical fact recall mean | 0.8079 |
| exact value accuracy mean | 0.9444 |
| forbidden claim count | 0 |
| judge | NOT_USED_DETERMINISTIC_ONLY |

## Context

| Metric | Median | P95 | Max |
| --- | ---: | ---: | ---: |
| working context tokens | 6443 | 11589 | 12672 |
| final context tokens | 5653 | 16802 | 21544 |
| cross-task tool tokens | 0 | 0 | 0 |
| TaskToolTranscript estimated tokens | 4833 | 16129 | 20845 |

P95 使用 nearest-rank；建议结合三次重复的分布解释。

## Tool

| Metric | Total / Max |
| --- | ---: |
| tool calls | 292 |
| produced estimated tokens | 397489 |
| injected estimated tokens | 1256325 |
| largest single result | 2413 |

## Compression

| Metric | Value |
| --- | ---: |
| tasks with compression | 39/72 |
| compression event count | 102 |
| max events per task | 8 |
| max summary depth | 8 |
| estimated tokens removed | 153394 |
| latency ms | 1596284 |

## Latency

| Phase | Median ms | P95 ms | Max ms |
| --- | ---: | ---: | ---: |
| task | 78343 | 258841 | 277683 |
| think | 25948 | 74818 | 86731 |
| tool | 3407 | 13930 | 15157 |
| compression | 8032 | 87407 | 139040 |
| final | 27912 | 95443 | 138518 |

P95 使用 nearest-rank；建议结合三次重复的分布解释。

## Stability

| Metric | Count |
| --- | ---: |
| recorded failures | 0 |
| orphan tool protocol | 0 |
| protocol validation failure | 0 |
| context overflow | 0 |
| compression failure | 0 |
| tool execution failure | 0 |

## Legacy Architecture Observations

- Final request 中 TaskToolTranscript 的估算 token 合计：431938。
- Final request 因 transcript merge 增加的可归因估算 token 合计：167357。
- Task2 首轮 THINK 中归因到 completed-task tool protocol 的估算 token 合计：0。
- 以上 token 均使用统一 message estimator；provider usage 仅保存在 raw JSON 的 actual 字段，不与估算值混算。

## Measurement Limitations

- Compression 调用当前 provider usage 不可取得，其 input/output token 仅为估算。
- Working context、来源归因、Tool Result 与 TaskToolTranscript 均为 `ESTIMATED_MESSAGE_CHARS_V1`。
- `summaryDepth` 定义为同一 task 内按发生顺序累计的 compression generation。
- Correctness 使用确定性 structured fact coverage，不使用 LLM judge；语义同义词覆盖受 case 词表限制。
- 外部 repo 工作区状态被记录但 snapshot 由数据库 file/chunk manifests 冻结。

## Anomalies

详见 `context-lifecycle-anomalies.csv`；空数据行表示本次未发现异常。
