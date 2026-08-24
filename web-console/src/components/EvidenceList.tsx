import { FileSearchOutlined } from "@ant-design/icons";
import type { CodeEvidence } from "../types";
import { formatEvidenceRef } from "../utils/evidence";

export function EvidenceList({ evidence }: { evidence: CodeEvidence[] }) {
  if (evidence.length === 0) {
    return null;
  }
  return (
    <div className="evidence-list">
      {evidence.slice(0, 5).map((item, index) => (
        <article className="evidence-row" key={`${item.filePath}-${item.lineRange}-${index}`}>
          <FileSearchOutlined />
          <div className="evidence-content">
            <div className="evidence-title-row">
              <span className="evidence-index">证据 {item.index ?? index + 1}</span>
              {item.chunkType ? <span className="evidence-type">{item.chunkType}</span> : null}
            </div>
            <div className="evidence-file" title={item.filePath}>
              {formatEvidenceRef(item)}
            </div>
            <div className="evidence-meta">
              {[item.symbolName, item.apiPath, item.httpMethod, item.score]
                .filter(Boolean)
                .join(" · ") || "未提供符号信息"}
            </div>
            {item.snippet ? (
              <pre className="evidence-snippet">{item.snippet}</pre>
            ) : null}
          </div>
        </article>
      ))}
      {evidence.length > 5 ? (
        <div className="list-note">还有 {evidence.length - 5} 条证据在原始结果中</div>
      ) : null}
    </div>
  );
}
