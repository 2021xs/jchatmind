# JChatMind

JChatMind is a Spring Boot and Spring AI based Agent platform for Java backend development scenarios. It is no longer a simple chat demo: the project now supports observable Agent execution, document RAG, Code RAG over imported Java projects, SSE events, and a guarded tool execution gateway.

## Tech Stack

- Java 17, Spring Boot 3.5
- Spring AI DeepSeek client
- PostgreSQL with pgvector
- MyBatis
- Ollama `bge-m3` embeddings
- Redis-ready backend structure
- SSE for Agent progress events

## Core Capabilities

- Multi-turn chat through persisted `chat_session` and `chat_message`.
- Agent execution observability through `agent_task`, `agent_step`, and `tool_call_log`.
- Document RAG over Markdown chunks stored in `chunk_bge_m3`.
- Code RAG over imported Java/Spring Boot projects stored in `code_repository`, `code_file`, and `code_chunk`.
- Tool gateway with registry, permission metadata, result truncation, SSE events, and tool call logging.
- Read-only SQL safety policy for `DataBaseTools`.

## Three-Phase Upgrade

1. Observable Agent loop: added AgentTask, AgentStep, ToolCallLog, structured SSE events, and structured RAG results.
2. Code RAG minimal loop: added repository import, rule-based Java/MyBatis parsing, pgvector search, and `searchProjectCode`.
3. Tool execution gateway: added ToolRegistry and ToolExecutionService for unified permission checks, logging, SSE, and truncation.

## Architecture Flow

Ordinary chat:

```text
POST /api/chat-messages
  -> ChatMessageFacadeService
  -> ChatEvent
  -> ChatEventListener
  -> JChatMind.run()
  -> AgentTask / AgentStep
  -> Spring AI model call
  -> chat_message
  -> SSE message_start / step_done / done
```

Code RAG tool call:

```text
Agent question
  -> JChatMind think/execute loop
  -> Spring AI ToolCallingManager
  -> ToolExecutionService
  -> CodeSearchTools.searchProjectCode
  -> CodeSearchService
  -> code_chunk pgvector search
  -> tool_call_log
  -> SSE tool_call_start / retrieval_result / tool_call_result / done
```

## Environment Requirements

- JDK 17 or newer.
- PostgreSQL with pgvector.
- Ollama with `bge-m3`.
- A DeepSeek-compatible endpoint. The validated demo endpoint is `https://rapi.asia/v1` with model `deepseek-v4-pro`.

## Environment Variables

Create a local `.env` or configure the variables in your shell. Do not commit real secrets.

```text
DEEPSEEK_API_KEY=
DEEPSEEK_BASE_URL=https://rapi.asia/v1
DEEPSEEK_MODEL=deepseek-v4-pro
POSTGRES_URL=jdbc:postgresql://localhost:5432/jchatmind
POSTGRES_USER=postgres
POSTGRES_PASSWORD=postgres
CODE_RAG_ALLOWED_ROOTS=D:/Project,D:/workspace
```

The current `code-rag.allowed-roots` is still a YAML list for safe binding. Update it in `application.yaml` to match your local project directories before importing code repositories.

## PostgreSQL + pgvector

Example Docker setup:

```powershell
docker run --name jchatmind-postgres `
  -e POSTGRES_DB=jchatmind `
  -e POSTGRES_USER=postgres `
  -e POSTGRES_PASSWORD=postgres `
  -p 5432:5432 `
  -d pgvector/pgvector:pg16
```

## SQL Initialization

Run the base business SQL first if your local database is empty. This repository also contains `jchatmind_assert/jchatmind.sql` as a candidate initialization/demo SQL file; review it before use.

Then run the phase upgrade SQL files:

```sql
\i src/main/resources/db/init_agent_observability.sql
\i src/main/resources/db/init_code_rag.sql
```

Verify the schema:

```powershell
docker exec -i jchatmind-postgres psql -U postgres -d jchatmind -f scripts/verify_db.sql
```

## Ollama bge-m3

```bash
ollama serve
ollama pull bge-m3
```

`bge-m3` outputs 1024-dimensional embeddings, and `code_chunk.embedding` is defined as `vector(1024)`.

## DeepSeek Transit Configuration

The working model service combination verified during acceptance was:

```text
base-url: https://rapi.asia/v1
model: deepseek-v4-pro
```

The API key must be provided through `DEEPSEEK_API_KEY`. Never write the real key into source files, docs, logs, or screenshots.

## Start The Project

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
$env:DEEPSEEK_API_KEY='your-local-key'
.\mvnw.cmd -DskipTests package
.\mvnw.cmd spring-boot:run
```

Health check:

```http
GET http://localhost:8080/health
```

Expected response:

```text
ok
```

## Ordinary Chat Demo

1. Create or reuse an Agent without tools.
2. Create a chat session:

```http
POST /api/chat-sessions
Content-Type: application/json

{
  "agentId": "YOUR_AGENT_ID",
  "title": "plain chat demo"
}
```

3. Connect SSE:

```http
GET /sse/connect/YOUR_CHAT_SESSION_ID
```

4. Send a user message:

```http
POST /api/chat-messages
Content-Type: application/json

{
  "agentId": "YOUR_AGENT_ID",
  "sessionId": "YOUR_CHAT_SESSION_ID",
  "role": "user",
  "content": "你好，简单介绍一下你自己。"
}
```

Expected SSE events: `message_start`, `step_done`, `done`.

## Document RAG Demo

1. Create a knowledge base.
2. Upload a Markdown document through `/api/documents/upload`.
3. Confirm chunks are inserted into `chunk_bge_m3`.
4. Enable `KnowledgeTool` for an Agent and ask a question related to the uploaded document.

Expected behavior: retrieval results are returned to the model, and tool execution is recorded in `tool_call_log`.

## Code RAG Demo

Detailed guide: [docs/code_rag_demo.md](docs/code_rag_demo.md).

Minimal flow:

```http
POST /api/code-repositories/import
Content-Type: application/json

{
  "name": "your-project",
  "rootPath": "D:/Project/your-project"
}
```

Manual search:

```http
GET /api/code-repositories/YOUR_REPO_ID/search?query=代码库RAG查询链路&topK=5
```

Agent tool question:

```text
请调用 searchProjectCode 工具检索代码库，分析这个项目的代码库 RAG 查询链路，重点说明 Controller、Service、Mapper 和 pgvector 检索分别在哪里。
```

Expected SSE events: `tool_call_start`, `retrieval_result`, `tool_call_result`, `step_done`, `done`.

## Tool Gateway And SQL Safety Demo

Detailed guide: [docs/tool_gateway_demo.md](docs/tool_gateway_demo.md).

Dangerous SQL should be rejected by policy:

```sql
DELETE FROM users
UPDATE users SET name='x'
SELECT * FROM a; DROP TABLE b
SELECT * FROM users -- comment
EXPLAIN ANALYZE SELECT * FROM users
```

Safe SQL examples:

```sql
SELECT * FROM chat_message
SELECT * FROM chat_message LIMIT 10
EXPLAIN SELECT * FROM chat_message
```

Rejected SQL returns text containing `[REJECTED_BY_POLICY]`; it should not crash the whole Agent task.

## Demo Data

`jchatmind_assert/` currently contains demo-data and initialization SQL candidates such as `eshop.sql`, `eshop_data.sql`, `eshop.md`, and `jchatmind.sql`. Review these files before using them. They are intentionally not moved in this cleanup pass.

## Known Limits

- Code RAG is rule-based parsing plus semantic retrieval, not a precise static call graph.
- Java parsing does not use a full AST.
- Controller path extraction, inheritance, generics, overloads, and dynamic SQL may be incomplete.
- Tool call loops are bounded, but models may still call `searchProjectCode` multiple times for broad questions.
- `FileSystemTools` is intentionally disabled in the registry and should not be exposed without a separate read-only design.
- Local demo depends on PostgreSQL, pgvector, Ollama `bge-m3`, and a compatible DeepSeek endpoint.

## Roadmap

- Add lightweight unit tests for SQL safety policy, ToolRegistry aliases, and CodeChunkParser.
- Add a small `scripts/demo.http` or curl examples document.
- Add optional Flyway migration after SQL scripts stabilize.
- Add MCP integration later as a standard tool access layer.
- Add ContextManager and context compression only after the current Agent loop is stable.

## Interview Talking Points

- Explain why Agent observability matters: task, step, tool logs, and SSE events make the Agent debuggable.
- Explain Code RAG as a backend-oriented feature: repository import, rule-based chunking, metadata, pgvector search, and structured results.
- Explain tool safety: ToolRegistry, permission levels, allowed tools, disabled filesystem tool, SQL whitelist, timeout, row limit, and result truncation.
- Explain engineering tradeoffs: minimal invasive changes, no MCP yet, no full AST yet, and a clear extension path.
