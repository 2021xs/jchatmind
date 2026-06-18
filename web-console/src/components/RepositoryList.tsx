import { Button, Empty, Popconfirm } from "antd";
import { CodeOutlined, DeleteOutlined } from "@ant-design/icons";
import type { CodeRepository } from "../types";
import { formatDate } from "../utils/messageDisplay";
import { SectionHeader, StatusTag } from "./common";

export function RepositoryList({
  repositories,
  selectedRepoId,
  onSelectRepo,
  onDeleteRepo,
}: {
  repositories: CodeRepository[];
  selectedRepoId?: string;
  onSelectRepo: (repoId: string) => void;
  onDeleteRepo: (repoId: string) => Promise<void>;
}) {
  return (
    <section className="sidebar-section">
      <SectionHeader
        icon={<CodeOutlined />}
        title="代码仓库"
        count={repositories.length}
      />
      {repositories.length === 0 ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="请先导入代码仓库" />
      ) : (
        <div className="repo-list">
          {repositories.map((repo) => (
            <div
              className={`nav-row ${repo.id === selectedRepoId ? "selected" : ""}`}
              key={repo.id}
              role="button"
              tabIndex={0}
              onClick={() => onSelectRepo(repo.id)}
              onKeyDown={(event) => {
                if (event.key === "Enter" || event.key === " ") {
                  event.preventDefault();
                  onSelectRepo(repo.id);
                }
              }}
            >
              <span className="row-head">
                <span className="row-main">{repo.name}</span>
                <span
                  className="row-action"
                  onClick={(event) => event.stopPropagation()}
                  onKeyDown={(event) => event.stopPropagation()}
                >
                  <Popconfirm
                    title="确认删除该代码仓库索引？"
                    description="这会删除 JChatMind 中的文件、代码块和向量索引，不会删除本地源码。该操作不可恢复。相关会话可能保留。"
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
              <span className="row-meta">
                <span>{repo.language ?? "unknown"}</span>
                <StatusTag status={repo.status} />
              </span>
              <span className="row-time">{formatDate(repo.updatedAt ?? repo.createdAt)}</span>
            </div>
          ))}
        </div>
      )}
    </section>
  );
}
