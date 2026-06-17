import { useEffect, useMemo, useState, type ReactNode } from "react";
import {
  Alert,
  Badge,
  Button,
  Empty,
  Input,
  List,
  Segmented,
  Select,
  Space,
  Spin,
  Tag,
  Tooltip,
  Typography,
  message,
} from "antd";
import {
  AuditOutlined,
  CodeOutlined,
  CommentOutlined,
  PlusOutlined,
  ReloadOutlined,
  SendOutlined,
  ThunderboltOutlined,
} from "@ant-design/icons";
import {
  createChatSession,
  createSseConnection,
  getAgentTraces,
  getAgents,
  getChatMessages,
  getChatSessions,
  getRepositories,
  sendChatMessage,
} from "./api/client";
import type {
  Agent,
  AgentSseEvent,
  AgentTaskTrace,
  ChatMessage,
  ChatSession,
  CodeRepository,
  LegacySseMessage,
  LoadState,
  ToolCallTrace,
} from "./types";

type ViewKey = "repos" | "conversations" | "chat" | "trace";

interface RuntimeState {
  repositories: CodeRepository[];
  agents: Agent[];
  sessions: ChatSession[];
  messages: ChatMessage[];
  traces: AgentTaskTrace[];
  sseEvents: AgentSseEvent[];
  selectedRepoId?: string;
  selectedSessionId?: string;
  selectedAgentId?: string;
  view: ViewKey;
  loadState: LoadState;
  error?: string;
  sending: boolean;
}

const defaultState: RuntimeState = {
  repositories: [],
  agents: [],
  sessions: [],
  messages: [],
  traces: [],
  sseEvents: [],
  view: "chat",
  loadState: "idle",
  sending: false,
};

const viewOptions = [
  { label: "项目", value: "repos", icon: <CodeOutlined /> },
  { label: "会话", value: "conversations", icon: <CommentOutlined /> },
  { label: "Chat", value: "chat", icon: <ThunderboltOutlined /> },
  { label: "Trace", value: "trace", icon: <AuditOutlined /> },
];

function App() {
  const [state, setState] = useState<RuntimeState>(defaultState);
  const [draft, setDraft] = useState("");
  const [noticeApi, contextHolder] = message.useMessage();

  const selectedRepo = useMemo(
    () => state.repositories.find((repo) => repo.id === state.selectedRepoId),
    [state.repositories, state.selectedRepoId],
  );

  const selectedSession = useMemo(
    () =>
      state.sessions.find((session) => session.id === state.selectedSessionId),
    [state.sessions, state.selectedSessionId],
  );

  const selectedAgent = useMemo(
    () => state.agents.find((agent) => agent.id === state.selectedAgentId),
    [state.agents, state.selectedAgentId],
  );

  useEffect(() => {
    refreshConsole();
  }, []);

  useEffect(() => {
    if (!state.selectedSessionId) {
      setState((previous) => ({ ...previous, messages: [], traces: [] }));
      return;
    }

    const activeSessionId = state.selectedSessionId;
    void refreshSessionData(activeSessionId);
    const source = createSseConnection(activeSessionId);

    const appendAgentEvent = (event: Event) => {
      const parsed = parseSsePayload(eventData(event));
      if (!parsed) {
        return;
      }
      setState((previous) => ({
        ...previous,
        sseEvents: [parsed, ...previous.sseEvents].slice(0, 80),
      }));
      if (parsed.type === "done" || parsed.type === "error") {
        void refreshSessionData(activeSessionId);
      }
    };

    const appendLegacyMessage = (event: Event) => {
      const parsed = parseLegacyMessage(eventData(event));
      const messagePayload = parsed?.payload?.message;
      if (!messagePayload) {
        return;
      }
      setState((previous) => ({
        ...previous,
        messages: upsertMessage(previous.messages, messagePayload),
      }));
    };

    source.addEventListener("message_start", appendAgentEvent);
    source.addEventListener("tool_call_start", appendAgentEvent);
    source.addEventListener("tool_call_result", appendAgentEvent);
    source.addEventListener("retrieval_result", appendAgentEvent);
    source.addEventListener("step_done", appendAgentEvent);
    source.addEventListener("done", appendAgentEvent);
    source.addEventListener("error", appendAgentEvent);
    source.addEventListener("message", appendLegacyMessage);
    source.onerror = () => {
      setState((previous) => ({
        ...previous,
        sseEvents: [
          {
            type: "error",
            timestamp: new Date().toISOString(),
            payload: { errorMessage: "SSE connection interrupted" },
          },
          ...previous.sseEvents,
        ].slice(0, 80),
      }));
    };

    return () => {
      source.close();
    };
  }, [state.selectedSessionId]);

  async function refreshConsole() {
    setState((previous) => ({
      ...previous,
      loadState: "loading",
      error: undefined,
    }));
    try {
      const [repositories, agents, sessions] = await Promise.all([
        getRepositories(),
        getAgents(),
        getChatSessions(),
      ]);
      setState((previous) => {
        const selectedSessionId =
          previous.selectedSessionId && hasId(sessions, previous.selectedSessionId)
            ? previous.selectedSessionId
            : sessions[0]?.id;
        const selectedAgentId =
          previous.selectedAgentId && hasId(agents, previous.selectedAgentId)
            ? previous.selectedAgentId
            : sessions.find((session) => session.id === selectedSessionId)
                ?.agentId ?? agents[0]?.id;
        return {
          ...previous,
          repositories,
          agents,
          sessions,
          selectedRepoId:
            previous.selectedRepoId && hasId(repositories, previous.selectedRepoId)
              ? previous.selectedRepoId
              : repositories[0]?.id,
          selectedSessionId,
          selectedAgentId,
          loadState: "ready",
        };
      });
    } catch (error) {
      setState((previous) => ({
        ...previous,
        loadState: "error",
        error: errorMessage(error),
      }));
    }
  }

  async function refreshSessionData(sessionId: string) {
    try {
      const [messages, traces] = await Promise.all([
        getChatMessages(sessionId),
        getAgentTraces(sessionId),
      ]);
      setState((previous) => ({ ...previous, messages, traces }));
    } catch (error) {
      setState((previous) => ({ ...previous, error: errorMessage(error) }));
    }
  }

  async function handleCreateSession() {
    const agentId = state.selectedAgentId ?? state.agents[0]?.id;
    if (!agentId) {
      noticeApi.warning("需要先有可用 Agent，才能创建会话");
      return;
    }
    try {
      const sessionId = await createChatSession(agentId, "Web Console 会话");
      const sessions = await getChatSessions();
      setState((previous) => ({
        ...previous,
        sessions,
        selectedSessionId: sessionId,
        selectedAgentId: agentId,
        view: "chat",
      }));
    } catch (error) {
      noticeApi.error(errorMessage(error));
    }
  }

  async function handleSend() {
    const content = draft.trim();
    if (!content) {
      return;
    }
    const agentId = selectedSession?.agentId ?? state.selectedAgentId;
    if (!state.selectedSessionId || !agentId) {
      noticeApi.warning("请选择或创建会话");
      return;
    }
    setDraft("");
    setState((previous) => ({ ...previous, sending: true }));
    try {
      await sendChatMessage(state.selectedSessionId, agentId, content);
      await refreshSessionData(state.selectedSessionId);
    } catch (error) {
      noticeApi.error(errorMessage(error));
      setDraft(content);
    } finally {
      setState((previous) => ({ ...previous, sending: false }));
    }
  }

  return (
    <main className="console-shell">
      {contextHolder}
      <header className="console-header">
        <div>
          <Typography.Title level={3} className="console-title">
            JChatMind Web Console
          </Typography.Title>
          <Typography.Text type="secondary">
            Repo、会话、Agent Chat、Trace / Audit 的 Web 主入口
          </Typography.Text>
        </div>
        <Space wrap>
          <Tooltip title="重新加载项目、Agent、会话列表">
            <Button icon={<ReloadOutlined />} onClick={refreshConsole}>
              刷新
            </Button>
          </Tooltip>
          <Segmented
            options={viewOptions}
            value={state.view}
            onChange={(value) =>
              setState((previous) => ({
                ...previous,
                view: value as ViewKey,
              }))
            }
          />
        </Space>
      </header>

      {state.error ? (
        <Alert
          type="warning"
          showIcon
          closable
          message="后端接口暂不可用或返回失败"
          description={state.error}
          onClose={() =>
            setState((previous) => ({ ...previous, error: undefined }))
          }
        />
      ) : null}

      <section className="context-bar">
        <Select
          className="context-select"
          placeholder="选择当前 repo"
          value={state.selectedRepoId}
          options={state.repositories.map((repo) => ({
            value: repo.id,
            label: `${repo.name}${repo.status ? ` · ${repo.status}` : ""}`,
          }))}
          onChange={(selectedRepoId) =>
            setState((previous) => ({ ...previous, selectedRepoId }))
          }
          notFoundContent="暂无 repo"
        />
        <Select
          className="context-select"
          placeholder="选择 Agent"
          value={state.selectedAgentId}
          options={state.agents.map((agent) => ({
            value: agent.id,
            label: `${agent.name}${agent.model ? ` · ${agent.model}` : ""}`,
          }))}
          onChange={(selectedAgentId) =>
            setState((previous) => ({ ...previous, selectedAgentId }))
          }
          notFoundContent="暂无 Agent"
        />
        <Select
          className="context-select wide"
          placeholder="选择会话"
          value={state.selectedSessionId}
          options={state.sessions.map((session) => ({
            value: session.id,
            label: session.title || session.id,
          }))}
          onChange={(selectedSessionId) => {
            const session = state.sessions.find(
              (item) => item.id === selectedSessionId,
            );
            setState((previous) => ({
              ...previous,
              selectedSessionId,
              selectedAgentId: session?.agentId ?? previous.selectedAgentId,
            }));
          }}
          notFoundContent="暂无会话"
        />
        <Button icon={<PlusOutlined />} onClick={handleCreateSession}>
          新建会话
        </Button>
      </section>

      <Spin spinning={state.loadState === "loading"}>
        <section className="console-grid">
          <aside
            className={`panel ${state.view === "repos" ? "panel-focus" : ""}`}
          >
            <RepositoryPanel
              repositories={state.repositories}
              selectedRepoId={state.selectedRepoId}
              onSelect={(selectedRepoId) =>
                setState((previous) => ({ ...previous, selectedRepoId }))
              }
            />
          </aside>
          <aside
            className={`panel ${
              state.view === "conversations" ? "panel-focus" : ""
            }`}
          >
            <ConversationPanel
              sessions={state.sessions}
              selectedSessionId={state.selectedSessionId}
              selectedRepo={selectedRepo}
              agents={state.agents}
              onCreate={handleCreateSession}
              onSelect={(selectedSessionId) => {
                const session = state.sessions.find(
                  (item) => item.id === selectedSessionId,
                );
                setState((previous) => ({
                  ...previous,
                  selectedSessionId,
                  selectedAgentId: session?.agentId ?? previous.selectedAgentId,
                }));
              }}
            />
          </aside>
          <section
            className={`panel chat-panel ${
              state.view === "chat" ? "panel-focus" : ""
            }`}
          >
            <ChatPanel
              repo={selectedRepo}
              session={selectedSession}
              agent={selectedAgent}
              messages={state.messages}
              draft={draft}
              sending={state.sending}
              onDraftChange={setDraft}
              onSend={handleSend}
            />
          </section>
          <aside
            className={`panel trace-panel ${
              state.view === "trace" ? "panel-focus" : ""
            }`}
          >
            <TracePanel
              traces={state.traces}
              events={state.sseEvents}
              sessionId={state.selectedSessionId}
            />
          </aside>
        </section>
      </Spin>
    </main>
  );
}

function RepositoryPanel({
  repositories,
  selectedRepoId,
  onSelect,
}: {
  repositories: CodeRepository[];
  selectedRepoId?: string;
  onSelect: (repoId: string) => void;
}) {
  return (
    <>
      <PanelHeader title="仓库 / 项目" badge={repositories.length} />
      {repositories.length === 0 ? (
        <Empty description="暂无代码仓库，等待后端 /api/code-repositories 返回数据" />
      ) : (
        <List
          dataSource={repositories}
          renderItem={(repo) => (
            <button
              className={`list-row ${repo.id === selectedRepoId ? "selected" : ""}`}
              onClick={() => onSelect(repo.id)}
              type="button"
            >
              <span className="row-title">{repo.name}</span>
              <span className="row-subtitle">{repo.language ?? "java"}</span>
              <Tag color={repo.status === "READY" ? "green" : "default"}>
                {repo.status ?? "UNKNOWN"}
              </Tag>
            </button>
          )}
        />
      )}
    </>
  );
}

function ConversationPanel({
  sessions,
  selectedSessionId,
  selectedRepo,
  agents,
  onCreate,
  onSelect,
}: {
  sessions: ChatSession[];
  selectedSessionId?: string;
  selectedRepo?: CodeRepository;
  agents: Agent[];
  onCreate: () => void;
  onSelect: (sessionId: string) => void;
}) {
  const agentName = (agentId: string) =>
    agents.find((agent) => agent.id === agentId)?.name ?? agentId;

  return (
    <>
      <PanelHeader
        title="会话列表"
        badge={sessions.length}
        action={
          <Button size="small" icon={<PlusOutlined />} onClick={onCreate}>
            新建
          </Button>
        }
      />
      <div className="binding-hint">
        当前 repo: {selectedRepo?.name ?? "未选择"}。后端会话模型暂无 repoId，
        这里保留绑定入口。
      </div>
      {sessions.length === 0 ? (
        <Empty description="暂无会话，可先选择 Agent 后新建" />
      ) : (
        <List
          dataSource={sessions}
          renderItem={(session) => (
            <button
              className={`list-row ${
                session.id === selectedSessionId ? "selected" : ""
              }`}
              onClick={() => onSelect(session.id)}
              type="button"
            >
              <span className="row-title">{session.title || "未命名会话"}</span>
              <span className="row-subtitle">Agent: {agentName(session.agentId)}</span>
            </button>
          )}
        />
      )}
    </>
  );
}

function ChatPanel({
  repo,
  session,
  agent,
  messages,
  draft,
  sending,
  onDraftChange,
  onSend,
}: {
  repo?: CodeRepository;
  session?: ChatSession;
  agent?: Agent;
  messages: ChatMessage[];
  draft: string;
  sending: boolean;
  onDraftChange: (value: string) => void;
  onSend: () => void;
}) {
  return (
    <>
      <PanelHeader
        title="Chat 对话"
        badge={messages.length}
        action={
          <Space size={6} wrap>
            <Tag>{repo?.name ?? "未选择 repo"}</Tag>
            <Tag color="blue">{session?.title ?? "未选择会话"}</Tag>
          </Space>
        }
      />
      <div className="chat-context">
        <span>Agent: {agent?.name ?? "未选择"}</span>
        <span>Conversation: {session?.id ?? "无"}</span>
      </div>
      <div className="message-list">
        {messages.length === 0 ? (
          <Empty description="暂无消息，输入问题后将通过现有 chat message + SSE 链路触发 Agent" />
        ) : (
          messages.map((item) => <MessageBubble key={item.id} message={item} />)
        )}
      </div>
      <div className="composer">
        <Input.TextArea
          value={draft}
          onChange={(event) => onDraftChange(event.target.value)}
          onPressEnter={(event) => {
            if (!event.shiftKey) {
              event.preventDefault();
              onSend();
            }
          }}
          autoSize={{ minRows: 2, maxRows: 6 }}
          placeholder="向当前 Agent 提问。Shift + Enter 换行。"
          disabled={!session || sending}
        />
        <Button
          type="primary"
          icon={<SendOutlined />}
          loading={sending}
          disabled={!session || !draft.trim()}
          onClick={onSend}
        >
          发送
        </Button>
      </div>
    </>
  );
}

function TracePanel({
  traces,
  events,
  sessionId,
}: {
  traces: AgentTaskTrace[];
  events: AgentSseEvent[];
  sessionId?: string;
}) {
  const toolCalls = traces.flatMap((trace) => trace.toolCalls ?? []);
  return (
    <>
      <PanelHeader title="Trace / Audit" badge={toolCalls.length + events.length} />
      {!sessionId ? (
        <Empty description="选择会话后展示 Agent run、tool calls 和 SSE 事件" />
      ) : (
        <div className="trace-body">
          <section>
            <Typography.Text strong>持久化 Agent Run</Typography.Text>
            {traces.length === 0 ? (
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description="暂无 agent_task/tool_call_log 数据"
              />
            ) : (
              traces.map((trace) => (
                <div className="trace-run" key={trace.id}>
                  <Space wrap>
                    <Tag color={statusColor(trace.status)}>{trace.status}</Tag>
                    <span>{trace.traceId ?? trace.id}</span>
                    <span>{formatLatency(trace.latencyMs)}</span>
                  </Space>
                  <div className="trace-summary">
                    {trace.finishReason ?? trace.goal ?? "agent run"}
                  </div>
                  <ToolCallList toolCalls={trace.toolCalls ?? []} />
                </div>
              ))
            )}
          </section>

          <section>
            <Typography.Text strong>实时 SSE 事件</Typography.Text>
            {events.length === 0 ? (
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description="暂无实时事件；连接建立后会显示 tool/start/result/done"
              />
            ) : (
              events.map((event, index) => (
                <div className="event-row" key={`${event.eventId ?? index}`}>
                  <Tag color={event.type === "error" ? "red" : "processing"}>
                    {event.type ?? "event"}
                  </Tag>
                  <span>{event.taskId ?? "no-task"}</span>
                  <pre>{summarizeEvent(event.payload)}</pre>
                </div>
              ))
            )}
          </section>
        </div>
      )}
    </>
  );
}

function ToolCallList({ toolCalls }: { toolCalls: ToolCallTrace[] }) {
  if (toolCalls.length === 0) {
    return <div className="empty-line">本次 run 未记录工具调用</div>;
  }
  return (
    <div className="tool-list">
      {toolCalls.map((call) => (
        <div className="tool-row" key={call.id}>
          <Space wrap>
            <Tag color={call.blockedByPolicy ? "red" : statusColor(call.status)}>
              {call.blockedByPolicy ? "denied" : call.status ?? "unknown"}
            </Tag>
            <Tag>{toolKind(call)}</Tag>
            <span className="tool-name">
              {call.actualToolName ?? call.toolName ?? "unknown_tool"}
            </span>
            <span>{formatLatency(call.latencyMs)}</span>
            {call.errorType ? <Tag color="volcano">{call.errorType}</Tag> : null}
          </Space>
          <div className="tool-summary">
            {call.errorMessage ?? call.resultSummary ?? "无结果摘要"}
          </div>
        </div>
      ))}
    </div>
  );
}

function MessageBubble({ message }: { message: ChatMessage }) {
  return (
    <div className={`message-bubble role-${message.role}`}>
      <div className="message-role">{message.role}</div>
      <div className="message-content">{message.content}</div>
    </div>
  );
}

function PanelHeader({
  title,
  badge,
  action,
}: {
  title: string;
  badge?: number;
  action?: ReactNode;
}) {
  return (
    <div className="panel-header">
      <Space>
        <Typography.Title level={5}>{title}</Typography.Title>
        {typeof badge === "number" ? <Badge count={badge} /> : null}
      </Space>
      {action}
    </div>
  );
}

function hasId<T extends { id: string }>(items: T[], id: string): boolean {
  return items.some((item) => item.id === id);
}

function upsertMessage(messages: ChatMessage[], incoming: ChatMessage) {
  if (messages.some((item) => item.id === incoming.id)) {
    return messages.map((item) => (item.id === incoming.id ? incoming : item));
  }
  return [...messages, incoming];
}

function parseSsePayload(data: string): AgentSseEvent | null {
  try {
    return JSON.parse(data) as AgentSseEvent;
  } catch {
    return null;
  }
}

function parseLegacyMessage(data: string): LegacySseMessage | null {
  try {
    return JSON.parse(data) as LegacySseMessage;
  } catch {
    return null;
  }
}

function eventData(event: Event): string {
  return "data" in event ? String((event as MessageEvent).data) : "";
}

function errorMessage(error: unknown): string {
  if (error instanceof Error) {
    return error.message;
  }
  return String(error);
}

function statusColor(status?: string) {
  if (status === "SUCCESS") {
    return "green";
  }
  if (status === "FAILED" || status === "CRASHED") {
    return "red";
  }
  if (status === "RUNNING") {
    return "blue";
  }
  return "default";
}

function toolKind(call: ToolCallTrace) {
  const name = call.actualToolName ?? call.toolName ?? "";
  return name.startsWith("mcp_") ? "MCP tool" : "local tool";
}

function formatLatency(value?: number) {
  return typeof value === "number" ? `${value} ms` : "latency n/a";
}

function summarizeEvent(payload?: Record<string, unknown>) {
  if (!payload) {
    return "";
  }
  return JSON.stringify(payload, null, 2).slice(0, 360);
}

export default App;
