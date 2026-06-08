# 工具元数据事实来源盘点

盘点时间：2026-06-08

当前工具元数据分布在三处：

1. `Tool` Bean：`getName()`、`getDescription()`、`getType()`。
2. Spring AI `@Tool`：实际暴露给模型的方法名和描述。
3. `InMemoryToolRegistry`：运行时启用状态、权限、兼容别名和全局结果长度兜底。

## 当前工具

| Tool Bean | 方法 | `@Tool` name | `Tool.getName()` | 类型 | Registry alias | enabled / allowInAgent | max result length |
| --- | --- | --- | --- | --- | --- | --- | ---: |
| `KnowledgeTools` | `knowledgeQuery` | `knowledgeQuery` | `knowledgeQuery` | `FIXED` | `KnowledgeTool` | `true / true` | 6000 |
| `CodeSearchTools` | `searchProjectCode` | `searchProjectCode` | `searchProjectCode` | `OPTIONAL` | 无 | `true / true` | 7000 |
| `DataBaseTools` | `query` | `databaseQuery` | `databaseQuery` | `OPTIONAL` | `dataBaseTool` | `true / true` | 4000 |
| `TerminateTool` | `terminate` | `terminate` | `terminate` | `FIXED` | 无 | `true / true` | 1000 |

## 描述对照

### `KnowledgeTools`

- `Tool.getDescription()`：用于从知识库执行语义检索，输入知识库 ID 和查询文本。
- `@Tool description`：说明参数 `kbsId`、`query`，并返回最相关知识片段。

### `CodeSearchTools`

- `Tool.getDescription()`：搜索已导入的 Java 后端代码片段，并明确它不是精确静态调用图。
- `@Tool description`：说明按 `repoId` 和自然语言问题检索，并返回代码证据、路径、行号、symbol、API path 和分数。

### `DataBaseTools`

- `Tool.getDescription()`：只读 PostgreSQL 查询，仅允许安全单条 `SELECT`，并约束适用场景。
- `@Tool description`：补充 PostgreSQL 元数据查询方式、禁止 MySQL 专用语法，并要求代码链路分析优先使用 `searchProjectCode`。

### `TerminateTool`

- `Tool.getDescription()`：结束当前 Agent 任务，仅在任务已经完成时调用。
- `@Tool description`：任务全部完成后调用。

盘点结果：

- 所有生产 `Tool` Bean 均存在 Registry 注册。
- Registry 中没有注册不存在的工具。
- `@Tool` name 与 `Tool.getName()` 一致。
- `KnowledgeTool` 和 `dataBaseTool` 是历史兼容 alias，不应直接删除。
- 描述文本存在不同详细程度，但没有语义冲突；第三轮可考虑收敛为单一事实来源。

## 数据库工具截断边界

`DataBaseTools` 负责数据库查询自身的安全边界：

- `SqlSafetyValidator` 只允许单条安全 `SELECT`，并限制 `LIMIT`。
- `JdbcTemplate` 设置 `queryTimeout`、`maxRows` 和 `fetchSize`。
- `maxCellChars` 限制单个单元格，避免异常大字段撑爆结果。
- `maxResultLength` 限制格式化后的数据库表格文本。

`ToolRegistry` 和 `ToolExecutionService` 负责所有工具统一的全局兜底：

- 按 Registry 中的 `maxResultLength` 截断工具返回。
- 将截断后的摘要写入工具调用日志和 SSE 工具结果事件。

数据库工具自身裁剪与全局兜底是两层防护。本轮仅将内部方法命名明确为
`truncateFormattedResult`，没有删除任何数据库安全限制。

## 后续收敛建议

第三轮可以考虑由 Tool Bean 提供基础名称和描述，Registry 仅维护：

- enabled / allowInAgent
- alias
- max result length

在完成自动一致性校验前，不建议删除当前 Registry。
