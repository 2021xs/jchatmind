# Context Lifecycle Formal Legacy Baseline Preflight

## Identity

```text
run id:                 context-lifecycle-2026-08-29T12-28-15.129505200+08-00-79805e7c
architecture:           LEGACY
git commit:             6f5502df216ce9c74a5bee08797b1d795868e165
working tree:           CLEAN
suite version:          context-lifecycle-v1
active cases:           24
repeats:                3
```

## Suite and Profile

```text
suite Git blob:         82f65923f56fd9bbb19d5e68db16f874cf58c11a
suite normalized SHA:   0a2e5d3fc464f4d0ef59a07f76bc60927dbf19440d9bb54f5b1e55c1cd93ba1d
profile Git blob:       2562cc2c295876c75a962cb362776749a2d1ce95
profile normalized SHA: 047edf0b4bd440675843207090413da8da3213fab5fbe9c5bcf0f653e9729797
```

LF-normalized SHA 用于排除 Windows checkout 的 CRLF 物理换行差异；Git blob 和 normalized SHA 均与 Smoke execution commit 一致。

## Model and Agent Config

```text
primary model:          gpt-5.5
temperature:            0.7
topP:                   1.0
messageLength:          10
seed:                   unavailable
selector:               enabled / DEEPSEEK_HTTP / deepseek-chat
selector max chars:     600
selector max selected:  5
selector timeout:       30000 ms
local config SHA-256:   e9b11eb07ab3cd6100b839355682e006b1f5c95c2ff4acf3221bab31469a01a5
```

被 Git 忽略的 `application-local.yaml` 从 Smoke 工作目录按字节复制到 detached Formal worktree；只记录 hash，不固化或输出凭据。该文件最后修改时间为 2026-08-24，早于 Smoke 和 Formal。

## Repository Snapshot

```text
repo:                   FlashDeal
repoId:                 bf4ef891-330b-4ce8-9002-ba4c43ffe210
external HEAD:          52d0baf1de5fc273efdb47f070b1187fbdafd2cf
files/chunks/embeddings:167 / 642 / 642
file manifest MD5:      7baa292e917e30d3c81384e2b5cf76ca
chunk manifest MD5:     988c5abbe64e95bb4bf1ee1daf169a7c
embedding MD5:          f0c30bad38b9a0cc9ad918968177335c
snapshot dump SHA-256:  d6b241718103546ee1e706b846634087c02dda7c093a19eda8515196410f6798
restore validation:     PASS
```

可恢复快照见 `../context_lifecycle_flashdeal_snapshot_20260829/`。Formal 执行结束后再次校验 counts 和三个 manifest digest，结果不变。

## Benchmark Config

```text
max context/tool result:3500 / 1500
keep rounds/history:    2 / 8
chars per token:        3
retrieval raw/final K:  20 / 5
selector enabled:       true
max steps:              20
token measurement:      provider actual 与 ESTIMATED_MESSAGE_CHARS_V1 分开
correctness:            deterministic definitions；无 LLM judge
```

## Execution

```powershell
.\mvnw.cmd "-Dtest=ContextLifecycleBenchmarkTest" `
  "-Dcontext.benchmark.enabled=true" `
  "-Dcontext.benchmark.repeats=3" `
  "-Dlogging.level.org.springframework=INFO" test
```

```text
started:                2026-08-29T12:28:15.1295052+08:00
ended:                  2026-08-29T14:22:31.38666+08:00
Maven result:           BUILD SUCCESS
Maven exit code:        0
executions:             72
runner failures:        0
```
