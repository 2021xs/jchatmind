import { Button, Empty, Select } from "antd";
import { CommentOutlined, PlusOutlined } from "@ant-design/icons";
import type { Agent, ChatSession, CodeRepository } from "../types";
import {
  MAX_VISIBLE_SESSIONS,
  agentName,
  formatDate,
  selectedAgentCapability,
} from "../utils/messageDisplay";
import { SectionHeader } from "./common";

export function ConversationList({
  sessions,
  agents,
  selectedRepo,
  selectedSessionId,
  selectedAgentId,
  onSelectAgent,
  onSelectSession,
  onCreateSession,
}: {
  sessions: ChatSession[];
  agents: Agent[];
  selectedRepo?: CodeRepository;
  selectedSessionId?: string;
  selectedAgentId?: string;
  onSelectAgent: (agentId: string) => void;
  onSelectSession: (sessionId: string) => void;
  onCreateSession: () => void;
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
        className="agent-select"
        size="small"
        placeholder="选择 Agent"
        value={selectedAgentId}
        options={agents.map((agent) => {
          const capability = selectedAgentCapability(agent);
          return {
            value: agent.id,
            label: `${agent.name}${agent.model ? ` / ${agent.model}` : ""} - ${capability.description}`,
          };
        })}
        onChange={onSelectAgent}
        notFoundContent="暂无 Agent"
      />
      <div className="binding-hint">
        当前 repo: {selectedRepo?.name ?? "未选择"}。新建 Web Console 会话会写入
        WEB_CONSOLE channel 和 repoId。
      </div>
      {sessions.length === 0 ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="新建会话开始提问" />
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
  );
}
