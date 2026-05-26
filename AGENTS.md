# AGENTS.md

## 项目定位

JChatMind 是一个面向 Java 后端开发场景的智能 Agent 平台，基于 Spring Boot 和 Spring AI 构建，覆盖多轮对话、知识库检索、代码库语义检索、工具调用、工具安全边界和 SSE 执行事件推送。

当前阶段的核心目标不是继续堆功能，而是围绕简历和面试，把以下能力做成“可运行、可解释、可复现”的工程成果：

1. Code RAG：代码导入、chunk metadata、pgvector 检索、LLM evidence selector。
2. 工具安全：数据库查询工具、代码库扫描、工具注册与运行时权限。
3. 真实评测：Code RAG final eval、工具安全测试、后续 pgvector latency 数据。

后续所有修改优先服务三个原则：

1. 真实链路优先，不为了测试或指标绕过实际入口。
2. 改动小、可回滚，不大规模重构 Agent 主流程、SSE 协议或 ToolRegistry 设计。
3. 报告数据必须来自真实执行，不硬编码命中率、耗时或结论。

---

## 技术栈

- Java 17+
- Spring Boot 3.5.x
- Spring AI 1.1.x
- PostgreSQL
- pgvector
- MyBatis
- Redis
- SSE
- Ollama-compatible embedding endpoint
- JSqlParser
- JavaParser

具体版本以这些文件为准：

- `pom.xml`
- `src/main/resources/application.yaml`
- `README.md`
- `src/main/resources/db/*.sql`
- `src/main/resources/mapper/*.xml`

不要凭空假设依赖版本。当前本机默认 `java` 可能不是 Java 17+，运行 Maven 测试时建议显式使用：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
```

---

## 当前真实 Code RAG 主链路

当前 Agent 代码检索主链路是：

```text
Agent searchProjectCode
-> CodeRagAnswerEvidenceService.retrieve(repoId, query)
-> CodeSearchService.search(repoId, query, rawTopK)
-> CodeQueryEmbeddingCache
-> RagService.embed(query)
-> CodeChunkMapper.similaritySearch(repoId, vector, limit)
-> pgvector RAW_VECTOR candidates
-> CodeEvidenceCandidateCard
-> CodeLlmEvidenceSelector.select
-> selected CodeSearchResult evidence
-> Agent tool result
```

当前工作区中，检索阶段实际只接入了 `RAW_VECTOR`。`CodeChunkParserImpl` 已经能在导入阶段写入较丰富的 `code_chunk.metadata`，但 metadata symbol supplement 还没有作为独立检索补召回服务接入当前 `CodeSearchServiceImpl`。

因此后续描述项目时要准确：

- 可以说：导入阶段已抽取 symbol metadata，写入 `code_chunk.metadata`。
- 可以说：当前 Agent 代码检索经过 pgvector raw candidates 和 LLM evidence selector。
- 不要说：当前已上线 `CodeSymbolCandidateSupplementService` 或 `SYMBOL_METADATA/SYMBOL_LEGACY` 补召回链路，除非代码中重新实现并有测试/eval 证明。

优先维护现有入口：

- `CodeRagAnswerEvidenceService.retrieve(repoId, query)`
- `CodeSearchService.search(repoId, query, topK)`
- `CodeChunkMapper.similaritySearch`
- `CodeLlmEvidenceSelector.select`

不要新造并行检索入口。

---

## 当前 Code RAG metadata 状态

当前 `CodeChunkParserImpl` 已覆盖：

- Java 类、方法 chunk。
- Controller API 的 `METHOD` / `API_PATH` metadata。
- Mapper 接口方法的 `SQL_ID` metadata。
- MyBatis XML 的 `SQL_ID`、statement type、dynamic tags、include refs、table 相关 metadata。
- Java String 常量 metadata，例如 MQ queue、Redis/cache key。

metadata 示例：

```json
{
  "symbols": ["SECKILL_ORDER_QUEUE"],
  "literalValues": ["seckill.order.queue"],
  "symbolTypes": ["MQ_QUEUE"],
  "normalizedSymbols": ["seckill order queue"]
}
```

当前重点不是把它包装成 IDE 级 symbol solver。它只是 lightweight symbol metadata，为后续可解释召回提供基础。

后续如果恢复或新增 symbol supplement，必须满足：

- 基于 `code_chunk.metadata` 做通用匹配，不写 query-specific hardcode。
- metadata 候选、legacy fallback、raw vector source 在日志和 eval 中可观察。
- legacy fallback 如果存在，应作为兜底，不要删除可回滚路径。
- eval 报告中必须真实统计 metadata hit / legacy hit，不允许手写。

---

## 当前 Code RAG eval 状态

评测集：

- `src/test/resources/eval/code_rag_eval_cases.json`
- 共 80 条用例：
  - 40 BASIC
  - 20 MEDIUM
  - 20 HARD

final evaluation 测试类：

- `src/test/java/com/kama/jchatmind/eval/CodeRagFinalEvaluationTest.java`

真实入口：

```text
CodeRagAnswerEvidenceService.retrieve(repoId, query)
```

当前报告输出：

- `target/eval/code-rag-final-evaluation-report.md`

当前工作区最近报告显示：

| Metric | Result |
| --- | ---: |
| total cases | 80 |
| selected@1 | 72/80 |
| selected@3 | 77/80 |
| selected@5 | 78/80 |
| fallback count | 0 |
| jsonParseOk count | 80 |
| retrieval error count | 0 |

注意：

- 当前报告没有 metadata symbol hit count / legacy fallback hit count 字段。
- selected@K 是 selector evidence hit rate，不代表最终自然语言回答准确率，也不代表 raw pgvector Top-K 命中率。
- 不要为了指标好看删除失败样本、硬编码结果或修改评测口径。

---

## 数据库工具安全边界

`DataBaseTools` 当前已经具备双层防护：

### 应用层

- `SqlSafetyValidator` 使用 JSqlParser。
- 只允许单条 `SELECT`。
- 拒绝 `UPDATE` / `DELETE` / `DROP` / `ALTER` / `INSERT` / `CALL` 等非 SELECT。
- 拒绝多语句 SQL。
- 拒绝 `SELECT INTO`、`OUTFILE`、`DUMPFILE`。
- 拒绝 `FOR UPDATE`、`NOWAIT`、`SKIP LOCKED` 等锁查询。
- 自动补充或限制 `LIMIT`。
- `JdbcTemplate` 设置 `queryTimeout`、`maxRows`、`fetchSize`。
- 工具返回结果按 `maxResultLength` / `maxCellChars` 截断。

### 数据库权限层

当前已新增独立只读 datasource：

- `databaseToolDataSource`
- `databaseToolJdbcTemplate`

配置类：

- `src/main/java/com/kama/jchatmind/config/DatabaseToolDataSourceConfig.java`

`DataBaseTools` 通过 `@Qualifier("databaseToolJdbcTemplate")` 注入，只读工具不再使用主业务 `JdbcTemplate`。

配置前缀：

```yaml
jchatmind:
  database-tool:
    datasource:
      url: ${JCHATMIND_DB_READONLY_URL:jdbc:postgresql://localhost:5432/jchatmind}
      username: ${JCHATMIND_DB_READONLY_USERNAME:jchatmind_readonly}
      password: ${JCHATMIND_DB_READONLY_PASSWORD:}
      driver-class-name: org.postgresql.Driver
```

重要要求：

- 主业务 datasource 仍使用 `spring.datasource`。
- 不要让 database tool 静默回退到主业务账号。
- 不要在应用启动时自动执行授权 SQL。
- 不要提交真实数据库密码。

只读账号授权文档：

- `docs/database_tool_readonly_user.md`

本机 Docker PostgreSQL 已验证：

- 容器：`jchatmind-postgres`
- 镜像：`pgvector/pgvector:pg16`
- 端口：`localhost:5432`
- 数据库：`jchatmind`
- 只读账号：`jchatmind_readonly`
- `SELECT current_user` / `SELECT 1` / `SELECT COUNT(*) FROM code_repository` 成功。
- `UPDATE ... WHERE false` / `DELETE ... WHERE false` / `CREATE TABLE ...` 被 PostgreSQL 权限拒绝。

本地开发临时密码只限本机使用，后续应通过环境变量替换，不要写入仓库。

---

## 当前最高优先级

### P0：保持安全边界可信

优先维护：

- `DataBaseTools` 必须注入 `databaseToolJdbcTemplate`。
- `SqlSafetyValidator` 不要删除。
- 只读 datasource 未配置时必须明确失败，不要回退主账号。
- 数据库工具测试要覆盖应用层拒绝和只读账号验证。
- 权限验证 SQL 不要真实修改业务数据，写权限验证使用 `WHERE false` 或事务回滚。

### P1：补齐 pgvector latency 数据

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
- 对应 test / runner，优先复用 `CodeSearchService.search` 或 mapper 层真实 pgvector 查询。

### P2：让 metadata symbol supplement 重新落地

当前 metadata 已在导入阶段存在，但检索阶段尚未实际接入 metadata supplement。

如果继续做这部分，建议小步实现：

1. 增加 mapper 层 metadata 查询或基于 jsonb text 的最小可用召回。
2. 对 query 做 normalize。
3. 用 token overlap 或简单 score 排序 metadata candidates。
4. 合并到 RAW_VECTOR candidates，按 `chunkId` 去重。
5. 标记 `rerankSource=SYMBOL_METADATA`。
6. 候选不足时再考虑 legacy fallback，标记 `SYMBOL_LEGACY`。
7. eval 报告真实统计 metadata hit 和 legacy hit。

不要新建 `code_symbol_index` 表，除非用户明确要求。

### P3：工具调用安全报告

补齐报告：

- `docs/eval/tool_safety_test_report.md`

重点验证：

- `allowed-roots` 是否生效。
- 路径规范化是否拦截 `../`。
- 是否禁止扫描非授权目录。
- 是否只读扫描。
- SQL validator 是否拦截危险语句。
- 只读数据库账号是否拒绝写操作。
- 查询超时是否生效。
- 结果截断是否生效。
- 工具调用失败是否写入日志。
- SSE 是否能推送异常事件。

危险用例只用于验证防护是否生效，不要执行真实破坏性操作。

---

## 重要工作原则

### 1. 真实链路优先

不要为了测试好看绕过真实检索链路。

优先使用：

- `CodeRagAnswerEvidenceService.retrieve(repoId, query)`
- `CodeSearchService.search(repoId, query, topK)`
- `CodeChunkMapper.similaritySearch`
- `DataBaseTools.query(sql)`
- `ToolExecutionService`

单元测试可以 mock 依赖，但报告数据必须来自真实执行。

### 2. 不造假

所有报告数据必须来自真实运行。

允许记录失败，例如：

- Ollama embedding 500。
- PostgreSQL 不可用。
- LLM selector 超时。
- 缺少 eval repoId。
- 只读数据库账号未配置。

不要为了简历数据删除失败样本、硬编码命中率、手写耗时或修改评测口径。

### 3. 小步可回滚

每次改动优先做到：

- 文件少。
- 语义清楚。
- 有测试。
- 有报告或日志支撑。
- 不影响 Agent 工具对外语义。

谨慎修改：

- Controller 主逻辑。
- Service 主逻辑。
- Agent 执行主流程。
- SSE 事件协议。
- ToolRegistry 设计。
- 数据库核心表结构。

### 4. 面试可信

代码和报告要能支撑这类表述：

> 我没有只在应用层用正则拦 SQL，而是用 JSqlParser 做 AST 级校验，只允许单条安全 SELECT，并限制 LIMIT、timeout、maxRows 和返回长度。后来我又把数据库工具从主业务账号里拆出来，单独配置 databaseToolDataSource/databaseToolJdbcTemplate，使用 PostgreSQL 只读账号。这样即使应用层 validator 有遗漏，数据库权限层也会拒绝 UPDATE、DELETE、CREATE 等写操作。这个验证在本机 Docker PostgreSQL 上跑过，SELECT 成功，写语句被权限拒绝。

Code RAG 表述要准确：

> 当前项目已经在导入阶段抽取 Java 常量、API path、SQL id、MyBatis XML metadata，并写入 code_chunk.metadata。当前检索主链路仍是 pgvector RAW_VECTOR candidates 加 LLM evidence selector。metadata supplement 是下一步要接入检索阶段的优化点，不应把未接入的能力说成已上线。

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
- 然后检查 `filePath` 是否包含 `expectedFileKeywords`，或 `symbolName` / `apiPath` / `contentPreview` / `metadata` 是否包含 `expectedSymbolKeywords`。

---

## 常用命令

切换 Java：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
```

运行数据库工具安全相关测试：

```powershell
.\mvnw.cmd "-Dtest=ToolSafetyPolicyTest,DatabaseToolDataSourceConfigTest" test
```

运行本机只读账号集成验证：

```powershell
$env:JCHATMIND_DB_READONLY_URL="jdbc:postgresql://localhost:5432/jchatmind"
$env:JCHATMIND_DB_READONLY_USERNAME="jchatmind_readonly"
$env:JCHATMIND_DB_READONLY_PASSWORD="<local-dev-password>"
.\mvnw.cmd "-Dtest=DataBaseToolsReadOnlyAccountIntegrationTest" test
```

运行 Code RAG 聚焦测试：

```powershell
.\mvnw.cmd "-Dtest=CodeChunkParserImplTest,CodeChunkEmbeddingTextBuilderImplTest,CodeChunkContextBuilderTest,CodeRagAnswerEvidenceServiceImplTest,CodeSearchServiceImplTest,CodeRepositoryServiceImplTest" test
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

- 新增了哪些文件。
- 修改了哪些文件。
- 如何运行。
- 输出结果在哪里。
- 是否影响现有业务逻辑。
- 是否影响 Agent 工具入口。
- 是否影响 LLM selector 主逻辑。
- 测试和 eval 的真实结果。

如果某项测试无法运行，要明确说明原因，不要补虚假的结果。

---

## 禁止事项

不要做以下事情：

- 不要为了跑通测试绕过真实检索链路。
- 不要硬编码评测结果。
- 不要跳过 embedding 和 pgvector 直接 mock final eval。
- 不要把 LLM selector 耗时混入 pgvector 检索耗时。
- 不要声称未接入的 `SYMBOL_METADATA` / `SYMBOL_LEGACY` 链路已经上线。
- 不要让 DataBaseTools 回退使用主业务数据库账号。
- 不要删除 `SqlSafetyValidator`。
- 不要在应用启动时自动执行数据库授权 SQL。
- 不要新建 `code_symbol_index` 表，除非用户明确要求。
- 不要大规模重构 Agent 主流程。
- 不要修改 SSE 事件协议，除非明确必要。
- 不要删除已有日志模型。
- 不要修改已有数据库核心表结构，除非明确必要。
- 不要引入复杂的新框架。
- 不要提交真实密钥、数据库密码或私有路径。
- 不要执行真实破坏性 SQL。

---

## 文档语言规范

- 面向开发者的说明可以使用中文。
- 文件名、字段名、接口名、类名保持英文。
- 测试报告优先使用中文，便于后续整理简历和面试话术。
- 简历候选 bullet 使用中文。
- 指标必须带真实来源，例如测试命令、报告路径、运行时间或失败原因。
