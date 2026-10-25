import { useRef, useState } from "react";
import { FileText } from "lucide-react";
import { api } from "../../api";
import { Button } from "../../components/ui/Button";
import { Dialog } from "../../components/ui/Dialog";
import { StatusBadge } from "../../components/ui/StatusBadge";
import type { RagCitation } from "../../types";

export function EvidenceCitation({ citation, index }: { citation: RagCitation; index: number }) {
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [evidence, setEvidence] = useState<{ citation: RagCitation; content: string } | null>(null);
  const requestVersion = useRef(0);
  const verifiedReference = Boolean(citation.evidenceId && citation.spaceId);
  async function showEvidence() {
    if (!citation.evidenceId || !citation.spaceId) return;
    const version = ++requestVersion.current;
    setOpen(true); setLoading(true); setEvidence(null); setError("");
    try {
      const result = await api.ragEvidence(citation.spaceId, citation.evidenceId);
      if (version === requestVersion.current) setEvidence(result);
    }
    catch (reason) { if (version === requestVersion.current) setError(reason instanceof Error ? reason.message : "该依据当前不可访问"); }
    finally { if (version === requestVersion.current) setLoading(false); }
  }
  // Only server evidence routes may be followed; legacy arbitrary citation URLs are not trusted.
  function protectedLink(url?: string) {
    if (!url || !citation.evidenceId || !citation.spaceId) return undefined;
    const expected = `/api/space/${citation.spaceId}/rag/evidence/${encodeURIComponent(citation.evidenceId)}/`;
    if (!url.startsWith(expected)) return undefined;
    const action = url.slice(expected.length);
    return action === "download" || /^preview(?:#page=[1-9]\d*)?$/.test(action) ? url : undefined;
  }
  const title = citation.fileName || `来源 ${index + 1}`;
  const location = citation.pageStart
    ? `第 ${citation.pageStart}${citation.pageEnd && citation.pageEnd !== citation.pageStart ? `–${citation.pageEnd}` : ""} 页`
    : citation.heading || "未提供精确位置";
  return <>
    <article><FileText size={15} /><div>
      <strong>{title}</strong>
      <p>{citation.contentSummary || "相关文档片段"}</p>
      <small>{citation.revisionId ? `修订 ${citation.revisionId} · ` : ""}{location}</small>
      {citation.stale && <StatusBadge tone="warning">旧版本过渡内容</StatusBadge>}
      {!verifiedReference && <small>历史引用尚未绑定可核验版本</small>}
    </div>{verifiedReference && <Button size="sm" variant="ghost" onClick={showEvidence}>核验原文</Button>}</article>
    <Dialog open={open} onOpenChange={(value) => { setOpen(value); if (!value) { ++requestVersion.current; setEvidence(null); setLoading(false); } }} title="引用原文" description="访问时重新检查权限与版本有效性。">
      {loading && <p role="status">正在读取依据…</p>}
      {error && <p role="alert" className="error-banner">{error}</p>}
      {evidence && <div className="form-stack"><strong>{evidence.citation.fileName || title}</strong><small>{location}</small>
        <div className="message-bubble" style={{ whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}>{evidence.content}</div>
        <div className="toolbar-actions">
          {protectedLink(evidence.citation.previewUrl) && <a href={protectedLink(evidence.citation.previewUrl)} target="_blank" rel="noreferrer">打开对应版本</a>}
          {protectedLink(evidence.citation.downloadUrl) && <a href={protectedLink(evidence.citation.downloadUrl)}>下载对应版本</a>}
        </div>
      </div>}
    </Dialog>
  </>;
}
