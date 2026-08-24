# Agent Runtime & Eval Stage

## Governance

- Unified tool timeout through the bounded `toolExecutor`.
- Central Unicode-safe tool result guard with global/per-tool limits.
- Exact and consecutive duplicate tool-call detection with forced-final handling.
- Finalization V1 persists the final assistant answer before task success.

## Eval

- Real 30-case Agent Task Eval harness.
- 5-case x 3-run Evidence Diagnostic.
- Code Search Evidence Presentation V1 keeps Agent-facing evidence compact while retaining selector diagnostics for trace/eval.

## Baseline

- Agent Task Success: 28/30 (93.33%).
- Runtime Success: 30/30.
- Final Answer Missing: 0.
- Evidence Presentation A/B: mean Agent-facing result chars 6474 -> 3881; P50 7359 -> 3586.

## Findings

- Current evidence does not justify Query Rewrite, maxToolCalls, or Selector V2c work.
- Frozen deterministic matchers can produce semantic false negatives for Agent answers.
- Evidence Presentation V1 is retained as engineering cleanup; the 15-run A/B did not show clear Agent evidence-usage improvement.

## Known Boundaries

- Main Agent token usage is currently unavailable.
- Tool trace summary truncation cannot fully distinguish Runtime Guard truncation from trace-summary truncation.
- Full tests that initialize external MCP clients require the external MCP environment.
