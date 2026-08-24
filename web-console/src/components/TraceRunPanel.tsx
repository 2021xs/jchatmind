import { Alert, Collapse, Empty, Select, Space, Tag } from "antd";
import type { AgentStepTrace, AgentTaskTrace } from "../types";
import { statusLabel, stepDisplay } from "../utils/executionDisplay";
import { formatLatency, statusColor } from "../utils/messageDisplay";
import { Metric, RawBlock } from "./common";

export function TraceRunPanel({
  traces,
  selectedTrace,
  recentFallback,
  activeTaskId,
  onSelectTrace,
}: {
  traces: AgentTaskTrace[];
  selectedTrace?: AgentTaskTrace;
  recentFallback?: boolean;
  activeTaskId?: string;
  onSelectTrace: (traceId: string) => void;
}) {
  if (traces.length === 0) {
    return <Empty description="当前会话暂无 Agent Run 记录" />;
  }

  return (
    <div className="trace-panel-content">
      <Select
        className="trace-select"
        value={selectedTrace?.id}
        disabled={Boolean(activeTaskId)}
        options={traces.map((trace) => ({
          value: trace.id,
          label: `${statusLabel(trace.status)} · ${formatLatency(trace.latencyMs)} · ${trace.actualSteps ?? trace.steps?.length ?? 0} 步`,
        }))}
        onChange={onSelectTrace}
      />
      {recentFallback ? (
        <Alert
          className="trace-error"
          type="info"
          showIcon
          message="当前展示最近一次任务"
        />
      ) : null}
      {activeTaskId && !selectedTrace ? (
        <Alert
          className="trace-error"
          type="info"
          showIcon
          message="当前任务执行中，步骤详情正在同步"
        />
      ) : null}
      {selectedTrace ? <TraceRun trace={selectedTrace} /> : null}
    </div>
  );
}

function TraceRun({ trace }: { trace: AgentTaskTrace }) {
  return (
    <section className="trace-run">
      <div className="view-intro">
        这里按阶段说明系统做了什么、是否成功以及耗时；模型输入输出和内部字段保留在原始详情中。
      </div>
      <div className="trace-summary-grid">
        <Metric label="任务状态" value={statusLabel(trace.status)} />
        <Metric label="总耗时" value={formatLatency(trace.latencyMs)} />
        <Metric label="执行步骤" value={String(trace.actualSteps ?? trace.steps?.length ?? 0)} />
        <Metric label="工具调用" value={String(trace.toolCallCount ?? trace.toolCalls?.length ?? 0)} />
        <Metric label="模型" value={trace.modelName ?? "未记录"} />
      </div>
      {trace.errorMessage ? (
        <Alert
          className="trace-error"
          type="error"
          showIcon
          message="任务执行失败"
          description={trace.errorMessage}
        />
      ) : null}
      <div className="step-list">
        {(trace.steps ?? []).length === 0 ? (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前任务暂无步骤记录" />
        ) : (
          trace.steps.map((step) => <TraceStep key={step.id} step={step} />)
        )}
      </div>
      <Collapse
        size="small"
        items={[
          {
            key: "run-raw",
            label: "任务原始详情",
            children: <RawBlock value={trace} />,
          },
        ]}
      />
    </section>
  );
}

export function TraceStep({ step }: { step: AgentStepTrace }) {
  const display = stepDisplay(step);
  return (
    <div className="step-row">
      <div className="step-head">
        <Space wrap size={6}>
          <Tag>Step {step.stepNo ?? "-"}</Tag>
          <Tag color={statusColor(step.status)}>{statusLabel(step.status)}</Tag>
          <strong className="step-title">{display.title}</strong>
        </Space>
        <span className="muted">{formatLatency(step.latencyMs)}</span>
      </div>
      <div className="step-copy">
        <p>{display.description}</p>
        {display.invocations.length > 0 ? (
          <div className="step-query-list">
            {display.invocations.map((invocation, index) => (
              <div className="query-row" key={`${invocation.toolName}-${index}`}>
                <span className="query-label">查询 {index + 1}</span>
                <span className="query-text">
                  {invocation.query ?? `${invocation.toolName} 参数已记录在原始详情中`}
                </span>
              </div>
            ))}
          </div>
        ) : null}
        {step.errorMessage ? <p className="danger-text">{step.errorMessage}</p> : null}
      </div>
      <Collapse
        ghost
        size="small"
        items={[
          {
            key: "step-raw",
            label: "步骤原始详情",
            children: <RawBlock value={step} />,
          },
        ]}
      />
    </div>
  );
}
