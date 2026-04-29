# Code RAG Demo

This document describes the reproducible demo flow for the minimal Code RAG loop.

## Scope

Current Code RAG is not a precise static call graph engine. It uses rule-based parsing, semantic retrieval, and metadata-assisted positioning to find related code snippets and possible related paths.

Known boundaries:

- Java parsing does not use a full AST.
- Complex inheritance, interface path composition, generics, overloaded methods, and dynamic SQL may be incomplete.
- Controller API path extraction is best-effort.
- MyBatis dynamic SQL is parsed as text and is not executed.
- Embedding depends on local Ollama `bge-m3`.
- The project never executes imported repository code and never modifies imported files.

## Environment

Required services:

- PostgreSQL with pgvector
- Ollama with `bge-m3`
- JDK 17 or newer

Start PostgreSQL and make sure the configured database exists:

```yaml
spring:
  datasource:
    url: ${POSTGRES_URL:jdbc:postgresql://localhost:5432/jchatmind}
    username: ${POSTGRES_USER:postgres}
    password: ${POSTGRES_PASSWORD:postgres}
  ai:
    deepseek:
      api-key: ${DEEPSEEK_API_KEY}
      base-url: ${DEEPSEEK_BASE_URL:https://rapi.asia/v1}
      chat:
        options:
          model: ${DEEPSEEK_MODEL:deepseek-v4-pro}
```

Start Ollama and prepare the embedding model:

```bash
ollama serve
ollama pull bge-m3
```

Build the project:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd -DskipTests package
```

Start the application:

```powershell
$env:DEEPSEEK_API_KEY='your-local-key'
.\mvnw.cmd spring-boot:run
```

## SQL Initialization

Both initialization files are required:

```sql
\i src/main/resources/db/init_agent_observability.sql
\i src/main/resources/db/init_code_rag.sql
```

`init_agent_observability.sql` creates:

- `agent_task`
- `agent_step`
- `tool_call_log`

`init_code_rag.sql` creates:

- `code_repository`
- `code_file`
- `code_chunk`

`code_chunk.embedding` is `vector(1024)`, matching the current local `bge-m3` embedding model.

Verify tables:

```sql
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'public'
  AND table_name IN (
    'agent_task',
    'agent_step',
    'tool_call_log',
    'code_repository',
    'code_file',
    'code_chunk'
  )
ORDER BY table_name;
```

Verify important columns:

```sql
SELECT table_name, column_name, data_type, udt_name
FROM information_schema.columns
WHERE table_schema = 'public'
  AND table_name IN ('code_repository', 'code_file', 'code_chunk', 'agent_task', 'agent_step', 'tool_call_log')
ORDER BY table_name, ordinal_position;
```

Verify pgvector dimension:

```sql
SELECT atttypmod
FROM pg_attribute
WHERE attrelid = 'code_chunk'::regclass
  AND attname = 'embedding';
```

For `vector(1024)`, pgvector stores typmod information for the embedding column.

## Code RAG Configuration

Configure allowed import roots in `src/main/resources/application.yaml`:

```yaml
jchatmind:
  code-rag:
    allowed-roots:
      - D:/Project
      - D:/workspace
    max-file-size-bytes: 1048576
    max-files-per-import: 2000
```

The first version keeps `allowed-roots` as a YAML list to avoid breaking Spring binding. Adjust these demo paths to match your local machine.

Security behavior:

- `rootPath` is converted to an absolute normalized path.
- `rootPath` must be under one configured `allowed-roots` path.
- Every scanned file is normalized and must start with the normalized repository root.
- Hidden directories and files are ignored.
- `.git`, `.idea`, `target`, `node_modules`, and `logs` are ignored.
- Files larger than `max-file-size-bytes` are skipped.
- The scan stops when `max-files-per-import` is reached and returns `truncated=true`.
- SQL files are parsed as text only and are never executed.

## Import A Repository

Use a real Spring Boot project under one allowed root, for example `D:/Project/seckill-demo`.

```http
POST /api/code-repositories/import
Content-Type: application/json

{
  "name": "seckill-demo",
  "rootPath": "D:/Project/seckill-demo"
}
```

Expected response:

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "repoId": "...",
    "fileCount": 123,
    "chunkCount": 256,
    "truncated": false,
    "message": "..."
  }
}
```

Verify repository data:

```sql
SELECT id, name, root_path, language, status, created_at, updated_at
FROM code_repository
ORDER BY created_at DESC;

SELECT repo_id, file_type, count(*) AS file_count
FROM code_file
GROUP BY repo_id, file_type
ORDER BY file_count DESC;

SELECT repo_id, chunk_type, count(*) AS chunk_count
FROM code_chunk
GROUP BY repo_id, chunk_type
ORDER BY chunk_count DESC;
```

Check whether Controller/API/MyBatis chunks were generated:

```sql
SELECT chunk_type, count(*)
FROM code_chunk
WHERE repo_id = 'YOUR_REPO_ID'
GROUP BY chunk_type
ORDER BY count(*) DESC;

SELECT cf.file_path, cc.symbol_name, cc.api_path, cc.http_method, cc.start_line, cc.end_line
FROM code_chunk cc
JOIN code_file cf ON cf.id = cc.file_id
WHERE cc.repo_id = 'YOUR_REPO_ID'
  AND cc.chunk_type IN ('CONTROLLER', 'API', 'MYBATIS_SQL')
ORDER BY cf.file_path, cc.start_line
LIMIT 50;
```

If API chunks are few, common causes are:

- Controllers do not use `@RestController` or `@Controller`.
- Mapping annotations are composed/custom annotations.
- API paths are inherited from interfaces or parent classes.
- Mapping annotations and method signatures are too far apart for the first-version rule parser.

## Manual Search

Call the search API:

```http
GET /api/code-repositories/YOUR_REPO_ID/search?query=秒杀接口链路&topK=5
```

Expected result fields:

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

Example SQL to inspect search candidates manually:

```sql
SELECT cf.file_path,
       cc.chunk_type,
       cc.symbol_name,
       cc.api_path,
       cc.http_method,
       cc.start_line,
       cc.end_line,
       left(cc.content, 300) AS preview
FROM code_chunk cc
JOIN code_file cf ON cf.id = cc.file_id
WHERE cc.repo_id = 'YOUR_REPO_ID'
ORDER BY cc.created_at DESC
LIMIT 20;
```

## Agent Tool Calling Demo

The code search tool name is:

```text
searchProjectCode
```

To enable it for an agent, include it in the agent `allowedTools` list:

```json
["searchProjectCode"]
```

Connect SSE before sending the message:

```http
GET /sse/connect/YOUR_CHAT_SESSION_ID
```

Ask the agent:

```http
POST /api/chat-messages
Content-Type: application/json

{
  "agentId": "YOUR_AGENT_ID",
  "sessionId": "YOUR_CHAT_SESSION_ID",
  "role": "USER",
  "content": "请基于 repoId=YOUR_REPO_ID 检索：这个项目的秒杀接口链路是什么？"
}
```

Expected SSE events:

- `message_start`
- `tool_call_start`
- `retrieval_result`
- `tool_call_result`
- `step_done`
- `done`

Expected DB logs:

```sql
SELECT id, session_id, agent_id, status, started_at, finished_at, error_message
FROM agent_task
ORDER BY started_at DESC
LIMIT 5;

SELECT task_id, step_no, step_type, status, input_summary, output_summary, error_message
FROM agent_step
ORDER BY started_at DESC
LIMIT 20;

SELECT task_id, step_id, tool_name, arguments_json, status, latency_ms, error_message, created_at
FROM tool_call_log
ORDER BY created_at DESC
LIMIT 20;
```

The `tool_name` should be `searchProjectCode` when the agent calls the code search tool.

## Duplicate Import Check

Import the same `rootPath` twice:

```http
POST /api/code-repositories/import
Content-Type: application/json

{
  "name": "seckill-demo",
  "rootPath": "D:/Project/seckill-demo"
}
```

Verify chunks are rebuilt rather than duplicated:

```sql
SELECT id, name, root_path, status, updated_at
FROM code_repository
WHERE name = 'seckill-demo' OR root_path = 'D:/Project/seckill-demo'
ORDER BY updated_at DESC;

SELECT repo_id, count(*) AS file_count
FROM code_file
WHERE repo_id = 'YOUR_REPO_ID'
GROUP BY repo_id;

SELECT repo_id, count(*) AS chunk_count
FROM code_chunk
WHERE repo_id = 'YOUR_REPO_ID'
GROUP BY repo_id;

SELECT repo_id, file_path, count(*) AS duplicate_file_rows
FROM code_file
WHERE repo_id = 'YOUR_REPO_ID'
GROUP BY repo_id, file_path
HAVING count(*) > 1;
```

The duplicate query should return no rows.

## Common Problems

### `rootPath 不在允许扫描目录内`

Add the parent directory to:

```yaml
jchatmind:
  code-rag:
    allowed-roots:
      - D:/Project
```

Then restart the application.

### `relation "code_repository" does not exist`

Run:

```sql
\i src/main/resources/db/init_code_rag.sql
```

### `relation "agent_task" does not exist`

Run:

```sql
\i src/main/resources/db/init_agent_observability.sql
```

### Embedding request fails

Make sure Ollama is running and `bge-m3` is available:

```bash
ollama serve
ollama pull bge-m3
```

### Agent does not call `searchProjectCode`

Check:

- Agent `allowedTools` contains `"searchProjectCode"`.
- The prompt includes the `repoId`.
- The user question clearly asks to search/imported project code.
