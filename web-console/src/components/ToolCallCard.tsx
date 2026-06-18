import { Collapse, Space, Tag } from "antd";
import type { ToolCallTrace } from "../types";
import { parseCodeEvidence } from "../utils/evidence";
import { formatLatency, statusColor, toolKind } from "../utils/messageDisplay";
import { RawBlock } from "./common";
import { EvidenceList } from "./EvidenceList";

export function ToolCallSummaryCard({ call }: { call: ToolCallTrace }) {
  const summary = call.errorMessage ?? call.resultSummary ?? "暂无结果摘要";
  const evidence = parseCodeEvidence(summary);
  const diagnostics = selectorDiagnostics(summary, call);

  return (
    <div className="reasoning-tool-row">
      <div className="tool-card-head">
        <Space wrap size={6}>
          <strong>{call.actualToolName ?? call.toolName ?? "unknown_tool"}</strong>
          <Tag>{toolKind(call)}</Tag>
          <Tag color={statusColor(call.status)}>{call.status ?? "UNKNOWN"}</Tag>
        </Space>
        <span className="muted">{formatLatency(call.latencyMs)}</span>
      </div>
      <div className="tool-summary-text">{toolEvidenceSummary(summary, evidence.length)}</div>
      <Diagnostics diagnostics={diagnostics} />
      <EvidenceList evidence={evidence} />
      <Collapse
        ghost
        size="small"
        items={[
          {
            key: "tool-raw",
            label: "展开 raw detail",
            children: <RawBlock value={call} />,
          },
        ]}
      />
    </div>
  );
}

export function ToolCallCard({ call }: { call: ToolCallTrace }) {
  const denied = call.blockedByPolicy === true;
  const summary = call.errorMessage ?? call.resultSummary ?? "暂无结果摘要";
  const evidence = parseCodeEvidence(summary);
  const diagnostics = selectorDiagnostics(summary, call);

  return (
    <article className="tool-call-card">
      <div className="tool-card-head">
        <Space wrap size={6}>
          <Tag color={denied ? "red" : statusColor(call.status)}>
            {denied ? "DENIED" : call.status ?? "UNKNOWN"}
          </Tag>
          <Tag>{toolKind(call)}</Tag>
          <strong>{call.actualToolName ?? call.toolName ?? "unknown_tool"}</strong>
        </Space>
        <span className="muted">{formatLatency(call.latencyMs)}</span>
      </div>
      <div className="tool-summary-text">{toolEvidenceSummary(summary, evidence.length)}</div>
      <Diagnostics diagnostics={diagnostics} />
      <EvidenceList evidence={evidence} />
      <Space wrap size={6}>
        {call.errorType ? <Tag color="volcano">{call.errorType}</Tag> : null}
        {call.argumentTruncated ? <Tag>arguments truncated</Tag> : null}
        {call.resultTruncated ? <Tag>result truncated</Tag> : null}
      </Space>
      <Collapse
        ghost
        size="small"
        items={[
          {
            key: "tool-raw",
            label: "raw tool detail",
            children: <RawBlock value={call} />,
          },
        ]}
      />
    </article>
  );
}

function toolEvidenceSummary(summary: string, evidenceCount: number): string {
  if (evidenceCount > 0) {
    return `检索结果：命中 ${evidenceCount} 个代码片段`;
  }
  return firstMeaningfulLine(summary);
}

function selectorDiagnostics(summary: string, call: ToolCallTrace): string[] {
  const diagnostics: string[] = [];
  if (call.errorMessage) {
    diagnostics.push(`工具失败：${firstMeaningfulLine(call.errorMessage)}`);
  }
  if (/selectorFallback:\s*true/i.test(summary) || /fallback=true/i.test(summary)) {
    diagnostics.push("选择器：失败，已使用 fallback 证据选择");
  }
  if (/selectorJsonParseOk:\s*false/i.test(summary)) {
    diagnostics.push("选择器：JSON 解析失败");
  }
  if (/auth|unauthorized|401|403/i.test(summary)) {
    diagnostics.push("LLM 选择器认证失败或无权限");
  }
  if (call.blockedByPolicy) {
    diagnostics.push("安全策略：已拦截本次工具调用");
  }
  return Array.from(new Set(diagnostics));
}

function Diagnostics({ diagnostics }: { diagnostics: string[] }) {
  if (diagnostics.length === 0) {
    return null;
  }
  return (
    <div className="tool-diagnostics">
      {diagnostics.map((item) => (
        <Tag color="volcano" key={item}>
          {item}
        </Tag>
      ))}
    </div>
  );
}

function firstMeaningfulLine(value: string): string {
  return (
    value
      .split(/\r?\n/)
      .map((line) => line.trim())
      .find((line) => line.length > 0)
      ?.slice(0, 260) ?? "暂无结果摘要"
  );
}
