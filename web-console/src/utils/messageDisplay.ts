import type {
  Agent,
  AgentCapability,
  AgentSseEvent,
  ChatMessage,
  ChatSession,
  CodeRepository,
  SseStatus,
  ToolCallTrace,
} from "../types";

export const MAX_VISIBLE_SESSIONS = 24;
export const LONG_TEXT_LIMIT = 1200;

export function isPrimaryChatMessage(message: ChatMessage): boolean {
  if (message.role === "tool" || message.role === "system") {
    return false;
  }
  if (message.role === "assistant" && hasToolCalls(message)) {
    return false;
  }
  return true;
}

export function hasToolCalls(message: ChatMessage): boolean {
  return Array.isArray(message.metadata?.toolCalls) && message.metadata.toolCalls.length > 0;
}

export function sortSessions(sessions: ChatSession[]): ChatSession[] {
  return [...sessions].sort((left, right) => {
    const leftTime = Date.parse(left.updatedAt ?? left.createdAt ?? "");
    const rightTime = Date.parse(right.updatedAt ?? right.createdAt ?? "");
    return (Number.isNaN(rightTime) ? 0 : rightTime) - (Number.isNaN(leftTime) ? 0 : leftTime);
  });
}

export function hasId<T extends { id: string }>(items: T[], id: string): boolean {
  return items.some((item) => item.id === id);
}

export function upsertMessage(messages: ChatMessage[], incoming: ChatMessage): ChatMessage[] {
  if (messages.some((item) => item.id === incoming.id)) {
    return messages.map((item) => (item.id === incoming.id ? incoming : item));
  }
  return [...messages, incoming];
}

export function errorMessage(error: unknown): string {
  if (error instanceof Error) {
    return error.message;
  }
  return String(error);
}

export function roleText(role: string): string {
  if (role === "assistant") {
    return "Assistant";
  }
  if (role === "user") {
    return "User";
  }
  if (role === "tool") {
    return "Tool";
  }
  return role;
}

export function agentName(agents: Agent[], agentId: string): string {
  return agents.find((agent) => agent.id === agentId)?.name ?? agentId;
}

export function selectedAgentCapability(agent?: Agent): AgentCapability {
  const fallback = fallbackAgentCapability(agent);
  const description = agent?.description?.trim();
  return {
    ...fallback,
    description: description || fallback.description,
    detail: description || fallback.detail,
  };
}

export function isPlainAgent(agent?: Agent): boolean {
  const key = `${agent?.name ?? ""} ${agent?.id ?? ""}`.toLowerCase();
  const tools = agent?.allowedTools ?? [];
  if (tools.some((tool) => tool.toLowerCase().includes("searchprojectcode"))) {
    return false;
  }
  return key.includes("plain-agent") || key.includes("plain agent") || key.includes("plain");
}

export function statusColor(status?: string): string {
  if (status === "SUCCESS" || status === "COMPLETED") {
    return "green";
  }
  if (status === "FAILED" || status === "CRASHED" || status === "ERROR") {
    return "red";
  }
  if (status === "RUNNING") {
    return "blue";
  }
  return "default";
}

export function repoStatusColor(status?: string): string {
  if (status === "READY" || status === "SUCCESS") {
    return "green";
  }
  if (status === "FAILED") {
    return "red";
  }
  if (status === "IMPORTING" || status === "RUNNING") {
    return "blue";
  }
  return "default";
}

export function sseStatusColor(status: SseStatus): string {
  if (status === "connected") {
    return "green";
  }
  if (status === "error") {
    return "red";
  }
  if (status === "connecting") {
    return "blue";
  }
  return "default";
}

export function sseStatusLabel(status: SseStatus): string {
  if (status === "connected") {
    return "SSE connected";
  }
  if (status === "error") {
    return "SSE error";
  }
  if (status === "connecting") {
    return "SSE connecting";
  }
  return "SSE disconnected";
}

export function toolKind(call: ToolCallTrace): string {
  const name = call.actualToolName ?? call.toolName ?? "";
  return name.startsWith("mcp_") ? "MCP tool" : "local tool";
}

export function formatLatency(value?: number): string {
  if (typeof value !== "number") {
    return "n/a";
  }
  if (value >= 1000) {
    return `${(value / 1000).toFixed(1)}s`;
  }
  return `${value}ms`;
}

export function formatDate(value?: string): string {
  if (!value) {
    return "n/a";
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return date.toLocaleString("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}

export function summarizeEvent(payload?: Record<string, unknown>): string {
  if (!payload) {
    return "无 payload";
  }
  const keys = [
    "toolName",
    "actualToolName",
    "status",
    "resultSummary",
    "errorMessage",
    "finishReason",
  ];
  const values = keys
    .map((key) => payload[key])
    .filter((value) => typeof value === "string" && value.length > 0)
    .map(String);
  if (values.length > 0) {
    return values.join(" / ").slice(0, 280);
  }
  return JSON.stringify(payload).slice(0, 280);
}

export function readyRepositories(repositories: CodeRepository[]): CodeRepository[] {
  return repositories.filter((repo) => repo.status === "READY");
}

function fallbackAgentCapability(agent?: Agent): AgentCapability {
  const key = `${agent?.name ?? ""} ${agent?.id ?? ""} ${agent?.model ?? ""}`.toLowerCase();
  const tools = agent?.allowedTools ?? [];

  if (tools.some((tool) => tool.toLowerCase().includes("searchprojectcode")) || key.includes("code-agent")) {
    return {
      displayName: "code-agent",
      description: "代码问答，会调用 searchProjectCode",
      detail: "适合分析已导入仓库的代码链路、接口、SQL、常量和工具调用证据。",
      tone: "code",
    };
  }

  if (key.includes("gpt-5.5") || key.includes("gpt")) {
    return {
      displayName: "gpt-5.5",
      description: "GPT 模型对话能力",
      detail: "适合通用问答、解释和整理，不代表一定会主动检索代码。",
      tone: "model",
    };
  }

  if (key.includes("deepseek")) {
    return {
      displayName: "deepseek-chat",
      description: "DeepSeek 模型对话能力",
      detail: "适合通用问答、解释和整理，不展示模型隐藏思维链。",
      tone: "model",
    };
  }

  // TODO: 后续应由后端 AgentDTO/AgentVO 返回 displayName、description、capabilities。
  return {
    displayName: "plain-agent",
    description: "普通对话，不主动检索代码",
    detail: "当前 Agent 不会主动调用 searchProjectCode。若要分析代码，建议切换到 code-agent。",
    tone: "plain",
  };
}

export function asAgentSseEvent(data: string): AgentSseEvent | null {
  try {
    return JSON.parse(data) as AgentSseEvent;
  } catch {
    return null;
  }
}
