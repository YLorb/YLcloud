import { StatusBadge } from "../../../components/ui/StatusBadge";
import { AdminCollectionPage, type AdminCollectionConfig } from "../components/AdminCollectionPage";
import type { AdminRecord } from "../core/AdminDataSource";

type NodeRow = AdminRecord & { name: string; address: string; status: string; load: string; storage: string; version: string; updatedAt: string };
const config: AdminCollectionConfig<NodeRow> = {
  resource: "nodes", eyebrow: "运行与调度", title: "节点", description: "查看工作节点的连通状态、负载与容量概况。",
  searchPlaceholder: "搜索节点名称、地址或版本", searchFields: ["name", "address", "version"],
  filters: [{ key: "status", label: "状态", options: [{ value: "在线", label: "在线" }, { value: "离线", label: "离线" }, { value: "维护中", label: "维护中" }] }],
  columns: [
    { key: "name", label: "节点", render: (row) => <><strong>{row.name}</strong><small className="admin-cell-note">{row.address}</small></> },
    { key: "status", label: "状态", render: (row) => <StatusBadge tone={row.status === "在线" ? "success" : row.status === "离线" ? "danger" : "warning"}>{row.status}</StatusBadge> },
    { key: "load", label: "当前负载", render: (row) => row.load },
    { key: "storage", label: "可用存储", render: (row) => row.storage },
    { key: "version", label: "版本", render: (row) => row.version },
    { key: "updatedAt", label: "最后心跳", render: (row) => row.updatedAt || "—" }
  ],
  fields: [{ key: "name", label: "节点名称", required: true }, { key: "address", label: "节点地址", required: true, placeholder: "例如：10.0.0.8" }, { key: "status", label: "状态", type: "select", required: true, options: [{ value: "在线", label: "在线" }, { value: "离线", label: "离线" }, { value: "维护中", label: "维护中" }] }, { key: "load", label: "当前负载", required: true, placeholder: "例如：18%" }, { key: "storage", label: "可用存储", required: true, placeholder: "例如：320 GB" }, { key: "version", label: "版本", required: true, placeholder: "例如：1.0.0" }],
  createLabel: "添加演示节点", removeLabel: "移除", emptyTitle: "尚无节点演示数据", emptyMessage: "节点监控结构已就绪；不会探测真实服务器。",
  actions: [
    { label: "进入维护", when: (item) => item.status !== "维护中", patch: () => ({ status: "维护中" }), danger: true },
    { label: "恢复在线", when: (item) => item.status === "维护中", patch: () => ({ status: "在线" }), danger: false }
  ],
  build: (values) => ({ name: values.name, address: values.address, status: values.status, load: values.load, storage: values.storage, version: values.version, updatedAt: new Date().toLocaleString("zh-CN") }),
  stats: [{ label: "节点", value: (items) => items.length, detail: "演示清单" }, { label: "在线", value: (items) => items.filter((item) => item.status === "在线").length, detail: "可调度", tone: "success" }, { label: "异常", value: (items) => items.filter((item) => item.status === "离线").length, detail: "需要检查", tone: "danger" }, { label: "维护中", value: (items) => items.filter((item) => item.status === "维护中").length, detail: "暂停调度", tone: "warning" }]
};
export function NodesPage() { return <AdminCollectionPage config={config} />; }
