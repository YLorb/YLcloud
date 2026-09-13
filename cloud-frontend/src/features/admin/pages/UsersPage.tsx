import { StatusBadge } from "../../../components/ui/StatusBadge";
import { AdminCollectionPage, type AdminCollectionConfig } from "../components/AdminCollectionPage";
import type { AdminRecord } from "../core/AdminDataSource";

type UserRow = AdminRecord & { username: string; nickname: string; email: string; role: string; group: string; status: string; storage: string };
const config: AdminCollectionConfig<UserRow> = {
  resource: "users", eyebrow: "账号治理", title: "用户", description: "查看账号状态、归属用户组与前端权限摘要。",
  searchPlaceholder: "搜索用户名、昵称或邮箱", searchFields: ["username", "nickname", "email", "group"],
  filters: [
    { key: "role", label: "角色", options: [{ value: "ADMIN", label: "ADMIN" }, { value: "USER", label: "USER" }] },
    { key: "status", label: "状态", options: [{ value: "启用", label: "启用" }, { value: "停用", label: "停用" }] }
  ],
  textFilters: [{ key: "group", label: "用户组" }],
  columns: [
    { key: "id", label: "用户 ID", render: (row) => row.id },
    { key: "user", label: "用户", render: (row) => <><strong>{row.nickname}</strong><small className="admin-cell-note">@{row.username}</small></> },
    { key: "email", label: "Email", render: (row) => row.email || "—" },
    { key: "role", label: "角色", render: (row) => <StatusBadge tone={row.role === "ADMIN" ? "warning" : "neutral"}>{row.role}</StatusBadge> },
    { key: "group", label: "用户组", render: (row) => row.group || "未分组" },
    { key: "storage", label: "已用空间", render: (row) => row.storage },
    { key: "status", label: "状态", render: (row) => <StatusBadge tone={row.status === "启用" ? "success" : "danger"}>{row.status}</StatusBadge> }
  ],
  fields: [
    { key: "username", label: "用户名", required: true, placeholder: "例如：demo_user" },
    { key: "nickname", label: "昵称", required: true },
    { key: "email", label: "Email", type: "email" },
    { key: "role", label: "角色", type: "select", required: true, options: [{ value: "USER", label: "USER" }, { value: "ADMIN", label: "ADMIN" }] },
    { key: "group", label: "用户组", placeholder: "例如：默认用户组" },
    { key: "status", label: "状态", type: "select", required: true, options: [{ value: "启用", label: "启用" }, { value: "停用", label: "停用" }] }
  ],
  createLabel: "新建用户", emptyTitle: "尚无用户演示数据", emptyMessage: "新建、编辑和状态调整仅在当前页面中演示。",
  actions: [
    { label: "停用", when: (item) => item.status === "启用", patch: () => ({ status: "停用" }), danger: true },
    { label: "启用", when: (item) => item.status === "停用", patch: () => ({ status: "启用" }), danger: false }
  ],
  build: (values) => ({ username: values.username, nickname: values.nickname, email: values.email, role: values.role, group: values.group, status: values.status, storage: "0 B" }),
  stats: [{ label: "用户", value: (items) => items.length, detail: "演示账号" }, { label: "已启用", value: (items) => items.filter((item) => item.status === "启用").length, detail: "当前可用", tone: "success" }, { label: "管理员", value: (items) => items.filter((item) => item.role === "ADMIN").length, detail: "高权限角色", tone: "warning" }]
};
export function UsersPage() { return <AdminCollectionPage config={config} />; }
