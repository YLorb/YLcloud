import { StatusBadge } from "../../../components/ui/StatusBadge";
import { AdminCollectionPage, type AdminCollectionConfig } from "../components/AdminCollectionPage";
import type { AdminRecord } from "../core/AdminDataSource";
import { useAdminDataSource } from "../core/AdminDataSource";
import { LiveTasksPage } from "./LiveTasksPage";

type TaskRow = AdminRecord & { taskId: string; type: string; content: string; status: string; creator: string; node: string; createdAt?: string; updatedAt: string };
const config: AdminCollectionConfig<TaskRow> = {
  resource: "tasks", eyebrow: "运行与调度", title: "后台任务", description: "检查系统任务的状态、处理节点和最近动作。",
  searchPlaceholder: "搜索任务 ID、内容或创建者", searchFields: ["taskId", "content", "creator", "node"],
  filters: [{ key: "type", label: "类型", options: [{ value: "RAG", label: "RAG" }, { value: "文件", label: "文件" }, { value: "维护", label: "维护" }] }, { key: "status", label: "状态", options: [{ value: "等待中", label: "等待中" }, { value: "运行中", label: "运行中" }, { value: "成功", label: "成功" }, { value: "失败", label: "失败" }, { value: "已取消", label: "已取消" }] }],
  textFilters: [{ key: "creator", label: "创建者" }, { key: "node", label: "处理节点" }],
  columns: [{ key: "taskId", label: "任务 ID", render: (row) => <code>{row.taskId}</code> }, { key: "content", label: "任务内容", render: (row) => <strong>{row.content}</strong> }, { key: "type", label: "类型", render: (row) => row.type }, { key: "status", label: "状态", render: (row) => <StatusBadge tone={row.status === "成功" ? "success" : row.status === "失败" ? "danger" : row.status === "运行中" ? "info" : "warning"}>{row.status}</StatusBadge> }, { key: "creator", label: "创建者", render: (row) => row.creator }, { key: "node", label: "处理节点", render: (row) => row.node || "未分配" }, { key: "updatedAt", label: "最新动作", render: (row) => row.updatedAt || "—" }],
  fields: [{ key: "taskId", label: "任务 ID", required: true }, { key: "content", label: "任务内容", required: true }, { key: "type", label: "类型", type: "select", required: true, options: [{ value: "RAG", label: "RAG" }, { value: "文件", label: "文件" }, { value: "维护", label: "维护" }] }, { key: "status", label: "状态", type: "select", required: true, options: [{ value: "等待中", label: "等待中" }, { value: "运行中", label: "运行中" }, { value: "成功", label: "成功" }, { value: "失败", label: "失败" }] }, { key: "creator", label: "创建者", required: true }, { key: "node", label: "处理节点" }],
  createLabel: "添加演示任务", removeLabel: "清理", emptyTitle: "当前没有后台任务", emptyMessage: "任务队列结构已就绪；不会读取或操作真实队列。",
  actions: [
    { label: "重试", when: (item) => item.status === "失败", patch: () => ({ status: "等待中", updatedAt: new Date().toLocaleString("zh-CN") }), danger: false },
    { label: "取消", when: (item) => item.status === "等待中" || item.status === "运行中", patch: () => ({ status: "已取消", updatedAt: new Date().toLocaleString("zh-CN") }), danger: true }
  ],
  build: (values) => ({ taskId: values.taskId, content: values.content, type: values.type, status: values.status, creator: values.creator, node: values.node, createdAt: new Date().toISOString(), updatedAt: new Date().toISOString() }),
  renderDetail: (item) => <section className="admin-detail-timeline"><h3>演示时间线</h3><ol><li><strong>进入队列</strong><span>{item.createdAt || "未记录"}</span></li><li><strong>最近状态：{item.status}</strong><span>{item.updatedAt || "未记录"}</span></li></ol><small>此时间线仅反映当前页面的演示记录，不是服务端任务日志。</small></section>,
  stats: [{ label: "任务", value: (items) => items.length, detail: "演示队列" }, { label: "运行中", value: (items) => items.filter((item) => item.status === "运行中").length, detail: "处理中", tone: "info" }, { label: "失败", value: (items) => items.filter((item) => item.status === "失败").length, detail: "需要关注", tone: "danger" }]
};
export function AdminTasksPage() { return useAdminDataSource().mode === "live" ? <LiveTasksPage /> : <AdminCollectionPage config={config} />; }
