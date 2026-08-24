import { useCallback, useState } from "react";
import { cancelAgentTask, sendChatMessage } from "../api/client";
import type { ChatSession, RuntimeState, WebConsoleModel } from "../types";
import { errorMessage, upsertMessage } from "../utils/messageDisplay";
import { discardTerminalProvisionalMessages } from "../utils/streamingMessage";

export function useChatSend({
  selectedSession,
  selectedModel,
  selectedSessionId,
  activeTaskId,
  setState,
  refreshSessionData,
  onError,
}: {
  selectedSession?: ChatSession;
  selectedModel: WebConsoleModel;
  selectedSessionId?: string;
  activeTaskId?: string;
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
    const repoId = selectedSession?.repoId;
    if (!selectedSessionId || !selectedSession) {
      onError("请选择或新建会话");
      return;
    }
    if (!repoId) {
      onError("SESSION_REPOSITORY_UNBOUND: 请新建会话");
      return;
    }
    setDraft("");
    setState((previous) => ({
      ...previous,
      sending: true,
      messageStatus: "sending",
      activeRunId: undefined,
      activeTaskId: undefined,
      activeUserMessageId: undefined,
      selectedTraceId: undefined,
      sessionError: undefined,
      messages: discardTerminalProvisionalMessages(previous.messages),
    }));
    try {
      const response = await sendChatMessage(
        selectedSessionId,
        selectedModel,
        repoId,
        content,
      );
      setState((previous) => ({
        ...previous,
        sending: false,
        messageStatus: "generating",
        activeRunId: response.runId,
        activeTaskId: response.taskId,
        activeUserMessageId: response.userMessageId,
        selectedTraceId: response.taskId,
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
    refreshSessionData,
    selectedModel,
    selectedSession,
    selectedSessionId,
    setState,
  ]);

  const handleStop = useCallback(async () => {
    if (!selectedSessionId) {
      return;
    }
    const taskId = activeTaskId;
    setState((previous) => taskId ? { ...previous, messageStatus: "cancelling" } : previous);
    if (!taskId) {
      return;
    }
    try {
      const response = await cancelAgentTask(taskId, selectedSessionId);
      if (response.status === "TASK_ALREADY_FINISHED") {
        await refreshSessionData(selectedSessionId);
        setState((previous) => ({
          ...previous,
          messageStatus: "completed",
          activeTaskId: undefined,
        }));
      }
    } catch (error) {
      onError(errorMessage(error));
      setState((previous) => ({ ...previous, messageStatus: "generating" }));
    }
  }, [activeTaskId, onError, refreshSessionData, selectedSessionId, setState]);

  return {
    draft,
    setDraft,
    handleSend,
    handleStop,
  };
}
