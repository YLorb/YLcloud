import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { toast } from "sonner";
import { api } from "../../../api";
import type { AdminUser, PermissionDefinition, PermissionGroup } from "../../../types";
import { Button } from "../../../components/ui/Button";
import { Dialog } from "../../../components/ui/Dialog";
import { AdminField, AdminFormDialog } from "../components/AdminDialogs";
import { AdminFilterBar, AdminPage, AdminSearch, AdminSelect } from "../components/AdminPage";
import { AdminPagination, AdminTable } from "../components/AdminTable";

type Editor = "create" | "account" | "access";
const message = (error: unknown) => error instanceof Error ? error.message : "请求失败，请重试";

export function LiveUsersPage() {
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [groups, setGroups] = useState<PermissionGroup[]>([]);
  const [definitions, setDefinitions] = useState<PermissionDefinition[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [accessError, setAccessError] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [status, setStatus] = useState("");
  const [page, setPage] = useState(1);
  const [editor, setEditor] = useState<Editor | null>(null);
  const [target, setTarget] = useState<AdminUser | null>(null);
  const [detail, setDetail] = useState<AdminUser | null>(null);
  const [values, setValues] = useState<Record<string, string>>({});
  const [overrides, setOverrides] = useState<Record<string, boolean>>({});
  const [pending, setPending] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const requestVersion = useRef(0);
  const submitting = useRef(false);

  const refresh = useCallback(async () => {
    const version = ++requestVersion.current;
    setLoading(true);
    setError(null);
    setAccessError(null);
    const [accounts, permissions, groupList] = await Promise.allSettled([
      api.adminUsers(), api.permissionDefinitions(), api.permissionGroups()
    ]);
    if (version !== requestVersion.current) return;
    if (accounts.status === "fulfilled") setUsers(accounts.value);
    else { setUsers([]); setError(message(accounts.reason)); }
    if (permissions.status === "fulfilled") setDefinitions(permissions.value);
    if (groupList.status === "fulfilled") setGroups(groupList.value);
    if (permissions.status === "rejected" || groupList.status === "rejected") {
      setAccessError("权限定义或用户组加载失败，权限修改暂不可用。请刷新重试。");
    }
    setLoading(false);
  }, []);
  useEffect(() => { void refresh(); return () => { requestVersion.current++; }; }, [refresh]);

  const visible = useMemo(() => users.filter((user) =>
    (!status || String(user.status) === status) &&
    [user.username, user.nickname, user.email, user.groupName, String(user.id)].some((value) =>
      (value || "").toLocaleLowerCase().includes(query.trim().toLocaleLowerCase()))
  ), [users, query, status]);
  const currentPage = Math.min(page, Math.max(1, Math.ceil(visible.length / 20)));

  function open(kind: Editor, user: AdminUser | null = null) {
    setTarget(user);
    setValues({ username: "", nickname: "", email: "", password: "", role: user?.role || "USER", status: String(user?.status ?? 1), groupId: user?.groupId == null ? "" : String(user.groupId) });
    setOverrides({ ...user?.permissionOverrides });
    setSaveError(null);
    setEditor(kind);
  }
  function close() {
    if (submitting.current) return;
    setEditor(null);
    setValues({}); // Do not retain account passwords after closing.
    setTarget(null);
  }
  function field(key: string, value: string) { setValues((current) => ({ ...current, [key]: value })); }

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting.current) return;
    submitting.current = true;
    setPending(true);
    setSaveError(null);
    try {
      let updated: AdminUser;
      if (editor === "create") {
        updated = await api.createAdminUser({ username: values.username.trim(), nickname: values.nickname.trim(), email: values.email.trim() || undefined,
          password: values.password, role: values.role as "ADMIN" | "USER", groupId: values.groupId ? Number(values.groupId) : undefined });
      } else if (editor === "account" && target) {
        updated = await api.updateAdminUser(target.id, { role: values.role as "ADMIN" | "USER", status: Number(values.status) });
      } else if (editor === "access" && target) {
        updated = await api.updateAdminUserAccess(target.id, { groupId: values.groupId ? Number(values.groupId) : undefined, clearGroup: !values.groupId, overrides });
      } else return;
      setUsers((current) => current.some((user) => user.id === updated.id) ? current.map((user) => user.id === updated.id ? updated : user) : [...current, updated]);
      setEditor(null);
      setValues({});
      setTarget(null);
      toast.success("服务器已保存用户信息");
    } catch (reason) { setSaveError(message(reason)); }
    finally { submitting.current = false; setPending(false); }
  }

  return <AdminPage eyebrow="账号治理" title="用户" description="管理真实账号、角色、状态、分组和权限覆盖；不提供账号物理删除。"
    actions={<div className="button-row"><Button disabled={loading || pending} onClick={() => void refresh()}>刷新</Button><Button variant="primary" disabled={loading} onClick={() => open("create")}>新建用户</Button></div>}>
    {accessError && <p role="alert">{accessError}</p>}
    <AdminFilterBar><AdminSearch value={query} onChange={(value) => { setQuery(value); setPage(1); }} placeholder="搜索用户 ID、用户名、昵称、邮箱或用户组" />
      <AdminSelect label="状态" value={status} onChange={(value) => { setStatus(value); setPage(1); }} options={[{ value: "", label: "全部" }, { value: "1", label: "启用" }, { value: "0", label: "停用" }]} /></AdminFilterBar>
    <AdminTable items={visible.slice((currentPage - 1) * 20, currentPage * 20)} getKey={(user) => user.id} loading={loading} error={error} onRetry={() => void refresh()} emptyTitle="暂无用户" emptyMessage="没有符合当前筛选条件的服务器记录。" columns={[
      { key: "id", label: "用户 ID", render: (user) => user.id },
      { key: "name", label: "用户", render: (user) => <><strong>{user.nickname || user.username}</strong><small className="admin-cell-note">@{user.username}</small></> },
      { key: "email", label: "Email", render: (user) => user.email || "—" },
      { key: "role", label: "角色", render: (user) => `${user.role}${user.deploymentOwner ? "（部署所有者）" : ""}` },
      { key: "group", label: "用户组", render: (user) => user.groupName || "未分组" },
      { key: "status", label: "状态", render: (user) => user.status === 1 ? "启用" : "停用" },
      { key: "actions", label: "操作", render: (user) => <div className="admin-row-actions"><Button size="sm" onClick={() => setDetail(user)}>详情</Button><Button size="sm" onClick={() => open("account", user)}>角色与状态</Button><Button size="sm" disabled={Boolean(accessError)} onClick={() => open("access", user)}>分组与权限</Button></div> }
    ]} />
    <AdminPagination page={currentPage} total={visible.length} onPageChange={setPage} />
    <AdminFormDialog open={editor !== null} onOpenChange={(open) => !open && close()} title={editor === "create" ? "新建用户" : `${target?.username || ""} · ${editor === "account" ? "角色与状态" : "分组与权限"}`} description="保存将修改服务器账号。后端保护部署所有者、最后一个管理员及当前账号。" submitLabel="确认保存到服务器" pending={pending} onSubmit={save}>
      {saveError && <p role="alert">{saveError}</p>}
      {editor === "create" && <>
        <AdminField label="用户名"><input required autoComplete="off" value={values.username || ""} onChange={(event) => field("username", event.target.value)} /></AdminField>
        <AdminField label="昵称"><input required value={values.nickname || ""} onChange={(event) => field("nickname", event.target.value)} /></AdminField>
        <AdminField label="Email"><input type="email" value={values.email || ""} onChange={(event) => field("email", event.target.value)} /></AdminField>
        <AdminField label="初始密码"><input type="password" required autoComplete="new-password" value={values.password || ""} onChange={(event) => field("password", event.target.value)} /></AdminField>
      </>}
      {editor !== "access" && <AdminField label="角色"><select value={values.role || "USER"} onChange={(event) => field("role", event.target.value)}><option value="USER">USER</option><option value="ADMIN">ADMIN</option></select></AdminField>}
      {editor === "account" && <AdminField label="账号状态"><select value={values.status || "1"} onChange={(event) => field("status", event.target.value)}><option value="1">启用</option><option value="0">停用</option></select></AdminField>}
      {editor !== "account" && <AdminField label="用户组"><select disabled={Boolean(accessError)} value={values.groupId || ""} onChange={(event) => field("groupId", event.target.value)}><option value="">{editor === "create" ? "使用后端默认组" : "解除分组"}</option>{groups.map((group) => <option key={group.id} value={group.id}>{group.name}</option>)}</select></AdminField>}
      {editor === "access" && definitions.map((definition) => <AdminField key={definition.key} label={definition.label} hint={definition.description}><select value={overrides[definition.key] === undefined ? "inherit" : String(overrides[definition.key])} onChange={(event) => setOverrides((current) => { const next = { ...current }; if (event.target.value === "inherit") delete next[definition.key]; else next[definition.key] = event.target.value === "true"; return next; })}><option value="inherit">继承用户组</option><option value="true">允许</option><option value="false">拒绝</option></select></AdminField>)}
    </AdminFormDialog>
    <Dialog open={detail !== null} onOpenChange={(open) => !open && setDetail(null)} title="用户详情" description="有效权限为后端计算结果，不包含账号密码。">
      {detail && <dl className="admin-detail-list"><div><dt>用户</dt><dd>{detail.username}</dd></div><div><dt>创建时间</dt><dd>{detail.createTime || "—"}</dd></div>{Object.entries(detail.effectivePermissions).map(([key, allowed]) => <div key={key}><dt>{definitions.find((item) => item.key === key)?.label || key}</dt><dd>{allowed ? "允许" : "拒绝"}</dd></div>)}</dl>}
    </Dialog>
  </AdminPage>;
}
