import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { api } from "../../../api";
import type { PermissionGroup, QuotaPolicy } from "../../../types";
import { Button } from "../../../components/ui/Button";
import { Dialog } from "../../../components/ui/Dialog";
import { AdminField, AdminFormDialog } from "../components/AdminDialogs";
import { AdminPage, AdminSearch } from "../components/AdminPage";
import { AdminPagination, AdminTable } from "../components/AdminTable";

type Draft = { id?: number; name: string; description: string; permissions: Record<string, boolean> };
const quotaFields: [keyof Omit<QuotaPolicy, "groupId">, string][] = [
  ["storageBytes", "存储容量（字节）"], ["maxFileBytes", "单文件上限（字节）"], ["spaceLimit", "Space 数量"],
  ["monthlyApiCalls", "每月 API 调用"], ["monthlyModelTokens", "每月模型 Token 配额"],
  ["monthlyAgentTasks", "每月 Agent 任务"], ["concurrentAgentTasks", "并发 Agent 任务"]
];
const errorText = (error: unknown) => error instanceof Error ? error.message : "请求失败";

export function LiveGroupsPage() {
  const client = useQueryClient();
  const groups = useQuery({ queryKey: ["permission-groups"], queryFn: api.permissionGroups });
  const definitions = useQuery({ queryKey: ["permission-definitions"], queryFn: api.permissionDefinitions });
  const [query, setQuery] = useState("");
  const [page, setPage] = useState(1);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [removing, setRemoving] = useState<PermissionGroup | null>(null);
  const [quotaGroup, setQuotaGroup] = useState<PermissionGroup | null>(null);
  const save = useMutation({ mutationFn: (value: Draft) => value.id ? api.updatePermissionGroup(value.id, value) : api.createPermissionGroup(value),
    onSuccess: async () => { setDraft(null); await client.invalidateQueries({ queryKey: ["permission-groups"] }); } });
  const remove = useMutation({ mutationFn: api.deletePermissionGroup, onSuccess: async () => { setRemoving(null); await client.invalidateQueries({ queryKey: ["permission-groups"] }); } });
  const visible = (groups.data || []).filter((group) => `${group.name} ${group.description || ""}`.toLocaleLowerCase().includes(query.trim().toLocaleLowerCase()));
  const currentPage = Math.min(page, Math.max(1, Math.ceil(visible.length / 20)));
  return <AdminPage eyebrow="访问控制" title="用户组" description="管理真实用户组、权限及配额；存储策略暂不配置。"
    actions={<div className="button-row"><Button onClick={() => { void groups.refetch(); void definitions.refetch(); }} disabled={groups.isFetching}>刷新</Button><Button variant="primary" disabled={!definitions.data} onClick={() => { save.reset(); setDraft({ name: "", description: "", permissions: Object.fromEntries((definitions.data || []).map((item) => [item.key, false])) }); }}>新建用户组</Button></div>}>
    {definitions.isError && <p role="alert">权限定义加载失败，编辑暂不可用。{errorText(definitions.error)}</p>}
    <AdminSearch value={query} onChange={(value) => { setQuery(value); setPage(1); }} placeholder="搜索用户组名称或说明" />
    <AdminTable items={visible.slice((currentPage - 1) * 20, currentPage * 20)} getKey={(group) => group.id} loading={groups.isPending} error={groups.isError ? errorText(groups.error) : null} onRetry={() => void groups.refetch()} emptyTitle="暂无用户组" emptyMessage="没有符合条件的服务器记录。" columns={[
      { key: "id", label: "用户组 ID", render: (group) => group.id },
      { key: "name", label: "名称", render: (group) => <><strong>{group.name}</strong><small className="admin-cell-note">{group.description || "—"}</small></> },
      { key: "users", label: "用户数", render: (group) => group.userCount },
      { key: "system", label: "类型", render: (group) => group.systemGroup ? "系统组" : "自定义组" },
      { key: "permissions", label: "已授权权限", render: (group) => Object.values(group.permissions).filter(Boolean).length },
      { key: "actions", label: "操作", render: (group) => <div className="admin-row-actions"><Button size="sm" disabled={!definitions.data} onClick={() => { save.reset(); setDraft({ id: group.id, name: group.name, description: group.description || "", permissions: { ...group.permissions } }); }}>编辑权限</Button><Button size="sm" onClick={() => setQuotaGroup(group)}>配额</Button><Button size="sm" variant="danger" disabled={group.systemGroup || group.userCount > 0} onClick={() => { remove.reset(); setRemoving(group); }}>删除</Button></div> }
    ]} />
    <AdminPagination page={currentPage} total={visible.length} onPageChange={setPage} />
    <AdminFormDialog open={draft !== null} onOpenChange={(open) => !open && !save.isPending && setDraft(null)} title={draft?.id ? "编辑用户组" : "新建用户组"} description="权限调整将影响继承此组权限的用户。" submitLabel="确认保存到服务器" pending={save.isPending} onSubmit={(event) => { event.preventDefault(); if (draft && !save.isPending) save.mutate(draft); }}>
      {save.isError && <p role="alert">{errorText(save.error)}</p>}
      {draft && <><AdminField label="用户组名称"><input required value={draft.name} onChange={(event) => setDraft({ ...draft, name: event.target.value })} /></AdminField><AdminField label="说明"><textarea value={draft.description} onChange={(event) => setDraft({ ...draft, description: event.target.value })} /></AdminField>
        {(definitions.data || []).map((definition) => <AdminField key={definition.key} label={definition.label} hint={definition.description}><select value={String(Boolean(draft.permissions[definition.key]))} onChange={(event) => setDraft({ ...draft, permissions: { ...draft.permissions, [definition.key]: event.target.value === "true" } })}><option value="false">拒绝</option><option value="true">允许</option></select></AdminField>)}</>}
    </AdminFormDialog>
    <Dialog open={removing !== null} onOpenChange={(open) => !open && !remove.isPending && setRemoving(null)} title="删除用户组？" description="只有没有成员的非系统组可以删除；操作会写入服务器。" footer={<><Button disabled={remove.isPending} onClick={() => setRemoving(null)}>取消</Button><Button variant="danger" loading={remove.isPending} onClick={() => removing && !remove.isPending && remove.mutate(removing.id)}>确认删除</Button></>}>
      <p>{removing?.name}</p>{remove.isError && <p role="alert">{errorText(remove.error)}</p>}
    </Dialog>
    {quotaGroup && <GroupQuotaEditor group={quotaGroup} onClose={() => setQuotaGroup(null)} />}
  </AdminPage>;
}

function GroupQuotaEditor({ group, onClose }: { group: PermissionGroup; onClose: () => void }) {
  const client = useQueryClient();
  const quota = useQuery({ queryKey: ["admin-group-quota", group.id], queryFn: () => api.groupQuota(group.id) });
  const [changes, setChanges] = useState<Record<string, string>>({});
  const [validation, setValidation] = useState<string | null>(null);
  const save = useMutation({ mutationFn: (value: Omit<QuotaPolicy, "groupId">) => api.updateGroupQuota(group.id, value),
    onSuccess: (value) => { client.setQueryData(["admin-group-quota", group.id], value); onClose(); } });
  return <AdminFormDialog open onOpenChange={(open) => !open && !save.isPending && onClose()} title={`${group.name} · 配额`} description="配额按整数保存。Token 配额是已有策略字段，不代表已接入全站 Token 消耗统计。" submitLabel="确认保存配额" pending={save.isPending} onSubmit={(event) => {
    event.preventDefault(); if (!quota.data || save.isPending) return;
    const values = Object.fromEntries(quotaFields.map(([key]) => [key, Number(changes[key] ?? quota.data[key])])) as Omit<QuotaPolicy, "groupId">;
    if (Object.values(values).some((value) => !Number.isSafeInteger(value) || value < 0) || Object.values(changes).some((value) => !value.trim())) { setValidation("配额必须为非负安全整数，不能为空。"); return; }
    setValidation(null); save.mutate(values);
  }}>
    {quota.isPending && <p role="status">正在读取配额…</p>}
    {quota.isError && <div role="alert">{errorText(quota.error)}<Button type="button" onClick={() => void quota.refetch()}>重试</Button></div>}
    {(validation || save.isError) && <p role="alert">{validation || errorText(save.error)}</p>}
    {quota.data && quotaFields.map(([key, label]) => <AdminField key={key} label={label}><input type="number" required min="0" max={Number.MAX_SAFE_INTEGER} step="1" value={changes[key] ?? quota.data[key]} onChange={(event) => setChanges((current) => ({ ...current, [key]: event.target.value }))} /></AdminField>)}
  </AdminFormDialog>;
}
