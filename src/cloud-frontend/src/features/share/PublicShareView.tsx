import { useEffect, useState } from "react";
import { Download, FileText, Folder, HardDrive, Loader2 } from "lucide-react";
import { api } from "../../api";
import type { PublicSiteSettings, ShareFile } from "../../types";
import { formatSize } from "../../fileUtils";

function flattenShare(root: ShareFile): ShareFile[] {
  return [root, ...(root.children || []).flatMap(flattenShare)];
}

export function PublicShareView({ shareCode, settings }: { shareCode: string; settings: PublicSiteSettings | null }) {
  const [root, setRoot] = useState<ShareFile | null>(null);
  const [selected, setSelected] = useState<ShareFile | null>(null);
  const [error, setError] = useState("");

  useEffect(() => {
    setError("");
    api.getShare(shareCode)
      .then((file) => {
        setRoot(file);
        setSelected(file.dir ? flattenShare(file).find((item) => !item.dir) || file : file);
      })
      .catch((err) => setError(err instanceof Error ? err.message : "分享内容加载失败"));
  }, [shareCode]);

  return (
    <main className="public-share-page">
      <header>
        <span className="brand-icon"><HardDrive size={22} /></span>
        <div>
          <strong>{settings?.siteName || "YL Cloud"}</strong>
          <span>公开分享</span>
        </div>
      </header>
      {error ? (
        <section className="public-share-state error-state"><strong>无法打开分享</strong><span>{error}</span></section>
      ) : !root ? (
        <section className="public-share-state"><Loader2 className="spin" size={24} />正在加载分享内容</section>
      ) : (
        <section className="public-share-card">
          <aside>
            <p>分享内容</p>
            <h1>{root.name}</h1>
            <div className="share-file-list">
              {flattenShare(root).map((file) => (
                <button className={selected?.fileId === file.fileId ? "active" : ""} key={file.fileId} type="button" onClick={() => setSelected(file)}>
                  {file.dir ? <Folder size={18} /> : <FileText size={18} />}
                  <span>{file.name}</span>
                  {!file.dir && <small>{formatSize(file.size)}</small>}
                </button>
              ))}
            </div>
          </aside>
          <article className="public-share-preview">
            {selected?.dir ? (
              <div className="preview-placeholder"><Folder size={38} /><h2>{selected.name}</h2><p>请从左侧选择文件预览或下载。</p></div>
            ) : selected ? (
              <>
                <div className="public-share-preview-head">
                  <div><h2>{selected.name}</h2><span>{selected.contentType || "文件"} · {formatSize(selected.size)}</span></div>
                  {selected.downloadUrl && <a className="primary-button" href={selected.downloadUrl}><Download size={17} />下载</a>}
                </div>
                <div className="public-share-preview-body">
                  {selected.previewType === "text" ? <pre>{selected.textContent ?? ""}</pre>
                    : selected.previewUrl && selected.contentType?.startsWith("image/") ? <img src={selected.previewUrl} alt={selected.name} />
                    : selected.previewUrl ? <iframe title={selected.name} src={selected.previewUrl} />
                    : <div className="preview-placeholder"><FileText size={38} /><h3>此文件暂不支持在线预览</h3><p>可使用右上角按钮下载查看。</p></div>}
                </div>
              </>
            ) : null}
          </article>
        </section>
      )}
    </main>
  );
}
