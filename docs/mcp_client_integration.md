# MCP Client Integration 第一版

## 目标

本分支把 JChatMind 作为 MCP Host / MCP Client 接入外部 MCP Server。第一版只面向外部信息获取能力：

- DOCS：官方文档、框架文档检索。
- GITHUB：issue、PR、commit、branch、repo 元信息等只读查询。
- BROWSER：打开网页、读取网页正文、页面搜索等只读网页访问。

第一版不接入文件系统 MCP、数据库 MCP、shell 执行 MCP，也不把本项目本地工具暴露成 MCP Server。

## 默认关闭

MCP client 集成默认关闭：

```yaml
jchatmind:
  mcp:
    client:
      enabled: false
```

启用建议使用 `mcp` profile 或环境变量：

```powershell
$env:JCHATMIND_MCP_CLIENT_ENABLED="true"
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=mcp"
```

Spring AI MCP client auto configuration 也默认关闭，避免未配置时自动连接外部 server：

```yaml
spring:
  ai:
    mcp:
      client:
        enabled: false
        initialized: false
```

## 配置模型

配置前缀为 `jchatmind.mcp.client`。

```yaml
jchatmind:
  mcp:
    client:
      enabled: true
      max-result-length: 6000
      audit-enabled: true
      servers:
        - name: spring-docs
          type: DOCS
          transport: stdio
          command: "<command from local secure config>"
          enabled: true
          allowed-tools:
            - name: search_docs
              risk-level: READ_ONLY
              auto-invoke-allowed: true
            - name: fetch_doc
              risk-level: READ_ONLY
              auto-invoke-allowed: true
        - name: github-read
          type: GITHUB
          transport: http
          url: "https://example.invalid/mcp"
          enabled: true
          allowed-tools:
            - name: search_issues
              risk-level: NETWORK_READ
              auto-invoke-allowed: true
            - name: get_pull_request
              risk-level: NETWORK_READ
              auto-invoke-allowed: true
```

不要把 token、私有 URL、个人路径或本地 command 参数提交到仓库。真实 server command/url 应放在本机 `application-local.yaml` 或环境变量中。

## 白名单和风险分级

外部 server 必须显式配置且 `enabled=true` 才会被加载。第一版只支持：

- `DOCS`
- `GITHUB`
- `BROWSER`

以下类型会被拒绝：

- `FILESYSTEM`
- `DATABASE`
- `SHELL`

外部 tool 也必须在 `allowed-tools` 中出现才可能暴露给模型。风险等级包括：

- `READ_ONLY`
- `NETWORK_READ`
- `WRITE_OPERATION`
- `DANGEROUS`

策略：

- 所有外部 tool 的 `risk-level` 必须显式配置为 `READ_ONLY` 或 `NETWORK_READ`，并且 `auto-invoke-allowed=true` 才能暴露给模型。
- 未配置 `risk-level` 的工具默认按 `DANGEROUS`，即使 tool name、description、serverName、url 或 command 看起来像只读工具，也不会被自动放行。
- discovery 到的 tool name、description 和 schema 只用于展示与参数 schema，不用于安全等级推断。
- `WRITE_OPERATION` 和 `DANGEROUS` 第一版不会暴露给模型。

## Agent 接入方式

启用 MCP client 后，`McpToolCallbackAdapter` 会把通过白名单和风险策略的外部 MCP tools 转换为 Spring AI `ToolCallback`，追加到 Agent 可调用工具集合。

该实现不删除、不替换现有 `ToolRegistry`，也不修改 `CodeSearchTools`、`DataBaseTools`、`KnowledgeTools`。本地工具仍由原 `ToolRegistry` 管理；外部 MCP 工具使用 `mcp_<server>_<tool>` 形式命名，并且只在当前 Agent runtime tool list 中存在时允许执行记录通过。

## Audit

`McpToolAuditLogger` 会记录：

- traceId
- serverName
- serverType
- toolName
- riskLevel
- allowed
- arguments preview
- result summary
- latencyMs
- errorCode
- truncated

审计失败只写 debug 日志，不影响主流程。参数和结果只记录截断摘要，不记录全文敏感内容。

## 当前测试方式

第一版 focused tests 使用 fake discovery / fake invoker 验证 registry、policy、adapter、audit 逻辑，不要求本地启动真实 MCP Server。

真实联调建议：

1. 在本机安全配置中配置 DOCS/GITHUB/BROWSER 类型 MCP Server。
2. 只配置只读工具到 `allowed-tools`。
3. 启动 `mcp` profile。
4. 观察启动日志中可暴露工具数量。
5. 让 Agent 询问外部文档、GitHub issue 或网页内容，确认只读 MCP tool 被调用并产生 audit。

如果真实 MCP Server、网络、token 或外部服务不可用，应记录失败原因，不要伪造联调结果。
