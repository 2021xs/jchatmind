import { Alert, Collapse, Empty, Select, Space, Tag } from "antd";
import type { AgentStepTrace, AgentTaskTrace } from "../types";
import { formatLatency, statusColor } from "../utils/messageDisplay";
import { Metric, RawBlock } from "./common";

export function TraceRunPanel({
  traces,
  selectedTrace,
  onSelectTrace,
}: {
  traces: AgentTaskTrace[];
  selectedTrace?: AgentTaskTrace;
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
        options={traces.map((trace) => ({
          value: trace.id,
          label: `${trace.status ?? "UNKNOWN"} / ${trace.traceId ?? trace.id}`,
        }))}
        onChange={onSelectTrace}
      />
      {selectedTrace ? <TraceRun trace={selectedTrace} /> : null}
    </div>
  );
}

function TraceRun({ trace }: { trace: AgentTaskTrace }) {
  return (
    <section className="trace-run">
      <div className="trace-summary-grid">
        <Metric label="runId" value={trace.traceId ?? trace.id} />
        <Metric label="status" value={trace.status ?? "UNKNOWN"} />
        <Metric label="latency" value={formatLatency(trace.latencyMs)} />
        <Metric label="steps" value={String(trace.actualSteps ?? trace.steps?.length ?? 0)} />
        <Metric label="tool calls" value={String(trace.toolCallCount ?? trace.toolCalls?.length ?? 0)} />
        <Metric label="model" value={trace.modelName ?? "n/a"} />
      </div>
      {trace.errorMessage ? (
        <Alert
          className="trace-error"
          type="error"
          showIcon
          message="Agent Run 失败"
          description={trace.errorMessage}
        />
      ) : null}
      <div className="step-list">
        {(trace.steps ?? []).length === 0 ? (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前 run 暂无 step 记录" />
        ) : (
          trace.steps.map((step) => <TraceStep key={step.id} step={step} />)
        )}
      </div>
      <Collapse
        size="small"
        items={[
          {
            key: "run-raw",
            label: "raw run detail",
            children: <RawBlock value={trace} />,
          },
        ]}
      />
    </section>
  );
}

export function TraceStep({ step }: { step: AgentStepTrace }) {
  return (
    <div className="step-row">
      <div className="step-head">
        <Space wrap size={6}>
          <Tag>Step {step.stepNo ?? "-"}</Tag>
          <Tag color={statusColor(step.status)}>{step.status ?? "UNKNOWN"}</Tag>
          <span>{step.stepType ?? "step"}</span>
        </Space>
        <span className="muted">{formatLatency(step.latencyMs)}</span>
      </div>
      <div className="step-copy">
        {step.inputSummary ? <p>{step.inputSummary}</p> : null}
        {step.outputSummary ? <p>{step.outputSummary}</p> : null}
        {step.errorMessage ? <p className="danger-text">{step.errorMessage}</p> : null}
      </div>
      <Collapse
        ghost
        size="small"
        items={[
          {
            key: "step-raw",
            label: "raw step detail",
            children: <RawBlock value={step} />,
          },
        ]}
      />
    </div>
  );
}
