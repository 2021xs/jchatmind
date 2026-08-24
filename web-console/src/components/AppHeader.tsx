import { Button, Divider, Popover, Space, Tag, Tooltip, Typography } from "antd";
import {
  SafetyCertificateOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  ReloadOutlined,
} from "@ant-design/icons";
import type {
  WebConsoleCapabilitiesResponse,
  WebConsoleCapability,
  WebConsoleModel,
} from "../types";
import { modelLabel } from "../utils/messageDisplay";
import { ContextPill } from "./common";

export function AppHeader({
  model,
  capabilities,
  capabilityLoading,
  capabilityError,
  detailOpen,
  onRefresh,
  onToggleDetail,
}: {
  model: WebConsoleModel;
  capabilities?: WebConsoleCapabilitiesResponse;
  capabilityLoading?: boolean;
  capabilityError?: string;
  detailOpen: boolean;
  onRefresh: () => void;
  onToggleDetail: () => void;
}) {
  return (
    <header className="console-header">
      <div className="brand-block">
        <Typography.Title level={4} className="console-title">
          JChatMind Web Console
        </Typography.Title>
        <Typography.Text type="secondary">
          面向代码问答、工具调用和 Agent Trace 的主入口
        </Typography.Text>
      </div>
      <div className="header-context">
        <ContextPill label="助手" value="代码助手" />
        <ContextPill label="模型" value={modelLabel(model)} />
        <CapabilitiesPopover
          capabilities={capabilities}
          loading={capabilityLoading}
          error={capabilityError}
        />
        <Tooltip title="重新加载仓库、Agent、会话和当前 Trace">
          <Button icon={<ReloadOutlined />} onClick={onRefresh}>
            刷新
          </Button>
        </Tooltip>
        <Tooltip title={detailOpen ? "收起 Trace / Audit" : "展开 Trace / Audit"}>
          <Button
            icon={detailOpen ? <MenuFoldOutlined /> : <MenuUnfoldOutlined />}
            onClick={onToggleDetail}
          />
        </Tooltip>
      </div>
    </header>
  );
}

function CapabilitiesPopover({
  capabilities,
  loading,
  error,
}: {
  capabilities?: WebConsoleCapabilitiesResponse;
  loading?: boolean;
  error?: string;
}) {
  return (
    <Popover
      trigger="click"
      placement="bottomRight"
      content={
        <div className="capability-panel">
          <div className="capability-panel-head">
            <strong>{capabilities?.assistant ?? "代码助手"}</strong>
            <span>{capabilities?.profile ?? "WEB_CONSOLE_CODE_ASSISTANT_SAFE_FULL"}</span>
          </div>
          {error ? (
            <div className="danger-text">能力状态加载失败：{error}</div>
          ) : null}
          {loading ? <div className="muted">能力状态加载中</div> : null}
          {!loading && !error && !capabilities ? (
            <div className="muted">能力状态待加载</div>
          ) : null}
          <div className="capability-list">
            {(capabilities?.capabilities ?? []).map((item) => (
              <CapabilityRow key={item.key} capability={item} />
            ))}
          </div>
          <Divider className="capability-divider" />
          <div className="capability-not-supported">
            <span className="muted">不支持</span>
            <Space wrap size={4}>
              {(capabilities?.notSupported ?? []).map((item) => (
                <Tag key={item}>{item}</Tag>
              ))}
            </Space>
          </div>
        </div>
      }
    >
      <Button size="small" icon={<SafetyCertificateOutlined />} loading={loading}>
        能力
      </Button>
    </Popover>
  );
}

function CapabilityRow({ capability }: { capability: WebConsoleCapability }) {
  return (
    <div className="capability-row">
      <span className="capability-row-main">
        <strong>{capability.label}</strong>
        <span>{capability.description}</span>
        {!capability.enabled && capability.reason ? <span>{capability.reason}</span> : null}
        {capability.enabled && capability.reason ? <span>{capability.reason}</span> : null}
      </span>
      <Tag color={capability.enabled ? "green" : "default"}>
        {capability.enabled ? "已启用" : "未启用"}
      </Tag>
    </div>
  );
}
