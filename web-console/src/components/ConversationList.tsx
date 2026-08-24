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
  repositories,
  agents,
  selectedRepo,
  selectedSessionId,
  selectedModel,
  onSelectModel,
  onSelectSession,
  onCreateSession,
  creatingSession,
  onDeleteSession,
  onDeleteAllSessions,
}: {
  sessions: ChatSession[];
  repositories: CodeRepository[];
  agents: Agent[];
  selectedRepo?: CodeRepository;
  selectedSessionId?: string;
  selectedModel: WebConsoleModel;
  onSelectModel: (model: WebConsoleModel) => void;
  onSelectSession: (sessionId: string) => void;
  onCreateSession: () => void;
  creatingSession?: boolean;
  onDeleteSession: (sessionId: string) => Promise<void>;
  onDeleteAllSessions: () => Promise<void>;
}) {
  const visibleSessions = sessions.slice(0, MAX_VISIBLE_SESSIONS);

  return (
    <section className="sidebar-section">
      <SectionHeader
        icon={<CommentOutlined />}
        title="会话"
        count={sessions.length}
        action={
          <span className="section-header-actions">
            <Popconfirm
              title="确认删除全部会话？"
              description="会清理所有会话、消息、Trace 和工具日志，操作不可恢复。"
              okText="全部删除"
              cancelText="取消"
              okButtonProps={{ danger: true }}
              disabled={sessions.length === 0}
              onConfirm={onDeleteAllSessions}
            >
              <Button size="small" danger icon={<DeleteOutlined />} disabled={sessions.length === 0}>
                删除全部
              </Button>
            </Popconfirm>
          <Button
            size="small"
            icon={<PlusOutlined />}
            loading={creatingSession}
            onClick={onCreateSession}
          >
            新建
          </Button>
          </span>
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
                      description="删除后会清理该 Web Console 会话、消息、Agent Trace 和工具日志，操作不可恢复。"
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
                  <span>{repositoryName(session.repoId, repositories)}</span>
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

function repositoryName(repoId: string | undefined, repositories: CodeRepository[]): string {
  if (!repoId) {
    return "repository: unbound";
  }
  return repositories.find((repository) => repository.id === repoId)?.name ?? repoId;
}

function agentModel(agents: Agent[], agentId: string): string | undefined {
  return agents.find((agent) => agent.id === agentId)?.model;
}
