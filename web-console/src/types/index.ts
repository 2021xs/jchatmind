import type { ReactNode } from "react";

export type LoadState = "idle" | "loading" | "ready" | "error";

export type DetailMode = "trace" | "tools" | "events";

export type SseStatus = "disconnected" | "connecting" | "connected" | "error";

export type WebConsoleModel = "gpt-5.5" | "deepseek-chat";

export type MessageStatus =
  | "idle"
  | "sending"
  | "generating"
  | "cancelling"
  | "cancelled"
  | "completed"
  | "failed";

export interface CodeRepository {
  id: string;
  name: string;
  rootPath?: string;
  language?: string;
  status?: string;
  sourceType?: "LOCAL" | "GITHUB" | string;
  remoteUrl?: string;
  branch?: string;
  commitSha?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface RepositoryImportResponse {
  repoId?: string;
  fileCount?: number;
  chunkCount?: number;
  message?: string;
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

export type StreamingMessageStatus = "streaming" | "complete" | "aborted" | "failed";

export interface ChatMessage {
  id?: string;
  sessionId: string;
  role: MessageRole;
  content: string;
  metadata?: ChatMessageMetadata;
  createdAt?: string;
  updatedAt?: string;
  streamId?: string;
  taskId?: string;
  status?: StreamingMessageStatus;
  provisional?: boolean;
  lastSequence?: number;
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

export interface FinalMessageStartPayload {
  streamId: string;
  stepId: string;
  phase: "final_answer";
}

export interface FinalMessageTokenPayload {
  streamId: string;
  stepId: string;
  sequence: number;
  delta: string;
}

export interface FinalMessageDonePayload {
  streamId: string;
  stepId: string;
  messageId: string;
}

export interface FinalMessageAbortPayload {
  streamId: string;
  stepId: string;
  reason: string;
}

export type FinalStreamingSseEvent =
  | (AgentSseEvent & { type: "final_message_start"; payload: FinalMessageStartPayload })
  | (AgentSseEvent & { type: "token"; payload: FinalMessageTokenPayload })
  | (AgentSseEvent & { type: "final_message_done"; payload: FinalMessageDonePayload })
  | (AgentSseEvent & { type: "final_message_abort"; payload: FinalMessageAbortPayload });

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
  taskId: string;
  conversationId: string;
  sseUrl?: string;
}

export interface WebConsoleCapability {
  key: string;
  label: string;
  enabled: boolean;
  tools: string[];
  description?: string;
  reason?: string;
}

export interface WebConsoleCapabilitiesResponse {
  assistant: string;
  profile: string;
  model?: string;
  repoId?: string;
  capabilities: WebConsoleCapability[];
  notSupported: string[];
}

export interface CodeEvidence {
  index?: number;
  filePath?: string;
  lineRange?: string;
  chunkType?: string;
  symbolName?: string;
  apiPath?: string;
  httpMethod?: string;
  score?: string;
  snippet?: string;
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
  capabilities?: WebConsoleCapabilitiesResponse;
  selectedRepoId?: string;
  selectedSessionId?: string;
  selectedAgentId?: string;
  selectedModel: WebConsoleModel;
  selectedTraceId?: string;
  loadState: LoadState;
  error?: string;
  sessionError?: string;
  capabilityError?: string;
  capabilityLoading?: boolean;
  sending: boolean;
  messageStatus: MessageStatus;
  activeRunId?: string;
  activeTaskId?: string;
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
