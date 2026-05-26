# JChatMind

JChatMind is a Java backend Agent and Code RAG project built with Spring Boot and Spring AI. It focuses on multi-turn Agent execution, tool calling, SSE observability, document retrieval, code repository import, pgvector-based semantic search, and explainable Code RAG evidence selection.

The current baseline is designed for engineering demonstration and interview discussion: the important paths are runnable, testable, and documented in code through focused tests.

## Highlights

- Spring Boot backend for Agent chat workflows.
- Spring AI integration with configurable chat models.
- SSE execution events for Agent progress and tool results.
- Tool registry and runtime permission checks.
- Tool execution logs with task, step, and tool-call records.
- Document RAG and code repository import.
- Code chunk parsing for Java, Controller APIs, Mapper methods, SQL files, and MyBatis XML statements.
- Code RAG retrieval through embeddings and PostgreSQL pgvector.
- Answer-time evidence selection for the Agent `searchProjectCode` tool.
- SQL safety validation for the Agent database query tool using JSqlParser AST checks.

## Tech Stack

- Java 17+
- Spring Boot
- Spring AI
- MyBatis
- PostgreSQL
- pgvector
- Redis
- SSE
- Ollama-compatible embedding endpoint
- JSqlParser
- JavaParser

## Architecture Overview

### Agent Tool Execution

```text
Client
-> Agent run
-> Spring AI chat model
-> tool call
-> ToolExecutionService
-> ToolRegistry permission check
-> tool implementation
-> task / step / tool-call logs
-> SSE events
```

### Code RAG Main Path

```text
Agent searchProjectCode
-> CodeRagAnswerEvidenceService.retrieve
-> CodeSearchService.search(repoId, query, rawTopK)
-> pgvector RAW_VECTOR candidates
-> symbol metadata supplement
-> CodeEvidenceCandidateCard
-> CodeLlmEvidenceSelector.select
-> selected CodeSearchResult evidence
-> Agent tool result
```

The REST code search endpoint is kept as a raw retrieval/debug path. The Agent tool uses the answer-time evidence path.

## Code RAG Scope

JChatMind intentionally implements lightweight Code RAG rather than a full IDE-level symbol solver.

During repository import, `CodeChunkParserImpl` extracts structured chunk metadata into `code_chunk.metadata`, including Java constants, Controller API paths, Mapper method SQL ids, and MyBatis XML statement metadata. Retrieval uses pgvector candidates plus metadata supplement, then asks the LLM selector to choose final evidence.

## Database Tool Safety

The Agent database query tool is constrained for read-only diagnostics:

- SQL is parsed with JSqlParser.
- Only one `SELECT` statement is allowed.
- Multi-statement SQL is rejected.
- Write and DDL statements are rejected by AST type.
- `SELECT INTO OUTFILE` / `DUMPFILE` and `SELECT ... FOR UPDATE` are rejected.
- Missing `LIMIT` is capped.
- Oversized `LIMIT` is rewritten to the configured maximum.
- JDBC `queryTimeout`, `maxRows`, and `fetchSize` are configured.
- Large cell values and tool results are truncated.

Production deployments should still use a read-only database account for defense in depth.

## Requirements

- JDK 17 or newer. Local validation was run with JDK 21.
- Maven wrapper from this repository.
- PostgreSQL with pgvector for Code RAG storage and search.
- A chat model provider configured through Spring AI.
- An embedding endpoint compatible with the configured Ollama embedding API.

## Configuration

The default configuration lives in:

```text
src/main/resources/application.yaml
```

Use environment variables for secrets and local paths. Do not commit real credentials.

Common environment variables:

```env
POSTGRES_URL=jdbc:postgresql://localhost:5432/jchatmind
POSTGRES_USER=your_db_user
POSTGRES_PASSWORD=your_db_password

DEEPSEEK_RELAY_API_KEY=your-api-key
DEEPSEEK_RELAY_BASE_URL=https://rapi.asia/v1
DEEPSEEK_RELAY_MODEL=deepseek-v4-pro

DEEPSEEK_OFFICIAL_API_KEY=your-api-key
DEEPSEEK_OFFICIAL_BASE_URL=https://api.deepseek.com
DEEPSEEK_OFFICIAL_MODEL=deepseek-chat

OLLAMA_BASE_URL=http://localhost:11434
OLLAMA_EMBEDDING_MODEL=bge-m3
```

For local examples, start from:

```text
.env.example
```

## Database Setup

Initialize Code RAG and Agent observability tables with:

```text
src/main/resources/db/init_code_rag.sql
src/main/resources/db/init_agent_observability.sql
```

The project expects PostgreSQL and pgvector for vector search. Make sure the `vector` extension is available before importing repositories for Code RAG.

## Run Locally

Set Java first on Windows PowerShell:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
```

Compile:

```powershell
.\mvnw.cmd -DskipTests compile
```

Run the application:

```powershell
.\mvnw.cmd spring-boot:run
```

Health check:

```text
GET /health
```

## Useful API Entrypoints

- `GET /health`
- `GET /api/tools`
- `GET /sse/connect/{chatSessionId}`
- `GET /api/agents`
- `POST /api/agents`
- `GET /api/chat-sessions`
- `POST /api/chat-sessions`
- `GET /api/knowledge-bases`
- `POST /api/knowledge-bases`
- `POST /api/documents/upload`
- `POST /api/code-repositories/import`
- `GET /api/code-repositories`
- `GET /api/code-repositories/{repoId}/search`

## Tests

Run focused baseline tests before changing core behavior:

```powershell
.\mvnw.cmd "-Dtest=CodeChunkParserImplTest,CodeChunkEmbeddingTextBuilderImplTest,CodeChunkContextBuilderTest" test
.\mvnw.cmd "-Dtest=SqlSafetyValidatorTest,ToolSafetyPolicyTest" test
.\mvnw.cmd "-Dtest=CodeRagAnswerEvidenceServiceImplTest,CodeRepositoryServiceImplTest,CodeSearchServiceImplTest" test
.\mvnw.cmd "-Dtest=InMemoryToolRegistryTest,AgentTaskLogServiceImplTest,JChatMindRealRunObservabilityTest,ConversationContextCompressorTest" test
```

Run all tests:

```powershell
.\mvnw.cmd test
```

Some integration tests require external services such as PostgreSQL, pgvector, an embedding endpoint, and a configured chat model.

## Code RAG Evaluation

The final Code RAG evaluation uses real retrieval and evidence selection:

```powershell
.\mvnw.cmd "-Dtest=CodeRagFinalEvaluationTest" "-Deval.repoId=<repoId>" test
```

Or use the latest imported repository:

```powershell
.\mvnw.cmd "-Dtest=CodeRagFinalEvaluationTest" "-Deval.autoRepo=true" "-Deval.retryCount=3" test
```

The eval dataset contains 80 cases:

- 40 BASIC
- 20 MEDIUM
- 20 HARD

Metrics such as `selected@1`, `selected@3`, and `selected@5` measure selected evidence hit rate, not final natural-language answer quality.

## Development Notes

- Keep changes small and reversible.
- Do not bypass embedding, pgvector, or selector paths to fake evaluation metrics.
- Do not commit real keys, passwords, private directories, or local-only config.
- Prefer focused tests for parser, retrieval, tool safety, and observability changes.
- Use a read-only database account for Agent database query tools.

## Repository Hygiene

Ignored local artifacts include:

- `target/`
- `logs/`
- `.env`
- `.env.*`
- `application-local.yml`
- `application-local.yaml`
- `.idea/`
- `.kiro/`

## License

No open-source license file has been added yet. Add a `LICENSE` file before treating this repository as formally licensed open-source software.
