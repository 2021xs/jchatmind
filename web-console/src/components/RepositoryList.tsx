import { Button, Empty, Popconfirm, Space, Tooltip } from "antd";
import { CodeOutlined, DeleteOutlined, GithubOutlined, UploadOutlined } from "@ant-design/icons";
import type { CodeRepository } from "../types";
import { formatDate } from "../utils/messageDisplay";
import { SectionHeader, StatusTag } from "./common";

export function RepositoryList({
  repositories,
  selectedRepoId,
  selectionDisabled = false,
  onSelectRepo,
  onDeleteRepo,
  onImportLocal,
  onImportGithub,
}: {
  repositories: CodeRepository[];
  selectedRepoId?: string;
  selectionDisabled?: boolean;
  onSelectRepo: (repoId: string) => void;
  onDeleteRepo: (repoId: string) => Promise<void>;
  onImportLocal: () => void;
  onImportGithub: () => void;
}) {
  return (
    <section className="sidebar-section">
      <SectionHeader
        icon={<CodeOutlined />}
        title="代码仓库"
        count={repositories.length}
        action={
          <Space size={4}>
            <Button size="small" icon={<UploadOutlined />} onClick={onImportLocal}>
              Local
            </Button>
            <Button size="small" icon={<GithubOutlined />} onClick={onImportGithub}>
              GitHub
            </Button>
          </Space>
        }
      />
      {repositories.length === 0 ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="请先导入代码仓库" />
      ) : (
        <div className="repo-list">
          {repositories.map((repo) => (
            <div
              className={`nav-row ${repo.id === selectedRepoId ? "selected" : ""} ${selectionDisabled ? "disabled" : ""}`}
              key={repo.id}
              role="button"
              tabIndex={0}
              onClick={() => {
                if (!selectionDisabled) {
                  onSelectRepo(repo.id);
                }
              }}
              onKeyDown={(event) => {
                if (event.key === "Enter" || event.key === " ") {
                  event.preventDefault();
                  if (!selectionDisabled) {
                    onSelectRepo(repo.id);
                  }
                }
              }}
            >
              <span className="row-head">
                <span className="row-main">{repo.name}</span>
                <span className="repo-row-actions">
                  <StatusTag status={repo.status} />
                  <span
                    className="row-action"
                    onClick={(event) => event.stopPropagation()}
                    onKeyDown={(event) => event.stopPropagation()}
                  >
                    <Popconfirm
                      title="确认删除该代码仓库索引？"
                      description={repo.sourceType === "GITHUB"
                        ? "这会删除 JChatMind 中的索引和由 JChatMind 管理的 GitHub 克隆目录。该操作不可恢复，相关会话可能保留。"
                        : "这会删除 JChatMind 中的文件、代码块和向量索引，不会删除本地源码。该操作不可恢复，相关会话可能保留。"}
                      okText="删除"
                      cancelText="取消"
                      okButtonProps={{ danger: true }}
                      onConfirm={() => onDeleteRepo(repo.id)}
                    >
                      <Button
                        aria-label={`删除仓库 ${repo.name}`}
                        size="small"
                        type="text"
                        danger
                        icon={<DeleteOutlined />}
                      />
                    </Popconfirm>
                  </span>
                </span>
              </span>
              <Tooltip
                placement="right"
                title={
                  <div className="repo-detail-tooltip">
                    <div>来源：{repo.sourceType === "GITHUB" ? "GitHub" : "Local"}</div>
                    <div>语言：{repo.language ?? "unknown"}</div>
                    {repo.branch ? <div>分支：{repo.branch}</div> : null}
                    {repo.commitSha ? <div>Commit：{repo.commitSha}</div> : null}
                    {repo.remoteUrl ? <div>远程：{repo.remoteUrl}</div> : null}
                    <div>更新：{formatDate(repo.updatedAt ?? repo.createdAt)}</div>
                  </div>
                }
              >
                <span className="row-meta repo-meta">
                  <span className="repo-source">
                    {repo.sourceType === "GITHUB" ? <GithubOutlined /> : <CodeOutlined />}
                    {repo.sourceType === "GITHUB" ? "GitHub" : "Local"}
                  </span>
                  <span aria-hidden="true">·</span>
                  <span>{repo.language ?? "unknown"}</span>
                </span>
              </Tooltip>
            </div>
          ))}
        </div>
      )}
    </section>
  );
}
