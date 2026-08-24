# Code RAG Selector 阶段总结

## 当前链路

```text
Query
-> bge-m3 embedding
-> PostgreSQL pgvector RAW_VECTOR Top-20
-> CodeEvidenceCandidateFormatter
-> C01-C20 Local Candidate ID
-> gpt-5.5 evidence selector
-> {"selectedCandidateIds":[...]}
-> 本地映射真实 chunk UUID
-> Selected Evidence
```

当前 snapshot 保留 V2b Candidate Representation。Candidate prompt 保留 `file`、`symbol`、`type`、已有行号、非空 API、compact signals 和最多 420 字符的源码 snippet；不发送真实 UUID、candidate score、raw rank、`evidenceRole`、`evidenceHint`、冗余 metadata 或空字段。

Agent 工具仍调用 `CodeRagAnswerEvidenceService.retrieve(repoId, query)`，最终 evidence 继续使用真实 chunk UUID。Retrieval、Top-K、embedding、Agent 工具入口和 SSE contract 未改变。

## Eval Harness

分层评测通过生产主链路采集 Raw Retrieval candidates 和 Selector 结果，可以独立计算：

- Retrieval Recall@1/3/5/10 和 MRR。
- selected@1/3/5。
- `RETRIEVAL_MISS`、`SELECTOR_MISS`、`FALLBACK` 和执行错误。
- embedding、pgvector retrieval、selector、total latency。
- provider 返回的真实 prompt/completion/total token Usage。
- Local Candidate ID 的 proposed/valid/invalid 诊断和 prompt size。

当前 FlashDeal 80-case 数据的 Retrieval Recall@10 为 93.75%。Ground Truth 是 fixture 定义的 keyword-level acceptable evidence，不是精确 chunkId ground truth。

## Selector 结论

V2a 将真实 UUID 输出契约替换为调用内 `C01-C20` 和最小 JSON contract，消除了该轮 invalid ID 与 fallback，并降低输出 token。

V2b 在 V2a contract 上压缩 Candidate Representation。三次独立 80-case 结果为：

| Metric | Range |
| --- | ---: |
| selected@1 | 64-66 / 80 |
| selected@3 | 73-75 / 80 |
| selected@5 | 75-76 / 80 |
| SELECTOR_MISS | 2-3 |
| invalid selector ID | 0 |

V2b Prompt Token Sum 三轮均为 281271，相对历史 V2a Run1 的 431604 下降 34.83%。V2b Total Token Sum 三轮平均 298662.33，相对 V2a Run1 的 449668 下降 33.58%。

详细运行产物位于本机 `target/eval/`，该目录按项目规则保持 Git ignored。三轮稳定性聚合报告为 `target/eval/stability/selector-v2a-vs-v2b-stability-report.md`。

## 已知边界

- `basic_sql_004`：V2b 三轮均返回空 `selectedCandidateIds` 并进入 FALLBACK；Ground Truth Raw rank=4，fallback Top-5 仍命中。当前保留为 Candidate Compression 的已知边界，不继续针对单个 benchmark case 调参，避免 benchmark-specific optimization。
- `basic_redis_001`：fixture 的 expected chunk type 与实际 `LUA_SCRIPT` 不一致，不作为系统能力结论依据，本阶段未修改 fixture 或 matcher。
- `hard_class_001`、`medium_util_002`：V2b 三轮均为稳定 SELECTOR_MISS，留待后续独立阶段评估。

## 验证方式

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
.\mvnw.cmd "-Dtest=CodeEvidenceCandidateFormatterTest,CodeLlmEvidenceSelectorTest,CodeRagAnswerEvidenceServiceImplTest,CodeSearchServiceImplTest,CodeRagGroundTruthMatcherTest,CodeRagMetricCalculatorTest,CodeRagFailureClassifierTest,PercentileCalculatorTest,CodeRagEvaluationReportWriterTest" test
.\mvnw.cmd test
```

本阶段 snapshot 不包含运行产物，不自动运行真实 80-case，也不包含 V2a 源码快照。
