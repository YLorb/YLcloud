import { StatusBadge } from "../../../components/ui/StatusBadge";
import { AdminCollectionPage, type AdminCollectionConfig } from "../components/AdminCollectionPage";
import type { AdminRecord } from "../core/AdminDataSource";

type ShareRow = AdminRecord & { shareId: string; file: string; owner: string; views: number; downloads: number; expiresAt: string; status: string };
const config: AdminCollectionConfig<ShareRow> = {
  resource: "shares", eyebrow: "资源治理", title: "分享", description: "查看公开分享的有效期、访问量与当前状态。",
  searchPlaceholder: "搜索分享 ID、源文件或分享人", searchFields: ["shareId", "file", "owner"],
  filters: [{ key: "status", label: "状态", options: [{ value: "有效", label: "有效" }, { value: "已过期", label: "已过期" }, { value: "已撤销", label: "已撤销" }] }],
  textFilters: [{ key: "file", label: "源文件" }, { key: "owner", label: "分享人" }],
  columns: [{ key: "shareId", label: "Share ID", render: (row) => <code>{row.shareId}</code> }, { key: "file", label: "源文件", render: (row) => <strong>{row.file}</strong> }, { key: "owner", label: "分享人", render: (row) => row.owner }, { key: "views", label: "浏览量", render: (row) => row.views }, { key: "downloads", label: "下载量", render: (row) => row.downloads }, { key: "expiresAt", label: "过期时间", render: (row) => row.expiresAt || "永不过期" }, { key: "status", label: "状态", render: (row) => <StatusBadge tone={row.status === "有效" ? "success" : row.status === "已撤销" ? "danger" : "warning"}>{row.status}</StatusBadge> }],
  fields: [{ key: "shareId", label: "Share ID", required: true }, { key: "file", label: "源文件", required: true }, { key: "owner", label: "分享人", required: true }, { key: "expiresAt", label: "过期时间", type: "date" }, { key: "status", label: "状态", type: "select", required: true, options: [{ value: "有效", label: "有效" }, { value: "已过期", label: "已过期" }, { value: "已撤销", label: "已撤销" }] }],
  createLabel: "新建演示分享", removeLabel: "撤销", removePatch: () => ({ status: "已撤销" }), removeDisabled: (item) => item.status === "已撤销", emptyTitle: "尚无分享演示数据", emptyMessage: "分享结构已就绪；不会创建真实公开链接。",
  build: (values) => ({ shareId: values.shareId, file: values.file, owner: values.owner, views: 0, downloads: 0, expiresAt: values.expiresAt, status: values.status }),
  stats: [{ label: "分享", value: (items) => items.length, detail: "演示记录" }, { label: "有效", value: (items) => items.filter((item) => item.status === "有效").length, detail: "当前可访问", tone: "success" }, { label: "总下载", value: (items) => items.reduce((sum, item) => sum + item.downloads, 0), detail: "演示统计" }]
};
export function SharesPage() { return <AdminCollectionPage config={config} />; }
