# JChatMind Context Lifecycle Legacy Baseline Report

> 本报告由固定 Benchmark Runner 从真实执行结果生成，不包含手工填写的指标。

## Environment

| Field | Value |
| --- | --- |
| benchmark run | context-lifecycle-2026-08-29T07-59-51.088690200+08-00-5a5dde7b |
| architecture | LEGACY |
| git commit | `6f5502df216ce9c74a5bee08797b1d795868e165` |
| working tree | CLEAN |
| suite version | context-lifecycle-v1 |
| model | gpt-5.5 |
| temperature / seed | 0.7 / unavailable |
| repo | FlashDeal / `bf4ef891-330b-4ce8-9002-ba4c43ffe210` |
| repo snapshot | `7baa292e917e30d3c81384e2b5cf76ca` / `988c5abbe64e95bb4bf1ee1daf169a7c` |
| repo external HEAD / tree | `52d0baf1de5fc273efdb47f070b1187fbdafd2cf` / M src/main/java/com/flashdeal/config/RabbitMqConfig.java  M src/main/resources/application-dev.yaml  M src/main/resources/application.yaml  M src/test/java/com/flashdeal/config/RabbitMqConfigTest.java ?? src/test/java/com/flashdeal/config/SchedulingConfigurationTest.java |
| started / ended | 2026-08-29T07:59:51.088690200+08:00 / 2026-08-29T08:39:18.225473300+08:00 |
| repeats | recommended 3, actual 1 |
| token measurement | Provider usage when exposed; ESTIMATED_MESSAGE_CHARS_V1 otherwise, stored separately |

## Suite

总执行数：24。

| Category | Executions |
| --- | ---: |
| A_SMALL_NO_COMPRESSION | 3 |
| B_CURRENT_TASK_MULTI_TOOL_RAG | 5 |
| C_CONTEXT_PRESSURE | 3 |
| D_CROSS_TASK_POLLUTION | 6 |
| E_EXACT_DETAIL | 3 |
| F_LONG_PLANNING | 2 |
| G_FAILURE_PROTOCOL | 2 |

## Correctness

| Metric | Value |
| --- | ---: |
| task success | 24/24 |
| critical fact recall mean | 0.8299 |
| exact value accuracy mean | 0.9167 |
| forbidden claim count | 0 |
| judge | NOT_USED_DETERMINISTIC_ONLY |

## Context

| Metric | Median | P95 | Max |
| --- | ---: | ---: | ---: |
| working context tokens | 5306 | 9987 | 13091 |
| final context tokens | 4641 | 15880 | 21649 |
| cross-task tool tokens | 0 | 0 | 0 |
| TaskToolTranscript estimated tokens | 3542 | 16367 | 20779 |

P95 使用 nearest-rank；建议结合三次重复的分布解释。

## Tool

| Metric | Total / Max |
| --- | ---: |
| tool calls | 97 |
| produced estimated tokens | 122160 |
| injected estimated tokens | 370460 |
| largest single result | 2379 |

## Compression

| Metric | Value |
| --- | ---: |
| tasks with compression | 13/24 |
| compression event count | 39 |
| max events per task | 8 |
| max summary depth | 8 |
| estimated tokens removed | 43830 |
| latency ms | 629692 |

## Latency

| Phase | Median ms | P95 ms | Max ms |
| --- | ---: | ---: | ---: |
| task | 40502 | 256233 | 299714 |
| think | 18780 | 71996 | 90319 |
| tool | 3897 | 13015 | 14439 |
| compression | 10423 | 107779 | 136763 |
| final | 17996 | 89691 | 151570 |

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

- Final request 中 TaskToolTranscript 的估算 token 合计：135204。
- Final request 因 transcript merge 增加的可归因估算 token 合计：55081。
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
