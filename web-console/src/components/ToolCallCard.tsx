import { Collapse, Space, Tag } from "antd";
import type { ToolCallTrace } from "../types";
import { parseCodeEvidence } from "../utils/evidence";
import type { ToolInvocationDisplay } from "../utils/executionDisplay";
import {
  statusLabel,
  toolCallDiagnostics,
  toolDisplayName,
} from "../utils/executionDisplay";
import { formatLatency, statusColor, toolKind } from "../utils/messageDisplay";
import { RawBlock } from "./common";
import { EvidenceList } from "./EvidenceList";

export function ToolCallSummaryCard({ call }: { call: ToolCallTrace }) {
  const summary = call.errorMessage ?? call.resultSummary ?? "暂无结果摘要";
  const evidence = parseCodeEvidence(summary);
  const diagnostics = toolCallDiagnostics(call);

  return (
    <div className="reasoning-tool-row">
      <div className="tool-card-head">
        <Space wrap size={6}>
          <strong>{toolDisplayName(call.actualToolName ?? call.toolName)}</strong>
          <Tag>{toolKind(call)}</Tag>
          <Tag color={statusColor(call.status)}>{statusLabel(call.status)}</Tag>
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

export function ToolCallCard({
  call,
  invocation,
}: {
  call: ToolCallTrace;
  invocation?: ToolInvocationDisplay;
}) {
  const denied = call.blockedByPolicy === true;
  const summary = call.errorMessage ?? call.resultSummary ?? "暂无结果摘要";
  const evidence = parseCodeEvidence(summary);
  const diagnostics = toolCallDiagnostics(call);

  return (
    <article className="tool-call-card">
      <div className="tool-card-head">
        <Space wrap size={6}>
          <Tag color={denied ? "red" : statusColor(call.status)}>
            {denied ? "已拦截" : statusLabel(call.status)}
          </Tag>
          <Tag>{toolKind(call)}</Tag>
          <strong>{toolDisplayName(call.actualToolName ?? call.toolName)}</strong>
        </Space>
        <span className="muted">{formatLatency(call.latencyMs)}</span>
      </div>
      <code className="tool-technical-name">
        {call.actualToolName ?? call.toolName ?? "unknown_tool"}
      </code>
      {invocation?.query ? (
        <div className="tool-query">
          <span>查询主题</span>
          <strong>{invocation.query}</strong>
        </div>
      ) : null}
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
            label: "工具原始详情",
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
  if (/no related code evidence found/i.test(summary)) {
    return "本次检索没有找到相关代码证据。";
  }
  const firstLine = firstMeaningfulLine(summary);
  if (/selected code evidence/i.test(firstLine)) {
    return "已获得代码检索结果，完整内容可在原始详情中查看。";
  }
  return firstLine || "工具已执行，完整结果可在原始详情中查看。";
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
      ?.slice(0, 260) ?? ""
  );
}
