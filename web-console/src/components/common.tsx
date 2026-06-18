import type { ReactNode } from "react";
import { Badge, Tag } from "antd";
import type { SectionHeaderProps } from "../types";
import { repoStatusColor } from "../utils/messageDisplay";

export function ContextPill({ label, value }: { label: string; value: string }) {
  return (
    <span className="context-pill">
      <span>{label}</span>
      <strong>{value}</strong>
    </span>
  );
}

export function SectionHeader({ icon, title, count, action }: SectionHeaderProps) {
  return (
    <div className="section-header">
      <span className="section-header-title">
        {icon}
        <strong>{title}</strong>
        <Badge count={count} />
      </span>
      {action}
    </div>
  );
}

export function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div className="metric">
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

export function StatusTag({ status }: { status?: string }) {
  return <Tag color={repoStatusColor(status)}>{status ?? "UNKNOWN"}</Tag>;
}

export function RawBlock({ value }: { value: unknown }) {
  const text = typeof value === "string" ? value : JSON.stringify(value, null, 2);
  return <pre className="raw-block">{text}</pre>;
}

export function InlineMeta({
  children,
  className = "",
}: {
  children: ReactNode;
  className?: string;
}) {
  return <span className={`inline-meta ${className}`.trim()}>{children}</span>;
}
