import { useCallback, useMemo, useState } from "react";
import {
  createChatSession,
  getAgentTraces,
  getAgents,
  getChatMessages,
  getChatSessions,
  getRepositories,
} from "../api/client";
import type { DetailMode, RuntimeState } from "../types";
import { errorMessage, hasId, sortSessions } from "../utils/messageDisplay";

export const defaultConsoleState: RuntimeState = {
  repositories: [],
  agents: [],
  sessions: [],
  messages: [],
  traces: [],
  sseEvents: [],
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

  const selectedAgent = useMemo(
    () => state.agents.find((agent) => agent.id === state.selectedAgentId),
    [state.agents, state.selectedAgentId],
  );

  const selectedTrace = useMemo(
    () =>
      state.traces.find((trace) => trace.id === state.selectedTraceId) ??
      state.traces[0],
    [state.traces, state.selectedTraceId],
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
        const selectedAgentId =
          previous.selectedAgentId && hasId(agents, previous.selectedAgentId)
            ? previous.selectedAgentId
            : sorted.find((session) => session.id === selectedSessionId)?.agentId ??
              agents[0]?.id;
        return {
          ...previous,
          repositories,
          agents,
          sessions: sorted,
          selectedRepoId:
            previous.selectedRepoId && hasId(repositories, previous.selectedRepoId)
              ? previous.selectedRepoId
              : repositories[0]?.id,
          selectedSessionId,
          selectedAgentId,
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

  const createSession = useCallback(
    async (title = "Web Console 会话") => {
      const agentId = state.selectedAgentId ?? state.agents[0]?.id;
      if (!agentId) {
        throw new Error("需要先有可用 Agent，才能创建会话");
      }
      const sessionId = await createChatSession(agentId, title, state.selectedRepoId);
      const sessions = await getChatSessions();
      setState((previous) => ({
        ...previous,
        sessions: sortSessions(sessions),
        selectedSessionId: sessionId,
        selectedAgentId: agentId,
        detailMode: "trace",
      }));
      return sessionId;
    },
    [state.agents, state.selectedAgentId, state.selectedRepoId],
  );

  const selectSession = useCallback(
    (sessionId: string) => {
      const session = state.sessions.find((item) => item.id === sessionId);
      setState((previous) => ({
        ...previous,
        selectedSessionId: sessionId,
        selectedAgentId: session?.agentId ?? previous.selectedAgentId,
        selectedTraceId: undefined,
      }));
    },
    [state.sessions],
  );

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
    selectedAgent,
    selectedTrace,
    visibleToolCalls,
    refreshConsole,
    refreshSessionData,
    createSession,
    selectSession,
    openDetail,
  };
}
