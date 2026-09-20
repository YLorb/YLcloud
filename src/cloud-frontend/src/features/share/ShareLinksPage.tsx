import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { Button } from "../../components/ui/Button";
import { Dialog } from "../../components/ui/Dialog";
import { ShareLinkDialog } from "./ShareLinkDialog";
import { shareApi, shareUrl, type ShareLink } from "./shareApi";
import "./share-link.css";

export function ShareLinksPage({ admin = false }: { admin?: boolean }) {
  const client = useQueryClient();
  const [page, setPage] = useState(1);
  const [editing, setEditing] = useState<ShareLink | null>(null);
  const [visits, setVisits] = useState<number | null | undefined>(undefined);
  const [visitPage, setVisitPage] = useState(1);
  const [revoking, setRevoking] = useState<ShareLink | null>(null);
  const query = useQuery({ queryKey: ["share-links", admin, page], queryFn: () => shareApi.list(admin, page) });
  const logs = useQuery({ queryKey: ["share-visits", visits, visitPage], queryFn: () => shareApi.visits(visits ?? null, visitPage), enabled: admin && visits !== undefined });
  const refresh = () => { void client.invalidateQueries({ queryKey: ["share-links"] }); };
  const showLogs = (id: number | null) => { setVisitPage(1); setVisits(id); };
  return <section className="share-management">
    <header className="share-heading"><h1>{admin ? "分享管理" : "我的分享"}</h1><div><Button onClick={() => query.refetch()}>刷新</Button>{admin && <Button onClick={() => showLogs(null)}>全部访问日志</Button>}</div></header>
    {query.isLoading ? <p role="status">正在加载…</p> : query.isError ? <p role="alert">加载失败，请重试。</p> : <div className="data-table-wrap"><table className="data-table"><thead><tr>
      <th>文件</th><th>短码</th><th>到期时间</th><th>下载次数</th><th>状态</th><th>操作</th>
    </tr></thead><tbody>{query.data?.items.map((link) => <tr key={link.id}>
      <td>{link.name}{link.directory && <small> · 文件夹</small>}</td><td><code>{link.shortCode}</code></td>
      <td>{link.expiresAt ? new Date(link.expiresAt).toLocaleString() : "永久"}</td><td>{link.downloadCount} / {link.maxDownloads ?? "无限"}</td>
      <td>{link.state === "ACTIVE" ? "有效" : "已失效"}</td><td><div className="row-actions">
        <Button variant="ghost" onClick={() => navigator.clipboard.writeText(shareUrl(link.shortCode)).then(() => toast.success("已复制")).catch(() => toast.error("复制失败"))}>复制</Button>
        <Button variant="ghost" onClick={() => setEditing(link)}>编辑</Button><Button variant="ghost" onClick={() => setRevoking(link)}>撤销</Button>
        {admin && <Button variant="ghost" onClick={() => showLogs(link.id)}>访问日志</Button>}
      </div></td></tr>)}</tbody></table>{query.data?.items.length === 0 && <p>暂无分享链接</p>}</div>}
    <div className="share-pagination"><Button disabled={page <= 1} onClick={() => setPage(page - 1)}>上一页</Button><span>第 {page} 页 · 共 {query.data?.total ?? 0} 条</span><Button disabled={page * 20 >= (query.data?.total ?? 0)} onClick={() => setPage(page + 1)}>下一页</Button></div>
    {editing && <ShareLinkDialog key={editing.id} target={editing} link={editing} onClose={() => setEditing(null)} onSaved={refresh} />}
    {revoking && <Dialog open title="撤销分享" description="撤销后，原链接将无法访问。" onOpenChange={() => setRevoking(null)} footer={<><Button onClick={() => setRevoking(null)}>取消</Button><Button variant="primary" onClick={() => shareApi.revoke(revoking.id).then(() => { setRevoking(null); refresh(); }).catch((e) => toast.error(e.message))}>确认撤销</Button></>}><p>{revoking.name}</p></Dialog>}
    {admin && visits !== undefined && <Dialog open title="访问日志" description="保留最近 30 天的访问记录。" size="lg" onOpenChange={() => setVisits(undefined)}>
      {logs.isLoading ? <p>正在加载…</p> : logs.isError ? <p role="alert">日志加载失败</p> : <div className="data-table-wrap"><table className="data-table"><thead><tr><th>短码</th><th>账号 / IP</th><th>城市</th><th>时间</th><th>结果</th></tr></thead><tbody>
        {logs.data?.items.map((v) => <tr key={v.id}><td>{v.shortCode}</td><td>{v.username || v.userId || v.ip || "-"}</td><td>{v.city}</td><td>{v.visitedAt}</td><td>{{ READY: "访问成功", PASSWORD_REQUIRED: "等待密码", PASSWORD_FAILED: "密码错误", VERIFIED: "验证成功", EXPIRED: "已失效" }[v.outcome] || v.outcome}</td></tr>)}
      </tbody></table>{logs.data?.items.length === 0 && <p>暂无访问记录</p>}</div>}
      <div className="share-pagination"><Button disabled={visitPage <= 1} onClick={() => setVisitPage(visitPage - 1)}>上一页</Button><span>第 {visitPage} 页</span><Button disabled={visitPage * 20 >= (logs.data?.total ?? 0)} onClick={() => setVisitPage(visitPage + 1)}>下一页</Button></div>
    </Dialog>}
  </section>;
}
