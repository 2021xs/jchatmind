# 低风险工程改进记录

更新时间：2026-06-08

本文记录 `fix/low-risk-engineering` 分支已落地的主要工程改进。所有改动均保持 Agent 主循环、Agent 状态机、SSE 协议、数据库结构和 Code RAG 对外入口不变。

## 已完成改进

### 请求状态与异步执行

- 移除 `JChatMindFactory` 中的请求级共享状态，Spring 单例 Bean 不再保存单次 Agent 运行配置。
- 将 Code evidence selector executor 收敛为 Spring 管理的独立线程池，并保留失败时回退原始候选的行为。
- 明确 Agent、Code evidence selector 和飞书相关异步任务的 executor 使用与线程命名。
- ChatEvent 改为事务提交后异步消费，避免异步线程在用户消息事务提交前读取历史消息。
- 增加同一 `chatSessionId` 重复 Agent 启动的 best-effort 防护，不影响不同会话并发。

### 工具体系与安全边界

- 清理生产源码中的历史 Agent 示例，`JChatMindV1/V2` 保留在测试目录。
- `TestController` 使用 `dev` Profile 限制，不在默认生产 Profile 暴露。
- 抽取 `PgVectorUtils`，统一知识库 RAG 与 Code RAG 的 pgvector 字符串格式化。
- 收敛工具基础元数据来源，`ToolRegistry` 保留 enabled、alias、结果长度和工具类型等运行时策略。
- 增加工具名、alias、policy、FIXED 工具和 `maxResultLength` 的注册校验。
- 保持 `DataBaseTools` 的 SQL 校验、只读数据源和工具自身裁剪边界不变。

### RAG 职责边界

- 新增 `EmbeddingService`，集中承载 Ollama-compatible Embedding API 调用。
- Code RAG、代码导入、文档导入和 embedding warmup 改为直接依赖 `EmbeddingService`。
- `RagService` 继续负责普通知识库检索，并保留 `embed()` 委托以兼容现有调用方。
- Code RAG 当前真实检索链路仍为 pgvector `RAW_VECTOR` candidates 加 LLM evidence selector。

## 关键提交

| Commit | 内容 |
| --- | --- |
| `9458f686` | 修复单例请求级状态并治理 selector executor |
| `09d12f47` | 清理示例代码并统一 pgvector helper |
| `fdd8cad0` | 收敛异步 executor 配置 |
| `f2b6c263` | ChatEvent 改为事务提交后消费 |
| `92f0ad2c` | 增加同会话 Agent 重复启动保护 |
| `611047b8` | 收敛工具元数据注册与启动校验 |
| `4624cc9a` | 从 RagService 拆出 EmbeddingService |

## 验证结果

最终收口执行：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
.\mvnw.cmd test
```

结果：174 个测试，0 失败，0 错误，2 个因外部环境条件跳过。

## 保留边界

- 同会话重复启动保护是 service 层 best-effort 防护，不是数据库级强一致保证。
- `RagService.embed()` 暂时保留为兼容委托。
- Embedding API 调用保持原有同步阻塞模式和现有模型配置。
- 未引入消息队列、分布式锁或数据库唯一约束。
- 未改变 Agent 主循环、SSE 协议、数据库结构、Code RAG 对外入口和检索结果格式。
