import { Empty } from "antd";
import { CodeOutlined } from "@ant-design/icons";
import type { CodeRepository } from "../types";
import { formatDate } from "../utils/messageDisplay";
import { SectionHeader, StatusTag } from "./common";

export function RepositoryList({
  repositories,
  selectedRepoId,
  onSelectRepo,
}: {
  repositories: CodeRepository[];
  selectedRepoId?: string;
  onSelectRepo: (repoId: string) => void;
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
            <button
              className={`nav-row ${repo.id === selectedRepoId ? "selected" : ""}`}
              key={repo.id}
              type="button"
              onClick={() => onSelectRepo(repo.id)}
            >
              <span className="row-main">{repo.name}</span>
              <span className="row-meta">
                <span>{repo.language ?? "unknown"}</span>
                <StatusTag status={repo.status} />
              </span>
              <span className="row-time">{formatDate(repo.updatedAt ?? repo.createdAt)}</span>
            </button>
          ))}
        </div>
      )}
    </section>
  );
}
