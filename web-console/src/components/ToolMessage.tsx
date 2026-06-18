import { DownOutlined, ToolOutlined } from "@ant-design/icons";
import { Button, Collapse, Space, Tag } from "antd";
import type { ChatMessage } from "../types";
import { normalizeToolContent, summarizeToolMessage } from "../utils/evidence";
import { RawBlock } from "./common";
import { EvidenceList } from "./EvidenceList";

export function ToolMessage({
  message,
  onOpenTools,
}: {
  message: ChatMessage;
  onOpenTools: () => void;
}) {
  const summary = summarizeToolMessage(message);
  const normalizedContent = normalizeToolContent(message.content);

  return (
    <div className="tool-message">
      <div className="tool-card-head">
        <Space wrap size={6}>
          <Tag icon={<ToolOutlined />}>{summary.toolName}</Tag>
          <Tag color="success">local tool</Tag>
          <Tag>{summary.evidence.length} evidence</Tag>
        </Space>
        <Button size="small" type="text" onClick={onOpenTools}>
          查看 Trace
        </Button>
      </div>
      <div className="tool-summary-text">{summary.summary}</div>
      <EvidenceList evidence={summary.evidence} />
      <Collapse
        ghost
        size="small"
        expandIcon={({ isActive }) => <DownOutlined rotate={isActive ? 180 : 0} />}
        items={[
          {
            key: "raw",
            label: "展开 raw detail",
            children: <RawBlock value={normalizedContent} />,
          },
        ]}
      />
    </div>
  );
}
