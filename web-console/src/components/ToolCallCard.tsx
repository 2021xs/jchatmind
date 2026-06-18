import { Collapse, Space, Tag } from "antd";
import type { ToolCallTrace } from "../types";
import { parseCodeEvidence } from "../utils/evidence";
import { formatLatency, statusColor, toolKind } from "../utils/messageDisplay";
import { RawBlock } from "./common";
import { EvidenceList } from "./EvidenceList";

export function ToolCallSummaryCard({ call }: { call: ToolCallTrace }) {
  const summary = call.errorMessage ?? call.resultSummary ?? "暂无结果摘要";
  const evidence = parseCodeEvidence(summary);

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
      <div className="tool-summary-text">{summary}</div>
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
    return `命中 ${evidenceCount} 个代码证据`;
  }
  return summary;
}
