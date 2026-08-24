import { MenuFoldOutlined } from "@ant-design/icons";
import { Badge, Button, Collapse, Empty, Typography, Tag } from "antd";
import type { AgentSseEvent, AgentTaskTrace, DetailMode, ToolCallTrace } from "../types";
import {
  compactIdentifier,
  eventDisplay,
  parseToolInvocations,
} from "../utils/executionDisplay";
import { formatDate } from "../utils/messageDisplay";
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
  activeTaskId,
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
  activeTaskId?: string;
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
            执行过程
          </Typography.Title>
          <Typography.Text type="secondary">
            查看任务进度、检索证据与结果；内部字段按需展开
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
          任务概览 <Badge count={traces.length} />
        </button>
        <button
          className={mode === "tools" ? "active" : ""}
          type="button"
          onClick={() => onModeChange("tools")}
        >
          检索与工具 <Badge count={selectedTrace?.toolCalls?.length ?? toolCalls.length} />
        </button>
        <button
          className={mode === "events" ? "active" : ""}
          type="button"
          onClick={() => onModeChange("events")}
        >
          高级事件 <Badge count={events.length} />
        </button>
      </div>

      {!sessionId ? (
        <Empty description="选择会话后展示 Trace / Audit" />
      ) : mode === "trace" ? (
        <TraceRunPanel
          traces={traces}
          selectedTrace={selectedTrace}
          recentFallback={selectedTrace?.id === traces[0]?.id}
          activeTaskId={activeTaskId}
          onSelectTrace={onSelectTrace}
        />
      ) : mode === "tools" ? (
        activeTaskId && !selectedTrace ? (
          <Empty description="当前任务的工具记录正在同步" />
        ) : (
          <ToolAuditPanel
            toolCalls={selectedTrace ? selectedTrace.toolCalls ?? [] : toolCalls}
            trace={selectedTrace}
          />
        )
      ) : (
        <SseEventPanel events={events} />
      )}
    </aside>
  );
}

function ToolAuditPanel({
  toolCalls,
  trace,
}: {
  toolCalls: ToolCallTrace[];
  trace?: AgentTaskTrace;
}) {
  if (toolCalls.length === 0) {
    return <Empty description="本次回答未触发工具调用" />;
  }

  return (
    <div className="detail-view-content">
      <div className="view-intro">
        按执行顺序展示本次任务使用的检索与工具。结果优先结构化呈现，完整原始记录可在卡片底部展开。
      </div>
      <div className="tool-audit-list">
        {toolCalls.map((call, index) => {
          const step = trace?.steps?.find((item) => item.id === call.stepId);
          const invocations = parseToolInvocations(step?.inputSummary);
          const position = toolCalls
            .slice(0, index)
            .filter((item) => item.stepId === call.stepId).length;
          return (
            <ToolCallCard
              key={call.id}
              call={call}
              invocation={
                invocations[position] ??
                invocations.find(
                  (item) => item.toolName === (call.actualToolName ?? call.toolName),
                )
              }
            />
          );
        })}
      </div>
    </div>
  );
}

function SseEventPanel({ events }: { events: AgentSseEvent[] }) {
  if (events.length === 0) {
    return <Empty description="当前会话暂无实时 SSE 事件" />;
  }

  return (
    <div className="detail-view-content event-debug-view">
      <div className="view-intro">
        高级运行事件用于排查连接与状态问题，不影响上方任务概览。事件 payload 默认折叠。
      </div>
      <div className="event-list">
        {events.map((event, index) => {
          const display = eventDisplay(event);
          return (
            <article className="event-row" key={event.eventId ?? `${event.type}-${index}`}>
              <div className="tool-card-head">
                <span className="section-header-title">
                  <Tag color={display.color}>{display.label}</Tag>
                  <code className="event-type">{event.type ?? "event"}</code>
                </span>
                <span className="muted">{formatDate(event.timestamp)}</span>
              </div>
              <div className="tool-summary-text">{display.description}</div>
              {event.taskId ? (
                <div className="technical-id" title={event.taskId}>
                  任务 {compactIdentifier(event.taskId)}
                </div>
              ) : null}
              <Collapse
                ghost
                size="small"
                items={[
                  {
                    key: "event-raw",
                    label: "原始事件 payload",
                    children: <RawBlock value={event.payload ?? event} />,
                  },
                ]}
              />
            </article>
          );
        })}
      </div>
    </div>
  );
}
