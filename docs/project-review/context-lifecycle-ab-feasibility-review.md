# Step 19 Offline Same-Trace Context Lifecycle A/B Result

## 1. Baseline

```text
Current HEAD:
07ac3db444042fec4c647100b1763eecc498ce3c

Current working tree:
CLEAN

Legacy replay commit:
6f5502df216ce9c74a5bee08797b1d795868e165
```

`6f5502df...` is the execution commit recorded by the frozen Legacy formal preflight. The later
`5cb28b...` commit only freezes benchmark documentation and artifacts; its parent is `f654ab0...`
and it does not change `src/main` or `src/test` relative to the recorded execution implementation.

## 2. Replay Feasibility

```text
Legacy:
NOT_COMPARABLE

Current:
NOT_COMPARABLE

Formal A/B comparable:
NO

Gate result:
SAME_TRACE_REPLAY_NOT_COMPARABLE
```

The current raw artifact serializes model-call counters, token estimates, and the Final-boundary
managed Working Context, but it does not serialize each THINK call's `requestMessages` or
`additionalSystemPrompt`. Reconstructing those inputs from the final snapshot and current source
would be a derived approximation, not exact artifact reconstruction.

The Legacy implementation is available, including `ProtocolAwareMessageWindowChatMemory`,
`ToolResultGuard`, `TaskToolTranscript`, transcript merge/dedupe, and `FinalContextCompiler`.
However, exact execution of those classes still requires the missing per-round provider-bound
messages and planning prompts. Five trajectories also lack a complete canonical Tool/log identity.

## 3. Trace Set

```text
candidate cases:
24

formal included:
0

excluded:
24
```

All 24 cases are excluded from the formal aggregate because the global per-THINK replay input is
not serialized. Additional case-specific integrity gaps exist for:

- `c_pressure_cache_auth_rate`: 11 ToolCallLog entries versus 10 canonical observations; one call remained `RUNNING`.
- `c_pressure_mq_reliability`: 12 ToolCallLog entries versus 11 canonical observations; one call remained `RUNNING`.
- `c_pressure_seckill_architecture`: benchmark cutoff left 0 assembled ToolCallLog entries versus 23 canonical observations.
- `g_invalid_repo_failure`: 1 ToolCallLog entry versus 0 canonical observations.
- `g_safe_database_failure`: 1 ToolCallLog entry versus 0 canonical observations.

## 4. Same-trace Integrity

```text
tool sequence match:
UNKNOWN FOR FORMAL REPLAY

canonical result match:
19/24 case-level trajectories have matching ToolCallLog/canonical-observation counts;
5/24 are incomplete or inconsistent

logical-group match:
Final managed snapshots preserve protocol groups, but per-THINK group/window state is not serialized
```

The final managed snapshot is useful lineage evidence, but it is not proof that every earlier
Planner request contained the same message window and ordering.

## 5. Token Accounting

```text
estimator:
ESTIMATED_MESSAGE_CHARS_V1, charsPerToken=3

tokens:
ESTIMATED
```

The estimator counts message text plus Tool call/response identity and payload characters, then
applies `ceil(chars / 3)`. No cross-architecture token result is reported because exact same-trace
inputs are unavailable.

## 6. Peak Working Context

| Metric | Legacy | Current | Improvement |
| ------ | -----: | ------: | ----------: |
| P50 | N/A | N/A | N/A |
| P95 | N/A | N/A | N/A |
| Max | N/A | N/A | N/A |

The recorded Current production benchmark distribution (`6812 / 28841 / 33297`) remains valid as
a Current-only observation. It is not promoted into a Legacy-to-Current same-trace percentage.

## 7. Tool Payload Residency

```text
Canonical Tool Tokens:
N/A

Legacy model-resident:
N/A

Current model-resident:
N/A

Reduction:
N/A
```

Most cases preserve both canonical and projected bodies, but exact Legacy long-term residency also
depends on each Planner message-window checkpoint. The missing checkpoints prevent a formal total.

## 8. Context Growth per Tool Group

| Metric | Legacy | Current | Improvement |
| ------ | -----: | ------: | ----------: |
| Mean | N/A | N/A | N/A |
| P50 | N/A | N/A | N/A |
| P95 | N/A | N/A | N/A |
| Max | N/A | N/A | N/A |

## 9. Final Provider Input

| Metric | Legacy | Current | Improvement |
| ------ | -----: | ------: | ----------: |
| P50 | N/A | N/A | N/A |
| P95 | N/A | N/A | N/A |
| Max | N/A | N/A | N/A |

Current compiled Final provider messages are preserved exactly where Final was reached. An exact
Legacy counterpart cannot be claimed for the complete 24-case set because the replay trace is not
formally complete.

## 10. Legacy Transcript Recovery Burden

```text
Transcript recovery payload:
N/A FOR SAME-TRACE A/B

Overlap / duplicate payload:
N/A

Unique recovery-only:
N/A

Current transcript path:
0
```

The frozen Legacy benchmark independently reports TaskToolTranscript payload and attributable merge
contribution for its own Legacy trajectories. Those are not the same Current trajectories and are
therefore not used for a formal percentage. Transcript snapshot tokens must not be described as
provider-bound tokens when Legacy dedupe removed overlap.

## 11. Context Pressure Exposure

| Threshold | Legacy | Current | Reduction |
| --------: | -----: | ------: | --------: |
| 30K | N/A | N/A | N/A |
| 50K | N/A | N/A | N/A |
| 80K | N/A | N/A | N/A |
| 100K | N/A | N/A | N/A |
| 200K | N/A | N/A | N/A |
| 256K | N/A | N/A | N/A |

These are common offline pressure thresholds, not six production configurations.

## 12. 80K Historical Application Threshold

The 80K threshold has historical application-level relevance, but the number of same trajectories
crossing it cannot be computed defensibly without exact Legacy peaks. No exposure-reduction claim is
made.

## 13. Current 200K Trigger

The Current production benchmark's recorded maximum is 33297 estimated tokens, below the 200K
compression trigger. This remains a Current-only observation and does not establish how many of the
same traces Legacy would expose above 200K.

## 14. Outliers

```text
Top improvements:
N/A

Smallest improvements:
N/A

Current > Legacy:
N/A
```

No outlier ranking is generated from non-comparable inputs.

## 15. Evidence Safety

Existing production benchmark evidence, separate from Step 19 measurement:

```text
ExactValueAccuracy:
1.0000

Targeted valid reread:
94 / 94

Stable-ref failures:
0

Protocol failures:
0

Historical raw Tool leakage:
0
```

## 16. Architecture Interpretation

```text
Raw Tool long-term residency reduced:
NOT QUANTIFIED BY SAME-TRACE A/B

Context growth reduced:
NOT QUANTIFIED BY SAME-TRACE A/B

Pressure exposure reduced:
NOT QUANTIFIED BY SAME-TRACE A/B

Final provider input reduced:
NOT QUANTIFIED BY SAME-TRACE A/B

Transcript duplicate path eliminated:
YES
```

The last statement is a source-architecture fact: the Current implementation removed
`TaskToolTranscript` and Legacy Final transcript recovery. It is not a token-reduction percentage.

## 17. Claims Supported

- The formal Legacy runtime implementation is identified as `6f5502df...` from frozen execution metadata.
- Current artifacts preserve canonical and projected Tool bodies for 19 complete case trajectories and exact compiled Final messages where Final was reached.
- Current architecture has no TaskToolTranscript recovery path.
- Existing, separately sourced safety evidence remains healthy: exact values 1.0000, valid rereads 94/94, stable-ref/protocol/leakage failures 0.
- The available Step 14 artifact is insufficient for a defensible full same-trace Context-construction A/B.

## 18. Claims Not Supported

```text
Legacy vs Current Peak Working Context reduction %
Legacy vs Current Tool residency reduction %
Legacy vs Current context-growth reduction %
Legacy vs Current Final input reduction %
API cost reduction
actual compression event reduction
Final correctness improvement
latency improvement
```

## 19. Resume-safe Quantified Claim

No new Legacy-to-Current quantified resume claim is generated from Step 19. A safe existing claim is:

> 在 production-like benchmark 中，当前 Context Lifecycle 的 ExactValueAccuracy 为 100%，targeted valid stable reread 为 94/94，protocol failure、stable-ref failure 和历史 raw Tool leakage 均为 0；由于历史 trace 未保存逐轮 provider-bound messages，本次没有伪造 Legacy 对比百分比。

## 20. Interview-safe Summary

这次离线 A/B 先做了可比性门禁。Legacy 的正式执行提交和真实 transcript/window/final
实现都能定位，Current trace 也保存了 canonical/result projection 和 Final provider messages；
但它没有保存每轮 THINK 的完整 request messages 与 planning system prompt，且 5 条轨迹的
canonical/log identity 不完整。缺少这些输入时，重建出来的 Peak Context、每 Tool group 增长
和 Legacy Final 只能是推导值，不是同轨迹实测。所以我停止了百分比计算，保留已有 100%
exact-value、94/94 stable reread 和 0 protocol/leakage 作为安全证据，同时不把不可比数据写进简历。

## 21. Decision

```text
OFFLINE_CONTEXT_AB_NOT_COMPARABLE
```

## 22. Context Freeze Status

```text
REMAINS_FROZEN
```

The result is a measurement-input limitation, not evidence of a Context Lifecycle regression.

## 23. Production Changes

```text
production:
0

tests semantics:
0

Agent runs:
0

provider calls:
0

Tool executions:
0
```

## 24. Artifacts

```text
step19-offline-same-trace-ab-report.md
step19-per-case.csv
step19-per-case.json
step19-threshold-exposure.csv
step19-trace-integrity.csv
```

## 25. Git Status

```text
HEAD:
07ac3db444042fec4c647100b1763eecc498ce3c

working tree:
CLEAN
```
