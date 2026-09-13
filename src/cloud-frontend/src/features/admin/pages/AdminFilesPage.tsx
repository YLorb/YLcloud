import { StatusBadge } from "../../../components/ui/StatusBadge";
import { AdminCollectionPage, type AdminCollectionConfig } from "../components/AdminCollectionPage";
import type { AdminRecord } from "../core/AdminDataSource";

type FileRow = AdminRecord & { uuid: string; name: string; type: string; size: string; owner: string; status: string; createdAt: string };
const config: AdminCollectionConfig<FileRow> = {
  resource: "files", eyebrow: "资源治理", title: "文件", description: "按资源标识、所有者与状态查看系统文件。",
  searchPlaceholder: "搜索文件名、UUID 或所有者", searchFields: ["uuid", "name", "owner", "type"],
  filters: [{ key: "type", label: "类型", options: [{ value: "文档", label: "文档" }, { value: "图片", label: "图片" }, { value: "归档", label: "归档" }] }, { key: "status", label: "状态", options: [{ value: "可用", label: "可用" }, { value: "隔离", label: "隔离" }] }],
  textFilters: [{ key: "owner", label: "所有人" }, { key: "uuid", label: "文件 UUID" }],
  dateField: "createdAt",
  columns: [
    { key: "uuid", label: "文件 UUID", render: (row) => <code>{row.uuid}</code> },
    { key: "name", label: "文件名", render: (row) => <strong>{row.name}</strong> },
    { key: "type", label: "类型", render: (row) => row.type },
    { key: "size", label: "大小", render: (row) => row.size },
    { key: "owner", label: "所有人", render: (row) => row.owner },
    { key: "status", label: "状态", render: (row) => <StatusBadge tone={row.status === "可用" ? "success" : "warning"}>{row.status}</StatusBadge> },
    { key: "createdAt", label: "创建时间", render: (row) => row.createdAt ? new Date(row.createdAt).toLocaleString("zh-CN") : "—" }
  ],
  fields: [{ key: "uuid", label: "文件 UUID", required: true, placeholder: "demo-file-uuid" }, { key: "name", label: "文件名", required: true }, { key: "type", label: "类型", type: "select", required: true, options: [{ value: "文档", label: "文档" }, { value: "图片", label: "图片" }, { value: "归档", label: "归档" }] }, { key: "size", label: "大小", required: true, placeholder: "例如：2.4 MB" }, { key: "owner", label: "所有人", required: true }, { key: "status", label: "状态", type: "select", required: true, options: [{ value: "可用", label: "可用" }, { value: "隔离", label: "隔离" }] }],
  createLabel: "导入演示记录", removeLabel: "删除", emptyTitle: "尚无文件演示数据", emptyMessage: "文件列和操作结构已就绪；此处不会读取对象存储。",
  build: (values) => ({ uuid: values.uuid, name: values.name, type: values.type, size: values.size, owner: values.owner, status: values.status, createdAt: new Date().toISOString() }),
  stats: [{ label: "文件", value: (items) => items.length, detail: "演示记录" }, { label: "可用", value: (items) => items.filter((item) => item.status === "可用").length, detail: "未隔离", tone: "success" }, { label: "隔离", value: (items) => items.filter((item) => item.status === "隔离").length, detail: "需要复核", tone: "warning" }]
};
export function AdminFilesPage() { return <AdminCollectionPage config={config} />; }
