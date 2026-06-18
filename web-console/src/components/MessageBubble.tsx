import { Collapse } from "antd";
import { XMarkdown } from "@ant-design/x-markdown";
import "@ant-design/x-markdown/dist/x-markdown.css";
import type { AgentTaskTrace, ChatMessage, CodeRepository } from "../types";
import { LONG_TEXT_LIMIT, formatDate, roleText } from "../utils/messageDisplay";
import { RawBlock } from "./common";
import { ReasoningPanel } from "./ReasoningPanel";
import { ToolMessage } from "./ToolMessage";

export function MessageBubble({
  message,
  trace,
  question,
  repo,
  onOpenTools,
}: {
  message: ChatMessage;
  trace?: AgentTaskTrace;
  question?: string;
  repo?: CodeRepository;
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
        <AssistantMessageContent
          message={message}
          trace={trace}
          question={question}
          repo={repo}
          onOpenTools={onOpenTools}
        />
      ) : (
        <LongText text={message.content} />
      )}
    </article>
  );
}

function AssistantMessageContent({
  message,
  trace,
  question,
  repo,
  onOpenTools,
}: {
  message: ChatMessage;
  trace?: AgentTaskTrace;
  question?: string;
  repo?: CodeRepository;
  onOpenTools: () => void;
}) {
  return (
    <div className="assistant-message">
      <ReasoningPanel
        trace={trace}
        question={question}
        repo={repo}
        onOpenTools={onOpenTools}
      />
      <section className="answer-section">
        <div className="answer-title">正式回答</div>
        <MarkdownContent content={message.content} />
      </section>
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
