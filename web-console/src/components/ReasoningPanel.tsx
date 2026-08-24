import { Button, Collapse, Space, Tag } from "antd";
import type { AgentTaskTrace, CodeRepository } from "../types";
import { formatEvidenceRef, parseCodeEvidence } from "../utils/evidence";
import { stepDisplay, toolDisplayName } from "../utils/executionDisplay";
import { formatLatency, modelLabel } from "../utils/messageDisplay";
import { ToolCallSummaryCard } from "./ToolCallCard";

export function ReasoningPanel({
  trace,
  question,
  repo,
  onOpenTools,
}: {
  trace?: AgentTaskTrace;
  question?: string;
  repo?: CodeRepository;
  onOpenTools: () => void;
}) {
  const steps = trace?.steps ?? [];
  const toolCalls = trace?.toolCalls ?? [];
  const stepCount = trace?.actualSteps ?? steps.length;
  const toolCallCount = trace?.toolCallCount ?? toolCalls.length;
  const primaryTools = toolCalls
    .map((call) => call.actualToolName ?? call.toolName)
    .filter(Boolean)
    .map((name) => toolDisplayName(name))
    .slice(0, 2)
    .join(", ");
  const title = trace
    ? `已执行 ${formatLatency(trace.latencyMs)} · ${stepCount} 步 · ${toolCallCount} 次工具调用${primaryTools ? ` · ${primaryTools}` : ""}`
    : "本次回答暂无可展示执行过程";

  return (
    <Collapse
      className="reasoning-collapse"
      size="small"
      items={[
        {
          key: "reasoning",
          label: title,
          children: (
            <div className="reasoning-trace">
              {!trace ? (
                <div className="muted">本次回答暂无可展示执行过程</div>
              ) : (
                <>
                  <div className="reasoning-lines">
                    <p>理解问题：{question ?? trace.goal ?? "本次对话请求"}</p>
                    <p>使用仓库：{repo?.name ?? "未选择仓库"}</p>
                    <p>使用助手：代码助手 · {modelLabel(trace.modelName)}</p>
                    {steps.slice(0, 3).map((step) => (
                      <p key={step.id}>
                        {stepDisplay(step).title}：{stepDisplay(step).description}
                      </p>
                    ))}
                    {toolCalls.length === 0 ? (
                      <p>本次回答未触发工具调用</p>
                    ) : (
                      <p>
                        本次共 {stepCount} 个步骤，{toolCallCount} 次工具调用
                      </p>
                    )}
                  </div>
                  {toolCalls.length > 0 ? (
                    <div className="reasoning-tools">
                      {toolCalls.slice(0, 4).map((call) => (
                        <ToolCallSummaryCard key={call.id} call={call} />
                      ))}
                      <EvidenceSummary trace={trace} />
                      {toolCalls.length > 4 ? (
                        <Button size="small" type="link" onClick={onOpenTools}>
                          查看全部 {toolCalls.length} 次工具调用
                        </Button>
                      ) : null}
                    </div>
                  ) : (
                    <div className="muted">本次回答未触发工具调用</div>
                  )}
                  <Space wrap size={6}>
                    <Tag>summary only</Tag>
                    <Button size="small" type="link" onClick={onOpenTools}>
                      查看 Trace / Audit 调试详情
                    </Button>
                  </Space>
                </>
              )}
            </div>
          ),
        },
      ]}
    />
  );
}

function EvidenceSummary({ trace }: { trace: AgentTaskTrace }) {
  const evidence = (trace.toolCalls ?? [])
    .flatMap((call) => parseCodeEvidence(call.resultSummary ?? ""))
    .slice(0, 3);
  if (evidence.length === 0) {
    return null;
  }
  return (
    <div className="reasoning-evidence-summary">
      命中证据：
      {evidence.map((item, index) => (
        <span key={`${item.filePath}-${item.lineRange}-${index}`}>
          {index > 0 ? "，" : ""}
          {formatEvidenceRef(item)}
        </span>
      ))}
    </div>
  );
}
