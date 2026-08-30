# MCP Client / Host V1

## Failure Semantics

MCP Completion V1 keeps failure semantics inside the existing unified tool runtime:

- MCP discovery failure is isolated per enabled server. The server is unavailable for the current build and exposes zero tools; local tools and other healthy MCP servers remain available.
- MCP invocation exceptions are raised as `MCP_TOOL_CALL_FAILED`. MCP audit and unified `ToolCallLog`/SSE failure events use the same failure type. Remote exception details stay in internal server logs; the Agent-facing message is compact and safe.
- Runtime timeout remains `TOOL_TIMEOUT`; it is not converted to `MCP_TOOL_CALL_FAILED`.
- Duplicate detection and risk/allow-list preflight keep their existing `DUPLICATE_TOOL_CALL` and policy-rejection semantics. They do not execute `callTool` and do not create MCP invocation audit events.

Verified transport for this project: `STDIO`. Spring AI framework support for other transports is not project-level verification. There is no retry or automatic MCP server recovery.

## 范围

当前实现把 JChatMind 作为 MCP Host / Client 接入外部 MCP Server 提供的 tools、resources、prompts。

本轮不实现 JChatMind MCP Server Gateway，也不把本地 `CodeSearchTools`、`DataBaseTools`、`KnowledgeTools` 暴露成 MCP Server。现有 Agent REST API、SSE 协议和本地 `ToolRegistry` 保持不变。

## 默认安全

MCP Client 默认关闭。`application-mcp.yaml` 中 Spring AI MCP Client 和 JChatMind MCP Client 都需要显式环境变量启用：

```yaml
spring:
  ai:
    mcp:
      client:
        enabled: false
        initialized: false

jchatmind:
  mcp:
    client:
      enabled: false
      servers: []
```

示例 DOCS / BROWSER / GITHUB server 只作为注释示例存在，并且 server 默认 disabled。不要提交 token、密钥、本地绝对路径、私有 URL 或个人配置。

V1 只允许显式配置的 `DOCS`、`GITHUB`、`BROWSER` 类型。`FILESYSTEM`、`DATABASE`、`SHELL` 和写操作能力不默认接入，也不会根据 server name、url、command、tool/resource/prompt description 做安全推断。

## 配置模型

配置前缀是 `jchatmind.mcp.client`。

```yaml
jchatmind:
  mcp:
    client:
      enabled: true
      max-result-length: 6000
      audit-enabled: true
      servers:
        - name: context7-docs
          type: DOCS
          transport: stdio
          command: ${JCHATMIND_MCP_DOCS_COMMAND:}
          enabled: false
          allowed-tools:
            - name: resolve-library-id
              risk-level: NETWORK_READ
              auto-invoke-allowed: true
          allowed-resources:
            - uri: context7://library/readme
              risk-level: READ_ONLY
              auto-attach-allowed: false
          allowed-prompts:
            - name: explain-library
              risk-level: READ_ONLY
              auto-attach-allowed: false
```

授权只来自显式配置：

- `server.enabled=true`
- `server.type`
- `allowed-tools` / `allowed-resources` / `allowed-prompts` 精确白名单
- `risk-level`
- `auto-invoke-allowed` / `auto-attach-allowed`
- registry 注册状态
- policy 判断

未配置 `risk-level` 的 capability 默认视为 `DANGEROUS`。discovery 得到的 name、uri、description、schema 只用于注册和展示，不参与安全等级推断。

## Tools 接入 Agent

Agent 构建时，`JChatMindFactory` 会合并两类工具：

- 本地 `ToolRegistry` 允许暴露的 fixed / optional tools。
- `McpToolCallbackAdapter.toolCallbacks()` 生成的外部 MCP `ToolCallback`。

外部 MCP tool 只来自 `McpToolCallbackAdapter.exposedToolNames()`，并加入当前 Agent runtime tool name 白名单。执行前 `ToolExecutionServiceImpl` 仍会做 preflight：

- tool 已注册。
- tool 来自 enabled server。
- tool 在 `allowed-tools` 精确白名单中。
- `risk-level` 显式为 `READ_ONLY` 或 `NETWORK_READ`。
- `auto-invoke-allowed=true`。
- policy 通过。
- tool name 必须存在于当前 Agent runtime tool list。

外部 MCP tool callback 会把经过远端 provider / transport contract 的完整结果交给统一 Tool Runtime：先持久化 canonical ToolResponse，再由全局 model-view guard 控制 Agent Context。MCP audit 与 `tool_call_log` summary 仍独立保持 bounded。`max-result-length` 继续用于下方受控 resource / prompt access，不再截断 Agent tool callback。外部调用异常会以 `MCP_TOOL_CALL_FAILED` typed failure 进入统一 Tool Runtime；不会伪装成普通成功结果，也不会让 callback 异常直接泄漏给用户。

## Resources

resources 通过 `ExternalMcpResourceRegistry` 注册，读取通过 `ExternalMcpResourceAccessService` 受控执行。

规则：

- resource 必须出现在 `allowed-resources` 精确白名单中。
- 第一版只允许 `READ_ONLY` resource 被读取。
- 未配置 `risk-level` 默认 `DANGEROUS`，拒绝读取。
- description 中出现 `safe`、`read-only` 不会改变风险等级。
- 内容读取经过 `max-result-length` 截断。
- allowed / denied / failure 都写 audit。
- resource 不会自动注入 Agent prompt，除非后续调用方显式使用 `auto-attach-allowed=true` 的注册结果。
- 外部 resource 不可用不会影响本地 Agent 启动。

## Prompts

prompts 通过 `ExternalMcpPromptRegistry` 注册，获取通过 `ExternalMcpPromptAccessService` 受控执行。

规则：

- prompt 必须出现在 `allowed-prompts` 精确白名单中。
- 第一版只允许 `READ_ONLY` prompt 使用。
- 未配置 `risk-level` 默认 `DANGEROUS`，拒绝使用。
- description 中出现 `safe` 不会改变风险等级。
- prompt 不会自动覆盖系统提示词。
- prompt 只能作为用户显式选择或配置允许的模板被获取。
- prompt required arguments 会做基础缺失校验。
- audit 只记录参数名，不记录敏感参数全文。
- 外部 prompt 不可用不会影响本地 Agent 启动。

## 测试命令

编译：

```powershell
$env:JAVA_HOME='<path-to-jdk-17-or-newer>'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
.\mvnw.cmd -DskipTests compile
```

focused MCP / Agent tests：

```powershell
.\mvnw.cmd "-Dtest=ExternalMcpRegistryAndPolicyTest,McpResourcePromptAccessTest,McpToolCallbackAdapterTest,McpClientIntegrationConfigTest,McpFakeEndToEndIntegrationTest,McpLocalToolRegistryIsolationTest,JChatMindFactoryMcpToolIntegrationTest,JChatMindFactoryStateTest,ToolExecutionServiceImplTest,InMemoryToolRegistryTest,ToolSafetyPolicyTest" test
```

真实 DOCS / BROWSER / GITHUB MCP Server 手动测试保留在：

- `ExternalMcpDocsRealServerManualIntegrationTest`
- `ExternalMcpBrowserRealServerManualIntegrationTest`
- `ExternalMcpGithubRealServerManualIntegrationTest`

这些测试默认跳过，只有显式环境变量启用时才运行。真实联调结果必须来自实际执行，不能伪造；需要 token 的 server 只能从环境变量读取 token。

## 当前限制

- 当前完整范围是 MCP Client / Host V1。
- 不包含 JChatMind MCP Server Gateway。
- resources/prompts 已有受控 registry 与 access service，但不会默认注入 Agent 系统提示词。
- 不默认启用 filesystem、database、shell 或写操作 MCP 能力。
