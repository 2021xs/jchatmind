import type { Agent, ChatSession, CodeRepository } from "../types";
import { ConversationList } from "./ConversationList";
import { RepositoryList } from "./RepositoryList";

export function Sidebar({
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

  return (
    <aside className="sidebar">
      <RepositoryList
        repositories={repositories}
        selectedRepoId={selectedRepoId}
        onSelectRepo={onSelectRepo}
      />
      <ConversationList
        sessions={sessions}
        agents={agents}
        selectedRepo={selectedRepo}
        selectedSessionId={selectedSessionId}
        selectedAgentId={selectedAgentId}
        onSelectAgent={onSelectAgent}
        onSelectSession={onSelectSession}
        onCreateSession={onCreateSession}
      />
    </aside>
  );
}
