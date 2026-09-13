import { Eye, Plus, RefreshCw, ShieldCheck } from "lucide-react";
import { useMemo, useState, type FormEvent } from "react";
import { toast } from "sonner";
import { Button } from "../../../components/ui/Button";
import { Dialog } from "../../../components/ui/Dialog";
import { StatusBadge } from "../../../components/ui/StatusBadge";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "../../../components/ui/Tabs";
import { AdminField, AdminFormDialog } from "../components/AdminDialogs";
import { AdminFilterBar, AdminPage, AdminSearch, AdminSection, AdminSelect, AdminStat, AdminStats } from "../components/AdminPage";
import { AdminPagination, AdminTable } from "../components/AdminTable";
import { useAdminCollection, type AdminRecord } from "../core/AdminDataSource";

type EventRow = AdminRecord & { channel: "system" | "audit"; level: string; type: string; subject: string; summary: string; payload: string; occurredAt: string };
const levels = [{ value: "信息", label: "信息" }, { value: "警告", label: "警告" }, { value: "错误", label: "错误" }];
const blank = { level: "信息", type: "", subject: "", summary: "", payload: "" };

export function EventsPage() {
  const collection = useAdminCollection<EventRow>("events");
  const [channel, setChannel] = useState<"system" | "audit">("system");
  const [query, setQuery] = useState("");
  const [level, setLevel] = useState("");
  const [eventType, setEventType] = useState("");
  const [page, setPage] = useState(1);
  const [dateFrom, setDateFrom] = useState("");
  const [dateTo, setDateTo] = useState("");
  const [detail, setDetail] = useState<EventRow | null>(null);
  const [formOpen, setFormOpen] = useState(false);
  const [form, setForm] = useState(blank);
  const [retentionDays, setRetentionDays] = useState("30");
  const visible = useMemo(() => collection.items.filter((item) => item.channel === channel && (!query || `${item.subject} ${item.summary}`.toLowerCase().includes(query.toLowerCase())) && (!level || item.level === level) && (!eventType || item.type.toLowerCase().includes(eventType.toLowerCase())) && (!dateFrom || item.occurredAt.slice(0, 10) >= dateFrom) && (!dateTo || item.occurredAt.slice(0, 10) <= dateTo)), [channel, collection.items, dateFrom, dateTo, eventType, level, query]);
  const currentPage = Math.min(page, Math.max(1, Math.ceil(visible.length / 20)));

  async function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    try {
      await collection.create({ ...form, channel, occurredAt: new Date().toISOString() });
      setFormOpen(false);
      setForm(blank);
      toast.success("事件已添加到演示数据，未写入服务器或审计日志");
    } catch (error) { toast.error(error instanceof Error ? error.message : "演示操作失败"); }
  }

  const eventTable = <AdminSection title={channel === "system" ? "系统事件" : "安全审计"} description={channel === "system" ? "查看任务、存储和服务状态事件。" : "查看登录、权限和敏感操作的审计条目。"} actions={<div className="button-row"><Button variant="ghost" onClick={() => void collection.refresh()}><RefreshCw size={16} />刷新</Button><Button variant="primary" onClick={() => setFormOpen(true)}><Plus size={16} />添加演示事件</Button></div>}>
    <AdminStats><AdminStat label="当前分类" value={collection.items.filter((item) => item.channel === channel).length} detail="演示记录" /><AdminStat label="警告" value={collection.items.filter((item) => item.channel === channel && item.level === "警告").length} detail="待关注" tone="warning" /><AdminStat label="错误" value={collection.items.filter((item) => item.channel === channel && item.level === "错误").length} detail="待排查" tone="danger" /></AdminStats>
    <AdminFilterBar><AdminSearch value={query} onChange={setQuery} placeholder="搜索主体或摘要" /><AdminSelect label="级别" value={level} onChange={setLevel} options={[{ value: "", label: "全部级别" }, ...levels]} /><label className="admin-select-field"><span>类型</span><input value={eventType} onChange={(event) => setEventType(event.target.value)} placeholder="输入事件类型" /></label><label className="admin-select-field"><span>开始日期</span><input type="date" value={dateFrom} max={dateTo || undefined} onChange={(event) => setDateFrom(event.target.value)} /></label><label className="admin-select-field"><span>结束日期</span><input type="date" value={dateTo} min={dateFrom || undefined} onChange={(event) => setDateTo(event.target.value)} /></label></AdminFilterBar>
    <AdminTable items={visible.slice((currentPage - 1) * 20, currentPage * 20)} getKey={(item) => item.id} loading={collection.loading} error={collection.error} onRetry={() => void collection.refresh()} columns={[
      { key: "time", label: "发生时间", render: (item) => new Date(item.occurredAt).toLocaleString("zh-CN") },
      { key: "level", label: "级别", render: (item) => <StatusBadge tone={item.level === "错误" ? "danger" : item.level === "警告" ? "warning" : "info"}>{item.level}</StatusBadge> },
      { key: "type", label: "类型", render: (item) => <code>{item.type}</code> },
      { key: "subject", label: "主体", render: (item) => item.subject || "未知" },
      { key: "summary", label: "摘要", render: (item) => <span className="admin-break-value">{item.summary}</span> },
      { key: "actions", label: "操作", render: (item) => <Button size="sm" variant="ghost" onClick={() => setDetail(item)}><Eye size={14} />详情</Button> }
    ]} emptyTitle={query || level || eventType || dateFrom || dateTo ? "没有匹配的事件" : "暂无事件记录"} emptyMessage="当前不读取服务器日志；可添加临时演示事件检查列表和详情。" /><AdminPagination page={currentPage} total={visible.length} onPageChange={setPage} />
  </AdminSection>;

  return <AdminPage eyebrow="系统可观测性" title="事件" description="集中浏览系统事件与安全审计；当前不读取真实日志。">
    <Tabs value={channel} onValueChange={(value) => { setChannel(value as "system" | "audit"); setPage(1); }} className="admin-horizontal-tabs"><TabsList className="admin-tabs-list" aria-label="事件分类"><TabsTrigger value="system">系统事件</TabsTrigger><TabsTrigger value="audit">安全审计</TabsTrigger></TabsList><TabsContent value="system">{eventTable}</TabsContent><TabsContent value="audit">{eventTable}<AdminSection title="审计留存设置" description="只保存当前页面的演示选择，不变更真实日志留存策略。"><div className="admin-settings-form"><AdminField label="审计记录保留天数"><input type="number" min="1" max="3650" value={retentionDays} onChange={(event) => setRetentionDays(event.target.value)} /></AdminField><Button variant="confirm" disabled={!retentionDays || Number(retentionDays) < 1} onClick={() => toast.success("审计留存设置已在演示页面中更新，未保存到服务器")}><ShieldCheck size={16} />保存演示设置</Button></div></AdminSection></TabsContent></Tabs>
    <AdminFormDialog open={formOpen} onOpenChange={setFormOpen} title="添加演示事件" description="仅用于测试页面结构，不会写入真实审计链。" onSubmit={create}>
      <AdminField label="级别"><select value={form.level} onChange={(event) => setForm((current) => ({ ...current, level: event.target.value }))}>{levels.map((entry) => <option key={entry.value} value={entry.value}>{entry.label}</option>)}</select></AdminField>
      <AdminField label="类型"><input required value={form.type} onChange={(event) => setForm((current) => ({ ...current, type: event.target.value }))} placeholder="例如：storage.policy.changed" /></AdminField>
      <AdminField label="主体"><input required value={form.subject} onChange={(event) => setForm((current) => ({ ...current, subject: event.target.value }))} placeholder="演示主体" /></AdminField>
      <AdminField label="摘要"><input required value={form.summary} onChange={(event) => setForm((current) => ({ ...current, summary: event.target.value }))} /></AdminField>
      <AdminField label="载荷（文本）" hint="作为纯文本显示，不解析 HTML。"><textarea rows={3} value={form.payload} onChange={(event) => setForm((current) => ({ ...current, payload: event.target.value }))} /></AdminField>
    </AdminFormDialog>
    <Dialog open={Boolean(detail)} onOpenChange={(open) => !open && setDetail(null)} title="事件详情" description="来自当前页面内存中的演示事件。" size="lg"><dl className="admin-detail-list"><div><dt>发生时间</dt><dd>{detail?.occurredAt || "—"}</dd></div><div><dt>主体</dt><dd>{detail?.subject || "—"}</dd></div><div><dt>类型</dt><dd>{detail?.type || "—"}</dd></div><div><dt>摘要</dt><dd>{detail?.summary || "—"}</dd></div><div><dt>载荷</dt><dd><pre className="admin-payload">{detail?.payload || "无载荷"}</pre></dd></div></dl></Dialog>
  </AdminPage>;
}
