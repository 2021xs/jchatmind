import { useCallback, useMemo, useState } from "react";
import {
  createChatSession,
  deleteChatSession,
  deleteRepository,
  getAgentTraces,
  getAgents,
  getChatMessages,
  getChatSessions,
  getRepositories,
  getWebConsoleCapabilities,
} from "../api/client";
import type { DetailMode, RuntimeState, WebConsoleModel } from "../types";
import {
  DEFAULT_WEB_CONSOLE_MODEL,
  codeAssistantAgent,
  errorMessage,
  hasId,
  normalizeWebConsoleModel,
  readyRepositories,
  sortSessions,
} from "../utils/messageDisplay";
import { selectExecutionTrace } from "../utils/executionScope";
import { reconcileLoadedMessages } from "../utils/streamingMessage";

export const defaultConsoleState: RuntimeState = {
  repositories: [],
  agents: [],
  sessions: [],
  messages: [],
  traces: [],
  sseEvents: [],
  selectedModel: DEFAULT_WEB_CONSOLE_MODEL,
  loadState: "idle",
  sending: false,
  messageStatus: "idle",
  detailOpen: true,
  detailMode: "trace",
  sseStatus: "disconnected",
};

export function useConsoleState() {
  const [state, setState] = useState<RuntimeState>(defaultConsoleState);

  const selectedRepo = useMemo(
    () => state.repositories.find((repo) => repo.id === state.selectedRepoId),
    [state.repositories, state.selectedRepoId],
  );

  const sortedSessions = useMemo(() => sortSessions(state.sessions), [state.sessions]);

  const selectedSession = useMemo(
    () => state.sessions.find((session) => session.id === state.selectedSessionId),
    [state.sessions, state.selectedSessionId],
  );

  const selectedTrace = useMemo(
    () => selectExecutionTrace({
      traces: state.traces,
      activeTaskId: state.activeTaskId,
      selectedTraceId: state.selectedTraceId,
      activeUserMessageId: state.activeUserMessageId,
      activeRunId: state.activeRunId,
      taskPending: state.sending || state.messageStatus === "sending",
    }),
    [
      state.activeRunId,
      state.activeTaskId,
      state.activeUserMessageId,
      state.messageStatus,
      state.sending,
      state.traces,
      state.selectedTraceId,
    ],
  );

  const visibleToolCalls = useMemo(
    () => selectedTrace?.toolCalls ?? [],
    [selectedTrace],
  );

  const refreshConsole = useCallback(async () => {
    setState((previous) => ({
      ...previous,
      loadState: "loading",
      error: undefined,
    }));
    try {
      const [repositories, agents, sessions] = await Promise.all([
        getRepositories(),
        getAgents(),
        getChatSessions(),
      ]);
      const sorted = sortSessions(sessions);
      setState((previous) => {
        const selectedSessionId =
          previous.selectedSessionId && hasId(sorted, previous.selectedSessionId)
            ? previous.selectedSessionId
            : sorted[0]?.id;
        const sessionChanged = previous.selectedSessionId !== selectedSessionId;
        const selectedSession = sorted.find((session) => session.id === selectedSessionId);
        const assistantAgent = codeAssistantAgent(agents);
        const selectedRepoId =
          selectedSession?.repoId ??
          (selectedSessionId
            ? undefined
            : previous.selectedRepoId && hasId(repositories, previous.selectedRepoId)
              ? previous.selectedRepoId
              : readyRepositories(repositories)[0]?.id ?? repositories[0]?.id);
        const selectedModel = normalizeWebConsoleModel(
            selectedSession?.model ??
            previous.selectedModel,
        );
        return {
          ...previous,
          repositories,
          agents,
          sessions: sorted,
          selectedRepoId,
          selectedSessionId,
          selectedAgentId: assistantAgent?.id,
          selectedModel,
          loadState: "ready",
          messages: sessionChanged ? [] : previous.messages,
          traces: sessionChanged ? [] : previous.traces,
          sseEvents: sessionChanged ? [] : previous.sseEvents,
          selectedTraceId: sessionChanged ? undefined : previous.selectedTraceId,
          activeRunId: sessionChanged ? undefined : previous.activeRunId,
          activeTaskId: sessionChanged ? undefined : previous.activeTaskId,
          activeUserMessageId: sessionChanged ? undefined : previous.activeUserMessageId,
        };
      });
    } catch (error) {
      setState((previous) => ({
        ...previous,
        loadState: "error",
        error: errorMessage(error),
      }));
    }
  }, []);

  const refreshSessionData = useCallback(async (sessionId: string) => {
    try {
      const [messages, traces] = await Promise.all([
        getChatMessages(sessionId),
        getAgentTraces(sessionId),
      ]);
      setState((previous) => {
        if (previous.selectedSessionId !== sessionId) {
          return previous;
        }
        return {
          ...previous,
          messages: reconcileLoadedMessages(
            previous.messages,
            messages,
            sessionId,
            traces,
          ),
          traces,
          selectedTraceId:
            previous.activeTaskId ??
            (previous.selectedTraceId && hasId(traces, previous.selectedTraceId)
              ? previous.selectedTraceId
              : traces[0]?.id),
          sessionError: undefined,
        };
      });
    } catch (error) {
      setState((previous) => previous.selectedSessionId === sessionId
        ? {
            ...previous,
            sessionError: errorMessage(error),
          }
        : previous);
    }
  }, []);

  const refreshCapabilities = useCallback(async (
    repoId?: string,
    model: WebConsoleModel = DEFAULT_WEB_CONSOLE_MODEL,
  ) => {
    setState((previous) => ({
      ...previous,
      capabilityLoading: true,
      capabilityError: undefined,
    }));
    try {
      const capabilities = await getWebConsoleCapabilities(repoId, model);
      setState((previous) => ({
        ...previous,
        capabilities,
        capabilityLoading: false,
        capabilityError: undefined,
      }));
    } catch (error) {
      setState((previous) => ({
        ...previous,
        capabilityLoading: false,
        capabilityError: errorMessage(error),
      }));
    }
  }, []);

  const createSession = useCallback(
    async (title: string, repoId?: string) => {
      const trimmedTitle = title.trim();
      if (!trimmedTitle) {
        throw new Error("请输入会话名称");
      }
      const agentId = codeAssistantAgent(state.agents)?.id;
      if (!agentId) {
        throw new Error("需要先有可用代码助手 Agent，才能创建会话");
      }
      const sessionId = await createChatSession(
        agentId,
        trimmedTitle,
        state.selectedModel,
        repoId ?? state.selectedRepoId,
      );
      const sessions = await getChatSessions();
      setState((previous) => ({
        ...previous,
        sessions: sortSessions(sessions),
        selectedSessionId: sessionId,
        selectedRepoId: repoId ?? state.selectedRepoId,
        selectedAgentId: agentId,
        selectedModel: state.selectedModel,
        detailMode: "trace",
        messages: [],
        traces: [],
        sseEvents: [],
        selectedTraceId: undefined,
        activeRunId: undefined,
        activeTaskId: undefined,
        activeUserMessageId: undefined,
      }));
      return sessionId;
    },
    [state.agents, state.selectedModel, state.selectedRepoId],
  );

  const selectSession = useCallback(
    (sessionId: string) => {
      const session = state.sessions.find((item) => item.id === sessionId);
      setState((previous) => ({
        ...previous,
        selectedSessionId: sessionId,
        selectedRepoId: session?.repoId,
        selectedAgentId: codeAssistantAgent(previous.agents)?.id,
        selectedModel: normalizeWebConsoleModel(session?.model ?? previous.selectedModel),
        messages: [],
        traces: [],
        sseEvents: [],
        selectedTraceId: undefined,
        activeRunId: undefined,
        activeTaskId: undefined,
        activeUserMessageId: undefined,
        messageStatus: "idle",
      }));
    },
    [state.sessions],
  );

  const removeRepository = useCallback(async (repoId: string) => {
    await deleteRepository(repoId);
    const repositories = await getRepositories();
    setState((previous) => {
      const nextRepoId =
        previous.selectedRepoId === repoId
          ? readyRepositories(repositories).find((repo) => repo.id !== repoId)?.id ??
            repositories.find((repo) => repo.id !== repoId)?.id
          : previous.selectedRepoId && hasId(repositories, previous.selectedRepoId)
            ? previous.selectedRepoId
            : readyRepositories(repositories)[0]?.id ?? repositories[0]?.id;
      return {
        ...previous,
        repositories,
        selectedRepoId: nextRepoId,
      };
    });
  }, []);

  const removeSession = useCallback(async (sessionId: string) => {
    await deleteChatSession(sessionId);
    const sessions = sortSessions(await getChatSessions());
    setState((previous) => {
      const selectedSessionId =
        previous.selectedSessionId === sessionId
          ? sessions.find((session) => session.id !== sessionId)?.id
          : previous.selectedSessionId && hasId(sessions, previous.selectedSessionId)
            ? previous.selectedSessionId
            : sessions[0]?.id;
      const selectedSession = sessions.find((session) => session.id === selectedSessionId);
      const sessionChanged = previous.selectedSessionId !== selectedSessionId;
      return {
        ...previous,
        sessions,
        selectedSessionId,
        selectedRepoId: selectedSession?.repoId,
        selectedModel: normalizeWebConsoleModel(selectedSession?.model ?? previous.selectedModel),
        messages: sessionChanged ? [] : previous.messages,
        traces: sessionChanged ? [] : previous.traces,
        sseEvents: sessionChanged ? [] : previous.sseEvents,
        selectedTraceId: sessionChanged ? undefined : previous.selectedTraceId,
        activeRunId: sessionChanged ? undefined : previous.activeRunId,
        activeTaskId: sessionChanged ? undefined : previous.activeTaskId,
        activeUserMessageId: sessionChanged ? undefined : previous.activeUserMessageId,
        messageStatus: sessionChanged ? "idle" : previous.messageStatus,
      };
    });
  }, []);

  const openDetail = useCallback((mode: DetailMode, traceId?: string) => {
    setState((previous) => ({
      ...previous,
      detailOpen: true,
      detailMode: mode,
      selectedTraceId: traceId ?? previous.selectedTraceId,
    }));
  }, []);

  const removeAllSessions = useCallback(async () => {
    const sessionIds = state.sessions.map((session) => session.id);
    await Promise.all(sessionIds.map((sessionId) => deleteChatSession(sessionId)));
    const sessions = sortSessions(await getChatSessions());
    setState((previous) => ({
      ...previous,
      sessions,
      selectedSessionId: undefined,
      selectedRepoId: undefined,
      messages: [],
      traces: [],
      sseEvents: [],
      selectedTraceId: undefined,
      activeRunId: undefined,
      activeTaskId: undefined,
      activeUserMessageId: undefined,
    }));
  }, [state.sessions]);

  return {
    state,
    setState,
    selectedRepo,
    sortedSessions,
    selectedSession,
    selectedTrace,
    visibleToolCalls,
    refreshConsole,
    refreshSessionData,
    createSession,
    selectSession,
    removeRepository,
    removeSession,
    removeAllSessions,
    openDetail,
    refreshCapabilities,
  };
}
