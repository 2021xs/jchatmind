# JChatMind

JChatMind is a Java backend Agent and Code RAG project built with Spring Boot and
Spring AI. It demonstrates multi-turn Agent execution, tool calling, durable Final
delivery, SSE observability, document retrieval, bounded GitHub repository import,
pgvector code search, and answer-time evidence selection.

The repository is maintained as an engineering and interview artifact: public
claims below distinguish deterministic tests, provider benchmarks, and individual
case observations.

## Highlights

- Protocol-aware Agent memory, context compression, and tool-call lifecycle.
- Tool registry plus runtime preflight and permission checks.
- Validation-gated Final synthesis with PostgreSQL transactional completion.
- JavaParser and MyBatis XML code indexing with pgvector semantic retrieval.
- LLM evidence selector behind the Agent `searchProjectCode` tool.
- JSqlParser SQL validation plus a mandatory independent read-only datasource.
- Web Console session/repository/model selection, cancellation, traces, and SSE
  reconciliation.

## Technology

- Java 17+ (release verification uses JDK 21)
- Spring Boot 3.5.x and Spring AI 1.1.x (exact versions: `pom.xml`)
- Maven Wrapper
- PostgreSQL, pgvector, MyBatis, and Redis
- Ollama-compatible embedding endpoint
- SSE, JSqlParser, and JavaParser
- React 19, TypeScript, Vite, and npm

The frontend follows Vite's declared Node engine (`^20.19.0 || >=22.12.0`) and
uses the committed `package-lock.json`; this release was verified with Node
24.14.0 and npm 11.9.0. pnpm is not used by this repository.

## Runtime Architecture

### Agent and tools

```text
Client / Chat / Feishu
-> JChatMindFactory
-> JChatMind planning request
-> ToolExecutionService
-> ToolRegistry + runtime preflight
-> tool implementation
-> task / step / tool-call logs
-> stage-level SSE events
```

An Agent's `temperature` and `topP` settings apply to its Planning requests. An
unset value remains absent so the selected Provider can use its own default. Model
routing continues through `ChatClientRegistry`. Final synthesis has a separate,
fixed safety contract and does not inherit these Planning sampling settings.

### Code RAG production path

```text
JavaParser / MyBatis XML indexing
-> Embedding
-> PostgreSQL pgvector RAW_VECTOR candidates
-> CodeEvidenceCandidateCard
-> LLM evidence selector
-> selected evidence
-> Agent tool result
```

`CodeChunkParserImpl` extracts Java constants, Controller API paths, Mapper SQL ids,
and MyBatis XML metadata into `code_chunk.metadata` during import. That metadata is
available for explanation and future retrieval work; the current production
`CodeSearchService` does **not** add a metadata-symbol supplement. The REST search
endpoint remains a raw retrieval/debug path, while the Agent uses
`CodeRagAnswerEvidenceService.retrieve(repoId, query)`.

### Final synthesis and SSE

The Final delivery design evolved through three stages:

1. Stage-level SSE execution events.
2. Direct display of Final Provider token chunks.
3. The current validation-gated buffered replay.

In the current path, the Provider still uses streaming, but its chunks are buffered
on the backend for the entire attempt. `FinalContextCompiler` builds the isolated
request with no tools; `FinalOutputValidator` validates the complete answer; and a
single attempt budget also covers corrective retry. Only a valid answer enters
`FinalCompletionService`, which commits the Final AssistantMessage,
`FINAL_SYNTHESIS`/`FINISH` steps, and Task `SUCCESS` in one PostgreSQL transaction.
TOKEN replay begins only after commit.

`JCHATMIND_WEB_CONSOLE_FINAL_STREAMING_ENABLED=false` disables post-commit TOKEN
replay/lifecycle events; it does not disable compilation, validation, retry, or the
durable transaction. This is not immediate Provider-token display and the project
does not implement an Outbox. PostgreSQL is the source of truth, so an SSE client
that disconnects after commit recovers the Final by reloading the session.

## Database Tool Safety

The Agent SQL tool uses two enforced layers:

1. JSqlParser validates exactly one read-only `SELECT`, rejects write/DDL,
   multi-statement, `SELECT INTO`/file output, and locking queries, and bounds
   `LIMIT`.
2. `databaseToolJdbcTemplate` uses the independent
   `jchatmind.database-tool.datasource` account. It never falls back to the main
   application datasource.

Runtime preflight is applied before execution. JDBC also enforces query timeout,
`maxRows`, and `fetchSize`; cell values and the complete tool result are truncated
to configured bounds. Create the PostgreSQL read-only account separately as
described in `docs/database_tool_readonly_user.md`.

## Configuration

The tracked defaults live in `src/main/resources/application.yaml`. Copy values
from `.env.example` into the process environment or use the ignored root
`application-local.yaml` for local secrets. Never commit real credentials.

Important configuration groups are:

- `POSTGRES_*`: main business datasource.
- `JCHATMIND_DB_READONLY_*`: mandatory SQL-tool datasource/account.
- `ZHIPUAI_*`, `DEEPSEEK_OFFICIAL_*`, `GPT_COMPATIBLE_*`: chat Providers.
- `OLLAMA_BASE_URL` and `OLLAMA_EMBEDDING_MODEL`: embedding endpoint/model.
- `CODE_RAG_ALLOWED_ROOTS`: comma-separated canonical roots allowed for local
  repository scans.
- `JCHATMIND_GITHUB_WORKSPACE_ROOT`: parent directory for GitHub clones.

Both repository settings use portable defaults under `./workspace`; production
deployments should set explicit canonical paths. GitHub imports use the lower-case
canonical identity `{owner}--{repository}`. For example, RuoYi-Cloud is stored as
`<workspace>/yangzongzhuan--ruoyi-cloud`, alongside a local directory such as
`<workspace>/FlashDeal`. An existing target is never overwritten or reused.

## Database Initialization

Provide PostgreSQL with pgvector, then execute the tracked scripts in this order:

```text
1. src/main/resources/db/init_code_rag.sql
2. src/main/resources/db/init_agent_observability.sql
```

The first script enables `vector` and creates repository/file/chunk storage. The
second creates Agent task, step, tool-call, and message observability storage. The
SQL-tool account/grants are intentionally not created during application startup.

## Run Locally

On Windows PowerShell, select JDK 21 (Java 17+ is supported):

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
```

Build and start the backend with the Maven Wrapper:

```powershell
.\mvnw.cmd package
.\mvnw.cmd spring-boot:run
```

Spring Boot uses port `8080` unless `server.port` is overridden. Useful endpoints
include `GET /health`, `GET /api/tools`, `GET /api/agents`,
`GET /api/code-repositories`, and `GET /sse/connect/{chatSessionId}`.

Start the frontend in another terminal:

```powershell
cd web-console
npm ci
npm run dev
```

Vite proxies `/api` and `/sse` to `http://127.0.0.1:8080` by default. Override
`VITE_JCHATMIND_DEV_PROXY_TARGET` in an ignored frontend `.env` when necessary.

## Tests

The default backend command uses deterministic/mock contracts and does not call a
real LLM, embedding endpoint, GitHub, or MCP server:

```powershell
.\mvnw.cmd test
```

PostgreSQL transaction tests use Testcontainers and require Docker; they are
skipped with an explicit condition when Docker is unavailable. Historical-database
diagnostics and real Provider diagnostics are separately property-gated. The
context-sanitization default fixture is self-contained and does not load personal
session/task data or `application-local.yaml`.

Representative focused commands:

```powershell
.\mvnw.cmd "-Dtest=FinalCompletionTransactionIntegrationTest" test
.\mvnw.cmd "-Dtest=JChatMindFinalStreamingTest,JChatMindFinalToolIsolationTest,JChatMindFinalContextSanitizationTest" test
.\mvnw.cmd "-Dtest=SqlSafetyValidatorTest,ToolSafetyPolicyTest,DatabaseToolDataSourceConfigTest" test
```

Frontend deterministic assertions compile their source dependencies themselves:

```powershell
cd web-console
npm run build
npm test
```

Provider benchmarks and the real Code RAG evaluation are opt-in diagnostics. For
example, a configured evaluation repository can be supplied explicitly:

```powershell
.\mvnw.cmd "-Dtest=CodeRagFinalEvaluationTest" "-Deval.repoId=<repoId>" test
```

## Verified Evaluation Vocabulary

These three result sets have different inputs and must not be merged.

### Selector transport latency benchmark

The 40-call benchmark uses 4 fixed queries × 2 clients × 5 runs per client:

| Metric | Baseline | Current |
| --- | ---: | ---: |
| Mean | 13.81 s | 0.81 s |
| P50 | 6.45 s | 0.78 s |
| P95 | 30.01 s | 1.07 s |

### Selector evidence quality benchmark

The quality comparison uses FlashDeal, frozen candidates, 80 cases, and 160 total
selector calls (baseline plus current):

| Metric | Baseline | Current |
| --- | ---: | ---: |
| selected@1 | 59/80 | 59/80 |
| selected@3 | 67/80 | 74/80 |
| selected@5 | 70/80 | 75/80 |
| timeout | 23/80 | 0/80 |

`selected@K` measures evidence selection, not final natural-language answer
accuracy. The timeout change reflects the combined transport, retry, and thinking
strategy; it is not attributed to a single switch. Latencies from this 80-case run
are not substituted for the separate 40-call benchmark.

### Planning case observation

For the fixed query “秒杀脚本是怎么样的”, one baseline run and one current run
observed `searchProjectCode` calls fall from 9 to 3, `seckill.lua` occurrences from
8 to 1, and total latency from 313824 ms to 39714 ms. This is a case study, not a
statistically stable benchmark. The live run did not trigger Hard Guard because
each search introduced new evidence; deterministic tests verify the Guard itself.

Final transaction atomicity and rollback are verified by PostgreSQL Testcontainers.
A separate single E2E artifact observed 798 TOKEN events / 1610 characters and an
identical SHA for aggregated TOKEN text and the durable DB Final; that observation
is not presented as an aggregate benchmark.

## Web Console Contract

The console persists session `repoId` and model selection through the backend,
supports task cancellation and trace inspection, and scopes SSE reducer state to
the active task/run. Provisional stream state is reconciled with durable messages.
Because Final text is published after commit, session reload—not an in-memory token
buffer—is the recovery path after disconnect.

## Repository Hygiene

Ignored local artifacts include `target/`, `web-console/dist/`, `node_modules/`,
logs, runtime files, `.env*` except `.env.example`, `application-local.yaml`, IDE
settings, and the internal `docs/codex_goal`. Generated benchmark/E2E responses are
not release inputs unless separately reviewed and deliberately published as a
sanitized report.

## License

No open-source license file has been added. Do not treat the repository as formally
licensed open source until a license is chosen and committed.
