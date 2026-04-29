# Final Acceptance Notes

This document records the final acceptance result after the three core upgrade phases. It intentionally omits secrets and full runtime logs.

## Environment

- PostgreSQL with pgvector was running.
- Required base tables existed: `agent`, `chat_session`, `chat_message`, `knowledge_base`, `document`, `chunk_bge_m3`.
- Phase tables existed: `agent_task`, `agent_step`, `tool_call_log`, `code_repository`, `code_file`, `code_chunk`.
- `code_chunk.embedding` was verified as `vector(1024)`.
- `tool_call_log.arguments_json` was verified as `jsonb`.
- Ollama `bge-m3` was available for embeddings.
- DeepSeek transit endpoint was verified with:
  - base URL: `https://rapi.asia/v1`
  - model: `deepseek-v4-pro`
  - API key supplied by environment variable.

## Ordinary Chat

Result: SUCCESS.

Observed behavior:

- User message was inserted into `chat_message`.
- Agent execution created one `agent_task`.
- `agent_task.status` became `SUCCESS`.
- `agent_step` included:
  - `THINK` / `SUCCESS`
  - `FINISH` / `SUCCESS`
- Assistant response was inserted into `chat_message`.
- No tool call was needed, so `tool_call_log` was empty for the ordinary chat task.

Observed SSE event types:

- `message_start`
- `step_done`
- `done`

## searchProjectCode Tool Calling

Result: SUCCESS.

The Agent was configured with:

```json
["searchProjectCode"]
```

The test prompt explicitly asked the model to call `searchProjectCode` to analyze the Code RAG query path.

Observed behavior:

- The model produced real `searchProjectCode` tool calls.
- `ToolExecutionService` emitted tool start/result events and wrote logs.
- `CodeSearchTools` emitted `retrieval_result` events.
- `tool_call_log` contained `searchProjectCode` rows with `SUCCESS`.
- `agent_task.status` became `SUCCESS`.
- `agent_step` included repeated `THINK` and `TOOL_CALL` steps, followed by `FINISH`.

Observed SSE event types:

- `message_start`
- `tool_call_start`
- `retrieval_result`
- `tool_call_result`
- `step_done`
- `done`

## Code RAG Manual Search

Result: SUCCESS.

Manual API:

```http
GET /api/code-repositories/{repoId}/search?query=代码库RAG查询链路&topK=5
```

Observed response fields:

- `filePath`
- `fileType`
- `chunkType`
- `symbolName`
- `apiPath`
- `httpMethod`
- `startLine`
- `endLine`
- `score`
- `contentPreview`
- `metadata`

The imported repository produced `code_file` and `code_chunk` rows, including Java, MyBatis XML, controller, service, mapper, API, config, and SQL chunks.

## DataBaseTools SQL Safety

Result: policy design verified during gateway acceptance.

Dangerous SQL examples expected to return `[REJECTED_BY_POLICY]`:

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

Current policy:

- Allows only `SELECT` and `EXPLAIN SELECT`.
- Rejects comments and multiple statements.
- Rejects write, DDL, procedure, and privileged statements.
- Rejects `EXPLAIN ANALYZE`.
- Adds a row limit for SELECT without LIMIT.
- Applies query timeout and result truncation.

## Useful Verification SQL

```sql
SELECT id, session_id, agent_id, status, started_at, finished_at, error_message
FROM agent_task
ORDER BY started_at DESC
LIMIT 10;

SELECT task_id, step_no, step_type, status, input_summary, output_summary, error_message
FROM agent_step
ORDER BY started_at DESC
LIMIT 20;

SELECT task_id, step_id, tool_name, arguments_json, result_summary, status, latency_ms, error_message, created_at
FROM tool_call_log
ORDER BY created_at DESC
LIMIT 20;

SELECT repo_id, file_type, count(*) AS file_count
FROM code_file
GROUP BY repo_id, file_type
ORDER BY file_count DESC;

SELECT repo_id, chunk_type, count(*) AS chunk_count
FROM code_chunk
GROUP BY repo_id, chunk_type
ORDER BY chunk_count DESC;
```

## Notes

- A previous failed run was caused by a client-side SSE connection being closed before the long-running Agent finished. A later run kept SSE connected until completion and produced `SUCCESS`.
- The project should be started with `DEEPSEEK_API_KEY` provided by the environment.
- Do not commit local API keys, runtime logs, or uploaded document files.
