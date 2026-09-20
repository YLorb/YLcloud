import { useEffect, useRef, useState } from "react";
import { FileText, Folder, Download } from "lucide-react";
import type { PublicSiteSettings } from "../../types";
import { Button } from "../../components/ui/Button";
import { shareApi, type ShareOpen } from "./shareApi";
import "./share-link.css";

export function PublicShareView({ shareCode, settings, legacy = false }: { shareCode: string; settings: PublicSiteSettings | null; legacy?: boolean }) {
  const [data, setData] = useState<ShareOpen | null>(null);
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [verifying, setVerifying] = useState(false);
  const opening = useRef<Promise<ShareOpen> | null>(null);
  const form = useRef<HTMLFormElement>(null);
  const triggered = useRef(false);
  useEffect(() => {
    let live = true;
    // React StrictMode replays effects: this is still one page opening.
    opening.current ??= shareApi.open(shareCode, legacy);
    opening.current.then((result) => { if (live) setData(result); }).catch((e) => { if (live) setError(e.message); });
    return () => { live = false; };
  }, [shareCode, legacy]);
  useEffect(() => {
    if (data?.state === "READY" && data.forceDownload && !triggered.current && form.current) {
      triggered.current = true; form.current.submit();
    }
  }, [data]);
  async function verify() {
    if (!data?.visitToken) return;
    setVerifying(true); setError("");
    try { const result = await shareApi.verify(shareCode, password, data.visitToken); setData(result); setError(result.message || ""); setPassword(""); }
    catch (e) { setError(e instanceof Error ? e.message : "验证失败"); }
    finally { setVerifying(false); }
  }
  const download = <form ref={form} method="post" action={`/api/public/share-links/${encodeURIComponent(shareCode)}/download`}>
    <input type="hidden" name="credential" value={data?.downloadToken || ""} />
    <Button type="submit" variant="primary"><Download size={16} />下载{data?.directory ? " ZIP" : ""}</Button>
  </form>;
  if (data?.state === "READY" && data.forceDownload) return <main className="share-public"><div><p role="status">正在开始下载…</p>{download}</div></main>;
  return <main className="share-public"><section className="share-public-card">
    <p>{settings?.siteName || "YL Cloud"} · 分享</p>
    {data?.state === "EXPIRED" ? <h1>文件已过期</h1> : !data ? <p role="status">{error ? "无法加载分享" : "正在加载…"}</p> :
      data.state === "PASSWORD_REQUIRED" ? <><h1>请输入提取密码</h1><form onSubmit={(e) => { e.preventDefault(); void verify(); }}>
        <label htmlFor="share-password">提取密码</label><input id="share-password" type="password" autoComplete="off" required value={password} onChange={(e) => setPassword(e.target.value)} />
        <Button type="submit" variant="primary" disabled={verifying}>{verifying ? "正在验证…" : "确认"}</Button>
      </form></> : <>{data.directory ? <Folder size={32} /> : <FileText size={32} />}<h1>{data.name}</h1><p>{data.directory ? "文件夹 · ZIP 下载" : "文件"}</p>{download}</>}
    {error && <p role="alert">{error}</p>}
  </section></main>;
}
