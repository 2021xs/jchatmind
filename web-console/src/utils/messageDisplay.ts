import type {
  Agent,
  AgentSseEvent,
  ChatMessage,
  ChatSession,
  CodeRepository,
  SseStatus,
  ToolCallTrace,
  WebConsoleModel,
} from "../types";

export const MAX_VISIBLE_SESSIONS = 24;
export const LONG_TEXT_LIMIT = 1200;
export const WEB_CONSOLE_MODELS: Array<{ value: WebConsoleModel; label: string }> = [
  { value: "gpt-5.5", label: "GPT 5.5" },
  { value: "deepseek-chat", label: "DeepSeek Chat" },
];

export const DEFAULT_WEB_CONSOLE_MODEL: WebConsoleModel = "gpt-5.5";

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

export function codeAssistantAgent(agents: Agent[]): Agent | undefined {
  const hasCodeSearchTool = (agent: Agent) =>
    (agent.allowedTools ?? []).some((tool) =>
      tool.toLowerCase().includes("searchprojectcode"),
    );
  const onlyCodeSearchTool = (agent: Agent) => {
    const tools = agent.allowedTools ?? [];
    return tools.length === 1 && hasCodeSearchTool(agent);
  };
  return agents.find(onlyCodeSearchTool) ?? agents.find(hasCodeSearchTool);
}

export function modelLabel(model?: string): string {
  return WEB_CONSOLE_MODELS.find((item) => item.value === model)?.label ?? model ?? "未记录";
}

export function normalizeWebConsoleModel(model?: string): WebConsoleModel {
  return WEB_CONSOLE_MODELS.some((item) => item.value === model)
    ? (model as WebConsoleModel)
    : DEFAULT_WEB_CONSOLE_MODEL;
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

export function asAgentSseEvent(data: string): AgentSseEvent | null {
  try {
    return JSON.parse(data) as AgentSseEvent;
  } catch {
    return null;
  }
}
