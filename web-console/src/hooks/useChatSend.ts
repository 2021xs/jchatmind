import { useCallback, useState } from "react";
import { sendChatMessage } from "../api/client";
import type { Agent, ChatSession, RuntimeState, WebConsoleModel } from "../types";
import { codeAssistantAgent, errorMessage, upsertMessage } from "../utils/messageDisplay";

export function useChatSend({
  selectedSession,
  agents,
  selectedAgentId,
  selectedModel,
  selectedRepoId,
  selectedSessionId,
  setState,
  refreshSessionData,
  onError,
}: {
  selectedSession?: ChatSession;
  agents: Agent[];
  selectedAgentId?: string;
  selectedModel: WebConsoleModel;
  selectedRepoId?: string;
  selectedSessionId?: string;
  setState: React.Dispatch<React.SetStateAction<RuntimeState>>;
  refreshSessionData: (sessionId: string) => Promise<void>;
  onError: (message: string) => void;
}) {
  const [draft, setDraft] = useState("");

  const handleSend = useCallback(async () => {
    const content = draft.trim();
    if (!content) {
      return;
    }
    const agentId = codeAssistantAgent(agents)?.id ?? selectedSession?.agentId ?? selectedAgentId;
    const repoId = selectedRepoId;
    if (!selectedSessionId || !agentId) {
      onError("请选择或新建会话");
      return;
    }
    if (!repoId) {
      onError("请选择仓库");
      return;
    }
    setDraft("");
    setState((previous) => ({
      ...previous,
      sending: true,
      messageStatus: "sending",
      activeRunId: undefined,
      activeUserMessageId: undefined,
      sessionError: undefined,
    }));
    try {
      const response = await sendChatMessage(
        selectedSessionId,
        agentId,
        selectedModel,
        repoId,
        content,
      );
      setState((previous) => ({
        ...previous,
        sending: false,
        messageStatus: "generating",
        activeRunId: response.runId,
        activeUserMessageId: response.userMessageId,
        messages: upsertMessage(previous.messages, {
          id: response.userMessageId,
          sessionId: response.conversationId,
          role: "user",
          content,
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
        }),
      }));
      await refreshSessionData(selectedSessionId);
    } catch (error) {
      onError(errorMessage(error));
      setDraft(content);
      setState((previous) => ({
        ...previous,
        sending: false,
        messageStatus: "failed",
      }));
    } finally {
      setState((previous) => ({ ...previous, sending: false }));
    }
  }, [
    draft,
    onError,
    agents,
    refreshSessionData,
    selectedAgentId,
    selectedModel,
    selectedRepoId,
    selectedSession,
    selectedSessionId,
    setState,
  ]);

  return {
    draft,
    setDraft,
    handleSend,
  };
}
