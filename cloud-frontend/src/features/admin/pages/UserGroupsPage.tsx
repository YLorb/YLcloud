import { StatusBadge } from "../../../components/ui/StatusBadge";
import { AdminCollectionPage, type AdminCollectionConfig } from "../components/AdminCollectionPage";
import type { AdminRecord } from "../core/AdminDataSource";

type GroupRow = AdminRecord & { name: string; description: string; policy: string; quota: string; users: number; system: string };

const config: AdminCollectionConfig<GroupRow> = {
  resource: "groups", eyebrow: "访问控制", title: "用户组", description: "组织用户的默认权限、存储策略与容量边界。",
  searchPlaceholder: "搜索用户组名称或说明", searchFields: ["name", "description", "policy"],
  filters: [{ key: "system", label: "类型", options: [{ value: "系统组", label: "系统组" }, { value: "自定义组", label: "自定义组" }] }],
  columns: [
    { key: "id", label: "用户组 ID", render: (row) => row.id },
    { key: "name", label: "名称", render: (row) => <><strong>{row.name}</strong><small className="admin-cell-note">{row.description || "未填写说明"}</small></> },
    { key: "policy", label: "存储策略", render: (row) => row.policy },
    { key: "quota", label: "容量", render: (row) => row.quota },
    { key: "users", label: "用户数", render: (row) => row.users },
    { key: "system", label: "类型", render: (row) => <StatusBadge tone={row.system === "系统组" ? "info" : "neutral"}>{row.system}</StatusBadge> }
  ],
  fields: [
    { key: "name", label: "用户组名称", required: true, placeholder: "例如：课程管理员" },
    { key: "description", label: "说明", type: "textarea", placeholder: "描述该组的使用范围" },
    { key: "policy", label: "存储策略", type: "select", required: true, options: [{ value: "默认策略", label: "默认策略" }, { value: "只读策略", label: "只读策略" }] },
    { key: "quota", label: "容量", required: true, placeholder: "例如：10 GB" }
  ],
  createLabel: "新建用户组", editDisabled: (item) => item.system === "系统组", removeDisabled: (item) => item.system === "系统组", emptyTitle: "尚无用户组演示数据", emptyMessage: "页面结构已就绪；新建记录仅用于当前前端会话。",
  build: (values) => ({ name: values.name, description: values.description, policy: values.policy, quota: values.quota, users: 0, system: "自定义组" }),
  renderDetail: (item) => <section className="admin-detail-timeline"><h3>演示权限摘要</h3><dl className="admin-detail-list"><div><dt>读取文件</dt><dd>允许（演示）</dd></div><div><dt>上传文件</dt><dd>{item.policy === "只读策略" ? "不允许（演示）" : "允许（演示）"}</dd></div><div><dt>分享文件</dt><dd>{item.policy === "只读策略" ? "不允许（演示）" : "允许（演示）"}</dd></div></dl><small>此处不是实际权限检查结果。</small></section>,
  stats: [{ label: "用户组", value: (items) => items.length, detail: "当前演示会话" }, { label: "系统组", value: (items) => items.filter((item) => item.system === "系统组").length, detail: "受保护组", tone: "info" }, { label: "成员总数", value: (items) => items.reduce((sum, item) => sum + item.users, 0), detail: "演示统计" }]
};

export function UserGroupsPage() { return <AdminCollectionPage config={config} />; }
