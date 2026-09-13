import { Eye, Pencil, Plus, RefreshCw, Trash2 } from "lucide-react";
import { useMemo, useState, type ReactNode } from "react";
import { toast } from "sonner";
import { Button } from "../../../components/ui/Button";
import { Dialog } from "../../../components/ui/Dialog";
import type { AdminRecord, AdminResource } from "../core/AdminDataSource";
import { useAdminCollection } from "../core/AdminDataSource";
import { AdminConfirmDialog, AdminField, AdminFormDialog } from "./AdminDialogs";
import { AdminFilterBar, AdminPage, AdminSearch, AdminSelect, AdminStat, AdminStats } from "./AdminPage";
import { AdminPagination, AdminTable, type AdminColumn } from "./AdminTable";

export type AdminDemoField<T> = {
  key: keyof T & string;
  label: string;
  type?: "text" | "email" | "number" | "url" | "date" | "textarea" | "select";
  placeholder?: string;
  required?: boolean;
  options?: Array<{ value: string; label: string }>;
  hint?: string;
  min?: number;
  max?: number;
  maxLength?: number;
};

export type AdminDemoFilter<T> = {
  key: keyof T & string;
  label: string;
  options: Array<{ value: string; label: string }>;
};

export type AdminCollectionConfig<T extends AdminRecord> = {
  resource: AdminResource;
  eyebrow: string;
  title: string;
  description: string;
  searchPlaceholder: string;
  searchFields: Array<keyof T & string>;
  filters?: AdminDemoFilter<T>[];
  textFilters?: Array<{ key: keyof T & string; label: string; placeholder?: string }>;
  dateField?: keyof T & string;
  columns: AdminColumn<T>[];
  fields?: AdminDemoField<T>[];
  createLabel?: string;
  emptyTitle: string;
  emptyMessage: string;
  build: (values: Record<string, string>) => Omit<T, "id">;
  onCreated?: (item: T) => void;
  renderDetail?: (item: T) => ReactNode;
  stats?: Array<{ label: string; detail: string; tone?: "neutral" | "info" | "success" | "warning" | "danger"; value: (items: T[]) => ReactNode }>;
  allowEdit?: boolean;
  editDisabled?: (item: T) => boolean;
  allowRemove?: boolean;
  removeLabel?: string;
  removePatch?: (item: T) => Partial<T>;
  removeDisabled?: (item: T) => boolean;
  actions?: Array<{ label: string; when: (item: T) => boolean; patch: (item: T) => Partial<T>; danger?: boolean }>;
};

export function AdminCollectionPage<T extends AdminRecord>({ config }: { config: AdminCollectionConfig<T> }) {
  const collection = useAdminCollection<T>(config.resource);
  const [query, setQuery] = useState("");
  const [filters, setFilters] = useState<Record<string, string>>({});
  const [dateFrom, setDateFrom] = useState("");
  const [dateTo, setDateTo] = useState("");
  const [formOpen, setFormOpen] = useState(false);
  const [detail, setDetail] = useState<T | null>(null);
  const [editing, setEditing] = useState<T | null>(null);
  const [removing, setRemoving] = useState<T | null>(null);
  const [actioning, setActioning] = useState<{ item: T; action: NonNullable<typeof config.actions>[number] } | null>(null);
  const [values, setValues] = useState<Record<string, string>>({});
  const [pending, setPending] = useState(false);
  const [page, setPage] = useState(1);

  const visible = useMemo(() => collection.items.filter((item) => {
    const normalized = query.trim().toLocaleLowerCase();
    const searchMatch = !normalized || config.searchFields.some((field) => String(item[field] ?? "").toLocaleLowerCase().includes(normalized));
    const filterMatch = (config.filters || []).every((filter) => !filters[filter.key] || String(item[filter.key] ?? "") === filters[filter.key]);
    const textMatch = (config.textFilters || []).every((filter) => !filters[filter.key] || String(item[filter.key] ?? "").toLocaleLowerCase().includes(filters[filter.key].trim().toLocaleLowerCase()));
    const date = config.dateField ? String(item[config.dateField] ?? "").slice(0, 10) : "";
    return searchMatch && filterMatch && textMatch && (!dateFrom || date >= dateFrom) && (!dateTo || date <= dateTo);
  }), [collection.items, config.dateField, config.filters, config.searchFields, config.textFilters, dateFrom, dateTo, filters, query]);
  const currentPage = Math.min(page, Math.max(1, Math.ceil(visible.length / 20)));
  const paged = visible.slice((currentPage - 1) * 20, currentPage * 20);

  const columns: AdminColumn<T>[] = useMemo(() => [...config.columns, {
    key: "actions",
    label: "操作",
    className: "admin-table-actions-cell",
    render: (item: T) => <div className="admin-row-actions">
      <Button size="sm" variant="ghost" onClick={() => setDetail(item)}><Eye size={14} />详情</Button>
      {config.allowEdit !== false && config.fields?.length ? <Button size="sm" variant="ghost" disabled={config.editDisabled?.(item)} onClick={() => openEdit(item)}><Pencil size={14} />编辑</Button> : null}
      {config.actions?.filter((action) => action.when(item)).map((action) => <Button key={action.label} size="sm" variant="ghost" onClick={() => setActioning({ item, action })}>{action.label}</Button>)}
      {config.allowRemove !== false && <Button size="sm" variant="ghost" disabled={config.removeDisabled?.(item)} onClick={() => setRemoving(item)}><Trash2 size={14} />{config.removeLabel || "移除"}</Button>}
    </div>
  }], [config]);

  function openCreate() {
    setEditing(null);
    setValues({});
    setFormOpen(true);
  }

  function openEdit(item: T) {
    setEditing(item);
    setValues(Object.fromEntries((config.fields || []).map((field) => [field.key, String(item[field.key] ?? "")] )));
    setFormOpen(true);
  }

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setPending(true);
    try {
      const record = config.build(values);
      if (editing) {
        const fields = record as unknown as Record<string, unknown>;
        const patch = Object.fromEntries((config.fields || []).map((field) => [field.key, fields[field.key]])) as Partial<T>;
        await collection.update(editing.id, patch);
      }
      else {
        const created = await collection.create(record as Omit<T, "id">);
        config.onCreated?.(created);
      }
      toast.success("演示数据已更新，未保存到服务器");
      setFormOpen(false);
    } catch (reason) {
      toast.error(reason instanceof Error ? reason.message : "演示操作失败");
    } finally {
      setPending(false);
    }
  }

  async function confirmRemove() {
    if (!removing) return;
    try {
      if (config.removePatch) await collection.update(removing.id, config.removePatch(removing));
      else await collection.remove(removing.id);
      setRemoving(null);
      toast.success("演示记录已在当前页面更新，重新加载页面后会恢复初始状态");
    } catch (reason) { toast.error(reason instanceof Error ? reason.message : "演示操作失败"); }
  }

  async function confirmAction() {
    if (!actioning) return;
    try {
      await collection.update(actioning.item.id, actioning.action.patch(actioning.item));
      setActioning(null);
      toast.success("状态已在演示数据中更新，未保存到服务器");
    } catch (reason) { toast.error(reason instanceof Error ? reason.message : "演示操作失败"); }
  }

  return (
    <AdminPage eyebrow={config.eyebrow} title={config.title} description={config.description} actions={<div className="button-row"><Button variant="ghost" onClick={() => void collection.refresh()}><RefreshCw size={16} />刷新</Button>{config.createLabel && <Button variant="primary" onClick={openCreate}><Plus size={16} />{config.createLabel}</Button>}</div>}>
      {config.stats?.length ? <AdminStats>{config.stats.map((stat) => <AdminStat key={stat.label} label={stat.label} detail={stat.detail} tone={stat.tone} value={stat.value(collection.items)} />)}</AdminStats> : null}
      <AdminFilterBar>
        <AdminSearch value={query} onChange={(value) => { setQuery(value); setPage(1); }} placeholder={config.searchPlaceholder} />
        {(config.filters || []).map((filter) => <AdminSelect key={filter.key} label={filter.label} value={filters[filter.key] || ""} onChange={(value) => { setFilters((current) => ({ ...current, [filter.key]: value })); setPage(1); }} options={[{ value: "", label: "全部" }, ...filter.options]} />)}
        {(config.textFilters || []).map((filter) => <label className="admin-select-field" key={filter.key}><span>{filter.label}</span><input value={filters[filter.key] || ""} onChange={(event) => { setFilters((current) => ({ ...current, [filter.key]: event.target.value })); setPage(1); }} placeholder={filter.placeholder || `筛选${filter.label}`} /></label>)}
        {config.dateField && <><label className="admin-select-field"><span>开始日期</span><input type="date" value={dateFrom} max={dateTo || undefined} onChange={(event) => { setDateFrom(event.target.value); setPage(1); }} /></label><label className="admin-select-field"><span>结束日期</span><input type="date" value={dateTo} min={dateFrom || undefined} onChange={(event) => { setDateTo(event.target.value); setPage(1); }} /></label></>}
      </AdminFilterBar>
      <AdminTable columns={columns} items={paged} getKey={(item) => item.id} loading={collection.loading} error={collection.error} onRetry={() => void collection.refresh()} emptyTitle={query || Object.values(filters).some(Boolean) || dateFrom || dateTo ? "没有匹配的记录" : config.emptyTitle} emptyMessage={query || Object.values(filters).some(Boolean) || dateFrom || dateTo ? "请调整筛选条件，或清除搜索内容。" : config.emptyMessage} />
      <AdminPagination page={currentPage} total={visible.length} onPageChange={setPage} />

      {config.fields?.length ? <AdminFormDialog open={formOpen} onOpenChange={setFormOpen} title={editing ? `编辑${config.title}演示记录` : config.createLabel || `新建${config.title}演示记录`} description="只会修改当前页面内存中的演示数据，刷新后重置。" pending={pending} onSubmit={submit}>
        {config.fields.map((field) => <AdminField key={field.key} label={field.label} hint={field.hint}>{field.type === "textarea" ? <textarea rows={3} required={field.required} maxLength={field.maxLength} value={values[field.key] || ""} placeholder={field.placeholder} onChange={(event) => setValues((current) => ({ ...current, [field.key]: event.target.value }))} /> : field.type === "select" ? <select required={field.required} value={values[field.key] || ""} onChange={(event) => setValues((current) => ({ ...current, [field.key]: event.target.value }))}><option value="">请选择</option>{field.options?.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}</select> : <input type={field.type || "text"} required={field.required} min={field.min} max={field.max} maxLength={field.maxLength} step={field.type === "number" ? "1" : undefined} value={values[field.key] || ""} placeholder={field.placeholder} onChange={(event) => setValues((current) => ({ ...current, [field.key]: event.target.value }))} />}</AdminField>)}
      </AdminFormDialog> : null}

      <Dialog open={Boolean(detail)} onOpenChange={(open) => !open && setDetail(null)} title={`${config.title}详情`} description="当前展示的是前端演示记录。" size="lg">
        <dl className="admin-detail-list">{detail && config.columns.map((column) => <div key={column.key}><dt>{column.label}</dt><dd>{column.render(detail)}</dd></div>)}</dl>
        {detail && config.renderDetail?.(detail)}
      </Dialog>
      <AdminConfirmDialog open={Boolean(removing)} onOpenChange={(open) => !open && setRemoving(null)} title={`${config.removeLabel || "移除"}${config.title}记录？`} description={config.removePatch ? "确认后只会改变当前页面的演示记录状态，服务器数据不会改变。" : "确认后只会从当前页面的演示列表中移除，服务器数据不会改变。"} confirmLabel={`确认${config.removeLabel || "移除"}`} onConfirm={() => void confirmRemove()} />
      <AdminConfirmDialog open={Boolean(actioning)} onOpenChange={(open) => !open && setActioning(null)} title={`${actioning?.action.label || "更新"}${config.title}记录？`} description="确认后只修改当前页面内存中的演示状态，不会执行真实业务动作。" confirmLabel={`确认${actioning?.action.label || "更新"}`} danger={actioning?.action.danger ?? true} onConfirm={() => void confirmAction()} />
    </AdminPage>
  );
}
