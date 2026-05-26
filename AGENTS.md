# AGENTS.md

## 项目定位

JChatMind 是一个面向 Java 后端开发场景的智能 Agent 平台，基于 Spring AI 构建，支持多轮对话、文档知识库检索、代码库语义检索、工具调用和 SSE 执行事件推送。

当前阶段的核心目标不是继续堆功能，而是围绕简历和面试，把 Code RAG、pgvector 检索和工具安全边界做成“可运行、可解释、可复现”的工程成果。

后续所有修改优先服务三个目标：

1. Code RAG 检索链路可解释，尤其是 symbol supplement 从硬编码规则逐步前移到导入阶段 metadata。
2. 评测数据来自真实执行，不硬编码命中率、耗时或结论。
3. 改动小、可回滚，不大规模重构 Agent 主流程、SSE 协议或 ToolRegistry 设计。

---

## 技术栈

- Java 17+
- Spring Boot
- Spring AI
- PostgreSQL
- pgvector
- MyBatis
- Redis
- SSE
- Ollama

如需确认具体版本，请优先阅读：

- `pom.xml`
- `src/main/resources/application.yaml`
- `README.md`
- `docs/code-rag.md`
- `docs/evaluation.md`
- `src/main/resources/db/*.sql`
- `src/main/resources/mapper/*.xml`

不要凭空假设依赖版本。当前本机默认 `java` 可能是 Java 8，运行 Maven 测试时需要显式使用 Java 17+；本项目最近一次测试使用 `C:\Program Files\Java\jdk-21`。

---

## 当前 Code RAG 主链路

当前 Agent 代码检索主链路是：

```text
Agent searchProjectCode
-> CodeRagAnswerEvidenceService.retrieve
-> CodeSearchService.search(repoId, query, rawTopK)
-> pgvector RAW_VECTOR candidates
-> CodeSymbolCandidateSupplementService.supplement
-> SYMBOL_METADATA candidates + legacy hardcode fallback
-> merge by chunkId
-> CodeEvidenceCandidateCard
-> CodeLlmEvidenceSelector.select
-> selected CodeSearchResult evidence
-> Agent tool result
```

请优先维护这条主链路，不要新造并行检索入口。

---

## 当前已完成进度

### Code RAG eval

当前维护的评测集：

- `src/test/resources/eval/code_rag_eval_cases.json`
- 共 80 条用例：
  - 40 BASIC
  - 20 MEDIUM
  - 20 HARD

当前 final evaluation 测试类：

- `src/test/java/com/kama/jchatmind/eval/CodeRagFinalEvaluationTest.java`

它调用真实入口：

```text
CodeRagAnswerEvidenceService.retrieve(repoId, query)
```

并统计：

- selected@1
- selected@3
- selected@5
- fallback count
- jsonParseOk count
- retrieval error count
- metadata symbol hit count
- legacy fallback hit count

最新一次本地 80 条评测结果：

| Metric | Result |
| --- | ---: |
| Total cases | 80 |
| selected@1 | 77/80 |
| selected@3 | 79/80 |
| selected@5 | 79/80 |
| fallback count | 0 |
| jsonParseOk count | 80 |
| retrieval error count | 0 |
| metadata symbol hit count | 310 |
| legacy fallback hit count | 0 |

此前有两条 query 会稳定触发本地 Ollama `bge-m3` embedding API `500 Internal Server Error`。这两条已替换为同 category、同 difficulty、同 expected target 的等价 case，以避免评测长期受本地 embedding 服务 bug 影响。不要为了指标好看删除失败样本；如果替换 case，必须说明原因并保持同层次。

报告输出：

- `target/eval/code-rag-final-evaluation-report.md`

### Symbol metadata supplement

当前已实现的方向：

```text
导入阶段抽取 symbol metadata
-> 写入 code_chunk.metadata
-> 检索阶段优先基于 metadata 做 symbol supplement
-> metadata 候选不足时再走 legacy hardcode fallback
```

关键类和文件：

- `CodeChunkParserImpl`
- `SymbolTextNormalizer`
- `CodeSymbolCandidateSupplementService`
- `CodeRagAnswerEvidenceServiceImpl`
- `CodeChunkMapper.java`
- `CodeChunkMapper.xml`
- `CodeAnswerEvidenceResult`
- `CodeEvidenceCandidateCard`
- `CodeLlmEvidenceSelector`

当前 metadata 示例：

```json
{
  "symbols": ["SECKILL_ORDER_QUEUE"],
  "literalValues": ["seckill.order.queue"],
  "symbolTypes": ["MQ_QUEUE"],
  "normalizedSymbols": ["seckill order queue"]
}
```

Redis/cache key 示例：

```json
{
  "symbols": ["CACHE_SHOP_KEY"],
  "literalValues": ["cache:shop:"],
  "symbolTypes": ["REDIS_KEY"],
  "normalizedSymbols": ["cache shop key", "cache shop"]
}
```

当前 `CodeChunkParserImpl` 已覆盖：

- Java String 常量 metadata
- Controller API 的 METHOD / API_PATH metadata
- Mapper 方法的 SQL_ID metadata
- MyBatis XML 的 SQL_ID / TABLE metadata

当前 `CodeSymbolCandidateSupplementService` 策略：

```text
normalize query
-> metadataSymbolSupplementCandidates(repoId, pattern, limit)
-> token overlap score
-> SYMBOL_METADATA candidates
-> 如果不足，再 legacySupplement
-> SYMBOL_LEGACY candidates
```

legacy hardcode fallback 仍然保留，不能删除。它现在主要作为兜底和可回滚路径。

---

## 当前最高优先级

### P0：巩固 metadata symbol supplement

优先检查并维护：

- Java 常量抽取是否稳定覆盖 `public/private/static final String`
- `symbolType` 推断是否是通用规则，不要加入 query-specific hardcode
- `normalizedSymbols` 是否能处理驼峰、下划线、点号、冒号、横线、斜杠
- metadata 候选是否优先于 legacy hardcode
- `SYMBOL_METADATA` / `SYMBOL_LEGACY` source 是否在日志和 eval 中可观察
- 80 条 eval 是否仍能跑通并输出真实结果

不要把这部分包装成完整 IDE 级 symbol solver。它只是 lightweight symbol metadata recall。

### P1：pgvector 检索耗时评测

下一步重点补齐 pgvector latency 数据。

只统计代码语义检索阶段耗时，不要把 LLM selector 或最终回答耗时混入。

推荐统计范围：

```text
query embedding 已生成 / 或开始检索
-> pgvector Top-K 查询
-> 返回 chunk 结果
```

报告至少包含：

- code chunk 数量
- query 样本数量
- Top-K 参数
- 平均耗时
- P95 耗时
- 最大耗时
- 最小耗时
- 测试环境说明

建议新增或维护：

- `docs/eval/vector_search_latency_report.md`
- `docs/eval/vector_search_latency_results.csv`
- 对应 test / runner，优先复用 `CodeSearchService.search` 或 mapper 层真实 pgvector 查询

### P2：工具调用安全边界验证

针对代码库导入、文件扫描、数据库查询工具，补充安全测试和报告。

重点验证：

- `allowed-roots` 是否生效
- 路径规范化是否拦截 `../`
- 是否禁止扫描非授权目录
- 是否只读扫描
- SQL 白名单是否拦截危险语句
- 查询超时是否生效
- 结果截断是否生效
- 工具调用失败是否写入日志
- SSE 是否能推送异常事件

危险用例只用于验证防护是否生效，不要执行真实破坏性操作：

```text
../../etc/passwd
C:\Windows\System32
DROP TABLE xxx
DELETE FROM xxx
UPDATE xxx
SELECT sleep(10)
超大结果集查询
超长工具返回结果
```

建议新增或维护：

- `docs/eval/tool_safety_test_report.md`
- `ToolSafetyPolicyTest`
- 其他围绕工具边界的最小单测

### P3：根据 eval 结果做小范围优化

只有在评测数据暴露具体失败点时，才做小范围优化。

允许优化：

- chunk metadata 拼接
- symbol metadata 抽取
- normalize 规则
- metadata supplement score
- eval 报告统计字段

谨慎修改：

- Controller 主逻辑
- Service 主逻辑
- Agent 执行主流程
- SSE 事件协议
- ToolRegistry 设计
- 数据库核心表结构

除非明确必要，不要新建 `code_symbol_index` 表。当前第一版坚持写入 `code_chunk.metadata`。

---

## 重要工作原则

### 1. 真实链路优先

不要为了测试好看绕过真实检索链路。

优先使用：

- `CodeRagAnswerEvidenceService.retrieve(repoId, query)`
- `CodeSearchService.search(repoId, query, topK)`
- `CodeChunkMapper.similaritySearch`
- `CodeSymbolCandidateSupplementService.supplement`

不要跳过 embedding 和 pgvector 直接 mock final eval 结果。

单元测试可以 mock 依赖，但报告数据必须来自真实执行。

### 2. 不造假

所有报告数据必须来自真实运行。

允许记录失败，例如：

- Ollama embedding 500
- PostgreSQL 不可用
- LLM selector 超时
- 缺少 eval repoId

不要为了简历数据删除失败样本、硬编码命中率、手写耗时或修改评测口径。

### 3. 小步可回滚

每次改动优先做到：

- 文件少
- 语义清楚
- 有测试
- 有报告或日志支撑
- 不影响 Agent 工具对外语义

不要大规模重构业务代码。不要为了一个 eval case 引入难解释的特判。

### 4. 面试可信

代码和报告都要能支撑这类表述：

> 我之前的 symbol supplement 主要靠检索阶段规则补 Redis/RabbitMQ 常量定义点，这能解决问题，但可解释性和跨项目泛化一般。后来我把这部分前移到导入阶段：在 JavaParser 解析 chunk 的同时抽取常量名、字面值、API path、SQL id、表名等 symbol metadata，并写入 code_chunk.metadata。检索时不再优先硬编码 query 到常量名的映射，而是把 query normalize 后去匹配 metadata 里的 symbols、literalValues 和 normalizedSymbols，再把命中的 chunk 补进 pgvector 候选池，最后仍由 LLM selector 选择 evidence。这个改动让 symbol supplement 从项目特定规则变成了结构化 metadata 召回，但它还不是完整 IDE 级 symbol solver。

---

## Eval 用例规范

评测用例文件：

- `src/test/resources/eval/code_rag_eval_cases.json`

推荐字段：

```json
{
  "id": "basic_mq_001",
  "query": "Where is the seckill order queue defined?",
  "expectedFileKeywords": ["RabbitConstants"],
  "expectedSymbolKeywords": ["SECKILL_ORDER_QUEUE", "seckill.order.queue"],
  "expectedChunkTypes": ["CLASS_SUMMARY"],
  "category": "MQ",
  "difficulty": "BASIC"
}
```

命中规则：

- 如果 `expectedChunkTypes` 存在，selected evidence 必须先满足 chunk type。
- 然后检查：
  - `filePath` 是否包含 `expectedFileKeywords`
  - 或 `symbolName` / `apiPath` / `contentPreview` / `metadata` 是否包含 `expectedSymbolKeywords`

当前 selected@K 是 selector evidence hit rate，不代表最终自然语言回答准确率，也不代表 raw pgvector Top-K 命中率。

---

## 常用命令

如果默认 Java 是 8，请先临时切到 Java 17+。Windows PowerShell 示例：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
```

运行聚焦测试：

```powershell
.\mvnw.cmd "-Dtest=SymbolTextNormalizerTest,CodeChunkParserImplTest,CodeSymbolCandidateSupplementServiceTest,CodeRagAnswerEvidenceServiceImplTest" test
```

运行完整测试：

```powershell
.\mvnw.cmd test
```

运行 80 条 Code RAG final eval：

```powershell
.\mvnw.cmd "-Dtest=CodeRagFinalEvaluationTest" "-Deval.autoRepo=true" "-Deval.retryCount=3" test
```

指定 repoId：

```powershell
.\mvnw.cmd "-Dtest=CodeRagFinalEvaluationTest" "-Deval.repoId=<repoId>" test
```

只跑部分 eval：

```powershell
.\mvnw.cmd "-Dtest=CodeRagFinalEvaluationTest" "-Deval.repoId=<repoId>" "-Deval.limit=10" test
```

---

## 修改后必须说明

每次完成任务后，请说明：

- 新增了哪些文件
- 修改了哪些文件
- 如何运行
- 输出结果在哪里
- 是否影响现有业务逻辑
- 是否影响 Agent 工具入口
- 是否影响 LLM selector 主逻辑
- 测试和 eval 的真实结果

如果某项测试无法运行，要明确说明原因，不要补虚假的结果。

---

## 禁止事项

不要做以下事情：

- 不要为了跑通测试绕过真实检索链路
- 不要硬编码评测结果
- 不要跳过 embedding 和 pgvector 直接 mock final eval
- 不要把 LLM selector 耗时混入 pgvector 检索耗时
- 不要删除 legacy hardcode fallback
- 不要新建 `code_symbol_index` 表，除非用户明确要求
- 不要大规模重构 Agent 主流程
- 不要修改 SSE 事件协议，除非明确必要
- 不要删除已有日志模型
- 不要修改已有数据库核心表结构，除非明确必要
- 不要引入复杂的新框架
- 不要提交真实密钥、数据库密码或私有路径
- 不要执行真实破坏性 SQL

---

## 文档语言规范

- 面向开发者的说明可以使用中文。
- 文件名、字段名、接口名、类名保持英文。
- 测试报告优先使用中文，便于后续整理简历和面试话术。
- 简历候选 bullet 使用中文。
- 指标必须带真实来源，例如测试命令、报告路径、运行时间或失败原因。


