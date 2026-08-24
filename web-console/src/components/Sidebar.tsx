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
  onDeleteAllSessions,
  onImportLocal,
  onImportGithub,
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
  onDeleteAllSessions: () => Promise<void>;
  onImportLocal: () => void;
  onImportGithub: () => void;
}) {
  const selectedRepo = repositories.find((repo) => repo.id === selectedRepoId);

  return (
    <aside className="sidebar">
      <RepositoryList
        repositories={repositories}
        selectedRepoId={selectedRepoId}
        selectionDisabled={Boolean(selectedSessionId)}
        onSelectRepo={onSelectRepo}
        onDeleteRepo={onDeleteRepo}
        onImportLocal={onImportLocal}
        onImportGithub={onImportGithub}
      />
      <ConversationList
        sessions={sessions}
        repositories={repositories}
        agents={agents}
        selectedRepo={selectedRepo}
        selectedSessionId={selectedSessionId}
        selectedModel={selectedModel}
        onSelectModel={onSelectModel}
        onSelectSession={onSelectSession}
        onCreateSession={onCreateSession}
        creatingSession={creatingSession}
        onDeleteSession={onDeleteSession}
        onDeleteAllSessions={onDeleteAllSessions}
      />
    </aside>
  );
}
