# Step 18 Production Benchmark Final Attribution Review Result

## 1. Baseline

```text
HEAD:
07ac3db444042fec4c647100b1763eecc498ce3c

HEAD message:
chore(observability): attribute final output failure stages

working tree before review:
CLEAN
```

Budget recalibration freeze commit:

```text
fda641049b54b1b9ebb15534e1e5dabde680bcc2
refactor(context): separate compression trigger from hard limit
```

No checkout, reset, rebase, pull, source edit, Agent run, provider call, or Compression replay was performed.

## 2. Reviewed Evidence

| Step | Evidence | Artifact |
| ---- | -------- | -------- |
| Step 14 | Budget recalibration, 644-test regression, 24x1 production benchmark | `target/benchmark/context-lifecycle/context-lifecycle-2026-09-01T15-09-48.428326400+08-00-d402af80/step14-production-benchmark-report.md` |
| Step 14 targeted | Eight selected cases, two additional observations each | `target/benchmark/context-lifecycle/step14-targeted-repeats-20260901/step14-targeted-repeat-report.md` |
| Step 15 | Failure inventory and initial attribution | `target/benchmark/context-lifecycle/step15-failure-attribution-20260901/step15-production-benchmark-failure-attribution-report.md` |
| Step 16 | Final stream/assembly/persistence/delivery observability | `target/benchmark/context-lifecycle/step16-final-empty-output-observability-20260901/step16-final-empty-output-observability-report.md` |
| Step 17A | `f_long_planning_reliability_compare` evidence lineage | `target/benchmark/context-lifecycle/step17a-final-correctness-lineage-20260901/step17a-f-long-reliability-compare-evidence-lineage-report.md` |
| Step 17B | `c_pressure_seckill_architecture` latency attribution | `target/benchmark/context-lifecycle/step17b-c-pressure-latency-attribution-20260901/step17b-c-pressure-seckill-architecture-latency-attribution-report.md` |

Supporting raw evidence:

- original Step 14 `context-lifecycle-raw.json`, `context-lifecycle-cases.csv`, and anomalies report;
- `combined-targeted-observations.json` and the eight preserved targeted raw runs;
- persisted Agent task/step/tool/message rows used read-only in Step 17B;
- current source/config read-only verification.

Evidence availability: all required artifacts were readable.

## 3. Production Architecture

```text
Tool execution
    |
    v
tool-specific business / security / resource boundary
    |
    v
Persistent canonical Tool Result (full durable body)
    |
    v
shared Tool Model View
    |
    v
Working Context
    +-- eligible completed historical User / Final
    +-- exact raw Current User
    +-- Continuation State when compression is needed
    +-- uncovered protocol-safe current-task Tool groups
    |
    v
FinalSynthesisRequestFactory
    |
    v
FinalContextCompiler
    |
    v
single Final provider call
    |
    v
FinalCompletionService
    |
    v
durable Final persistence and SSE delivery
```

Removed production paths:

```text
TaskToolTranscript class / writes / reads / reset
Legacy Final transcript recovery merge
Transcript-vs-memory dedupe
duplicate Final evidence side-channel
replacement Transcript 2.0 store
```

Production source has zero references to the removed components. Benchmark output code retains the historical label `TaskToolTranscript` solely to render Legacy metrics and current `REMOVED_NOT_APPLICABLE`; it is not a production recovery path.

## 4. Budget Configuration

```text
compressionTriggerTokens (soft trigger):
200000

workingContextHardLimitTokens (application hard limit):
256000
```

```text
Context < 200K
-> normal execution

Context >= 200K
-> Continuation State compression eligible

compressed/retained valid context > 200K and <= 256K
-> may continue

complete provider-bound candidate > 256K and cannot be legally reduced
-> fail closed
```

The 256K value is an application trade-off, not provider physical context capacity.

Step 14 Phase A scope:

```text
Budget semantics changed: YES
Projection changed: NO
Continuation State semantics changed: NO
Final changed: NO
Persistence changed: NO
Tool behavior changed: NO
Protocol grouping changed: NO
```

Phase A regression:

```text
644 tests
0 failures
0 errors
22 skipped
```

## 5. Production Workload Context Distribution

Step 14 production-like 24x1 profile:

```text
Peak Working Context (ESTIMATED_MESSAGE_CHARS_V1)

P50:
6812

P95:
28841

max:
33297

Compression:
0 / 24 tasks, 0 events

Hard-limit failures:
0
```

Calculated utilization:

```text
max / 200K soft trigger:
16.6485%

max / 256K application hard limit:
13.0066%
```

The current production benchmark workload has substantial headroom before semantic compression is required. This is application Working Context utilization, not provider-capacity utilization.

Compression under this workload is a rare fallback. The zero-event result is healthy and expected; it does not fully validate compression output quality in production-like runs.

## 6. Correctness Summary

Initial 24x1 benchmark:

```text
Success / Final reached:
20 / 24

CriticalFactRecall, all observations:
0.7500

CriticalFactRecall, excluding the benchmark-cutoff observation:
0.7826

ExactValueAccuracy:
1.0000

Perfect Exact:
24 / 24

Forbidden facts:
0
```

Eight-case targeted 3-observation set:

```text
Observations:
24

Success / Final reached within benchmark contract:
18 / 24

CriticalFactRecall, all observations:
0.5868

CriticalFactRecall, Final-reached observations:
0.7824

ExactValueAccuracy:
1.0000

Forbidden facts:
0
```

The `0.5868` aggregate is not a Context architecture correctness score. It includes four terminal empty-Final observations and two five-minute benchmark cutoffs, all scored as zero without deleting or replacing them. Final-reached recall plus evidence-lineage attribution is the relevant Context review evidence.

## 7. Protocol / Isolation

```text
orphan ToolCall groups:
0

orphan ToolResponse groups:
0

incomplete protocol batches:
0

protocol validation/provider protocol failures:
0

Historical raw Tool leakage:
0

Historical ToolCall leakage:
0 observed

Historical planning leakage:
0 observed

Historical Continuation State leakage:
0 observed
```

All six Step 14 cross-task pollution tasks passed with critical recall and exact accuracy `1.0`. Eligible prior User/Final projection did not pull prior raw Tool, Planning, or State into a later task.

## 8. Stable Reread

```text
Initial 24x1 getCodeChunk:
54 / 56 exact success

Initial invalid calls:
1 INVALID_ARGUMENT from model-altered repoId
1 NOT_FOUND from model-supplied non-existent chunkId

repeated failed reread loops:
0

same-task duplicate exact rereads:
0
```

The two initial failures used model-generated arguments rather than refs emitted by a projection path; all fitting results used the exact fast path in that run.

Targeted observations:

```text
getCodeChunk:
94 / 94 success

stable-ref failures:
0

same-chunk duplicate reread within an observation:
0
```

Step 17A subset:

```text
17 / 17 success
17 unique refs
0 duplicate rereads
0 NOT_FOUND
0 INVALID_ARGUMENT
```

Step 17B subset:

```text
26 / 26 valid stable refs succeeded
0 duplicate rereads
0 NOT_FOUND
1 additional malformed Planner-generated chunkId, non-blocking
```

Conclusion:

```text
Exact reread mechanism:
HEALTHY
```

`getCodeChunk` count itself is not treated as a minimization KPI; valid exact recovery and lack of repeated failure loops are the relevant outcomes.

## 9. Step 17A Correctness Attribution

Case and fact:

```text
f_long_planning_reliability_compare
duplicate = allOf[sismember, selectExistingUserVoucherPairs]
type = DERIVED_RELATIONSHIP
```

R1 lineage:

```text
Canonical Tool Result: Lua + DB PRESENT
Tool Model View:       Lua + DB PRESENT
Working Context:       Lua + DB PRESENT
Final Request:         Lua + DB PRESENT
Provider Messages:     Lua + DB PRESENT
Final Output:          sismember PRESENT; selectExistingUserVoucherPairs ABSENT

first missing layer:
L8 Final Output
```

R2/R3 lineage:

```text
DB evidence acquired and preserved through Provider Messages
Lua sismember evidence not acquired by Tool path
selectExistingUserVoucherPairs absent from Final output
```

Final attribution:

```text
Primary:
MODEL_FINAL_SYNTHESIS_OMISSION

Secondary:
TOOL_PATH_EVIDENCE_MISS

SCORER_FALSE_NEGATIVE:
NO

CONTEXT_LIFECYCLE_INVOLVED:
NO
```

Every acquired evidence item was preserved through Canonical -> Model View -> Working Context -> Final Request -> compiled Provider Messages.

## 10. Step 17B Latency Attribution

```text
Success:
260490 ms

Timeout 1 benchmark cutoff:
~300000 ms
eventual completion: 444222 ms SUCCESS

Timeout 2 benchmark cutoff:
~300000 ms
eventual completion: 324836 ms SUCCESS
```

Stage latency:

| Observation | THINK | Tool | Final |
| ----------- | ----: | ---: | ----: |
| Success | 74093 ms | 14737 ms | 171154 ms |
| Timeout 1 | 206744 ms | 17895 ms | 218982 ms |
| Timeout 2 | 184098 ms | 18833 ms | 120462 ms |

THINK plus Final accounts for more than 93% of each task.

```text
Planning rounds:
9 / 10 / 9

PLANNER_ROUND_EXPLOSION:
NO

terminal Planner call:
9979 / 104751 / 88629 ms

Primary:
FINAL_LATENCY

Secondary:
PLANNER_PROVIDER_LATENCY

Decision:
LATENCY_ATTRIBUTED_TO_PROVIDER

CONTEXT_LIFECYCLE_INVOLVED:
NO
```

Both cutoff tasks had already compiled Final attempt 1. The benchmark's five-minute poll stopped while Final was in flight; it did not cancel the Agent. Both tasks later completed successfully with a single Final attempt, no retry, and no provider failure.

The timeout trajectories requested 24/26 tools versus 15 for the success trajectory, but Tool wall time remained below 6%, exact duplicate calls and duplicate rereads were zero, and late batches still gained evidence. Broader planning is a latency amplifier, not a no-progress loop or Context regression.

## 11. Final Empty Output

Historical observations:

```text
terminal empty-Final failures:
4

Final request construction:
HEALTHY

Current User / required evidence / protocol:
PRESENT / VALID

Budget involvement:
NO

Compression involvement:
NO

historical provider-vs-assembly boundary:
PROVIDER/ASSEMBLY_BOUNDARY_UNKNOWN
```

Historical artifacts cannot be retroactively classified as `FINAL_STREAM_ASSEMBLY_FAILURE` or definitive provider raw-empty.

Step 16 commit `07ac3db444042fec4c647100b1763eecc498ce3c` adds diagnostic observability only:

```text
rawContentCharCount = 0
+ normal stream completion
-> PROVIDER_FINAL_OUTPUT_FAILURE

rawContentCharCount > 0
+ assembledFinalCharCount = 0
-> FINAL_STREAM_ASSEMBLY_FAILURE
```

Downstream fields distinguish assembly, persistence, and server-side SSE delivery:

```text
assembledFinalCharCount
persistedFinalCharCount
deliveredFinalCharCount
persistenceCompleted
deliveryCompleted
failureStage
failureClassification
```

Step 16 changed no Final prompt, attempt count, retry, fallback, model/provider selection, validator behavior, persistence content, SSE payload semantics, Agent Tool behavior, or Context Lifecycle behavior.

```text
business semantics changed:
NO

future attribution observability:
AVAILABLE
```

## 12. Context Lifecycle Component Matrix

| Component | Status | Evidence |
| --------- | ------ | -------- |
| Canonical Tool persistence | HEALTHY | Full canonical Tool results remained durable; Step 17A acquired evidence was present at canonical boundary and downstream |
| Tool Model View | HEALTHY | No Canonical-present / Model-View-absent transition in Step 17A; fitting results retained exact fast path |
| Structured projection | HEALTHY | No projection loss observed; deterministic projection/stable-ref tests pass; normal 24x1 run did not cross the 8K projection threshold |
| Working Context | HEALTHY | Step 17A Model View evidence preserved into Working Context; exact Current User retained; peak bounded at 33297 |
| Completed-task projection | HEALTHY | Prior eligible User/Final preserved; historical raw Tool/Planning/State leakage 0 |
| Continuation State | NOT_EXERCISED | Compression 0 in production-like runs; dedicated deterministic/stress tests cover fallback semantics |
| Protocol grouping | HEALTHY | Orphan calls/responses, incomplete groups, and provider protocol failures all 0 |
| Stable refs | HEALTHY | 94/94 targeted valid rereads; Step 17A 17/17; no valid-ref failure loops |
| getCodeChunk reread | HEALTHY | Exact recovery succeeds; no duplicate same-task rereads; malformed model arguments fail closed |
| Final request construction | HEALTHY | Empty-Final inputs and Step 17A required evidence were present with exact Current User and valid roles |
| Final compiler | HEALTHY | Step 17A structured request and compiled Provider Messages have identical required-evidence status and valid SYSTEM/SYSTEM/USER order |
| Cross-task isolation | HEALTHY | Historical raw Tool leakage 0 across six pollution fixtures |
| Budget semantics | HEALTHY | 644-test regression green; soft/hard boundaries independent; normal hard failures 0 |
| Compression behavior | NOT_EXERCISED | 0/24 normal tasks triggered compression; deterministic pressure tests pass but normal production behavior was not exercised |
| Legacy Transcript removal | HEALTHY | Production Transcript/recovery/merge/dedupe references 0; no replacement evidence store introduced |

Compression interpretation:

```text
Compression was not exercised under normal production-like workload.

Existing dedicated stress/replay/deterministic tests cover fallback behavior,
but normal benchmark evidence shows that it is currently a rare fallback.
```

Benchmark final health summary:

| Dimension | Verdict |
| --------- | ------- |
| Context correctness | HEALTHY for acquired evidence |
| Exact values | HEALTHY, accuracy 1.0000 |
| Protocol safety | HEALTHY |
| Cross-task isolation | HEALTHY |
| Stable reread | HEALTHY |
| Working-context size | HEALTHY, max 33297 |
| Budget semantics | HEALTHY |
| Compression necessity | NOT REQUIRED by observed normal workload |
| Final synthesis reliability | KNOWN NON-CONTEXT ISSUE |
| Planner retrieval reliability | KNOWN NON-CONTEXT ISSUE |
| Provider latency | KNOWN NON-CONTEXT ISSUE |
| Final empty observability | AVAILABLE for future recurrence |

## 13. Remaining Non-context Issues

| Issue | Classification | Current Evidence | Context Lifecycle Related |
| ----- | -------------- | ---------------- | ------------------------- |
| Historical Final empty output | `INDEPENDENT_PROVIDER/FINAL_OBSERVABILITY_ISSUE` | Four healthy Final inputs; exact historical provider/assembly boundary unknown; future observability available | NO |
| Final method/fact omission | `INDEPENDENT_FINAL_SYNTHESIS_RELIABILITY` | `selectExistingUserVoucherPairs` provider-bound 3/3, output 0/3 | NO |
| Planner retrieval variance | `INDEPENDENT_PLANNER_RETRIEVAL_RELIABILITY` | R2/R3 did not acquire required Lua evidence | NO |
| Planner/Final latency | `INDEPENDENT_PROVIDER_PERFORMANCE` | slow terminal Planner calls and long Final calls; cutoff tasks eventually succeeded | NO |
| Broader productive Tool trajectories | `PLANNING_EFFICIENCY_OPPORTUNITY` | timeout paths 24/26 requests vs 15; no no-progress loop; evidence continued advancing | NO |

These are real Agent/provider reliability or performance findings. They are not dismissed, but they do not block the Context Lifecycle freeze and are not repaired in this review.

## 14. Architecture Benefit

Directly supported by evidence:

- Persistent canonical Tool bodies and model-facing evidence have a single lifecycle; acquired evidence showed no Context or Final-input loss.
- Historical raw Tool leakage is `0`; completed-task projection admits eligible User/Final rather than raw Tool/Planning/State.
- Valid stable refs support exact selective reread; targeted `getCodeChunk` was `94/94` with no duplicate reread.
- ExactValueAccuracy is `1.0000`; Lua return codes, TTL, MQ constants, status values, and API/method fixtures remained exact.
- Working Context remained bounded in the observed workload: P50 `6812`, P95 `28841`, max `33297`.
- Compression was unnecessary for every normal 24x1 task, consistent with a rare-fallback design.
- Protocol failures and orphan groups are `0`.
- The duplicate Legacy Final evidence recovery route is removed: no production TaskToolTranscript, merge, dedupe, backup store, or Transcript 2.0.

Qualitative Legacy comparison:

```text
Legacy:
ChatMemory + TaskToolTranscript + Final recovery merge + transcript/memory dedupe

Current:
Persistent canonical result + Working Context + one compiled Final input path

duplicate Final evidence recovery path:
REMOVED
```

The 24x1 Tool Context retention ratio was `100%` because no allowed result crossed the configured 8K model-specific projection threshold. That run therefore does not quantify structured-projection token savings; it does show that fitting results take the exact fast path.

## 15. Claims Not Supported

No same-model, same-suite, same-profile, same-token-accounting Legacy-vs-current A/B exists for the 200K/256K production-like profile. Therefore this review does not claim:

```text
Legacy vs New token reduction %
Legacy vs New compression reduction %
Legacy vs New API cost reduction %
Legacy vs New latency reduction %
Legacy vs New peak-context reduction %
```

These remain:

```text
NOT QUANTIFIED BY SAME-PROFILE A/B
```

The review also does not claim:

- compression correctness was fully exercised by normal production workload;
- zero Compression events prove the 100K threshold is worse than 200K;
- 256K is provider physical capacity;
- a Context reduction percentage from the historical 3500 Legacy profile;
- historical empty Finals were definitely provider-raw empty or definitely assembly loss.

## 16. 3500 Stress Reclassification

```text
ADVERSARIAL_CONTEXT_FLOOR_STRESS
```

The preserved partial run is useful for:

- protocol-safe mandatory working-set floor testing;
- fail-closed behavior when mandatory evidence cannot legally fit;
- Continuation State/compression-path robustness;
- confirming that successful compression does not guarantee a later complete protocol group can fit an unrealistically small hard floor.

It is not a production acceptance baseline and cannot establish normal production workload capacity. Its failures are not a formal current-architecture regression conclusion.

## 17. Compression Replay Requirement

```text
OPTIONAL_FUTURE_ROBUSTNESS_WORK
```

Reason:

```text
normal workload max:
33297

200K trigger utilization:
16.6485%

normal Compression:
0 / 24

normal hard-limit failures:
0

dedicated deterministic/stress coverage:
AVAILABLE
```

A stored-trace 100K-vs-200K replay may later test robustness and cost trade-offs, but no current workload evidence makes it required before freeze. It should not be run merely to force Compression activity.

## 18. Freeze Criteria

| # | Criterion | Result | Evidence |
| -: | --------- | ------ | -------- |
| 1 | No canonical -> model-view -> working-context evidence loss | PASS | Step 17A preserves every acquired required term through these layers |
| 2 | No Final request construction loss | PASS | Required evidence survives structured request and compiled messages; empty-Final inputs healthy |
| 3 | Protocol failures = 0 | PASS | No orphan/incomplete/provider protocol failures |
| 4 | Historical raw Tool leakage = 0 | PASS | Cross-task pollution diagnostics and fixtures |
| 5 | Stable-ref exact reread healthy | PASS | 94/94 targeted valid rereads; no duplicate/failure loop |
| 6 | ExactValueAccuracy healthy | PASS | 1.0000 in initial and targeted evidence |
| 7 | Budget recalibration has no regression | PASS | 644 tests green; no runtime hard-budget/compression failure |
| 8 | Normal workload remains well below hard limit | PASS | max 33297 = 13.0066% of 256K |
| 9 | Benchmark failures attributed outside Context Lifecycle | PASS | Step 17A model/tool-path; Step 17B provider latency; empty boundary independent and observed |
| 10 | No known architecture-specific blocker remains | PASS | Component review has no `REGRESSION` or `UNKNOWN`; only normal-run Compression is `NOT_EXERCISED` |

## 19. Final Decision

```text
CONTEXT_LIFECYCLE_FREEZE_PASS_WITH_KNOWN_NON_CONTEXT_ISSUES
```

Why:

```text
Context correctness:
PASS for all acquired evidence; no projection/Working Context/Final-input loss

Exact evidence:
PASS, ExactValueAccuracy 1.0000 and healthy valid stable rereads

Protocol and isolation:
PASS, failures/orphans/leakage 0

Budget:
PASS, independent soft/hard semantics and max normal context 33297

Compression:
not needed by normal workload; deterministic/stress fallback coverage exists

Architecture simplicity:
PASS, duplicate Transcript recovery path remains removed

Remaining failures:
attributed to provider output/latency, Final synthesis, Planner retrieval, or benchmark deadline
```

## 20. Frozen Scope

The following production decisions are formally frozen:

```text
Persistent canonical Tool Result strategy
tool-specific business/security/resource boundaries
shared Tool Model View strategy
structured projection design
completed-task User/Final projection
Working Context semantics
exact Current User preservation
Continuation State semantics
stable repoId/chunkId locator model
getCodeChunk selective exact reread
protocol-safe logical Tool grouping
FinalSynthesisRequestFactory path
FinalContextCompiler path
single Final input path
FinalCompletionService durable completion path
no TaskToolTranscript / no Final recovery merge
compressionTriggerTokens = 200000
workingContextHardLimitTokens = 256000
```

Freeze means no further proactive optimization of these modules based on the current benchmark. It does not prohibit changes required by a real production bug, a demonstrated regression, a new provider contract, or materially different workload evidence.

## 21. Remaining Backlog

### P0

```text
Confirmed production correctness/availability blocker:
NONE

Context Lifecycle related:
NO
```

The historical empty-Final signal is retained, but its exact boundary is unknown and Step 16 now supplies recurrence observability. There is no current evidence supporting a Context or production repair in this review.

### P1

| Item | Purpose | Context Lifecycle Related |
| ---- | ------- | ------------------------- |
| Observe and classify the next real Final-empty recurrence using Step 16 fields | Separate provider raw-empty, assembly, persistence, and delivery before any repair | NO |
| Final synthesis adherence review | Evaluate explicit required-symbol omission without changing Context input | NO |
| Planner retrieval reliability review | Evaluate missing Lua evidence and Tool-path variance | NO |
| Provider latency/TTFT/TTLT ROI review | Assess slow terminal Planner and Final calls independently of benchmark cutoff | NO |

### P2

| Item | Purpose | Context Lifecycle Related |
| ---- | ------- | ------------------------- |
| Productive Tool-trajectory efficiency analysis | Determine whether broad searches have worthwhile latency/token ROI | NO |
| 100K-vs-200K stored-trace Compression replay | Optional robustness comparison without full Agent reruns | YES, optional frozen-module robustness only |
| Benchmark timeout-policy review | Decide whether complex-case acceptance needs a reporting boundary distinct from five minutes | NO |

No backlog item was executed.

## 22. Recommended Next Project Step

```text
NEXT:
Project final audit and evidence/material consolidation
```

Stop proactive Context Lifecycle optimization. Consolidate the frozen architecture, benchmark attribution, test evidence, and precise limitations into final project documentation and resume/interview material. Independent Agent reliability/performance work should be selected later through an explicit ROI review.

## 23. Production Changes

```text
production:
0

tests:
0

benchmark semantics:
0

new Agent runs:
0

provider calls:
0

100K / 200K Compression replay:
NOT_RUN
```

The only new artifact is this report under ignored `target/benchmark/context-lifecycle`.

## 24. Git Status

```text
HEAD:
07ac3db444042fec4c647100b1763eecc498ce3c

working tree:
CLEAN
```
