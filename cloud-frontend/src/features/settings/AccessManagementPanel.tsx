import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { KeyRound, Pencil, Plus, Search, ShieldCheck, Trash2, UserPlus, Users } from "lucide-react";
import { useMemo, useState, type FormEvent } from "react";
import { toast } from "sonner";
import { api } from "../../api";
import { useSession } from "../../app/session";
import { Button } from "../../components/ui/Button";
import { Dialog } from "../../components/ui/Dialog";
import { EmptyState, ErrorState, LoadingState } from "../../components/ui/PageState";
import { StatusBadge } from "../../components/ui/StatusBadge";
import { formatTime } from "../../fileUtils";
import type { AdminUser, PermissionDefinition, PermissionGroup } from "../../types";

type UserDraft = { username: string; password: string; nickname: string; email: string; role: "ADMIN" | "USER"; groupId: string };
type GroupDraft = { id?: number; name: string; description: string; permissions: Record<string, boolean>; systemGroup?: boolean };
type OverrideMode = "inherit" | "allow" | "deny";
type AccessDraft = { user: AdminUser; groupId: string; overrides: Record<string, OverrideMode> };

const emptyUser: UserDraft = { username: "", password: "", nickname: "", email: "", role: "USER", groupId: "" };

export function AccessManagementPanel() {
  const client = useQueryClient();
  const { user: currentUser } = useSession();
  const users = useQuery({ queryKey: ["admin-users"], queryFn: api.adminUsers });
  const groups = useQuery({ queryKey: ["permission-groups"], queryFn: api.permissionGroups });
  const definitions = useQuery({ queryKey: ["permission-definitions"], queryFn: api.permissionDefinitions, staleTime: 300_000 });
  const [view, setView] = useState<"users" | "groups">("users");
  const [query, setQuery] = useState("");
  const [userDraft, setUserDraft] = useState<UserDraft | null>(null);
  const [groupDraft, setGroupDraft] = useState<GroupDraft | null>(null);
  const [accessDraft, setAccessDraft] = useState<AccessDraft | null>(null);
  const [accountChange, setAccountChange] = useState<{ user: AdminUser; payload: { role?: "ADMIN" | "USER"; status?: number }; description: string } | null>(null);
  const [deleteGroup, setDeleteGroup] = useState<PermissionGroup | null>(null);

  const refresh = () => {
    client.invalidateQueries({ queryKey: ["admin-users"] });
    client.invalidateQueries({ queryKey: ["permission-groups"] });
  };
  const createUser = useMutation({
    mutationFn: (draft: UserDraft) => api.createAdminUser({ ...draft, email: draft.email || undefined, groupId: draft.groupId ? Number(draft.groupId) : undefined }),
    onSuccess: () => { toast.success("用户已创建并初始化个人空间"); setUserDraft(null); refresh(); },
    onError: report("创建用户失败")
  });
  const saveGroup = useMutation({
    mutationFn: (draft: GroupDraft) => draft.id
      ? api.updatePermissionGroup(draft.id,draft)
      : api.createPermissionGroup(draft),
    onSuccess: () => { toast.success("用户组权限已保存"); setGroupDraft(null); refresh(); },
    onError: report("保存用户组失败")
  });
  const removeGroup = useMutation({
    mutationFn: (id: number) => api.deletePermissionGroup(id),
    onSuccess: () => { toast.success("用户组已删除"); setDeleteGroup(null); refresh(); },
    onError: report("删除用户组失败")
  });
  const updateAccount = useMutation({
    mutationFn: () => api.updateAdminUser(accountChange!.user.id,accountChange!.payload),
    onSuccess: () => { toast.success("账号角色或状态已更新"); setAccountChange(null); refresh(); },
    onError: report("用户更新失败")
  });
  const updateAccess = useMutation({
    mutationFn: (draft: AccessDraft) => api.updateAdminUserAccess(draft.user.id,{
      groupId: draft.groupId ? Number(draft.groupId) : undefined,
      clearGroup: !draft.groupId,
      overrides: Object.fromEntries(Object.entries(draft.overrides).filter(([,mode]) => mode !== "inherit").map(([key,mode]) => [key,mode === "allow"]))
    }),
    onSuccess: () => { toast.success("用户权限已更新"); setAccessDraft(null); refresh(); },
    onError: report("用户权限更新失败")
  });
  const filtered = useMemo(() => (users.data || []).filter((item) => `${item.username} ${item.nickname || ""} ${item.groupName || ""}`.toLowerCase().includes(query.trim().toLowerCase())), [users.data,query]);

  if(users.isLoading || groups.isLoading || definitions.isLoading) return <LoadingState label="正在加载权限系统" />;
  const error = users.error || groups.error || definitions.error;
  if(error) return <ErrorState message={error instanceof Error ? error.message : "无法加载权限系统"} onRetry={() => { users.refetch(); groups.refetch(); definitions.refetch(); }} />;
  const permissionDefinitions = definitions.data || [];
  const permissionGroups = groups.data || [];

  return <section className="access-management" aria-labelledby="access-management-title">
    <header className="access-management__header"><div><h3 id="access-management-title"><ShieldCheck size={18} />用户、用户组与权限</h3><p>用户默认继承所属组权限；单用户覆盖优先于用户组。云盘总权限关闭时覆盖全部单项权限。</p></div><div className="button-row"><Button onClick={() => setUserDraft({ ...emptyUser })}><UserPlus size={16} />新建用户</Button><Button variant="confirm" onClick={() => setGroupDraft(newGroupDraft(permissionDefinitions))}><Plus size={16} />新建用户组</Button></div></header>
    <div className="access-view-switch" role="tablist" aria-label="权限管理视图"><button role="tab" aria-selected={view === "users"} onClick={() => setView("users")}><Users size={16} />用户 <span>{users.data?.length || 0}</span></button><button role="tab" aria-selected={view === "groups"} onClick={() => setView("groups")}><KeyRound size={16} />用户组 <span>{permissionGroups.length}</span></button></div>
    {view === "users" ? <>
      <label className="access-search"><Search size={16} aria-hidden="true" /><input type="search" value={query} onChange={(event) => setQuery(event.target.value)} placeholder="搜索用户名、昵称或用户组" aria-label="搜索用户" /></label>
      {filtered.length ? <div className="data-table-wrap"><table className="data-table admin-users-table"><thead><tr><th>用户</th><th>角色</th><th>用户组</th><th>有效权限</th><th>状态</th><th>操作</th></tr></thead><tbody>{filtered.map((item) => { const self = item.id === currentUser?.id; const enabled = Object.values(item.effectivePermissions || {}).filter(Boolean).length; return <tr key={item.id}><td><strong>{item.nickname || item.username}</strong><small>@{item.username}{self ? " · 当前账号" : ""}<br />{formatTime(item.createTime)}</small></td><td><select aria-label={`${item.username} 的角色`} value={item.role} disabled={self} onChange={(event) => setAccountChange({ user: item, payload: { role: event.target.value as "ADMIN" | "USER" }, description: `将 ${item.username} 的角色改为 ${event.target.value}` })}><option value="USER">USER</option><option value="ADMIN">ADMIN</option></select></td><td>{item.groupName ? <StatusBadge>{item.groupName}</StatusBadge> : <span className="muted-text">未分组</span>}</td><td><span className="permission-count"><strong>{item.role === "ADMIN" ? "全部" : `${enabled}/${permissionDefinitions.length}`}</strong><small>{Object.keys(item.permissionOverrides || {}).length ? `${Object.keys(item.permissionOverrides).length} 项单独覆盖` : "继承用户组"}</small></span></td><td><select aria-label={`${item.username} 的状态`} value={item.status} disabled={self} onChange={(event) => setAccountChange({ user: item, payload: { status: Number(event.target.value) }, description: `${event.target.value === "1" ? "启用" : "停用"}账号 ${item.username}` })}><option value={1}>启用</option><option value={0}>停用</option></select></td><td><Button size="sm" onClick={() => setAccessDraft(accessFrom(item,permissionDefinitions))}><Pencil size={14} />管理权限</Button></td></tr>; })}</tbody></table></div> : <EmptyState title="没有匹配的用户" message="请调整搜索条件，或创建新用户。" />}
    </> : <div className="permission-group-grid">{permissionGroups.map((group) => <article className="permission-group-card" key={group.id}><header><div><h4>{group.name}</h4><p>{group.description || "未填写用户组说明"}</p></div>{group.systemGroup && <StatusBadge tone="success">系统组</StatusBadge>}</header><div className="permission-group-card__meta"><span><Users size={15} />{group.userCount} 名用户</span><span>{Object.values(group.permissions).filter(Boolean).length}/{permissionDefinitions.length} 项已授权</span></div><ul>{permissionDefinitions.map((definition) => <li key={definition.key} data-enabled={Boolean(group.permissions[definition.key])}><span>{definition.label}</span><small>{group.permissions[definition.key] ? "允许" : "禁止"}</small></li>)}</ul><footer><Button size="sm" onClick={() => setGroupDraft({ id: group.id, name: group.name, description: group.description || "", permissions: { ...group.permissions }, systemGroup: group.systemGroup })}><Pencil size={14} />编辑</Button><Button size="sm" variant="danger" disabled={group.systemGroup} onClick={() => setDeleteGroup(group)}><Trash2 size={14} />删除</Button></footer></article>)}</div>}

    <UserCreateDialog draft={userDraft} groups={permissionGroups} pending={createUser.isPending} onChange={setUserDraft} onSubmit={(event) => { event.preventDefault(); if(userDraft) createUser.mutate(userDraft); }} />
    <GroupDialog draft={groupDraft} definitions={permissionDefinitions} pending={saveGroup.isPending} onChange={setGroupDraft} onSubmit={(event) => { event.preventDefault(); if(groupDraft) saveGroup.mutate(groupDraft); }} />
    <AccessDialog draft={accessDraft} groups={permissionGroups} definitions={permissionDefinitions} pending={updateAccess.isPending} onChange={setAccessDraft} onSave={() => accessDraft && updateAccess.mutate(accessDraft)} />
    <Dialog open={Boolean(accountChange)} onOpenChange={(open) => !open && setAccountChange(null)} title="确认账号变更？" description="角色和账号状态变更会立即影响该用户的后续请求。" footer={<><Button onClick={() => setAccountChange(null)}>取消</Button><Button variant={accountChange?.payload.status === 0 ? "danger" : "confirm"} loading={updateAccount.isPending} onClick={() => updateAccount.mutate()}>确认变更</Button></>}><div className={accountChange?.payload.status === 0 ? "danger-callout" : "info-callout"}>{accountChange?.description}</div></Dialog>
    <Dialog open={Boolean(deleteGroup)} onOpenChange={(open) => !open && setDeleteGroup(null)} title="删除用户组？" description="只有不包含用户的非系统组可以删除。" footer={<><Button onClick={() => setDeleteGroup(null)}>取消</Button><Button variant="danger" loading={removeGroup.isPending} onClick={() => deleteGroup && removeGroup.mutate(deleteGroup.id)}>确认删除</Button></>}><div className="danger-callout">将删除用户组：<strong>{deleteGroup?.name}</strong></div></Dialog>
  </section>;
}

function UserCreateDialog({ draft, groups, pending, onChange, onSubmit }: { draft: UserDraft | null; groups: PermissionGroup[]; pending: boolean; onChange: (draft: UserDraft | null) => void; onSubmit: (event: FormEvent) => void }) {
  if(!draft) return null;
  const set = (key: keyof UserDraft, value: string) => onChange({ ...draft,[key]: value });
  return <Dialog open onOpenChange={(open) => !open && onChange(null)} title="新建用户" description="系统将同时创建根目录和默认个人空间。" footer={<><Button onClick={() => onChange(null)}>取消</Button><Button variant="confirm" type="submit" form="create-admin-user" loading={pending}>创建用户</Button></>}><form id="create-admin-user" className="dialog-form" onSubmit={onSubmit}><label>用户名<input required maxLength={100} autoComplete="off" value={draft.username} onChange={(event) => set("username",event.target.value)} /></label><label>昵称<input required maxLength={100} value={draft.nickname} onChange={(event) => set("nickname",event.target.value)} /></label><label>初始密码<input required minLength={8} maxLength={100} type="password" autoComplete="new-password" value={draft.password} onChange={(event) => set("password",event.target.value)} /></label><label>邮箱（可选）<input type="email" maxLength={255} value={draft.email} onChange={(event) => set("email",event.target.value)} /></label><label>角色<select value={draft.role} onChange={(event) => set("role",event.target.value)}><option value="USER">USER</option><option value="ADMIN">ADMIN</option></select></label><label>用户组<select value={draft.groupId} onChange={(event) => set("groupId",event.target.value)}><option value="">未分组（兼容默认权限）</option>{groups.map((group) => <option key={group.id} value={group.id}>{group.name}</option>)}</select></label></form></Dialog>;
}

function GroupDialog({ draft, definitions, pending, onChange, onSubmit }: { draft: GroupDraft | null; definitions: PermissionDefinition[]; pending: boolean; onChange: (draft: GroupDraft | null) => void; onSubmit: (event: FormEvent) => void }) {
  if(!draft) return null;
  return <Dialog open onOpenChange={(open) => !open && onChange(null)} title={draft.id ? "编辑用户组" : "新建用户组"} description="用户组提供默认权限，单用户覆盖仍具有更高优先级。" footer={<><Button onClick={() => onChange(null)}>取消</Button><Button variant="confirm" type="submit" form="permission-group-form" loading={pending}>保存用户组</Button></>}><form id="permission-group-form" className="dialog-form" onSubmit={onSubmit}><label>用户组名称<input required maxLength={100} value={draft.name} onChange={(event) => onChange({ ...draft,name: event.target.value })} /></label><label>说明<textarea rows={2} maxLength={500} value={draft.description} onChange={(event) => onChange({ ...draft,description: event.target.value })} /></label><fieldset className="permission-fieldset"><legend>组权限</legend>{definitions.map((definition) => <label key={definition.key} className="permission-toggle"><input type="checkbox" checked={Boolean(draft.permissions[definition.key])} onChange={(event) => onChange({ ...draft,permissions: { ...draft.permissions,[definition.key]: event.target.checked } })} /><span><strong>{definition.label}</strong><small>{definition.description}</small></span></label>)}</fieldset></form></Dialog>;
}

function AccessDialog({ draft, groups, definitions, pending, onChange, onSave }: { draft: AccessDraft | null; groups: PermissionGroup[]; definitions: PermissionDefinition[]; pending: boolean; onChange: (draft: AccessDraft | null) => void; onSave: () => void }) {
  if(!draft) return null;
  return <Dialog open onOpenChange={(open) => !open && onChange(null)} title={`管理 ${draft.user.username} 的权限`} description="“继承”使用所属用户组设置；单独允许或禁止会覆盖用户组。" footer={<><Button onClick={() => onChange(null)}>取消</Button><Button variant="confirm" loading={pending} onClick={onSave}>保存权限</Button></>}><div className="dialog-form"><label>所属用户组<select value={draft.groupId} onChange={(event) => onChange({ ...draft,groupId: event.target.value })}><option value="">未分组（兼容默认权限）</option>{groups.map((group) => <option key={group.id} value={group.id}>{group.name}</option>)}</select></label><div className="permission-override-list">{definitions.map((definition) => <label key={definition.key}><span><strong>{definition.label}</strong><small>{definition.description}</small></span><select aria-label={`${definition.label}的用户覆盖`} value={draft.overrides[definition.key] || "inherit"} onChange={(event) => onChange({ ...draft,overrides: { ...draft.overrides,[definition.key]: event.target.value as OverrideMode } })}><option value="inherit">继承用户组</option><option value="allow">单独允许</option><option value="deny">单独禁止</option></select></label>)}</div>{draft.user.role === "ADMIN" && <div className="info-callout">ADMIN 为防止平台失去管理入口，业务权限校验默认放行；此处配置会保留，角色调整为 USER 后立即生效。</div>}</div></Dialog>;
}

function newGroupDraft(definitions: PermissionDefinition[]): GroupDraft { return { name: "",description: "",permissions: Object.fromEntries(definitions.map((definition) => [definition.key,false])) }; }
function accessFrom(user: AdminUser, definitions: PermissionDefinition[]): AccessDraft { return { user,groupId: user.groupId ? String(user.groupId) : "",overrides: Object.fromEntries(definitions.map((definition) => [definition.key,definition.key in (user.permissionOverrides || {}) ? user.permissionOverrides[definition.key] ? "allow" : "deny" : "inherit"])) }; }
function report(fallback: string) { return (error: Error) => toast.error(error instanceof Error ? error.message : fallback); }
