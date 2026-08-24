import { AuditOutlined, SendOutlined, StopOutlined, ToolOutlined } from "@ant-design/icons";
import { Alert, Button, Empty, Input, Space, Tag, Typography } from "antd";
import { useCallback, useLayoutEffect, useRef, type UIEvent } from "react";
import type {
  AgentTaskTrace,
  ChatMessage,
  ChatSession,
  CodeRepository,
  MessageStatus,
  SseStatus,
  WebConsoleModel,
} from "../types";
import {
  isPrimaryChatMessage,
  modelLabel,
} from "../utils/messageDisplay";
import { isNearBottom } from "../utils/scroll";
import { MessageBubble } from "./MessageBubble";

export function ChatPanel({
  repo,
  session,
  model,
  messages,
  traces,
  executionTrace,
  draft,
  sending,
  messageStatus,
  sessionError,
  sseStatus,
  onDraftChange,
  onSend,
  onStop,
  onOpenTrace,
  onOpenTools,
  onRetrySession,
}: {
  repo?: CodeRepository;
  session?: ChatSession;
  model: WebConsoleModel;
  messages: ChatMessage[];
  traces: AgentTaskTrace[];
  executionTrace?: AgentTaskTrace;
  draft: string;
  sending: boolean;
  messageStatus: MessageStatus;
  sessionError?: string;
  sseStatus: SseStatus;
  onDraftChange: (value: string) => void;
  onSend: () => void;
  onStop: () => void;
  onOpenTrace: (traceId?: string) => void;
  onOpenTools: () => void;
  onRetrySession: () => void;
}) {
  const scrollContainerRef = useRef<HTMLDivElement>(null);
  const shouldAutoScrollRef = useRef(true);
  const currentTrace = executionTrace;
  const visibleMessages = messages.filter(isPrimaryChatMessage);
  const hasStreamingAssistant = visibleMessages.some(
    (message) => message.provisional && message.status === "streaming",
  );
  const busy = sending || messageStatus === "generating" || messageStatus === "cancelling";
  const toolCallCount = currentTrace?.toolCalls?.length ?? 0;

  const scrollToBottom = useCallback(() => {
    const container = scrollContainerRef.current;
    if (container) {
      container.scrollTop = container.scrollHeight;
    }
  }, []);

  useLayoutEffect(() => {
    shouldAutoScrollRef.current = true;
    const frameId = requestAnimationFrame(scrollToBottom);
    return () => cancelAnimationFrame(frameId);
  }, [scrollToBottom, session?.id]);

  useLayoutEffect(() => {
    if (!shouldAutoScrollRef.current) {
      return;
    }
    const frameId = requestAnimationFrame(() => {
      if (shouldAutoScrollRef.current) {
        scrollToBottom();
      }
    });
    return () => cancelAnimationFrame(frameId);
  }, [messageStatus, messages, scrollToBottom]);

  const handleMessageListScroll = useCallback((event: UIEvent<HTMLDivElement>) => {
    shouldAutoScrollRef.current = isNearBottom(event.currentTarget);
  }, []);

  const handleSend = useCallback(() => {
    if (draft.trim()) {
      shouldAutoScrollRef.current = true;
    }
    onSend();
  }, [draft, onSend]);

  return (
    <section className="chat-panel">
      <div className="chat-heading">
        <div>
          <Typography.Title level={4} className="panel-title">
            代码助手
          </Typography.Title>
          <Typography.Text type="secondary">
            使用安全全能力代码助手模板，可按当前模型调用已启用的只读/受控工具。
          </Typography.Text>
        </div>
        <Space wrap>
          {session?.title ? <Tag>{session.title}</Tag> : null}
          <Tag>{repo?.name ?? "未选择 repo"}</Tag>
          <Tag color="green">代码助手</Tag>
          <Tag color="blue">{modelLabel(model)}</Tag>
          {currentTrace ? (
            <Button
              size="small"
              icon={<AuditOutlined />}
              onClick={() => onOpenTrace(currentTrace.id)}
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

      <div
        className="message-list"
        ref={scrollContainerRef}
        onScroll={handleMessageListScroll}
      >
        {!session ? (
          <Empty description="请选择或新建会话后开始提问" />
        ) : visibleMessages.length === 0 ? (
          <Empty description="新建会话开始提问" />
        ) : (
          visibleMessages.map((item, index) => (
            <MessageBubble
              key={item.id ?? item.streamId ?? `message-${index}`}
              message={item}
              trace={item.role === "assistant" ? traceForAssistant(item, messages, traces) : undefined}
              question={item.role === "assistant" ? questionForAssistant(item, messages) : undefined}
              repo={repo}
              onOpenTools={onOpenTools}
            />
          ))
        )}
        {messageStatus === "sending" ? (
          <div className="typing-row">
            <span className="typing-dot" />
            正在发送消息
          </div>
        ) :
          (messageStatus === "generating" || messageStatus === "cancelling") &&
          !hasStreamingAssistant ? (
          <article className="message-bubble role-assistant transient-message">
            <div className="message-meta">
              <span>Assistant</span>
              <span>{messageStatus === "cancelling" ? "正在停止" : "generating"}</span>
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
              handleSend();
            }
          }}
          autoSize={{ minRows: 2, maxRows: 6 }}
          placeholder="向代码助手提问。Shift + Enter 换行。"
          disabled={!session || busy}
        />
        {busy ? (
          <Button danger icon={<StopOutlined />} onClick={onStop}>
            停止
          </Button>
        ) : null}
        <Button
          type="primary"
          icon={<SendOutlined />}
          hidden={busy}
          disabled={!session || busy || !draft.trim()}
          onClick={handleSend}
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
  const assistantIndex = messages.findIndex((message) => message === assistant);
  const priorUser = findPriorUserMessage(messages, assistantIndex);
  if (!priorUser) {
    return undefined;
  }
  return traces.find((trace) => trace.userMessageId === priorUser.id);
}

function questionForAssistant(assistant: ChatMessage, messages: ChatMessage[]): string | undefined {
  const assistantIndex = messages.findIndex((message) => message === assistant);
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
