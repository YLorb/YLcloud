import { Database, Pencil, Plus, RefreshCw, ShieldCheck } from "lucide-react";
import { useMemo, useState, type FormEvent } from "react";
import { toast } from "sonner";
import { Button } from "../../../components/ui/Button";
import { StatusBadge } from "../../../components/ui/StatusBadge";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "../../../components/ui/Tabs";
import { AdminConfirmDialog, AdminField, AdminFormDialog } from "../components/AdminDialogs";
import { AdminFilterBar, AdminPage, AdminSearch, AdminSection, AdminSelect, AdminStat, AdminStats } from "../components/AdminPage";
import { AdminPagination, AdminTable } from "../components/AdminTable";
import { useAdminCollection, type AdminRecord } from "../core/AdminDataSource";

type Policy = AdminRecord & { name: string; type: string; endpoint: string; status: string; description: string };
const types = [{ value: "local", label: "本地存储" }, { value: "s3", label: "S3 兼容" }, { value: "webdav", label: "WebDAV" }];
const emptyForm = { name: "", type: "local", endpoint: "", description: "" };

export function StoragePoliciesPage() {
  const collection = useAdminCollection<Policy>("storage");
  const [query, setQuery] = useState("");
  const [type, setType] = useState("");
  const [page, setPage] = useState(1);
  const [editing, setEditing] = useState<Policy | null>(null);
  const [formOpen, setFormOpen] = useState(false);
  const [form, setForm] = useState(emptyForm);
  const [confirm, setConfirm] = useState<Policy | null>(null);
  const [pending, setPending] = useState(false);
  const [backupSearch, setBackupSearch] = useState("");
  const [restorePoint, setRestorePoint] = useState("");
  const visible = useMemo(() => collection.items.filter((item) => (!query || `${item.name} ${item.endpoint}`.toLowerCase().includes(query.toLowerCase())) && (!type || item.type === type)), [collection.items, query, type]);
  const currentPage = Math.min(page, Math.max(1, Math.ceil(visible.length / 20)));

  function openForm(item?: Policy) {
    setEditing(item || null);
    setForm(item ? { name: item.name, type: item.type, endpoint: item.endpoint, description: item.description } : emptyForm);
    setFormOpen(true);
  }

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setPending(true);
    try {
      const record = { ...form, name: form.name.trim(), endpoint: form.endpoint.trim(), description: form.description.trim() };
      if (editing) await collection.update(editing.id, record);
      else await collection.create({ ...record, status: "停用" });
      setFormOpen(false);
      toast.success("存储策略已在演示数据中更新，未保存到服务器");
    } catch (error) { toast.error(error instanceof Error ? error.message : "演示操作失败"); }
    finally { setPending(false); }
  }

  async function toggle() {
    if (!confirm) return;
    try {
      await collection.update(confirm.id, { status: confirm.status === "启用" ? "停用" : "启用" });
      setConfirm(null);
      toast.success("策略状态已在演示数据中更新，未保存到服务器");
    } catch (error) { toast.error(error instanceof Error ? error.message : "演示操作失败"); }
  }

  return <AdminPage eyebrow="存储管理" title="存储策略" description="管理存储接入规则，并查看备份与恢复流程的前端结构。">
    <Tabs defaultValue="policies" className="admin-horizontal-tabs">
      <TabsList className="admin-tabs-list" aria-label="存储管理分类"><TabsTrigger value="policies">策略列表</TabsTrigger><TabsTrigger value="overview">备份概览</TabsTrigger><TabsTrigger value="history">备份历史</TabsTrigger><TabsTrigger value="restore">恢复校验</TabsTrigger></TabsList>
      <TabsContent value="policies"><AdminSection title="策略列表" description="默认不读取真实存储配置。创建、编辑和启停只作用于当前页面。" actions={<div className="button-row"><Button variant="ghost" onClick={() => void collection.refresh()}><RefreshCw size={16} />刷新</Button><Button variant="primary" onClick={() => openForm()}><Plus size={16} />添加演示策略</Button></div>}>
        <AdminStats><AdminStat label="策略总数" value={collection.items.length} detail="当前演示列表" icon={<Database size={17} />} /><AdminStat label="已启用" value={collection.items.filter((item) => item.status === "启用").length} detail="并非真实服务状态" tone="success" /><AdminStat label="已停用" value={collection.items.filter((item) => item.status === "停用").length} detail="可在演示中启用" /></AdminStats>
        <AdminFilterBar><AdminSearch value={query} onChange={(value) => { setQuery(value); setPage(1); }} placeholder="搜索策略名称或端点" /><AdminSelect label="存储类型" value={type} onChange={(value) => { setType(value); setPage(1); }} options={[{ value: "", label: "全部类型" }, ...types]} /></AdminFilterBar>
        <AdminTable items={visible.slice((currentPage - 1) * 20, currentPage * 20)} getKey={(item) => item.id} loading={collection.loading} error={collection.error} onRetry={() => void collection.refresh()} emptyTitle={query || type ? "没有匹配的策略" : "尚无存储策略"} emptyMessage="可添加临时演示策略；重新加载页面后恢复为空。" columns={[
          { key: "name", label: "策略", render: (item) => <><strong>{item.name}</strong><small className="admin-cell-note">{item.description || "无描述"}</small></> },
          { key: "type", label: "类型", render: (item) => types.find((entry) => entry.value === item.type)?.label || item.type },
          { key: "endpoint", label: "端点 / 路径", render: (item) => <span className="admin-break-value">{item.endpoint || "未填写"}</span> },
          { key: "status", label: "状态", render: (item) => <StatusBadge tone={item.status === "启用" ? "success" : "neutral"}>{item.status}</StatusBadge> },
          { key: "actions", label: "操作", render: (item) => <div className="admin-row-actions"><Button size="sm" variant="ghost" onClick={() => openForm(item)}><Pencil size={14} />编辑</Button><Button size="sm" variant="ghost" onClick={() => setConfirm(item)}>{item.status === "启用" ? "停用" : "启用"}</Button></div> }
        ]} /><AdminPagination page={currentPage} total={visible.length} onPageChange={setPage} />
      </AdminSection></TabsContent>
      <TabsContent value="overview"><AdminSection title="备份概览" description="备份能力尚未接入；此处不探测数据库或对象存储。"><AdminStats><AdminStat label="最近备份" value="—" detail="暂无服务器数据" /><AdminStat label="备份任务" value="0" detail="演示记录" /><AdminStat label="恢复点" value="0" detail="未连接备份服务" /></AdminStats><div className="admin-info-panel"><ShieldCheck size={22} /><div><strong>尚未连接备份服务</strong><p>接入后显示备份范围、目标、容量、最近成功时间和失败告警。当前不执行真实备份。</p></div></div></AdminSection></TabsContent>
      <TabsContent value="history"><AdminSection title="备份历史" description="按任务编号或存储位置查找历史备份记录。"><AdminFilterBar><AdminSearch value={backupSearch} onChange={setBackupSearch} placeholder="搜索备份任务或目标" /></AdminFilterBar><AdminTable items={[]} getKey={() => ""} columns={[{ key: "job", label: "任务编号", render: () => null }, { key: "scope", label: "备份范围", render: () => null }, { key: "target", label: "备份目标", render: () => null }, { key: "status", label: "状态", render: () => null }, { key: "time", label: "完成时间", render: () => null }]} emptyTitle="暂无备份历史" emptyMessage="接入备份服务后显示历史记录；当前不会读取真实备份。" /><AdminPagination /></AdminSection></TabsContent>
      <TabsContent value="restore"><AdminSection title="恢复校验" description="仅设计恢复点完整性检查流程，不执行数据恢复。"><div className="admin-settings-form"><AdminField label="恢复点编号"><input value={restorePoint} onChange={(event) => setRestorePoint(event.target.value)} placeholder="输入演示恢复点编号" /></AdminField><Button variant="primary" disabled={!restorePoint.trim()} onClick={() => toast.message("演示校验入口已触发；未连接备份服务，无法验证真实恢复点")}>校验演示恢复点</Button></div><div className="admin-info-panel"><ShieldCheck size={22} /><div><strong>未选择可用恢复点</strong><p>未来将在这里显示校验清单、签名、依赖关系和恢复前风险提示；目前不更改任何数据。</p></div></div></AdminSection></TabsContent>
    </Tabs>
    <AdminFormDialog open={formOpen} onOpenChange={setFormOpen} title={editing ? "编辑演示存储策略" : "添加演示存储策略"} description="只修改当前页面内存中的演示数据。" pending={pending} onSubmit={save}>
      <AdminField label="策略名称"><input required maxLength={80} value={form.name} onChange={(event) => setForm((current) => ({ ...current, name: event.target.value }))} placeholder="例如：归档存储" /></AdminField>
      <AdminField label="存储类型"><select value={form.type} onChange={(event) => setForm((current) => ({ ...current, type: event.target.value }))}>{types.map((entry) => <option key={entry.value} value={entry.value}>{entry.label}</option>)}</select></AdminField>
      <AdminField label="端点或路径" hint="不要填写真实访问密钥。"><input required value={form.endpoint} onChange={(event) => setForm((current) => ({ ...current, endpoint: event.target.value }))} placeholder="例如：/data/archive" /></AdminField>
      <AdminField label="描述"><textarea rows={3} value={form.description} onChange={(event) => setForm((current) => ({ ...current, description: event.target.value }))} /></AdminField>
    </AdminFormDialog>
    <AdminConfirmDialog open={Boolean(confirm)} onOpenChange={(open) => !open && setConfirm(null)} title={confirm?.status === "启用" ? "停用演示策略？" : "启用演示策略？"} description="此操作仅改变页面内存中的状态，不影响真实存储服务。" confirmLabel={confirm?.status === "启用" ? "确认停用" : "确认启用"} onConfirm={() => void toggle()} />
  </AdminPage>;
}
