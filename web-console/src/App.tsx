import { useEffect, useMemo, useState, type ReactNode } from "react";
import {
  Alert,
  Badge,
  Button,
  Collapse,
  Empty,
  Input,
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
  DownOutlined,
  FileSearchOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  PlusOutlined,
  ReloadOutlined,
  SendOutlined,
  ToolOutlined,
} from "@ant-design/icons";
import { XMarkdown } from "@ant-design/x-markdown";
import "@ant-design/x-markdown/dist/x-markdown.css";
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
  AgentStepTrace,
  AgentTaskTrace,
  ChatMessage,
  ChatSession,
  CodeRepository,
  LegacySseMessage,
  LoadState,
  ToolCallTrace,
} from "./types";

type DetailMode = "trace" | "tools" | "events";
type SseStatus = "idle" | "connecting" | "connected" | "interrupted";

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
  selectedTraceId?: string;
  loadState: LoadState;
  error?: string;
  sessionError?: string;
  sending: boolean;
  detailOpen: boolean;
  detailMode: DetailMode;
  sseStatus: SseStatus;
}

interface CodeEvidence {
  filePath?: string;
  lineRange?: string;
  chunkType?: string;
  symbolName?: string;
  apiPath?: string;
  httpMethod?: string;
  score?: string;
}

interface ToolMessageSummary {
  toolName: string;
  summary: string;
  evidence: CodeEvidence[];
}

const defaultState: RuntimeState = {
  repositories: [],
  agents: [],
  sessions: [],
  messages: [],
  traces: [],
  sseEvents: [],
  loadState: "idle",
  sending: false,
  detailOpen: true,
  detailMode: "trace",
  sseStatus: "idle",
};

const MAX_VISIBLE_SESSIONS = 24;
const LONG_TEXT_LIMIT = 1200;

function App() {
  const [state, setState] = useState<RuntimeState>(defaultState);
  const [draft, setDraft] = useState("");
  const [noticeApi, contextHolder] = message.useMessage();

  const selectedRepo = useMemo(
    () => state.repositories.find((repo) => repo.id === state.selectedRepoId),
    [state.repositories, state.selectedRepoId],
  );

  const sortedSessions = useMemo(() => sortSessions(state.sessions), [state.sessions]);

  const selectedSession = useMemo(
    () => state.sessions.find((session) => session.id === state.selectedSessionId),
    [state.sessions, state.selectedSessionId],
  );

  const selectedAgent = useMemo(
    () => state.agents.find((agent) => agent.id === state.selectedAgentId),
    [state.agents, state.selectedAgentId],
  );

  const selectedTrace = useMemo(
    () =>
      state.traces.find((trace) => trace.id === state.selectedTraceId) ??
      state.traces[0],
    [state.traces, state.selectedTraceId],
  );

  const visibleToolCalls = useMemo(
    () => state.traces.flatMap((trace) => trace.toolCalls ?? []),
    [state.traces],
  );

  useEffect(() => {
    refreshConsole();
  }, []);

  useEffect(() => {
    if (!state.selectedSessionId) {
      setState((previous) => ({
        ...previous,
        messages: [],
        traces: [],
        sseEvents: [],
        selectedTraceId: undefined,
        sseStatus: "idle",
      }));
      return;
    }

    const activeSessionId = state.selectedSessionId;
    setState((previous) => ({
      ...previous,
      sseStatus: "connecting",
      sessionError: undefined,
    }));
    void refreshSessionData(activeSessionId);

    const source = createSseConnection(activeSessionId);

    const appendAgentEvent = (event: Event) => {
      const parsed = parseSsePayload(eventData(event));
      if (!parsed) {
        return;
      }
      setState((previous) => ({
        ...previous,
        sseStatus: "connected",
        sseEvents: [parsed, ...previous.sseEvents].slice(0, 80),
        detailMode:
          parsed.type === "tool_call_start" || parsed.type === "tool_call_result"
            ? "tools"
            : previous.detailMode,
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
        sseStatus: "connected",
        messages: upsertMessage(previous.messages, messagePayload),
      }));
    };

    source.addEventListener("init", () => {
      setState((previous) => ({ ...previous, sseStatus: "connected" }));
    });
    source.addEventListener("message_start", appendAgentEvent);
    source.addEventListener("token", appendAgentEvent);
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
        sseStatus: "interrupted",
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
      const sorted = sortSessions(sessions);
      setState((previous) => {
        const selectedSessionId =
          previous.selectedSessionId && hasId(sorted, previous.selectedSessionId)
            ? previous.selectedSessionId
            : sorted[0]?.id;
        const selectedAgentId =
          previous.selectedAgentId && hasId(agents, previous.selectedAgentId)
            ? previous.selectedAgentId
            : sorted.find((session) => session.id === selectedSessionId)?.agentId ??
              agents[0]?.id;
        return {
          ...previous,
          repositories,
          agents,
          sessions: sorted,
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
      setState((previous) => ({
        ...previous,
        messages,
        traces,
        selectedTraceId:
          previous.selectedTraceId && hasId(traces, previous.selectedTraceId)
            ? previous.selectedTraceId
            : traces[0]?.id,
        sessionError: undefined,
      }));
    } catch (error) {
      setState((previous) => ({
        ...previous,
        sessionError: errorMessage(error),
      }));
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
        sessions: sortSessions(sessions),
        selectedSessionId: sessionId,
        selectedAgentId: agentId,
        detailMode: "trace",
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
      noticeApi.warning("请选择或新建会话");
      return;
    }
    setDraft("");
    setState((previous) => ({ ...previous, sending: true, sessionError: undefined }));
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

  function selectSession(sessionId: string) {
    const session = state.sessions.find((item) => item.id === sessionId);
    setState((previous) => ({
      ...previous,
      selectedSessionId: sessionId,
      selectedAgentId: session?.agentId ?? previous.selectedAgentId,
      selectedTraceId: undefined,
    }));
  }

  function openDetail(mode: DetailMode, traceId?: string) {
    setState((previous) => ({
      ...previous,
      detailOpen: true,
      detailMode: mode,
      selectedTraceId: traceId ?? previous.selectedTraceId,
    }));
  }

  return (
    <main className="console-shell">
      {contextHolder}
      <Header
        repo={selectedRepo}
        agent={selectedAgent}
        session={selectedSession}
        sseStatus={state.sseStatus}
        detailOpen={state.detailOpen}
        onRefresh={refreshConsole}
        onToggleDetail={() =>
          setState((previous) => ({
            ...previous,
            detailOpen: !previous.detailOpen,
          }))
        }
      />

      {state.error ? (
        <Alert
          className="top-alert"
          type="warning"
          showIcon
          closable
          message="后端接口暂不可用"
          description={state.error}
          action={
            <Button size="small" onClick={refreshConsole}>
              重试
            </Button>
          }
          onClose={() =>
            setState((previous) => ({ ...previous, error: undefined }))
          }
        />
      ) : null}

      <Spin spinning={state.loadState === "loading"}>
        <section className={`console-layout ${state.detailOpen ? "" : "detail-collapsed"}`}>
          <Sidebar
            repositories={state.repositories}
            sessions={sortedSessions}
            agents={state.agents}
            selectedRepoId={state.selectedRepoId}
            selectedSessionId={state.selectedSessionId}
            selectedAgentId={state.selectedAgentId}
            onSelectRepo={(selectedRepoId) =>
              setState((previous) => ({ ...previous, selectedRepoId }))
            }
            onSelectAgent={(selectedAgentId) =>
              setState((previous) => ({ ...previous, selectedAgentId }))
            }
            onSelectSession={selectSession}
            onCreateSession={handleCreateSession}
          />

          <ChatPanel
            repo={selectedRepo}
            session={selectedSession}
            agent={selectedAgent}
            messages={state.messages}
            traces={state.traces}
            draft={draft}
            sending={state.sending}
            sessionError={state.sessionError}
            sseStatus={state.sseStatus}
            onDraftChange={setDraft}
            onSend={handleSend}
            onOpenTrace={(traceId) => openDetail("trace", traceId)}
            onOpenTools={() => openDetail("tools")}
            onRetrySession={() =>
              state.selectedSessionId
                ? void refreshSessionData(state.selectedSessionId)
                : undefined
            }
          />

          <DetailPanel
            open={state.detailOpen}
            mode={state.detailMode}
            traces={state.traces}
            selectedTrace={selectedTrace}
            toolCalls={visibleToolCalls}
            events={state.sseEvents}
            sessionId={state.selectedSessionId}
            onModeChange={(detailMode) =>
              setState((previous) => ({ ...previous, detailMode }))
            }
            onSelectTrace={(selectedTraceId) =>
              setState((previous) => ({ ...previous, selectedTraceId }))
            }
            onClose={() =>
              setState((previous) => ({ ...previous, detailOpen: false }))
            }
          />
        </section>
      </Spin>
    </main>
  );
}

function Header({
  repo,
  agent,
  session,
  sseStatus,
  detailOpen,
  onRefresh,
  onToggleDetail,
}: {
  repo?: CodeRepository;
  agent?: Agent;
  session?: ChatSession;
  sseStatus: SseStatus;
  detailOpen: boolean;
  onRefresh: () => void;
  onToggleDetail: () => void;
}) {
  return (
    <header className="console-header">
      <div className="brand-block">
        <Typography.Title level={4} className="console-title">
          JChatMind Web Console
        </Typography.Title>
        <Typography.Text type="secondary">
          面向代码问答、工具调用和 Agent Trace 的主入口
        </Typography.Text>
      </div>
      <div className="header-context">
        <ContextPill label="Repo" value={repo?.name ?? "未选择"} />
        <ContextPill label="Agent" value={agent?.name ?? "未选择"} />
        <ContextPill label="Conversation" value={session?.title ?? "未选择"} />
        <Tag color={sseStatusColor(sseStatus)}>{sseStatusLabel(sseStatus)}</Tag>
        <Tooltip title="重新加载仓库、Agent、会话和当前 Trace">
          <Button icon={<ReloadOutlined />} onClick={onRefresh}>
            刷新
          </Button>
        </Tooltip>
        <Tooltip title={detailOpen ? "收起 Trace / Audit" : "展开 Trace / Audit"}>
          <Button
            icon={detailOpen ? <MenuFoldOutlined /> : <MenuUnfoldOutlined />}
            onClick={onToggleDetail}
          />
        </Tooltip>
      </div>
    </header>
  );
}

function ContextPill({ label, value }: { label: string; value: string }) {
  return (
    <span className="context-pill">
      <span>{label}</span>
      <strong>{value}</strong>
    </span>
  );
}

function Sidebar({
  repositories,
  sessions,
  agents,
  selectedRepoId,
  selectedSessionId,
  selectedAgentId,
  onSelectRepo,
  onSelectAgent,
  onSelectSession,
  onCreateSession,
}: {
  repositories: CodeRepository[];
  sessions: ChatSession[];
  agents: Agent[];
  selectedRepoId?: string;
  selectedSessionId?: string;
  selectedAgentId?: string;
  onSelectRepo: (repoId: string) => void;
  onSelectAgent: (agentId: string) => void;
  onSelectSession: (sessionId: string) => void;
  onCreateSession: () => void;
}) {
  const selectedRepo = repositories.find((repo) => repo.id === selectedRepoId);
  const visibleSessions = sessions.slice(0, MAX_VISIBLE_SESSIONS);

  return (
    <aside className="sidebar">
      <section className="sidebar-section">
        <SectionHeader
          icon={<CodeOutlined />}
          title="代码仓库"
          count={repositories.length}
        />
        {repositories.length === 0 ? (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description="请先导入代码仓库"
          />
        ) : (
          <div className="repo-list">
            {repositories.map((repo) => (
              <button
                className={`nav-row ${repo.id === selectedRepoId ? "selected" : ""}`}
                key={repo.id}
                type="button"
                onClick={() => onSelectRepo(repo.id)}
              >
                <span className="row-main">{repo.name}</span>
                <span className="row-meta">
                  <span>{repo.language ?? "unknown"}</span>
                  <StatusTag status={repo.status} />
                </span>
                <span className="row-time">
                  {formatDate(repo.updatedAt ?? repo.createdAt)}
                </span>
              </button>
            ))}
          </div>
        )}
      </section>

      <section className="sidebar-section">
        <SectionHeader
          icon={<CommentOutlined />}
          title="会话"
          count={sessions.length}
          action={
            <Button size="small" icon={<PlusOutlined />} onClick={onCreateSession}>
              新建
            </Button>
          }
        />
        <Select
          className="agent-select"
          size="small"
          placeholder="选择 Agent"
          value={selectedAgentId}
          options={agents.map((agent) => ({
            value: agent.id,
            label: `${agent.name}${agent.model ? ` / ${agent.model}` : ""}`,
          }))}
          onChange={onSelectAgent}
          notFoundContent="暂无 Agent"
        />
        <div className="binding-hint">
          当前 repo: {selectedRepo?.name ?? "未选择"}。后端 chat_session 暂未绑定
          repoId，这里仅按当前仓库预留导航上下文。
        </div>
        {sessions.length === 0 ? (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description="新建会话开始提问"
          />
        ) : (
          <>
            <div className="conversation-list">
              {visibleSessions.map((session) => (
                <button
                  className={`nav-row compact ${
                    session.id === selectedSessionId ? "selected" : ""
                  }`}
                  key={session.id}
                  type="button"
                  onClick={() => onSelectSession(session.id)}
                >
                  <span className="row-main">{session.title || "未命名会话"}</span>
                  <span className="row-meta">
                    <span>Agent: {agentName(agents, session.agentId)}</span>
                  </span>
                  <span className="row-time">
                    {formatDate(session.updatedAt ?? session.createdAt)}
                  </span>
                </button>
              ))}
            </div>
            {sessions.length > MAX_VISIBLE_SESSIONS ? (
              <div className="list-note">
                已按最近使用显示前 {MAX_VISIBLE_SESSIONS} 条，共 {sessions.length} 条。
              </div>
            ) : null}
          </>
        )}
      </section>
    </aside>
  );
}

function ChatPanel({
  repo,
  session,
  agent,
  messages,
  traces,
  draft,
  sending,
  sessionError,
  sseStatus,
  onDraftChange,
  onSend,
  onOpenTrace,
  onOpenTools,
  onRetrySession,
}: {
  repo?: CodeRepository;
  session?: ChatSession;
  agent?: Agent;
  messages: ChatMessage[];
  traces: AgentTaskTrace[];
  draft: string;
  sending: boolean;
  sessionError?: string;
  sseStatus: SseStatus;
  onDraftChange: (value: string) => void;
  onSend: () => void;
  onOpenTrace: (traceId?: string) => void;
  onOpenTools: () => void;
  onRetrySession: () => void;
}) {
  const latestTrace = traces[0];
  const toolCallCount = traces.reduce(
    (count, trace) => count + (trace.toolCalls?.length ?? 0),
    0,
  );

  return (
    <section className="chat-panel">
      <div className="chat-heading">
        <div>
          <Typography.Title level={4} className="panel-title">
            代码助手
          </Typography.Title>
          <Typography.Text type="secondary">
            选择仓库和会话后提问，回答优先展示自然语言；证据和工具细节在右侧查看。
          </Typography.Text>
        </div>
        <Space wrap>
          <Tag>{repo?.name ?? "未选择 repo"}</Tag>
          <Tag color="blue">{agent?.name ?? "未选择 Agent"}</Tag>
          {latestTrace ? (
            <Button
              size="small"
              icon={<AuditOutlined />}
              onClick={() => onOpenTrace(latestTrace.id)}
            >
              Trace
            </Button>
          ) : null}
          <Button size="small" icon={<ToolOutlined />} onClick={onOpenTools}>
            Tool {toolCallCount}
          </Button>
        </Space>
      </div>

      {sessionError ? (
        <Alert
          className="session-alert"
          type="error"
          showIcon
          message="当前会话数据加载失败"
          description={sessionError}
          action={
            <Button size="small" onClick={onRetrySession}>
              重试
            </Button>
          }
        />
      ) : null}

      <div className="message-list">
        {!session ? (
          <Empty description="请选择或新建会话后开始提问" />
        ) : messages.length === 0 ? (
          <Empty description="新建会话开始提问" />
        ) : (
          messages.map((item) => (
            <MessageBubble
              key={item.id}
              message={item}
              onOpenTools={onOpenTools}
            />
          ))
        )}
        {sending ? (
          <div className="typing-row">
            <span className="typing-dot" />
            正在发送并等待 Agent 响应
          </div>
        ) : null}
      </div>

      {sseStatus === "interrupted" ? (
        <Alert
          className="sse-alert"
          type="warning"
          showIcon
          message="SSE 连接已断开"
          description="可以继续发送消息；如需恢复实时事件，请刷新或重新选择当前会话。"
        />
      ) : null}

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
    </section>
  );
}

function MessageBubble({
  message,
  onOpenTools,
}: {
  message: ChatMessage;
  onOpenTools: () => void;
}) {
  const roleLabel = roleText(message.role);
  const isTool = message.role === "tool";
  const isAssistant = message.role === "assistant";

  return (
    <article className={`message-bubble role-${message.role}`}>
      <div className="message-meta">
        <span>{roleLabel}</span>
        <span>{formatDate(message.createdAt)}</span>
      </div>
      {isTool ? (
        <ToolMessage message={message} onOpenTools={onOpenTools} />
      ) : isAssistant ? (
        <MarkdownContent content={message.content} />
      ) : (
        <LongText text={message.content} />
      )}
    </article>
  );
}

function ToolMessage({
  message,
  onOpenTools,
}: {
  message: ChatMessage;
  onOpenTools: () => void;
}) {
  const summary = summarizeToolMessage(message);
  const normalizedContent = normalizeToolContent(message.content);

  return (
    <div className="tool-message">
      <div className="tool-card-head">
        <Space wrap size={6}>
          <Tag icon={<ToolOutlined />}>{summary.toolName}</Tag>
          <Tag color="success">local tool</Tag>
          <Tag>{summary.evidence.length} evidence</Tag>
        </Space>
        <Button size="small" type="text" onClick={onOpenTools}>
          查看 Trace
        </Button>
      </div>
      <div className="tool-summary-text">{summary.summary}</div>
      <EvidenceList evidence={summary.evidence} />
      <Collapse
        ghost
        size="small"
        expandIcon={({ isActive }) => <DownOutlined rotate={isActive ? 180 : 0} />}
        items={[
          {
            key: "raw",
            label: "展开 tool 原始结果",
            children: <RawBlock value={normalizedContent} />,
          },
        ]}
      />
    </div>
  );
}

function MarkdownContent({ content }: { content: string }) {
  if (!content) {
    return <span className="muted">无内容</span>;
  }
  if (content.length <= LONG_TEXT_LIMIT) {
    return (
      <div className="markdown-body">
        <XMarkdown content={content} />
      </div>
    );
  }
  const preview = content.slice(0, LONG_TEXT_LIMIT);
  return (
    <div className="markdown-body">
      <XMarkdown content={`${preview}\n\n...`} />
      <Collapse
        ghost
        size="small"
        items={[
          {
            key: "full",
            label: "展开完整回答",
            children: <XMarkdown content={content} />,
          },
        ]}
      />
    </div>
  );
}

function LongText({ text }: { text: string }) {
  if (!text) {
    return <span className="muted">无内容</span>;
  }
  if (text.length <= LONG_TEXT_LIMIT) {
    return <div className="plain-text">{text}</div>;
  }
  return (
    <div>
      <div className="plain-text">{text.slice(0, LONG_TEXT_LIMIT)}...</div>
      <Collapse
        ghost
        size="small"
        items={[
          {
            key: "full",
            label: "展开完整文本",
            children: <RawBlock value={text} />,
          },
        ]}
      />
    </div>
  );
}

function DetailPanel({
  open,
  mode,
  traces,
  selectedTrace,
  toolCalls,
  events,
  sessionId,
  onModeChange,
  onSelectTrace,
  onClose,
}: {
  open: boolean;
  mode: DetailMode;
  traces: AgentTaskTrace[];
  selectedTrace?: AgentTaskTrace;
  toolCalls: ToolCallTrace[];
  events: AgentSseEvent[];
  sessionId?: string;
  onModeChange: (mode: DetailMode) => void;
  onSelectTrace: (traceId: string) => void;
  onClose: () => void;
}) {
  if (!open) {
    return null;
  }

  return (
    <aside className="detail-panel">
      <div className="detail-header">
        <div>
          <Typography.Title level={5} className="panel-title">
            Trace / Audit
          </Typography.Title>
          <Typography.Text type="secondary">
            摘要优先，raw detail 默认折叠
          </Typography.Text>
        </div>
        <Button size="small" type="text" icon={<MenuFoldOutlined />} onClick={onClose} />
      </div>

      <div className="detail-tabs">
        <button
          className={mode === "trace" ? "active" : ""}
          type="button"
          onClick={() => onModeChange("trace")}
        >
          Runs <Badge count={traces.length} />
        </button>
        <button
          className={mode === "tools" ? "active" : ""}
          type="button"
          onClick={() => onModeChange("tools")}
        >
          Tools <Badge count={toolCalls.length} />
        </button>
        <button
          className={mode === "events" ? "active" : ""}
          type="button"
          onClick={() => onModeChange("events")}
        >
          SSE <Badge count={events.length} />
        </button>
      </div>

      {!sessionId ? (
        <Empty description="选择会话后展示 Trace / Audit" />
      ) : mode === "trace" ? (
        <TraceRunPanel
          traces={traces}
          selectedTrace={selectedTrace}
          onSelectTrace={onSelectTrace}
        />
      ) : mode === "tools" ? (
        <ToolAuditPanel toolCalls={toolCalls} />
      ) : (
        <SseEventPanel events={events} />
      )}
    </aside>
  );
}

function TraceRunPanel({
  traces,
  selectedTrace,
  onSelectTrace,
}: {
  traces: AgentTaskTrace[];
  selectedTrace?: AgentTaskTrace;
  onSelectTrace: (traceId: string) => void;
}) {
  if (traces.length === 0) {
    return <Empty description="当前会话暂无 Agent Run 记录" />;
  }

  return (
    <div className="trace-panel-content">
      <Select
        className="trace-select"
        value={selectedTrace?.id}
        options={traces.map((trace) => ({
          value: trace.id,
          label: `${trace.status ?? "UNKNOWN"} / ${trace.traceId ?? trace.id}`,
        }))}
        onChange={onSelectTrace}
      />
      {selectedTrace ? <TraceRun trace={selectedTrace} /> : null}
    </div>
  );
}

function TraceRun({ trace }: { trace: AgentTaskTrace }) {
  return (
    <section className="trace-run">
      <div className="trace-summary-grid">
        <Metric label="runId" value={trace.traceId ?? trace.id} />
        <Metric label="status" value={trace.status ?? "UNKNOWN"} />
        <Metric label="latency" value={formatLatency(trace.latencyMs)} />
        <Metric label="steps" value={String(trace.actualSteps ?? trace.steps?.length ?? 0)} />
        <Metric label="tool calls" value={String(trace.toolCallCount ?? trace.toolCalls?.length ?? 0)} />
        <Metric label="model" value={trace.modelName ?? "n/a"} />
      </div>
      {trace.errorMessage ? (
        <Alert
          className="trace-error"
          type="error"
          showIcon
          message="Agent Run 失败"
          description={trace.errorMessage}
        />
      ) : null}
      <div className="step-list">
        {(trace.steps ?? []).length === 0 ? (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description="当前 run 暂无 step 记录"
          />
        ) : (
          trace.steps.map((step) => <TraceStep key={step.id} step={step} />)
        )}
      </div>
      <Collapse
        size="small"
        items={[
          {
            key: "run-raw",
            label: "raw run detail",
            children: <RawBlock value={trace} />,
          },
        ]}
      />
    </section>
  );
}

function TraceStep({ step }: { step: AgentStepTrace }) {
  return (
    <div className="step-row">
      <div className="step-head">
        <Space wrap size={6}>
          <Tag>Step {step.stepNo ?? "-"}</Tag>
          <Tag color={statusColor(step.status)}>{step.status ?? "UNKNOWN"}</Tag>
          <span>{step.stepType ?? "step"}</span>
        </Space>
        <span className="muted">{formatLatency(step.latencyMs)}</span>
      </div>
      <div className="step-copy">
        {step.inputSummary ? <p>{step.inputSummary}</p> : null}
        {step.outputSummary ? <p>{step.outputSummary}</p> : null}
        {step.errorMessage ? <p className="danger-text">{step.errorMessage}</p> : null}
      </div>
      <Collapse
        ghost
        size="small"
        items={[
          {
            key: "step-raw",
            label: "raw step detail",
            children: <RawBlock value={step} />,
          },
        ]}
      />
    </div>
  );
}

function ToolAuditPanel({ toolCalls }: { toolCalls: ToolCallTrace[] }) {
  if (toolCalls.length === 0) {
    return <Empty description="本次回答未触发工具调用" />;
  }

  return (
    <div className="tool-audit-list">
      {toolCalls.map((call) => (
        <ToolCallCard key={call.id} call={call} />
      ))}
    </div>
  );
}

function ToolCallCard({ call }: { call: ToolCallTrace }) {
  const denied = call.blockedByPolicy === true;
  const summary = call.errorMessage ?? call.resultSummary ?? "暂无结果摘要";

  return (
    <article className="tool-call-card">
      <div className="tool-card-head">
        <Space wrap size={6}>
          <Tag color={denied ? "red" : statusColor(call.status)}>
            {denied ? "DENIED" : call.status ?? "UNKNOWN"}
          </Tag>
          <Tag>{toolKind(call)}</Tag>
          <strong>{call.actualToolName ?? call.toolName ?? "unknown_tool"}</strong>
        </Space>
        <span className="muted">{formatLatency(call.latencyMs)}</span>
      </div>
      <div className="tool-summary-text">{summary}</div>
      <Space wrap size={6}>
        {call.errorType ? <Tag color="volcano">{call.errorType}</Tag> : null}
        {call.argumentTruncated ? <Tag>arguments truncated</Tag> : null}
        {call.resultTruncated ? <Tag>result truncated</Tag> : null}
      </Space>
      <Collapse
        ghost
        size="small"
        items={[
          {
            key: "tool-raw",
            label: "raw tool detail",
            children: <RawBlock value={call} />,
          },
        ]}
      />
    </article>
  );
}

function SseEventPanel({ events }: { events: AgentSseEvent[] }) {
  if (events.length === 0) {
    return <Empty description="当前会话暂无实时 SSE 事件" />;
  }

  return (
    <div className="event-list">
      {events.map((event, index) => (
        <article className="event-row" key={event.eventId ?? `${event.type}-${index}`}>
          <div className="tool-card-head">
            <Space wrap size={6}>
              <Tag color={event.type === "error" ? "red" : "processing"}>
                {event.type ?? "event"}
              </Tag>
              <span>{event.taskId ?? "no-task"}</span>
            </Space>
            <span className="muted">{formatDate(event.timestamp)}</span>
          </div>
          <div className="tool-summary-text">{summarizeEvent(event.payload)}</div>
          <Collapse
            ghost
            size="small"
            items={[
              {
                key: "event-raw",
                label: "raw SSE payload",
                children: <RawBlock value={event.payload ?? event} />,
              },
            ]}
          />
        </article>
      ))}
    </div>
  );
}

function EvidenceList({ evidence }: { evidence: CodeEvidence[] }) {
  if (evidence.length === 0) {
    return <div className="muted">未解析到代码证据摘要</div>;
  }
  return (
    <div className="evidence-list">
      {evidence.slice(0, 5).map((item, index) => (
        <div className="evidence-row" key={`${item.filePath}-${item.lineRange}-${index}`}>
          <FileSearchOutlined />
          <div>
            <div className="evidence-file">
              {item.filePath ?? "unknown file"}
              {item.lineRange ? `:${item.lineRange}` : ""}
            </div>
            <div className="evidence-meta">
              {[item.chunkType, item.symbolName, item.apiPath, item.httpMethod, item.score]
                .filter(Boolean)
                .join(" / ") || "no metadata"}
            </div>
          </div>
        </div>
      ))}
      {evidence.length > 5 ? (
        <div className="list-note">还有 {evidence.length - 5} 条证据在原始结果中</div>
      ) : null}
    </div>
  );
}

function SectionHeader({
  icon,
  title,
  count,
  action,
}: {
  icon: ReactNode;
  title: string;
  count: number;
  action?: ReactNode;
}) {
  return (
    <div className="section-header">
      <Space size={8}>
        {icon}
        <strong>{title}</strong>
        <Badge count={count} />
      </Space>
      {action}
    </div>
  );
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div className="metric">
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

function StatusTag({ status }: { status?: string }) {
  return <Tag color={repoStatusColor(status)}>{status ?? "UNKNOWN"}</Tag>;
}

function RawBlock({ value }: { value: unknown }) {
  const text = typeof value === "string" ? value : JSON.stringify(value, null, 2);
  return <pre className="raw-block">{text}</pre>;
}

function summarizeToolMessage(message: ChatMessage): ToolMessageSummary {
  const content = normalizeToolContent(message.content);
  const toolName =
    message.metadata?.toolResponse?.name ??
    inferToolName(content) ??
    "tool result";
  const evidence = parseCodeEvidence(content);
  const summary =
    evidence.length > 0
      ? `命中 ${evidence.length} 个代码证据：${evidence
          .slice(0, 2)
          .map((item) => item.filePath)
          .filter(Boolean)
          .join(", ")}`
      : firstMeaningfulLine(content) || "工具已返回结果，原始内容已折叠。";
  return { toolName, summary, evidence };
}

function parseCodeEvidence(content: string): CodeEvidence[] {
  const normalized = normalizeToolContent(content);
  if (!normalized.includes("[code snippet]")) {
    return [];
  }
  return normalized
    .split("[code snippet]")
    .slice(1)
    .map((block) => ({
      filePath: lineValue(block, "filePath"),
      lineRange: lineValue(block, "lineRange"),
      chunkType: lineValue(block, "chunkType"),
      symbolName: lineValue(block, "symbolName"),
      apiPath: lineValue(block, "apiPath"),
      httpMethod: lineValue(block, "httpMethod"),
      score: lineValue(block, "score"),
    }))
    .filter((item) => item.filePath || item.symbolName || item.apiPath);
}

function normalizeToolContent(content: string): string {
  if (!content) {
    return "";
  }
  const trimmed = content.trim();
  if (
    (trimmed.startsWith('"') && trimmed.endsWith('"')) ||
    (trimmed.startsWith("'") && trimmed.endsWith("'"))
  ) {
    try {
      const parsed = JSON.parse(trimmed) as unknown;
      if (typeof parsed === "string") {
        return parsed;
      }
    } catch {
      // Fall through to conservative unescape for legacy persisted tool strings.
    }
  }
  return trimmed
    .replace(/\\r\\n/g, "\n")
    .replace(/\\n/g, "\n")
    .replace(/\\"/g, '"');
}

function lineValue(block: string, key: string): string | undefined {
  const match = block.match(new RegExp(`^${key}:\\s*(.*)$`, "m"));
  const value = match?.[1]?.trim();
  return value || undefined;
}

function inferToolName(content: string): string | undefined {
  if (content.includes("Selected code evidence")) {
    return "searchProjectCode";
  }
  if (content.includes("No related code evidence found")) {
    return "searchProjectCode";
  }
  return undefined;
}

function firstMeaningfulLine(content: string): string {
  return content
    .split(/\r?\n/)
    .map((line) => line.trim())
    .find((line) => line.length > 0)
    ?.slice(0, 240) ?? "";
}

function sortSessions(sessions: ChatSession[]): ChatSession[] {
  return [...sessions].sort((left, right) => {
    const leftTime = Date.parse(left.updatedAt ?? left.createdAt ?? "");
    const rightTime = Date.parse(right.updatedAt ?? right.createdAt ?? "");
    return (Number.isNaN(rightTime) ? 0 : rightTime) - (Number.isNaN(leftTime) ? 0 : leftTime);
  });
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

function roleText(role: string): string {
  if (role === "assistant") {
    return "Assistant";
  }
  if (role === "user") {
    return "User";
  }
  if (role === "tool") {
    return "Tool";
  }
  return role;
}

function agentName(agents: Agent[], agentId: string): string {
  return agents.find((agent) => agent.id === agentId)?.name ?? agentId;
}

function statusColor(status?: string) {
  if (status === "SUCCESS" || status === "COMPLETED") {
    return "green";
  }
  if (status === "FAILED" || status === "CRASHED" || status === "ERROR") {
    return "red";
  }
  if (status === "RUNNING") {
    return "blue";
  }
  return "default";
}

function repoStatusColor(status?: string) {
  if (status === "READY" || status === "SUCCESS") {
    return "green";
  }
  if (status === "FAILED") {
    return "red";
  }
  if (status === "IMPORTING" || status === "RUNNING") {
    return "blue";
  }
  return "default";
}

function sseStatusColor(status: SseStatus) {
  if (status === "connected") {
    return "green";
  }
  if (status === "interrupted") {
    return "orange";
  }
  if (status === "connecting") {
    return "blue";
  }
  return "default";
}

function sseStatusLabel(status: SseStatus) {
  if (status === "connected") {
    return "SSE connected";
  }
  if (status === "interrupted") {
    return "SSE interrupted";
  }
  if (status === "connecting") {
    return "SSE connecting";
  }
  return "SSE idle";
}

function toolKind(call: ToolCallTrace) {
  const name = call.actualToolName ?? call.toolName ?? "";
  return name.startsWith("mcp_") ? "MCP tool" : "local tool";
}

function formatLatency(value?: number) {
  return typeof value === "number" ? `${value} ms` : "n/a";
}

function formatDate(value?: string) {
  if (!value) {
    return "n/a";
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return date.toLocaleString("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function summarizeEvent(payload?: Record<string, unknown>) {
  if (!payload) {
    return "无 payload";
  }
  const keys = ["toolName", "actualToolName", "status", "resultSummary", "errorMessage", "finishReason"];
  const values = keys
    .map((key) => payload[key])
    .filter((value) => typeof value === "string" && value.length > 0)
    .map(String);
  if (values.length > 0) {
    return values.join(" / ").slice(0, 280);
  }
  return JSON.stringify(payload).slice(0, 280);
}

export default App;
