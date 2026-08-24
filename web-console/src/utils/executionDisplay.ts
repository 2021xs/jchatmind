import type { AgentSseEvent, AgentStepTrace, ToolCallTrace } from "../types";
import { normalizeToolContent } from "./evidence.js";

export interface ToolInvocationDisplay {
  toolName: string;
  query?: string;
  argumentsText?: string;
}

export interface StepDisplay {
  title: string;
  description: string;
  invocations: ToolInvocationDisplay[];
}

export interface EventDisplay {
  label: string;
  description: string;
  color: string;
}

/**
 * Build diagnostics only from explicit execution state. Tool result summaries may
 * contain arbitrary repository code (for example, `ruoyi-auth`) and must never be
 * interpreted as infrastructure or selector errors.
 */
export function toolCallDiagnostics(call: ToolCallTrace): string[] {
  const diagnostics: string[] = [];
  const failure = firstMeaningfulLine(call.errorMessage);
  if (failure) {
    diagnostics.push(`工具失败：${failure}`);
  }
  if (call.blockedByPolicy) {
    diagnostics.push("安全策略：已拦截本次工具调用");
  }
  return Array.from(new Set(diagnostics));
}

export function parseToolInvocations(value?: string): ToolInvocationDisplay[] {
  if (!value) {
    return [];
  }
  return normalizeToolContent(value)
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .flatMap((line) => {
      const match = line.match(/^([\w.-]+)\((.*)\)$/);
      if (!match) {
        return [];
      }
      const toolName = match[1];
      const argumentsText = match[2]?.trim();
      if (!toolName) {
        return [];
      }
      let query: string | undefined;
      if (argumentsText) {
        try {
          const parsed = JSON.parse(argumentsText) as Record<string, unknown>;
          query = stringValue(parsed.query) ?? stringValue(parsed.keyword);
        } catch {
          query = quotedField(argumentsText, "query") ?? quotedField(argumentsText, "keyword");
        }
      }
      return [{ toolName, query, argumentsText }];
    });
}

export function stepDisplay(step: AgentStepTrace): StepDisplay {
  const stepType = (step.stepType ?? "").toUpperCase();
  const inputInvocations = parseToolInvocations(step.inputSummary);
  const outputInvocations = parseToolInvocations(step.outputSummary);
  const invocations = inputInvocations.length > 0 ? inputInvocations : outputInvocations;

  if (stepType === "THINK") {
    return {
      title: "分析问题",
      description:
        outputInvocations.length > 0
          ? `已规划 ${outputInvocations.length} 个后续检索主题`
          : "正在整理已有信息并决定下一步",
      invocations: [],
    };
  }
  if (stepType === "TOOL_CALL") {
    const names = unique(invocations.map((item) => toolDisplayName(item.toolName)));
    return {
      title: names.length === 1 ? names[0] ?? "调用工具" : "调用工具",
      description:
        invocations.length > 0
          ? `已完成 ${invocations.length} 个查询主题${names.length > 0 ? ` · ${names.join("、")}` : ""}`
          : "工具调用已执行",
      invocations,
    };
  }
  if (stepType === "CONTEXT_COMPRESSION") {
    return {
      title: "整理会话上下文",
      description: "已压缩较长历史，并保留最近相关信息",
      invocations: [],
    };
  }
  if (stepType === "FINAL_SYNTHESIS") {
    return {
      title: "生成最终回答",
      description: step.errorMessage ? "最终回答生成失败" : "已根据检索证据完成回答",
      invocations: [],
    };
  }
  if (stepType === "FINISH") {
    return {
      title: "完成任务",
      description: step.finishReason ? `任务已结束 · ${step.finishReason}` : "本次任务已结束",
      invocations: [],
    };
  }
  return {
    title: stepType ? humanizeCode(stepType) : "执行步骤",
    description: step.errorMessage ? "本步骤执行失败" : "本步骤已执行",
    invocations,
  };
}

export function eventDisplay(event: AgentSseEvent): EventDisplay {
  const payload = event.payload ?? {};
  const type = event.type ?? "event";
  switch (type) {
    case "message_start":
      return eventInfo("任务开始", "已接收请求，开始执行");
    case "final_message_start":
      return eventInfo("开始生成回答", "正在流式生成最终回答");
    case "final_message_done":
      return eventSuccess("回答已完成", "最终消息已落库并完成绑定");
    case "final_message_abort":
      return eventWarning("回答已中止", stringValue(payload.reason) ?? "最终回答生成已中止");
    case "tool_call_start":
      return eventInfo(
        "开始使用工具",
        `${toolDisplayName(stringValue(payload.actualToolName) ?? stringValue(payload.toolName) ?? "工具")}正在执行`,
      );
    case "tool_call_result":
      return eventSuccess(
        "工具执行完成",
        `${toolDisplayName(stringValue(payload.actualToolName) ?? stringValue(payload.toolName) ?? "工具")}已返回结果`,
      );
    case "retrieval_result":
      return eventSuccess("检索完成", "已获得可用于回答的检索结果");
    case "step_done":
      return eventSuccess(
        "步骤完成",
        stringValue(payload.stepType)
          ? `${humanizeCode(String(payload.stepType))} 已完成`
          : "一个执行步骤已完成",
      );
    case "done":
      return eventSuccess(
        "任务结束",
        stringValue(payload.finishReason)
          ? `任务已完成 · ${payload.finishReason}`
          : "本次任务已正常结束",
      );
    case "cancelled":
      return eventWarning("任务已取消", "已响应用户取消请求");
    case "error":
      return {
        label: "执行异常",
        description: stringValue(payload.errorMessage) ?? "执行过程中发生异常",
        color: "red",
      };
    default:
      return eventInfo(humanizeCode(type), "已收到一条运行事件");
  }
}

export function statusLabel(status?: string): string {
  switch ((status ?? "").toUpperCase()) {
    case "SUCCESS":
    case "COMPLETED":
      return "成功";
    case "RUNNING":
      return "进行中";
    case "FAILED":
    case "CRASHED":
    case "ERROR":
      return "失败";
    case "CANCELLED":
      return "已取消";
    default:
      return status || "未知";
  }
}

export function toolDisplayName(toolName?: string): string {
  if (!toolName) {
    return "工具调用";
  }
  if (toolName === "searchProjectCode") {
    return "代码检索";
  }
  if (toolName === "databaseQuery" || /database.*query/i.test(toolName)) {
    return "数据库查询";
  }
  if (toolName.startsWith("mcp_")) {
    return "外部信息检索";
  }
  return humanizeCode(toolName);
}

export function compactIdentifier(value?: string): string | undefined {
  if (!value) {
    return undefined;
  }
  return value.length > 18 ? `${value.slice(0, 8)}…${value.slice(-6)}` : value;
}

function eventInfo(label: string, description: string): EventDisplay {
  return { label, description, color: "blue" };
}

function eventSuccess(label: string, description: string): EventDisplay {
  return { label, description, color: "green" };
}

function eventWarning(label: string, description: string): EventDisplay {
  return { label, description, color: "orange" };
}

function stringValue(value: unknown): string | undefined {
  return typeof value === "string" && value.trim() ? value.trim() : undefined;
}

function firstMeaningfulLine(value?: string): string | undefined {
  return value
    ?.split(/\r?\n/)
    .map((line) => line.trim())
    .find(Boolean)
    ?.slice(0, 260);
}

function quotedField(value: string, field: string): string | undefined {
  const match = value.match(new RegExp(`"${field}"\\s*:\\s*"((?:\\\\.|[^"\\\\])*)"`));
  if (!match?.[1]) {
    return undefined;
  }
  try {
    return JSON.parse(`"${match[1]}"`) as string;
  } catch {
    return match[1];
  }
}

function humanizeCode(value: string): string {
  return value
    .replace(/^mcp_/, "")
    .replace(/[_-]+/g, " ")
    .replace(/([a-z])([A-Z])/g, "$1 $2")
    .trim();
}

function unique(values: string[]): string[] {
  return Array.from(new Set(values.filter(Boolean)));
}
