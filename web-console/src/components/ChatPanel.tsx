import { AuditOutlined, SendOutlined, ToolOutlined } from "@ant-design/icons";
import { Alert, Button, Empty, Input, Space, Tag, Typography } from "antd";
import type {
  Agent,
  AgentTaskTrace,
  ChatMessage,
  ChatSession,
  CodeRepository,
  MessageStatus,
  SseStatus,
} from "../types";
import {
  isPlainAgent,
  isPrimaryChatMessage,
  selectedAgentCapability,
} from "../utils/messageDisplay";
import { MessageBubble } from "./MessageBubble";

export function ChatPanel({
  repo,
  session,
  agent,
  messages,
  traces,
  draft,
  sending,
  messageStatus,
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
  messageStatus: MessageStatus;
  sessionError?: string;
  sseStatus: SseStatus;
  onDraftChange: (value: string) => void;
  onSend: () => void;
  onOpenTrace: (traceId?: string) => void;
  onOpenTools: () => void;
  onRetrySession: () => void;
}) {
  const latestTrace = traces[0];
  const visibleMessages = messages.filter(isPrimaryChatMessage);
  const busy = sending || messageStatus === "generating";
  const toolCallCount = traces.reduce(
    (count, trace) => count + (trace.toolCalls?.length ?? 0),
    0,
  );
  const capability = selectedAgentCapability(agent);
  const showPlainAgentHint = Boolean(repo && agent && isPlainAgent(agent));

  return (
    <section className="chat-panel">
      <div className="chat-heading">
        <div>
          <Typography.Title level={4} className="panel-title">
            代码助手
          </Typography.Title>
          <Typography.Text type="secondary">
            {capability.detail}
          </Typography.Text>
        </div>
        <Space wrap>
          <Tag>{repo?.name ?? "未选择 repo"}</Tag>
          <Tag color={capability.tone === "code" ? "green" : "blue"}>
            {agent?.name ?? "未选择 Agent"} · {capability.description}
          </Tag>
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

      {showPlainAgentHint ? (
        <Alert
          className="session-alert"
          type="info"
          showIcon
          message="当前 Agent 是普通对话，不会主动检索代码。若要分析代码，建议切换到 code-agent。"
        />
      ) : null}

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
        ) : visibleMessages.length === 0 ? (
          <Empty description="新建会话开始提问" />
        ) : (
          visibleMessages.map((item) => (
            <MessageBubble
              key={item.id}
              message={item}
              trace={item.role === "assistant" ? traceForAssistant(item, messages, traces) : undefined}
              question={item.role === "assistant" ? questionForAssistant(item, messages) : undefined}
              repo={repo}
              agent={agent}
              onOpenTools={onOpenTools}
            />
          ))
        )}
        {messageStatus === "sending" ? (
          <div className="typing-row">
            <span className="typing-dot" />
            正在发送消息
          </div>
        ) : messageStatus === "generating" ? (
          <article className="message-bubble role-assistant transient-message">
            <div className="message-meta">
              <span>Assistant</span>
              <span>generating</span>
            </div>
            <div className="typing-row">
              <span className="typing-dot" />
              Agent 正在生成回答
            </div>
          </article>
        ) : null}
      </div>

      {sseStatus === "error" ? (
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
          disabled={!session}
        />
        <Button
          type="primary"
          icon={<SendOutlined />}
          loading={busy}
          disabled={!session || busy || !draft.trim()}
          onClick={onSend}
        >
          发送
        </Button>
      </div>
    </section>
  );
}

function traceForAssistant(
  assistant: ChatMessage,
  messages: ChatMessage[],
  traces: AgentTaskTrace[],
): AgentTaskTrace | undefined {
  const assistantIndex = messages.findIndex((message) => message.id === assistant.id);
  const priorUser = findPriorUserMessage(messages, assistantIndex);
  if (!priorUser) {
    return undefined;
  }
  return traces.find((trace) => trace.userMessageId === priorUser.id);
}

function questionForAssistant(assistant: ChatMessage, messages: ChatMessage[]): string | undefined {
  const assistantIndex = messages.findIndex((message) => message.id === assistant.id);
  return findPriorUserMessage(messages, assistantIndex)?.content;
}

function findPriorUserMessage(messages: ChatMessage[], beforeIndex: number): ChatMessage | undefined {
  for (let index = Math.max(0, beforeIndex - 1); index >= 0; index -= 1) {
    if (messages[index]?.role === "user") {
      return messages[index];
    }
  }
  return undefined;
}
