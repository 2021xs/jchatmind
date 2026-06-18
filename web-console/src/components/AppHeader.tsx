import { Button, Tag, Tooltip, Typography } from "antd";
import {
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  ReloadOutlined,
} from "@ant-design/icons";
import type { ChatSession, CodeRepository, SseStatus, WebConsoleModel } from "../types";
import { modelLabel, sseStatusColor, sseStatusLabel } from "../utils/messageDisplay";
import { ContextPill } from "./common";

export function AppHeader({
  repo,
  model,
  session,
  sseStatus,
  detailOpen,
  onRefresh,
  onToggleDetail,
}: {
  repo?: CodeRepository;
  model: WebConsoleModel;
  session?: ChatSession;
  sseStatus: SseStatus;
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
        <ContextPill label="Repo" value={repo?.name ?? "未选择"} />
        <ContextPill label="助手" value="代码助手" />
        <ContextPill label="模型" value={modelLabel(model)} />
        <ContextPill label="Conversation" value={session?.title ?? "未选择"} />
        <Tag color={sseStatusColor(sseStatus)}>{sseStatusLabel(sseStatus)}</Tag>
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
