import { useState } from "react";
import { toast } from "sonner";
import { Button } from "../../components/ui/Button";
import { Dialog } from "../../components/ui/Dialog";
import { shareApi, shareUrl, type ShareLink, type ShareTarget } from "./shareApi";
import "./share-link.css";

export function ShareLinkDialog({ target, link, onClose, onSaved }: {
  target: ShareTarget; link?: ShareLink; onClose: () => void; onSaved?: () => void;
}) {
  const [expiry, setExpiry] = useState(link ? "KEEP" : "PERMANENT");
  const [date, setDate] = useState("");
  const [limit, setLimit] = useState(link?.maxDownloads?.toString() || "");
  const [protectedLink, setProtected] = useState(link?.passwordEnabled || false);
  const [password, setPassword] = useState("");
  const [force, setForce] = useState(link?.forceDownload || false);
  const [result, setResult] = useState<ShareLink | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  async function save() {
    setBusy(true); setError("");
    try {
      const max = limit ? Number(limit) : null;
      if (max !== null && (!Number.isSafeInteger(max) || max < 1)) throw new Error("请输入有效的正整数次数");
      const conditions = { expiryMode: /^\d+$/.test(expiry) ? "EXTEND" : expiry,
        days: /^\d+$/.test(expiry) ? Number(expiry) : undefined,
        expiresAt: expiry === "SET" ? new Date(date).toISOString() : undefined,
        maxDownloads: max, passwordEnabled: protectedLink, password: password || undefined, forceDownload: force };
      setResult(link ? await shareApi.update(link, conditions) : await shareApi.create(target, conditions));
      onSaved?.();
    } catch (e) { setError(e instanceof Error ? e.message : "保存失败"); }
    finally { setBusy(false); }
  }
  return <Dialog open onOpenChange={(open) => !open && onClose()} title={result ? "分享链接" : link ? "编辑分享链接" : "创建分享链接"}
    description={target.name} footer={result ? <Button onClick={onClose}>完成</Button> : <>
      <Button onClick={onClose}>取消</Button><Button variant="primary" disabled={busy} onClick={save}>{busy ? "正在保存…" : link ? "保存" : "生成链接"}</Button></>}>
    {result ? <div className="share-result"><label htmlFor="share-result-url">短链接</label><input id="share-result-url" readOnly value={shareUrl(result.shortCode)} />
      <Button onClick={() => navigator.clipboard.writeText(shareUrl(result.shortCode)).then(() => toast.success("链接已复制")).catch(() => toast.error("复制失败，请选中链接手动复制"))}>复制链接</Button>
      <p>{result.expiresAt ? new Date(result.expiresAt).toLocaleString() + " 到期" : "永久有效"} · {result.maxDownloads == null ? "不限下载次数" : `已下载 ${result.downloadCount} / ${result.maxDownloads} 次`}</p>
    </div> : <div className="share-fields">
      <label>有效期<select value={expiry} onChange={(e) => setExpiry(e.target.value)}>
        {link && <option value="KEEP">保持原有效期</option>}<option value="PERMANENT">永久</option><option value="1">延长 1 天</option><option value="7">延长 7 天</option><option value="30">延长 30 天</option><option value="SET">自定义到期时间</option>
      </select></label>
      {expiry === "SET" && <label>到期时间<input type="datetime-local" value={date} onChange={(e) => setDate(e.target.value)} /></label>}
      <label>下载次数上限<input type="number" min="1" step="1" value={limit} placeholder="留空表示无限" onChange={(e) => setLimit(e.target.value)} /></label>
      {link && <small>已批准下载 {link.downloadCount} 次，修改上限不会重置次数。</small>}
      <label className="share-check"><input type="checkbox" checked={protectedLink} disabled={force} onChange={(e) => setProtected(e.target.checked)} />密码保护</label>
      {protectedLink && <label>{link?.passwordEnabled ? "新密码（留空保持原密码）" : "提取密码"}<input type="password" autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} /></label>}
      <label className="share-check"><input type="checkbox" checked={force} disabled={protectedLink} onChange={(e) => setForce(e.target.checked)} />打开链接直接下载</label>
      <small>密码保护与直接下载不能同时开启。</small>
    </div>}
    {error && <p role="alert" className="inline-error">{error}</p>}
  </Dialog>;
}
