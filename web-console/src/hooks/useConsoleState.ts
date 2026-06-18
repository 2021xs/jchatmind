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
    () =>
      state.traces.find((trace) => trace.id === state.selectedTraceId) ??
      state.traces.find((trace) => trace.userMessageId === state.activeUserMessageId) ??
      state.traces.find((trace) => trace.traceId === state.activeRunId) ??
      state.traces[0],
    [state.activeRunId, state.activeUserMessageId, state.traces, state.selectedTraceId],
  );

  const visibleToolCalls = useMemo(
    () => state.traces.flatMap((trace) => trace.toolCalls ?? []),
    [state.traces],
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
        const assistantAgent = codeAssistantAgent(agents);
        const selectedRepoId =
          previous.selectedRepoId && hasId(repositories, previous.selectedRepoId)
            ? previous.selectedRepoId
            : readyRepositories(repositories)[0]?.id ?? repositories[0]?.id;
        const selectedModel = normalizeWebConsoleModel(
          sorted.find((session) => session.id === selectedSessionId)?.model ??
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
      setState((previous) => ({
        ...previous,
        messages,
        traces,
        selectedTraceId:
          previous.selectedTraceId && hasId(traces, previous.selectedTraceId)
            ? previous.selectedTraceId
            : traces[0]?.id,
        sessionError: undefined,
      }));
    } catch (error) {
      setState((previous) => ({
        ...previous,
        sessionError: errorMessage(error),
      }));
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
    async (title: string) => {
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
        state.selectedRepoId,
      );
      const sessions = await getChatSessions();
      setState((previous) => ({
        ...previous,
        sessions: sortSessions(sessions),
        selectedSessionId: sessionId,
        selectedAgentId: agentId,
        selectedModel: state.selectedModel,
        detailMode: "trace",
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
        selectedAgentId: codeAssistantAgent(previous.agents)?.id,
        selectedModel: normalizeWebConsoleModel(session?.model ?? previous.selectedModel),
        selectedTraceId: undefined,
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
      return {
        ...previous,
        sessions,
        selectedSessionId,
        selectedModel: normalizeWebConsoleModel(selectedSession?.model ?? previous.selectedModel),
        messages: selectedSessionId ? previous.messages : [],
        traces: selectedSessionId ? previous.traces : [],
        sseEvents: selectedSessionId ? previous.sseEvents : [],
        selectedTraceId: selectedSessionId ? previous.selectedTraceId : undefined,
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
    openDetail,
    refreshCapabilities,
  };
}
