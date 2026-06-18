import { Button, Empty, Popconfirm, Select } from "antd";
import { CommentOutlined, DeleteOutlined, PlusOutlined } from "@ant-design/icons";
import type { Agent, ChatSession, CodeRepository, WebConsoleModel } from "../types";
import {
  MAX_VISIBLE_SESSIONS,
  formatDate,
  modelLabel,
  WEB_CONSOLE_MODELS,
} from "../utils/messageDisplay";
import { SectionHeader } from "./common";

export function ConversationList({
  sessions,
  agents,
  selectedRepo,
  selectedSessionId,
  selectedModel,
  onSelectModel,
  onSelectSession,
  onCreateSession,
  onDeleteSession,
}: {
  sessions: ChatSession[];
  agents: Agent[];
  selectedRepo?: CodeRepository;
  selectedSessionId?: string;
  selectedModel: WebConsoleModel;
  onSelectModel: (model: WebConsoleModel) => void;
  onSelectSession: (sessionId: string) => void;
  onCreateSession: () => void;
  onDeleteSession: (sessionId: string) => Promise<void>;
}) {
  const visibleSessions = sessions.slice(0, MAX_VISIBLE_SESSIONS);

  return (
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
        className="model-select"
        size="small"
        placeholder="选择模型"
        value={selectedModel}
        options={WEB_CONSOLE_MODELS}
        onChange={onSelectModel}
      />
      <div className="binding-hint">
        当前助手：代码助手。当前 repo: {selectedRepo?.name ?? "未选择"}。
        新建 Web Console 会话会写入 WEB_CONSOLE channel、repoId 和 model。
      </div>
      {sessions.length === 0 ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="新建会话开始提问" />
      ) : (
        <>
          <div className="conversation-list">
            {visibleSessions.map((session) => (
              <div
                className={`nav-row compact ${
                  session.id === selectedSessionId ? "selected" : ""
                }`}
                key={session.id}
                role="button"
                tabIndex={0}
                onClick={() => onSelectSession(session.id)}
                onKeyDown={(event) => {
                  if (event.key === "Enter" || event.key === " ") {
                    event.preventDefault();
                    onSelectSession(session.id);
                  }
                }}
              >
                <span className="row-head">
                  <span className="row-main">{session.title || "未命名会话"}</span>
                  <span
                    className="row-action"
                    onClick={(event) => event.stopPropagation()}
                    onKeyDown={(event) => event.stopPropagation()}
                  >
                    <Popconfirm
                      title="确认删除该 Web Console 会话？"
                      description="删除后会话历史将不可恢复。消息、Trace 和工具日志是否清理取决于后端当前实现。"
                      okText="删除"
                      cancelText="取消"
                      okButtonProps={{ danger: true }}
                      onConfirm={() => onDeleteSession(session.id)}
                    >
                      <Button
                        aria-label={`删除会话 ${session.title || session.id}`}
                        size="small"
                        type="text"
                        danger
                        icon={<DeleteOutlined />}
                      />
                    </Popconfirm>
                  </span>
                </span>
                <span className="row-meta">
                  <span>模型：{modelLabel(session.model)}</span>
                  {!session.model ? (
                    <span title="历史会话未记录 model">
                      fallback: {modelLabel(agentModel(agents, session.agentId))}
                    </span>
                  ) : null}
                </span>
                <span className="row-time">
                  {formatDate(session.updatedAt ?? session.createdAt)}
                </span>
              </div>
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
  );
}

function agentModel(agents: Agent[], agentId: string): string | undefined {
  return agents.find((agent) => agent.id === agentId)?.model;
}
