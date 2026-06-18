import type { Agent, ChatSession, CodeRepository, WebConsoleModel } from "../types";
import { ConversationList } from "./ConversationList";
import { RepositoryList } from "./RepositoryList";

export function Sidebar({
  repositories,
  sessions,
  agents,
  selectedRepoId,
  selectedSessionId,
  selectedModel,
  onSelectRepo,
  onSelectModel,
  onSelectSession,
  onCreateSession,
  creatingSession,
  onDeleteRepo,
  onDeleteSession,
}: {
  repositories: CodeRepository[];
  sessions: ChatSession[];
  agents: Agent[];
  selectedRepoId?: string;
  selectedSessionId?: string;
  selectedModel: WebConsoleModel;
  onSelectRepo: (repoId: string) => void;
  onSelectModel: (model: WebConsoleModel) => void;
  onSelectSession: (sessionId: string) => void;
  onCreateSession: () => void;
  creatingSession?: boolean;
  onDeleteRepo: (repoId: string) => Promise<void>;
  onDeleteSession: (sessionId: string) => Promise<void>;
}) {
  const selectedRepo = repositories.find((repo) => repo.id === selectedRepoId);

  return (
    <aside className="sidebar">
      <RepositoryList
        repositories={repositories}
        selectedRepoId={selectedRepoId}
        onSelectRepo={onSelectRepo}
        onDeleteRepo={onDeleteRepo}
      />
      <ConversationList
        sessions={sessions}
        agents={agents}
        selectedRepo={selectedRepo}
        selectedSessionId={selectedSessionId}
        selectedModel={selectedModel}
        onSelectModel={onSelectModel}
        onSelectSession={onSelectSession}
        onCreateSession={onCreateSession}
        creatingSession={creatingSession}
        onDeleteSession={onDeleteSession}
      />
    </aside>
  );
}
