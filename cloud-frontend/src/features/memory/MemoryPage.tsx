import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Download, ExternalLink, ListTree, Pin, PinOff, Search, ShieldCheck, Trash2 } from "lucide-react";
import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { toast } from "sonner";
import { api } from "../../api";
import { Button } from "../../components/ui/Button";
import { Dialog } from "../../components/ui/Dialog";
import { EmptyState, ErrorState, LoadingState } from "../../components/ui/PageState";
import type { UserMemory } from "../../types";

const TYPES = ["", "FACT", "PREFERENCE", "CONSTRAINT", "DECISION"] as const;
const TYPE_LABEL: Record<string, string> = { FACT: "事实", PREFERENCE: "偏好", CONSTRAINT: "约束", DECISION: "决定" };

export function MemoryPage() {
  const client = useQueryClient();
  const [type, setType] = useState("");
  const [keyword, setKeyword] = useState("");
  const [edit, setEdit] = useState<UserMemory | null>(null);
  const [forget, setForget] = useState<UserMemory | null>(null);
  const [clearOpen, setClearOpen] = useState(false);
  const memories = useQuery({ queryKey: ["user-memories", type, keyword], queryFn: () => api.listUserMemories(type, keyword) });
  const latestSourceSession = memories.data?.find((item) => item.sourceSessionId)?.sourceSessionId;
  const sourceEpisodes = useQuery({ queryKey: ["memory-source-episodes", latestSourceSession], queryFn: () => api.listKnowledgeChatEpisodes(latestSourceSession!), enabled: Boolean(latestSourceSession) });
  const setting = useQuery({ queryKey: ["user-memory-setting"], queryFn: api.userMemorySetting });
  const stats = useQuery({ queryKey: ["user-memory-stats"], queryFn: api.userMemoryStats });
  const refresh = () => { client.invalidateQueries({ queryKey: ["user-memories"] }); client.invalidateQueries({ queryKey: ["user-memory-stats"] }); };
  const toggle = useMutation({ mutationFn: (enabled: boolean) => api.updateUserMemorySetting({ enabled }), onSuccess: (next) => { client.setQueryData(["user-memory-setting"], next); toast.success(next.enabled ? "长期记忆已开启" : "长期记忆已关闭"); }, onError: showError });
  const pin = useMutation({ mutationFn: (item: UserMemory) => api.pinUserMemory(item.id, !item.pinned), onSuccess: () => { refresh(); toast.success("固定状态已更新"); }, onError: showError });
  const remove = useMutation({ mutationFn: (id: number) => api.forgetUserMemory(id), onSuccess: () => { setForget(null); refresh(); toast.success("这条记忆已忘记"); }, onError: showError });
  const clear = useMutation({ mutationFn: api.clearUserMemories, onSuccess: () => { setClearOpen(false); refresh(); toast.success("长期记忆已清空"); }, onError: showError });

  return <div className="memory-page">
    <section className="memory-intro">
      <div><span className="section-eyebrow">Personal memory</span><h2>由你掌控的长期记忆</h2><p>查看 AI 从已完成对话中保存的偏好、事实、约束和决定。关闭后会立即停止写入和召回，已有内容将继续长期保存，直到你主动清理。</p></div>
      <label className="memory-toggle"><input type="checkbox" checked={setting.data?.enabled ?? true} disabled={setting.isLoading || toggle.isPending} onChange={(event) => toggle.mutate(event.target.checked)} /><span aria-hidden="true" /><strong>{setting.data?.enabled === false ? "已关闭" : "已开启"}</strong></label>
    </section>
    <section className="memory-metrics" aria-label="长期记忆概览">
      <Metric label="已激活" value={stats.data?.activeCount} /><Metric label="已固定" value={stats.data?.pinnedCount} /><Metric label="处理中" value={stats.data?.pendingCount} /><Metric label="上下文 Token" value={stats.data?.contextTokens} />
    </section>
    <section className="memory-panel">
      <header className="memory-toolbar"><div><h2>记忆列表</h2><p>记忆不会作为知识库引用，来源会话仅用于追溯。</p></div><div className="memory-toolbar__actions"><Button onClick={() => api.exportUserMemories().catch(showError)}><Download size={16} />导出</Button><Button variant="danger" onClick={() => setClearOpen(true)} disabled={!memories.data?.length}><Trash2 size={16} />清空</Button></div></header>
      <div className="memory-filters"><label className="search-box"><Search size={16} /><span className="sr-only">搜索记忆</span><input value={keyword} onChange={(event) => setKeyword(event.target.value)} placeholder="搜索内容或标准键" /></label><label><span className="sr-only">按类型筛选</span><select value={type} onChange={(event) => setType(event.target.value)}>{TYPES.map((value) => <option value={value} key={value}>{value ? TYPE_LABEL[value] : "全部类型"}</option>)}</select></label></div>
      {memories.isLoading ? <LoadingState label="正在加载记忆" /> : memories.isError ? <ErrorState message={memories.error instanceof Error ? memories.error.message : "无法加载记忆"} onRetry={() => memories.refetch()} /> : !memories.data?.length ? <EmptyState title="还没有长期记忆" message="在 AI Assistant 中明确表达稳定偏好或决定后，经过安全检查的内容会显示在这里。" /> : <div className="memory-list">{memories.data.map((item) => <article className="memory-item" key={item.id}><div className="memory-item__main"><div className="memory-item__meta"><span>{TYPE_LABEL[item.memoryType] || item.memoryType}</span><small>{item.pinned ? "已固定" : `置信度 ${Math.round((item.confidence || 0) * 100)}%`}</small></div><p>{item.content}</p><code>{item.normalizedKey}</code><div className="memory-source"><ShieldCheck size={14} />版本 {item.version} · 更新于 {formatDate(item.updatetime)}<Link to={`/assistant?sessionId=${item.sourceSessionId}`}><ExternalLink size={14} />查看来源</Link></div></div><div className="memory-item__actions"><Button size="icon" variant="ghost" aria-label={item.pinned ? "取消固定" : "固定记忆"} onClick={() => pin.mutate(item)}>{item.pinned ? <PinOff size={17} /> : <Pin size={17} />}</Button><Button size="sm" onClick={() => setEdit(item)}>编辑</Button><Button size="icon" variant="ghost" aria-label="忘记这条记忆" onClick={() => setForget(item)}><Trash2 size={17} /></Button></div></article>)}</div>}
    </section>
    {sourceEpisodes.data && sourceEpisodes.data.length > 1 && <section className="memory-episodes" aria-labelledby="memory-episodes-title"><header><ListTree size={18} /><div><h2 id="memory-episodes-title">最近来源会话的片段</h2><p>按主题片段返回原始会话，核对记忆形成时的上下文。</p></div></header><div>{sourceEpisodes.data.map((episode) => <Link key={episode.id} to={`/assistant?sessionId=${latestSourceSession}&episode=${episode.episodeNo}`} title={episode.summary}><strong>#{episode.episodeNo} {episode.title}</strong><small>{episode.messageCount} 条消息</small></Link>)}</div></section>}
    <EditDialog item={edit} onClose={() => setEdit(null)} onSaved={() => { setEdit(null); refresh(); }} />
    <Dialog open={Boolean(forget)} onOpenChange={(open) => !open && setForget(null)} title="忘记这条记忆？" description="删除会同步清理向量索引，之后不会再被召回。" footer={<><Button onClick={() => setForget(null)}>取消</Button><Button variant="danger" loading={remove.isPending} onClick={() => forget && remove.mutate(forget.id)}>确认忘记</Button></>}><p className="danger-callout">{forget?.content}</p></Dialog>
    <Dialog open={clearOpen} onOpenChange={setClearOpen} title="清空全部长期记忆？" description="此操作会删除所有未固定和已固定记忆，无法撤销。" footer={<><Button onClick={() => setClearOpen(false)}>取消</Button><Button variant="danger" loading={clear.isPending} onClick={() => clear.mutate()}>清空全部</Button></>}><p className="danger-callout">AI 将不再记得此前保存的个人偏好、事实、约束和决定。</p></Dialog>
  </div>;
}

function EditDialog({ item, onClose, onSaved }: { item: UserMemory | null; onClose: () => void; onSaved: () => void }) { const [content, setContent] = useState(""); const [memoryType, setMemoryType] = useState<UserMemory["memoryType"]>("FACT"); useEffect(() => { if (item) { setContent(item.content); setMemoryType(item.memoryType); } }, [item]); const update = useMutation({ mutationFn: () => api.updateUserMemory(item!.id, { content, memoryType }), onSuccess: () => { toast.success("记忆已更新并重新建立索引"); onSaved(); }, onError: showError }); return <Dialog open={Boolean(item)} onOpenChange={(value) => !value && onClose()} title="编辑长期记忆" description="修改会创建新版本并保留来源链。" footer={<><Button onClick={onClose}>取消</Button><Button variant="confirm" loading={update.isPending} disabled={!content.trim()} onClick={() => update.mutate()}>保存并重新索引</Button></>}><div className="memory-edit-form"><label>类型<select value={memoryType} onChange={(event) => setMemoryType(event.target.value as UserMemory["memoryType"])}>{TYPES.slice(1).map((value) => <option value={value} key={value}>{TYPE_LABEL[value]}</option>)}</select></label><label>内容<textarea rows={5} value={content} onChange={(event) => setContent(event.target.value)} maxLength={2000} /></label><small>仅保留明确、稳定且不含敏感信息的内容。</small></div></Dialog>; }
function Metric({ label, value }: { label: string; value?: number }) { return <article><span>{label}</span><strong>{value == null ? "—" : value.toLocaleString()}</strong></article>; }
function formatDate(value?: string) { return value ? new Intl.DateTimeFormat("zh-CN", { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" }).format(new Date(value)) : "未知时间"; }
function showError(error: unknown) { toast.error(error instanceof Error ? error.message : "操作失败，请稍后重试"); }
