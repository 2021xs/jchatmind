import { FileSearchOutlined } from "@ant-design/icons";
import type { CodeEvidence } from "../types";
import { formatEvidenceRef } from "../utils/evidence";

export function EvidenceList({ evidence }: { evidence: CodeEvidence[] }) {
  if (evidence.length === 0) {
    return <div className="muted">未解析到代码证据摘要</div>;
  }
  return (
    <div className="evidence-list">
      {evidence.slice(0, 5).map((item, index) => (
        <div className="evidence-row" key={`${item.filePath}-${item.lineRange}-${index}`}>
          <FileSearchOutlined />
          <div>
            <div className="evidence-file">{formatEvidenceRef(item)}</div>
            <div className="evidence-meta">
              {[item.chunkType, item.symbolName, item.apiPath, item.httpMethod, item.score]
                .filter(Boolean)
                .join(" / ") || "no metadata"}
            </div>
          </div>
        </div>
      ))}
      {evidence.length > 5 ? (
        <div className="list-note">还有 {evidence.length - 5} 条证据在原始结果中</div>
      ) : null}
    </div>
  );
}
