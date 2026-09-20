import { Download, Link2, Share2, X } from "lucide-react";
import { useState, type ReactNode } from "react";
import { formatSize, formatTime } from "../../fileUtils";
import type { FileItem, FilePreview } from "../../types";
import { FileTypeIcon } from "./FileTypeIcon";
import { AuthorizedPreview } from "./AuthorizedPreview";

type Tab = "preview" | "details" | "versions" | "summary";
const tabs: { id: Tab; label: string }[] = [
  { id: "preview", label: "预览" }, { id: "details", label: "详情" },
  { id: "versions", label: "历史版本" }, { id: "summary", label: "AI 总结" }
];

export function FilePreviewPanel({ item, preview, loading, error, onClose, onRetry, onDownload, onShare, onKnowledge, moreActions }: {
  item: FileItem;
  preview: FilePreview | null;
  loading: boolean;
  error: string | null;
  onClose: () => void;
  onRetry: () => void;
  onDownload: () => void;
  onShare: () => void;
  onKnowledge: () => void;
  moreActions: ReactNode;
}) {
  const [tab, setTab] = useState<Tab>("preview");

  return <aside className="file-preview-panel" aria-label={`${item.name} 的文件信息`}>
    <header className="file-preview-panel__header">
      <FileTypeIcon item={item} large />
      <div className="file-preview-panel__identity"><strong title={item.name}>{item.name}</strong><span>{formatSize(item.size)} · {formatTime(item.updateTime || item.createTime)}</span></div>
      <button className="file-preview-panel__close" aria-label="关闭文件预览" onClick={onClose}><X size={19} /></button>
    </header>
    <div className="file-preview-panel__tabs" role="tablist" aria-label="文件信息">
      {tabs.map(({ id, label }) => <button key={id} role="tab" aria-selected={tab === id} aria-controls={`file-preview-${id}`} className={tab === id ? "is-active" : ""} onClick={() => setTab(id)}>{label}</button>)}
    </div>
    <div className="file-preview-panel__body" id={`file-preview-${tab}`} role="tabpanel">
      {tab === "preview" && (loading ? <div className="file-preview-panel__state" role="status">正在加载预览…</div>
        : error ? <div className="file-preview-panel__state" role="alert"><strong>预览加载失败</strong><span>{error}</span><button onClick={onRetry}>重试</button></div>
        : preview?.textContent ? <pre className="file-preview-panel__text">{preview.textContent}</pre>
        : preview?.previewType === "office" && preview.previewUrl ? <AuthorizedPreview url={preview.previewUrl} title={item.name} />
        : preview?.previewUrl && preview.contentType?.startsWith("image/") ? <img className="file-preview-panel__media" src={preview.previewUrl} alt={item.name} />
        : preview?.previewUrl ? <iframe className="file-preview-panel__frame" src={preview.previewUrl} title={item.name} />
        : <div className="file-preview-panel__state"><FileTypeIcon item={item} large /><strong>暂不支持在线预览</strong><span>你仍可下载文件后查看。</span><button onClick={onDownload}>下载文件</button></div>)}
      {tab === "details" && <dl className="file-preview-panel__details"><div><dt>名称</dt><dd>{item.name}</dd></div><div><dt>类型</dt><dd>{item.type || "文件"}</dd></div><div><dt>大小</dt><dd>{formatSize(item.size)}</dd></div><div><dt>修改时间</dt><dd>{formatTime(item.updateTime || item.createTime)}</dd></div><div><dt>位置</dt><dd>{item.path || "我的文件"}</dd></div></dl>}
      {tab === "versions" && <div className="file-preview-panel__state"><strong>暂无历史版本</strong><span>个人文件当前没有可查看的版本记录。</span></div>}
      {tab === "summary" && <div className="file-preview-panel__state"><strong>在知识库中查看 AI 总结</strong><span>将文件加入知识库并完成处理后，可查看知识画像与摘要。</span><button onClick={onKnowledge}>添加到知识库</button></div>}
    </div>
    <footer className="file-preview-panel__actions">
      <button onClick={onDownload}><span><Download size={18} /></span>下载</button>
      <button onClick={onShare}><span><Share2 size={18} /></span>分享</button>
      <button onClick={onShare}><span><Link2 size={18} /></span>复制链接</button>
      <div className="file-preview-panel__more">{moreActions}<small>更多</small></div>
    </footer>
  </aside>;
}
