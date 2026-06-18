import type { ReactNode } from "react";

export type LoadState = "idle" | "loading" | "ready" | "error";

export type DetailMode = "trace" | "tools" | "events";

export type SseStatus = "disconnected" | "connecting" | "connected" | "error";

export type WebConsoleModel = "gpt-5.5" | "deepseek-chat";

export type MessageStatus =
  | "idle"
  | "sending"
  | "generating"
  | "completed"
  | "failed";

export interface CodeRepository {
  id: string;
  name: string;
  rootPath?: string;
  language?: string;
  status?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface Agent {
  id: string;
  name: string;
  description?: string;
  model?: string;
  allowedTools?: string[];
  allowedKbs?: string[];
}

export interface ChatSession {
  id: string;
  agentId: string;
  title?: string;
  channel?: string;
  repoId?: string;
  model?: WebConsoleModel | string;
  createdAt?: string;
  updatedAt?: string;
  metadata?: Record<string, unknown>;
}

export type MessageRole = "user" | "assistant" | "system" | "tool";

export interface ChatMessage {
  id: string;
  sessionId: string;
  role: MessageRole;
  content: string;
  metadata?: ChatMessageMetadata;
  createdAt?: string;
  updatedAt?: string;
}

export interface ChatMessageMetadata {
  toolResponse?: {
    id?: string;
    name?: string;
    responseData?: string;
  };
  toolCalls?: Array<{
    id?: string;
    type?: string;
    name?: string;
    arguments?: string;
  }>;
  [key: string]: unknown;
}

export interface AgentTaskTrace {
  id: string;
  sessionId?: string;
  agentId?: string;
  userMessageId?: string;
  status?: string;
  goal?: string;
  finishReason?: string;
  modelName?: string;
  maxSteps?: number;
  actualSteps?: number;
  toolCallCount?: number;
  latencyMs?: number;
  traceId?: string;
  startedAt?: string;
  finishedAt?: string;
  errorMessage?: string;
  steps: AgentStepTrace[];
  toolCalls: ToolCallTrace[];
}

export interface AgentStepTrace {
  id: string;
  taskId: string;
  stepNo?: number;
  stepType?: string;
  status?: string;
  inputSummary?: string;
  outputSummary?: string;
  latencyMs?: number;
  modelName?: string;
  llmLatencyMs?: number;
  inputTokens?: number;
  outputTokens?: number;
  finishReason?: string;
  startedAt?: string;
  finishedAt?: string;
  errorMessage?: string;
}

export interface ToolCallTrace {
  id: string;
  taskId: string;
  stepId?: string;
  toolName?: string;
  actualToolName?: string;
  toolCallId?: string;
  resultSummary?: string;
  status?: string;
  latencyMs?: number;
  errorMessage?: string;
  errorType?: string;
  blockedByPolicy?: boolean;
  argumentTruncated?: boolean;
  resultTruncated?: boolean;
  startedAt?: string;
  finishedAt?: string;
}

export interface AgentSseEvent {
  eventId?: string;
  taskId?: string;
  sessionId?: string;
  type?: string;
  timestamp?: string;
  payload?: Record<string, unknown>;
}

export interface LegacySseMessage {
  type?: string;
  payload?: {
    message?: ChatMessage;
    statusText?: string;
    done?: boolean;
  };
  metadata?: Record<string, unknown>;
}

export interface WebConsoleChatSendResponse {
  userMessageId: string;
  assistantMessageId?: string;
  runId?: string;
  conversationId: string;
  sseUrl?: string;
}

export interface CodeEvidence {
  filePath?: string;
  lineRange?: string;
  chunkType?: string;
  symbolName?: string;
  apiPath?: string;
  httpMethod?: string;
  score?: string;
}

export interface ToolMessageSummary {
  toolName: string;
  summary: string;
  evidence: CodeEvidence[];
}

export interface RuntimeState {
  repositories: CodeRepository[];
  agents: Agent[];
  sessions: ChatSession[];
  messages: ChatMessage[];
  traces: AgentTaskTrace[];
  sseEvents: AgentSseEvent[];
  selectedRepoId?: string;
  selectedSessionId?: string;
  selectedAgentId?: string;
  selectedModel: WebConsoleModel;
  selectedTraceId?: string;
  loadState: LoadState;
  error?: string;
  sessionError?: string;
  sending: boolean;
  messageStatus: MessageStatus;
  activeRunId?: string;
  activeUserMessageId?: string;
  detailOpen: boolean;
  detailMode: DetailMode;
  sseStatus: SseStatus;
}

export interface SectionHeaderProps {
  icon: ReactNode;
  title: string;
  count: number;
  action?: ReactNode;
}
