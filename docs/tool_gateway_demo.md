# Tool Gateway Demo

This document describes the phase 3 tool gateway implementation. It is a lightweight engineering layer over Spring AI's existing `ToolCallingManager`; it does not introduce MCP, replace Spring AI tool execution, or change the existing Agent orchestration model.

## 1. Design Goals

The tool gateway upgrades scattered tool execution into a consistent backend capability:

- Centralized tool metadata registration
- Unified allow-list and alias resolution
- Unified pre-execution permission checks
- Unified `tool_call_start` / `tool_call_result` SSE events
- Unified `tool_call_log` persistence
- Unified latency measurement
- Unified argument and result truncation
- Safer database tool execution
- A clear extension point for future MCP integration

The current implementation keeps `JChatMind` small: it still uses Spring AI `ToolCallingManager`, but delegates tool lifecycle handling to `ToolExecutionService`.

## 2. ToolRegistry Metadata

`ToolRegistry` is implemented as an in-memory registry in the first version. No `tool_registry` database table is introduced.

Each tool is described by `ToolDefinition`:

| Field | Meaning |
| --- | --- |
| `toolName` | Canonical internal tool name |
| `displayName` | Human-readable display name |
| `description` | Tool capability description |
| `category` | Tool category, such as `KNOWLEDGE`, `CODE_SEARCH`, `DATABASE`, `FILESYSTEM`, `UTILITY`, `COMMUNICATION` |
| `permissionLevel` | Risk level, such as `SAFE_READ`, `SENSITIVE_READ`, `WRITE`, `DANGEROUS` |
| `enabled` | Whether the tool is enabled at all |
| `timeoutMs` | Expected timeout metadata for the tool |
| `maxResultLength` | Maximum result length stored or returned by gateway policy |
| `allowInAgent` | Whether the tool can be exposed to Agent runtime |
| `riskDescription` | Short explanation of tool risk |
| `aliases` | Compatible names used by Bean name, `Tool.getName()`, or `@Tool(name=...)` |

Important aliases:

| Canonical Tool | Aliases |
| --- | --- |
| `KnowledgeTool` | `KnowledgeTool`, `knowledgeQuery` |
| `searchProjectCode` | `searchProjectCode` |
| `dataBaseTool` | `dataBaseTool`, `databaseQuery` |
| `terminate` | `terminate` |
| `emailTool` | `emailTool`, `sendEmail` |
| `fileSystemTool` | `fileSystemTool`, `readFile`, `writeFile`, `appendToFile`, `listFiles`, `deleteFile`, `createDirectory` |

`JChatMindFactory` resolves tool names through the registry, so Agent configuration can match either the local tool name or the model-visible `@Tool` name.

## 3. ToolExecutionService Flow

`ToolExecutionService` is the single place that writes `tool_call_log`.

Before tool execution:

1. Resolve the actual model tool name to a canonical tool name through `ToolRegistry`.
2. Check whether the tool exists.
3. Check `enabled=true`.
4. Check `allowInAgent=true`.
5. Check whether the tool is included in the current Agent runtime tools.
6. Create `tool_call_log` with status `RUNNING`.
7. Send `tool_call_start` SSE event.
8. Store a `ToolExecutionRecord` with:
   - tool call id, when available from Spring AI
   - actual tool name
   - canonical tool name
   - `tool_call_log.id`
   - start timestamp

After successful execution:

1. Match tool response to the preflight record by response order.
2. Truncate the result according to `ToolRegistry.maxResultLength`.
3. Update `tool_call_log` to `SUCCESS`.
4. Write `latency_ms`.
5. Send `tool_call_result` SSE event.

After failed execution:

1. Update `tool_call_log` to `FAILED`.
2. Write truncated error message.
3. Write `latency_ms`.
4. Send `tool_call_result` SSE event with failure status.
5. Let `JChatMind` continue its existing exception flow and mark the Agent step/task failed.

Note: Spring AI `ToolCall.id` is stored when available. Current tool responses are matched by return order because the response object is not relied on for stable id matching.

## 4. DataBaseTools SQL Safety Policy

`DataBaseTools` is a read-only PostgreSQL tool. Dangerous input is rejected as a normal tool result instead of throwing a system exception.

Rejected SQL returns:

```text
[REJECTED_BY_POLICY] rejected=true reason=...
```

This means dangerous SQL can be recorded as a successful policy rejection in `tool_call_log`, while real database/system failures still fail the tool call.

Implemented checks:

- Trim SQL.
- Remove one trailing semicolon if it is the only semicolon.
- Lowercase and compress whitespace for policy matching.
- Reject comments:
  - `--`
  - `/*`
  - `*/`
- Reject multiple statements.
- Reject dangerous keywords:
  - `INSERT`
  - `UPDATE`
  - `DELETE`
  - `DROP`
  - `ALTER`
  - `TRUNCATE`
  - `CREATE`
  - `GRANT`
  - `REVOKE`
  - `COPY`
  - `CALL`
  - `DO`
- Reject `EXPLAIN ANALYZE`.
- Allow only:
  - `SELECT`
  - `EXPLAIN SELECT`
- Add `LIMIT 50` around `SELECT` queries that do not already contain `LIMIT`.
- Do not wrap `EXPLAIN SELECT`.
- Set JDBC query timeout to 5 seconds.
- Limit returned rows to 50.
- Limit returned text to 4000 characters.

## 5. CodeSearchTools and KnowledgeTools Result Limits

`CodeSearchTools` and `KnowledgeTools` continue to send `retrieval_result` SSE events after retrieval.

`CodeSearchTools`:

- `searchProjectCode(repoId, query, topK)` clamps `topK` to a maximum of 10.
- If the requested `topK` is larger than 10, the returned text explains that it was clamped.
- Returns code snippets only, not full files.
- The final result text is truncated by `ToolRegistry.maxResultLength`.

`KnowledgeTools`:

- Uses structured RAG results.
- Formats source information for the model.
- The final result text is truncated by `ToolRegistry.maxResultLength`.

## 6. Why FileSystemTools Is Disabled

`FileSystemTools` contains risky operations:

- read file
- write file
- append file
- list directory
- delete file
- create directory

For the current project phase, filesystem tools are not exposed to Agent runtime:

- `FileSystemTools` does not have active `@Component`.
- It is not added to fixed tools.
- It is registered in `ToolRegistry` as:
  - `enabled=false`
  - `allowInAgent=false`
  - `permissionLevel=DANGEROUS`

If filesystem capability is needed later, it should be implemented as a separate `ReadOnlyFileTools` with:

- configured allowed roots
- path normalization
- `startsWith` root checks
- file size limits
- text-only reads
- no write/delete/move operations

## 7. SSE Events and tool_call_log

The gateway connects runtime observability with persisted logs.

Tool call lifecycle:

1. Agent decides to call a tool.
2. `ToolExecutionService.beforeToolCall(...)` creates `tool_call_log`.
3. Backend sends:

```json
{
  "type": "tool_call_start",
  "payload": {
    "stepId": "...",
    "toolCallLogId": "...",
    "toolCallId": "...",
    "toolName": "searchProjectCode",
    "actualToolName": "searchProjectCode",
    "argumentsPreview": "..."
  }
}
```

4. Spring AI `ToolCallingManager` executes the tool.
5. `ToolExecutionService.afterToolSuccess(...)` or `afterToolFailure(...)` updates `tool_call_log`.
6. Backend sends:

```json
{
  "type": "tool_call_result",
  "payload": {
    "toolCallLogId": "...",
    "toolCallId": "...",
    "toolName": "searchProjectCode",
    "actualToolName": "searchProjectCode",
    "status": "SUCCESS",
    "resultSummary": "...",
    "latencyMs": 123
  }
}
```

Retrieval tools also send:

```json
{
  "type": "retrieval_result",
  "payload": {
    "stepNo": 2,
    "stepId": "...",
    "query": "...",
    "results": []
  }
}
```

Useful verification SQL:

```sql
SELECT id, task_id, step_id, tool_name, status, latency_ms, created_at
FROM tool_call_log
ORDER BY created_at DESC
LIMIT 20;
```

## 8. Dangerous SQL Acceptance Tests

The following inputs should be rejected by policy and return `[REJECTED_BY_POLICY]`.

```sql
DELETE FROM users
```

```sql
UPDATE users SET name = 'x'
```

```sql
SELECT * FROM a; DROP TABLE b
```

```sql
SELECT * FROM users -- comment
```

```sql
EXPLAIN ANALYZE SELECT * FROM users
```

Expected behavior:

- Tool result contains `[REJECTED_BY_POLICY]`.
- Agent task should not fail just because the model attempted unsafe SQL.
- `tool_call_log.result_summary` should contain the rejection text.
- Real database connection or execution exceptions should still be recorded as `FAILED`.

## 9. Known Limits

Current limits are intentional for this phase:

- No MCP integration yet.
- Tool registry is Java in-memory, not database-driven.
- Tool timeout metadata is registered, but only `DataBaseTools` currently enforces JDBC query timeout directly.
- Tool responses are matched to records by return order after execution.
- `FileSystemTools` remains disabled.
- Code RAG is still semantic retrieval over imported code chunks, not a precise static call graph.
- SQL safety checks are conservative string/rule checks, not a full SQL parser.
- Email tool remains optionally available and should be enabled only when the Agent is explicitly configured to use it.

## 10. Future MCP Integration Direction

The gateway is designed as a preparation layer for MCP, but MCP is not part of this phase.

Future MCP integration can reuse the current concepts:

- Map MCP tool name to `ToolDefinition`.
- Use `ToolRegistry` for aliases, permission level, enablement, and result length.
- Route MCP tool invocation through `ToolExecutionService`.
- Keep `tool_call_log` as the unified persistence layer for both local tools and MCP tools.
- Keep SSE protocol unchanged:
  - `tool_call_start`
  - `tool_call_result`
  - `error`
  - `done`
- Add transport-specific metadata later, such as:
  - `mcpServerName`
  - `transport`
  - `resourceUri`
  - `promptName`

Recommended future path:

1. Keep local tools working through the current gateway.
2. Add an MCP client adapter that converts MCP tool metadata into `ToolDefinition`.
3. Add MCP invocation as another executor behind `ToolExecutionService`.
4. Add stricter permission confirmation for `WRITE` and `DANGEROUS` tools.

