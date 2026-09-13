import { StatusBadge } from "../../../components/ui/StatusBadge";
import { AdminCollectionPage, type AdminCollectionConfig } from "../components/AdminCollectionPage";
import type { AdminRecord } from "../core/AdminDataSource";

type ReportRow = AdminRecord & { reportNo: string; target: string; category: string; priority: string; status: string; assignee: string; summary: string };
const config: AdminCollectionConfig<ReportRow> = {
  resource: "reports", eyebrow: "内容治理", title: "滥用举报", description: "分流举报、查看证据摘要并演示处置流程。",
  searchPlaceholder: "搜索举报编号、对象或摘要", searchFields: ["reportNo", "target", "summary", "assignee"],
  filters: [{ key: "priority", label: "优先级", options: [{ value: "高", label: "高" }, { value: "中", label: "中" }, { value: "低", label: "低" }] }, { key: "status", label: "状态", options: [{ value: "待处理", label: "待处理" }, { value: "调查中", label: "调查中" }, { value: "已处置", label: "已处置" }, { value: "已驳回", label: "已驳回" }] }],
  textFilters: [{ key: "category", label: "举报类型" }],
  columns: [{ key: "reportNo", label: "举报编号", render: (row) => <code>{row.reportNo}</code> }, { key: "target", label: "举报对象", render: (row) => <strong>{row.target}</strong> }, { key: "category", label: "类型", render: (row) => row.category }, { key: "priority", label: "优先级", render: (row) => <StatusBadge tone={row.priority === "高" ? "danger" : row.priority === "中" ? "warning" : "neutral"}>{row.priority}</StatusBadge> }, { key: "status", label: "状态", render: (row) => <StatusBadge tone={row.status === "已处置" ? "success" : row.status === "调查中" ? "info" : row.status === "待处理" ? "warning" : "neutral"}>{row.status}</StatusBadge> }, { key: "assignee", label: "责任人", render: (row) => row.assignee || "未分派" }, { key: "summary", label: "证据摘要", render: (row) => row.summary || "—" }],
  fields: [{ key: "reportNo", label: "举报编号", required: true }, { key: "target", label: "举报对象", required: true }, { key: "category", label: "类型", required: true }, { key: "priority", label: "优先级", type: "select", required: true, options: [{ value: "高", label: "高" }, { value: "中", label: "中" }, { value: "低", label: "低" }] }, { key: "status", label: "状态", type: "select", required: true, options: [{ value: "待处理", label: "待处理" }, { value: "调查中", label: "调查中" }, { value: "已处置", label: "已处置" }, { value: "已驳回", label: "已驳回" }] }, { key: "assignee", label: "责任人" }, { key: "summary", label: "证据摘要", type: "textarea" }],
  createLabel: "添加演示举报", removeLabel: "归档", emptyTitle: "当前没有滥用举报", emptyMessage: "举报工作台结构已就绪；不会访问外部证据。",
  actions: [
    { label: "处置", when: (item) => item.status !== "已处置" && item.status !== "已驳回", patch: () => ({ status: "已处置" }), danger: true },
    { label: "驳回", when: (item) => item.status !== "已处置" && item.status !== "已驳回", patch: () => ({ status: "已驳回" }), danger: true }
  ],
  build: (values) => ({ reportNo: values.reportNo, target: values.target, category: values.category, priority: values.priority, status: values.status, assignee: values.assignee, summary: values.summary }),
  stats: [{ label: "举报", value: (items) => items.length, detail: "演示队列" }, { label: "待处理", value: (items) => items.filter((item) => item.status === "待处理").length, detail: "尚未分流", tone: "warning" }, { label: "高优先级", value: (items) => items.filter((item) => item.priority === "高").length, detail: "需要关注", tone: "danger" }]
};
export function ReportsPage() { return <AdminCollectionPage config={config} />; }
