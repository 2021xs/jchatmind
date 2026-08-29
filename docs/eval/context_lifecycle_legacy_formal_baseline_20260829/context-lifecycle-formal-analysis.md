# JChatMind Context Lifecycle Formal Legacy Baseline Analysis

> 本文档只读消费 runner 生成的 raw JSON，沿用既有 metric definitions；不重算或替换 raw/case CSV 中的单次指标。

## Formal Run Validation

| Field | Value |
| --- | --- |
| run id | context-lifecycle-2026-08-29T12-28-15.129505200+08-00-79805e7c |
| architecture | LEGACY |
| git commit | `6f5502df216ce9c74a5bee08797b1d795868e165` |
| suite / repeats | context-lifecycle-v1 / 3 |
| executions / unique cases | 72 / 24 |
| success | 72/72 |
| execution failures | 0 |

每个 case 已验证恰好包含 repeat 1/2/3。每个 case 只有 3 个样本，因此不报告 per-case P95；n=3 的 nearest-rank P95 等于 max，不能解释为稳定尾延迟。下面的 P95 只针对全部 72 个 execution，采用既有 nearest-rank 定义。

## Overall Distribution (72 executions)

| Metric | Min | Median | P95 | Max | Mean |
| --- | ---: | ---: | ---: | ---: | ---: |
| task latency ms | 4692 | 78343 | 258841 | 277683 | 94920.72 |
| think latency ms | 2014 | 25948 | 74818 | 86731 | 30718.74 |
| tool latency ms | 0 | 3407 | 13930 | 15157 | 4648.76 |
| compression latency ms | 0 | 8032 | 87407 | 139040 | 22170.61 |
| final latency ms | 2309 | 27912 | 95443 | 138518 | 37037.01 |
| max working context tokens | 1373 | 6443 | 11589 | 12672 | 6110.92 |
| final context tokens | 1037 | 5653 | 16802 | 21544 | 7168.11 |
| TaskToolTranscript tokens | 0 | 4833 | 16129 | 20845 | 5999.14 |
| compression count | 0 | 1 | 5 | 8 | 1.42 |
| CriticalFactRecall | 0 | 1 | 1 | 1 | 0.81 |
| ExactValueAccuracy | 0 | 1 | 1 | 1 | 0.94 |

## Repeat Distribution

| Repeat | Success | Critical recall mean | Exact accuracy mean | Task latency median ms | Final context median | Transcript median | Compression events |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 24/24 | 0.7465 | 0.9583 | 75723 | 5543 | 4479 | 25 |
| 2 | 24/24 | 0.8681 | 0.9583 | 51404 | 5162 | 4833 | 43 |
| 3 | 24/24 | 0.8090 | 0.9167 | 82691 | 5799 | 4989 | 34 |

## Token Availability

| Phase | Calls | Actual input available | Actual input sum | Actual output available | Actual output sum | Estimated input sum | Estimated output sum |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| THINK | 300 | 300/300 | 1885485 | 300/300 | 91747 | 1599688 | 28682 |
| SELECTOR | 278 | 278/278 | 1003909 | 278/278 | 5618 | 1253586 | 4949 |
| COMPRESSION | 102 | 0/102 | 0 | 0/102 | 0 | 101265 | 30927 |
| FINAL | 72 | 0/72 | 0 | 0/72 | 0 | 516104 | 75217 |

Task-level actual total available: 0/72; incomplete provider usage is not promoted to an actual task total.

## Tool / Compression / Protocol Totals

- Tool calls: 292 (databaseQuery=3, knowledgeQuery=1, mcp_github_mcp_server_search_repositories=1, searchProjectCode=282, terminate=5)
- Tool result produced / injected estimated tokens: 397489 / 1256325
- Largest single Tool Result: 2413
- Compression tasks / events / removed tokens / latency ms: 39 / 102 / 153394 / 1596284
- Transcript total / attributable merge contribution: 431938 / 167357
- Cross-task raw Tool protocol / completed user-final / session summary tokens: 0 / 18991 / 3109
- Forbidden claims / orphan protocol / protocol validation failure: 0 / 0 / 0
- Context overflow / compression failure / tool execution failure: 0 / 0 / 0

## Suite Tool-contract Observations

以下为 suite `requiredTools` / `allowedTools` 与真实 Tool Call 的只读对比，不新增或修改 Benchmark anomaly metric：

- a_small_db_repo repeat 1: missing=[databaseQuery], unexpected=[mcp_github_mcp_server_search_repositories,searchProjectCode], actual=[mcp_github_mcp_server_search_repositories,searchProjectCode]
- a_small_db_repo repeat 2: missing=[databaseQuery], unexpected=[searchProjectCode,searchProjectCode], actual=[searchProjectCode,searchProjectCode]
- a_small_db_repo repeat 3: missing=[databaseQuery], unexpected=[searchProjectCode], actual=[searchProjectCode]
- c_pressure_cache_auth_rate repeat 3: missing=[], unexpected=[knowledgeQuery], actual=[searchProjectCode,searchProjectCode,searchProjectCode,searchProjectCode,searchProjectCode,searchProjectCode,searchProjectCode,knowledgeQuery]

## Per-case Three-repeat Distribution

| Case | Success | Critical mean [min,max] | Exact mean [min,max] | Max ctx median [min,max] | Final ctx median [min,max] | Transcript median [min,max] | Compression median [min,max] | Task ms median [min,max] | Protocol failures |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| a_no_tool_hashmap | 3/3 | 1 [1,1] | 1 [1,1] | 1373 [1373,1373] | 1037 [1037,1037] | 0 [0,0] | 0 [0,0] | 5108 [4692,8564] | 0 |
| a_small_code_api | 3/3 | 1 [1,1] | 1 [1,1] | 2978 [2585,3006] | 2354 [1996,2450] | 1400 [1014,1402] | 0 [0,0] | 14749 [12087,14901] | 0 |
| a_small_db_repo | 3/3 | 0.5000 [0.5000,0.5000] | 0 [0,0] | 3105 [3020,4558] | 2698 [2305,4879] | 1644 [1453,3154] | 0 [0,0] | 20176 [16895,39390] | 0 |
| b_async_order_flow | 3/3 | 1 [1,1] | 1 [1,1] | 8739 [7300,9892] | 7887 [6331,12797] | 6823 [5442,12104] | 0 [0,3] | 118913 [99777,168687] | 0 |
| b_batch_persistence | 3/3 | 0.8889 [0.6667,1] | 1 [1,1] | 6682 [6606,6752] | 6097 [5653,6127] | 4985 [4942,5445] | 1 [0,1] | 63286 [51404,82691] | 0 |
| b_duplicate_protection | 3/3 | 0.8889 [0.6667,1] | 1 [1,1] | 9662 [8071,11679] | 13527 [7459,14409] | 12590 [6314,13234] | 2 [0,2] | 130029 [75723,147212] | 0 |
| b_timeout_close_flow | 3/3 | 0.4444 [0.3333,0.6667] | 1 [1,1] | 8567 [6443,9277] | 8284 [5543,9736] | 7165 [4479,8998] | 0 [0,2] | 99040 [79320,144252] | 0 |
| b_two_level_cache | 3/3 | 0.8889 [0.6667,1] | 1 [1,1] | 7238 [5839,8140] | 11266 [6073,13345] | 10840 [5630,13315] | 5 [2,6] | 184658 [124164,211207] | 0 |
| c_pressure_cache_auth_rate | 3/3 | 0.5556 [0.3333,0.6667] | 1 [1,1] | 8545 [6808,9765] | 7462 [5162,10394] | 6435 [4833,9740] | 1 [0,2] | 115140 [102742,132154] | 0 |
| c_pressure_mq_reliability | 3/3 | 0.4444 [0.3333,0.6667] | 1 [1,1] | 8272 [7670,9383] | 11780 [10493,13999] | 11538 [10012,13965] | 4 [3,6] | 239710 [222189,270503] | 0 |
| c_pressure_seckill_architecture | 3/3 | 0.4167 [0.2500,0.5000] | 1 [1,1] | 11589 [9965,12672] | 21460 [12675,21544] | 20756 [11651,20845] | 3 [3,4] | 203173 [188402,277683] | 0 |
| d1_heavy_seckill_task | 3/3 | 1 [1,1] | 1 [1,1] | 10588 [10070,10601] | 14362 [11742,16802] | 13858 [10852,16129] | 5 [2,5] | 221421 [135527,258841] | 0 |
| d1_unrelated_hashmap_task | 3/3 | 1 [1,1] | 1 [1,1] | 3714 [3626,3925] | 3378 [3290,3589] | 0 [0,0] | 1 [1,1] | 26921 [24415,30802] | 0 |
| d2_heavy_cache_task | 3/3 | 0.6667 [0,1] | 1 [1,1] | 6863 [6809,9393] | 8481 [5693,9936] | 8018 [5081,9364] | 1 [0,4] | 112805 [105834,183485] | 0 |
| d2_unrelated_http_task | 3/3 | 1 [1,1] | 1 [1,1] | 3410 [3137,3609] | 3074 [2801,3273] | 0 [0,0] | 1 [1,1] | 22504 [18283,24119] | 0 |
| d3_heavy_timeout_task | 3/3 | 0.6667 [0,1] | 1 [1,1] | 6454 [4937,6890] | 5494 [4110,12932] | 4425 [3090,12727] | 0 [0,8] | 147351 [59699,276219] | 0 |
| d3_unrelated_list_task | 3/3 | 1 [1,1] | 1 [1,1] | 4684 [3409,4931] | 4348 [3073,4595] | 0 [0,0] | 1 [0,1] | 17668 [7077,30849] | 0 |
| e_exact_login_ttl | 3/3 | 1 [1,1] | 0.6667 [0,1] | 3464 [2643,3935] | 2929 [2219,3452] | 1846 [1150,2399] | 0 [0,0] | 20050 [13495,23535] | 0 |
| e_exact_lua_return_codes | 3/3 | 0.6667 [0.5000,0.7500] | 1 [1,1] | 5321 [3771,5390] | 5799 [3420,5816] | 4728 [2337,4989] | 2 [0,3] | 78343 [34655,85761] | 0 |
| e_exact_mq_constants | 3/3 | 1 [1,1] | 1 [1,1] | 2816 [2192,2817] | 2313 [1806,2313] | 1285 [751,1285] | 0 [0,0] | 13140 [12018,13592] | 0 |
| f_long_planning_data_structures | 3/3 | 0.9167 [0.7500,1] | 1 [1,1] | 9858 [9649,10849] | 13335 [13099,18217] | 12610 [12327,17348] | 3 [2,3] | 204724 [181636,234987] | 0 |
| f_long_planning_reliability_compare | 3/3 | 0.4444 [0.3333,0.6667] | 1 [1,1] | 10729 [9739,11795] | 15196 [12851,16184] | 14400 [12601,15585] | 2 [1,3] | 165621 [118754,194623] | 0 |
| g_invalid_repo_failure | 3/3 | 1 [1,1] | 1 [1,1] | 1485 [1485,1547] | 1129 [1129,1169] | 103 [73,134] | 0 [0,0] | 13218 [10879,15562] | 0 |
| g_safe_database_failure | 3/3 | 1 [1,1] | 1 [1,1] | 1508 [1508,1508] | 1153 [1153,1153] | 98 [98,99] | 0 [0,0] | 9513 [7969,9776] | 0 |
