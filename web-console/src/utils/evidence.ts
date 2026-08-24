import type { ChatMessage, CodeEvidence, ToolMessageSummary } from "../types";

// Temporary compatibility parser for legacy raw tool results. The backend should
// eventually return structured display VO fields for Web Console evidence.
export function parseCodeEvidence(content: string): CodeEvidence[] {
  const normalized = normalizeToolContent(content);
  const selectedEvidence = parseSelectedCodeEvidence(normalized);
  if (selectedEvidence.length > 0) {
    return selectedEvidence;
  }
  if (!normalized.includes("[code snippet]")) {
    return [];
  }
  return normalized
    .split("[code snippet]")
    .slice(1)
    .map((block, index) => ({
      index: index + 1,
      filePath: lineValue(block, "filePath"),
      lineRange: lineValue(block, "lineRange"),
      chunkType: lineValue(block, "chunkType"),
      symbolName: lineValue(block, "symbolName"),
      apiPath: lineValue(block, "apiPath"),
      httpMethod: lineValue(block, "httpMethod"),
      score: lineValue(block, "score"),
      snippet: snippetValue(block),
    }))
    .filter((item) => item.filePath || item.symbolName || item.apiPath);
}

export function summarizeToolMessage(message: ChatMessage): ToolMessageSummary {
  const content = normalizeToolContent(message.content);
  const toolName =
    message.metadata?.toolResponse?.name ?? inferToolName(content) ?? "tool result";
  const evidence = parseCodeEvidence(content);
  const summary =
    evidence.length > 0
      ? `命中 ${evidence.length} 个代码证据：${evidence
          .slice(0, 2)
          .map(formatEvidenceRef)
          .filter(Boolean)
          .join(", ")}`
      : firstMeaningfulLine(content) || "工具已返回结果，原始内容已折叠。";
  return { toolName, summary, evidence };
}

export function normalizeToolContent(content: string): string {
  if (!content) {
    return "";
  }
  const trimmed = content.trim();
  if (
    (trimmed.startsWith('"') && trimmed.endsWith('"')) ||
    (trimmed.startsWith("'") && trimmed.endsWith("'"))
  ) {
    try {
      const parsed = JSON.parse(trimmed) as unknown;
      if (typeof parsed === "string") {
        return parsed;
      }
    } catch {
      // Fall through to conservative unescape for legacy persisted tool strings.
    }
  }
  const unquoted = trimmed.startsWith('"') && !trimmed.endsWith('"')
    ? trimmed.slice(1)
    : trimmed;
  return unquoted
    .replace(/\\r\\n/g, "\n")
    .replace(/\\n/g, "\n")
    .replace(/\\"/g, '"');
}

export function formatEvidenceRef(item: CodeEvidence): string {
  const path = item.filePath ?? item.symbolName ?? item.apiPath ?? "";
  if (!path) {
    return "";
  }
  return item.lineRange ? `${path}:${item.lineRange}` : path;
}

function lineValue(block: string, key: string): string | undefined {
  const match = block.match(new RegExp(`^${key}:\\s*(.*)$`, "m"));
  const value = match?.[1]?.trim();
  return value || undefined;
}

function parseSelectedCodeEvidence(content: string): CodeEvidence[] {
  if (!content.includes("Selected code evidence")) {
    return [];
  }
  const matches = Array.from(
    content.matchAll(/(?:^|\n)\[(\d+)]\s*\n([\s\S]*?)(?=\n\[\d+]\s*\n|$)/g),
  );
  return matches
    .map((match) => {
      const block = match[2] ?? "";
      return {
        index: Number(match[1]),
        filePath: lineValue(block, "file"),
        symbolName: lineValue(block, "symbol"),
        chunkType: lineValue(block, "type"),
        lineRange: lineValue(block, "lines"),
        snippet: snippetValue(block),
      } satisfies CodeEvidence;
    })
    .filter((item) => item.filePath || item.symbolName || item.snippet);
}

function snippetValue(block: string): string | undefined {
  const match = block.match(/(?:^|\n)snippet:\s*\n([\s\S]*)$/);
  const value = match?.[1]?.trim();
  return value || undefined;
}

function inferToolName(content: string): string | undefined {
  if (content.includes("Selected code evidence")) {
    return "searchProjectCode";
  }
  if (content.includes("No related code evidence found")) {
    return "searchProjectCode";
  }
  return undefined;
}

function firstMeaningfulLine(content: string): string {
  return (
    content
      .split(/\r?\n/)
      .map((line) => line.trim())
      .find((line) => line.length > 0)
      ?.slice(0, 240) ?? ""
  );
}
