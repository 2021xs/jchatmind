import type {
  Agent,
  AgentTaskTrace,
  ChatMessage,
  ChatSession,
  CodeRepository,
} from "../types";

interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
}

type QueryValue = string | number | boolean | null | undefined;

const configuredApiBase = import.meta.env.VITE_JCHATMIND_API_BASE_URL;
const configuredSseBase = import.meta.env.VITE_JCHATMIND_SSE_BASE_URL;

export const API_BASE_URL = normalizeBaseUrl(configuredApiBase, "/api");
export const SSE_BASE_URL = normalizeBaseUrl(configuredSseBase, "/sse");

function normalizeBaseUrl(value: string | undefined, fallback: string): string {
  const trimmed = value?.trim();
  if (!trimmed) {
    return fallback;
  }
  return trimmed.replace(/\/+$/, "");
}

function buildUrl(path: string, query?: Record<string, QueryValue>): string {
  const prefix = path.startsWith("/") ? "" : "/";
  const url = `${API_BASE_URL}${prefix}${path}`;
  const searchParams = new URLSearchParams();
  Object.entries(query ?? {}).forEach(([key, value]) => {
    if (value !== null && value !== undefined && value !== "") {
      searchParams.set(key, String(value));
    }
  });
  const queryString = searchParams.toString();
  return queryString ? `${url}?${queryString}` : url;
}

async function request<T>(
  path: string,
  options: RequestInit = {},
  query?: Record<string, QueryValue>,
): Promise<T> {
  const response = await fetch(buildUrl(path, query), {
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...options.headers,
    },
  });

  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`);
  }

  const body = (await response.json()) as ApiResponse<T>;
  if (body.code !== 200) {
    throw new Error(body.message || "Request failed");
  }
  return body.data;
}

export async function getRepositories(): Promise<CodeRepository[]> {
  const data = await request<{ repositories: CodeRepository[] }>(
    "/code-repositories",
  );
  return data.repositories ?? [];
}

export async function getAgents(): Promise<Agent[]> {
  const data = await request<{ agents: Agent[] }>("/agents");
  return data.agents ?? [];
}

export async function getChatSessions(): Promise<ChatSession[]> {
  const data = await request<{ chatSessions: ChatSession[] }>(
    "/chat-sessions",
  );
  return data.chatSessions ?? [];
}

export async function createChatSession(
  agentId: string,
  title: string,
): Promise<string> {
  const data = await request<{ chatSessionId: string }>("/chat-sessions", {
    method: "POST",
    body: JSON.stringify({ agentId, title }),
  });
  return data.chatSessionId;
}

export async function getChatMessages(
  sessionId: string,
): Promise<ChatMessage[]> {
  const data = await request<{ chatMessages: ChatMessage[] }>(
    `/chat-messages/session/${sessionId}`,
  );
  return data.chatMessages ?? [];
}

export async function sendChatMessage(
  sessionId: string,
  agentId: string,
  content: string,
): Promise<string> {
  const data = await request<{ chatMessageId: string }>("/chat-messages", {
    method: "POST",
    body: JSON.stringify({
      sessionId,
      agentId,
      role: "user",
      content,
    }),
  });
  return data.chatMessageId;
}

export async function getAgentTraces(
  sessionId?: string,
): Promise<AgentTaskTrace[]> {
  const data = await request<{ traces: AgentTaskTrace[] }>(
    "/agent-traces",
    {},
    { sessionId },
  );
  return data.traces ?? [];
}

export function createSseConnection(sessionId: string): EventSource {
  const encodedSessionId = encodeURIComponent(sessionId);
  return new EventSource(`${SSE_BASE_URL}/connect/${encodedSessionId}`);
}
