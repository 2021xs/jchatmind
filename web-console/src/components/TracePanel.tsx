import { MenuFoldOutlined } from "@ant-design/icons";
import { Badge, Button, Collapse, Empty, Typography, Tag } from "antd";
import type { AgentSseEvent, AgentTaskTrace, DetailMode, ToolCallTrace } from "../types";
import { formatDate, summarizeEvent } from "../utils/messageDisplay";
import { RawBlock } from "./common";
import { ToolCallCard } from "./ToolCallCard";
import { TraceRunPanel } from "./TraceRunPanel";

export function TracePanel({
  open,
  mode,
  traces,
  selectedTrace,
  toolCalls,
  events,
  sessionId,
  onModeChange,
  onSelectTrace,
  onClose,
}: {
  open: boolean;
  mode: DetailMode;
  traces: AgentTaskTrace[];
  selectedTrace?: AgentTaskTrace;
  toolCalls: ToolCallTrace[];
  events: AgentSseEvent[];
  sessionId?: string;
  onModeChange: (mode: DetailMode) => void;
  onSelectTrace: (traceId: string) => void;
  onClose: () => void;
}) {
  if (!open) {
    return null;
  }

  return (
    <aside className="detail-panel">
      <div className="detail-header">
        <div>
          <Typography.Title level={5} className="panel-title">
            Trace / Audit
          </Typography.Title>
          <Typography.Text type="secondary">
            完整调试信息保留在右侧，raw detail 默认折叠
          </Typography.Text>
        </div>
        <Button size="small" type="text" icon={<MenuFoldOutlined />} onClick={onClose} />
      </div>

      <div className="detail-tabs">
        <button
          className={mode === "trace" ? "active" : ""}
          type="button"
          onClick={() => onModeChange("trace")}
        >
          Runs <Badge count={traces.length} />
        </button>
        <button
          className={mode === "tools" ? "active" : ""}
          type="button"
          onClick={() => onModeChange("tools")}
        >
          Tools <Badge count={toolCalls.length} />
        </button>
        <button
          className={mode === "events" ? "active" : ""}
          type="button"
          onClick={() => onModeChange("events")}
        >
          SSE <Badge count={events.length} />
        </button>
      </div>

      {!sessionId ? (
        <Empty description="选择会话后展示 Trace / Audit" />
      ) : mode === "trace" ? (
        <TraceRunPanel
          traces={traces}
          selectedTrace={selectedTrace}
          onSelectTrace={onSelectTrace}
        />
      ) : mode === "tools" ? (
        <ToolAuditPanel toolCalls={toolCalls} />
      ) : (
        <SseEventPanel events={events} />
      )}
    </aside>
  );
}

function ToolAuditPanel({ toolCalls }: { toolCalls: ToolCallTrace[] }) {
  if (toolCalls.length === 0) {
    return <Empty description="本次回答未触发工具调用" />;
  }

  return (
    <div className="tool-audit-list">
      {toolCalls.map((call) => (
        <ToolCallCard key={call.id} call={call} />
      ))}
    </div>
  );
}

function SseEventPanel({ events }: { events: AgentSseEvent[] }) {
  if (events.length === 0) {
    return <Empty description="当前会话暂无实时 SSE 事件" />;
  }

  return (
    <div className="event-list">
      {events.map((event, index) => (
        <article className="event-row" key={event.eventId ?? `${event.type}-${index}`}>
          <div className="tool-card-head">
            <span className="section-header-title">
              <Tag color={event.type === "error" ? "red" : "processing"}>
                {event.type ?? "event"}
              </Tag>
              <span>{event.taskId ?? "no-task"}</span>
            </span>
            <span className="muted">{formatDate(event.timestamp)}</span>
          </div>
          <div className="tool-summary-text">{summarizeEvent(event.payload)}</div>
          <Collapse
            ghost
            size="small"
            items={[
              {
                key: "event-raw",
                label: "raw SSE payload",
                children: <RawBlock value={event.payload ?? event} />,
              },
            ]}
          />
        </article>
      ))}
    </div>
  );
}
