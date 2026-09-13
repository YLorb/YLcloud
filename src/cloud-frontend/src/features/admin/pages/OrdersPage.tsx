import { StatusBadge } from "../../../components/ui/StatusBadge";
import { AdminCollectionPage, type AdminCollectionConfig } from "../components/AdminCollectionPage";
import type { AdminRecord } from "../core/AdminDataSource";
import { formatMoney } from "../core/adminFormat";

type OrderRow = AdminRecord & { orderNo: string; user: string; orderType: string; product: string; amountCents: number; status: string; createdAt: string };
const config: AdminCollectionConfig<OrderRow> = {
  resource: "orders", eyebrow: "商业与计费", title: "订单", description: "审阅订单状态、金额和关联用户；字段仅用于前端原型。",
  searchPlaceholder: "搜索订单号、用户或产品", searchFields: ["orderNo", "user", "product"],
  filters: [{ key: "orderType", label: "类型", options: [{ value: "存储套餐", label: "存储套餐" }, { value: "增值服务", label: "增值服务" }] }, { key: "status", label: "状态", options: [{ value: "待支付", label: "待支付" }, { value: "已支付", label: "已支付" }, { value: "已关闭", label: "已关闭" }, { value: "已退款", label: "已退款" }] }],
  textFilters: [{ key: "user", label: "用户" }], dateField: "createdAt",
  columns: [{ key: "orderNo", label: "订单号", render: (row) => <code>{row.orderNo}</code> }, { key: "user", label: "用户", render: (row) => row.user }, { key: "orderType", label: "类型", render: (row) => row.orderType || "未分类" }, { key: "product", label: "产品", render: (row) => <strong>{row.product}</strong> }, { key: "amount", label: "金额", render: (row) => formatMoney(row.amountCents) }, { key: "status", label: "状态", render: (row) => <StatusBadge tone={row.status === "已支付" ? "success" : row.status === "待支付" ? "warning" : row.status === "已退款" ? "info" : "neutral"}>{row.status}</StatusBadge> }, { key: "createdAt", label: "创建时间", render: (row) => row.createdAt ? new Date(row.createdAt).toLocaleString("zh-CN") : "—" }],
  fields: [{ key: "orderNo", label: "订单号", required: true }, { key: "user", label: "用户", required: true }, { key: "orderType", label: "类型", type: "select", required: true, options: [{ value: "存储套餐", label: "存储套餐" }, { value: "增值服务", label: "增值服务" }] }, { key: "product", label: "产品", required: true }, { key: "amountCents", label: "金额（分）", type: "number", required: true, min: 0, hint: "使用非负整数最小货币单位，避免浮点误差。" }, { key: "status", label: "状态", type: "select", required: true, options: [{ value: "待支付", label: "待支付" }, { value: "已支付", label: "已支付" }, { value: "已关闭", label: "已关闭" }, { value: "已退款", label: "已退款" }] }],
  createLabel: "添加演示订单", removeLabel: "关闭", removePatch: () => ({ status: "已关闭" }), removeDisabled: (item) => item.status === "已关闭", emptyTitle: "尚无订单演示数据", emptyMessage: "订单字段已就绪；本页面不连接支付系统。",
  actions: [{ label: "退款", when: (item) => item.status === "已支付", patch: () => ({ status: "已退款" }), danger: true }],
  build: (values) => ({ orderNo: values.orderNo, user: values.user, orderType: values.orderType, product: values.product, amountCents: Number(values.amountCents || 0), status: values.status, createdAt: new Date().toISOString() }),
  stats: [{ label: "订单", value: (items) => items.length, detail: "演示记录" }, { label: "已支付", value: (items) => items.filter((item) => item.status === "已支付").length, detail: "非真实交易", tone: "success" }, { label: "演示金额", value: (items) => formatMoney(items.reduce((sum, item) => sum + item.amountCents, 0)), detail: "不代表收入", tone: "info" }]
};
export function OrdersPage() { return <AdminCollectionPage config={config} />; }
