import { StatusBadge } from "../../../components/ui/StatusBadge";
import { useState } from "react";
import { Dialog } from "../../../components/ui/Dialog";
import { AdminCollectionPage, type AdminCollectionConfig } from "../components/AdminCollectionPage";
import type { AdminRecord } from "../core/AdminDataSource";

type OAuthRow = AdminRecord & { name: string; clientId: string; owner: string; scopes: string; redirectUri: string; status: string };
const config: AdminCollectionConfig<OAuthRow> = {
  resource: "oauth", eyebrow: "开放与集成", title: "OAuth 应用", description: "设计客户端、Scope 与回调地址的管理体验，不签发真实凭据。",
  searchPlaceholder: "搜索应用、Client ID 或所有者", searchFields: ["name", "clientId", "owner", "scopes"],
  filters: [{ key: "status", label: "状态", options: [{ value: "启用", label: "启用" }, { value: "停用", label: "停用" }] }],
  textFilters: [{ key: "owner", label: "所有者" }],
  columns: [{ key: "name", label: "应用", render: (row) => <strong>{row.name}</strong> }, { key: "clientId", label: "Client ID", render: (row) => <code>{row.clientId}</code> }, { key: "owner", label: "所有者", render: (row) => row.owner }, { key: "scopes", label: "Scopes", render: (row) => row.scopes }, { key: "redirectUri", label: "回调地址", render: (row) => <span className="admin-break-value">{row.redirectUri}</span> }, { key: "status", label: "状态", render: (row) => <StatusBadge tone={row.status === "启用" ? "success" : "neutral"}>{row.status}</StatusBadge> }],
  fields: [{ key: "name", label: "应用名称", required: true }, { key: "clientId", label: "Client ID", required: true, hint: "仅使用演示标识，不填入真实客户端凭据。" }, { key: "owner", label: "所有者", required: true }, { key: "scopes", label: "Scopes", required: true, placeholder: "例如：files.read profile" }, { key: "redirectUri", label: "回调地址", type: "url", required: true, placeholder: "https://example.invalid/callback" }, { key: "status", label: "状态", type: "select", required: true, options: [{ value: "启用", label: "启用" }, { value: "停用", label: "停用" }] }],
  createLabel: "创建演示应用", removeLabel: "删除", emptyTitle: "尚无 OAuth 应用", emptyMessage: "应用管理结构已就绪；不会生成真实 Client Secret。",
  actions: [
    { label: "停用", when: (item) => item.status === "启用", patch: () => ({ status: "停用" }), danger: true },
    { label: "启用", when: (item) => item.status === "停用", patch: () => ({ status: "启用" }), danger: false }
  ],
  build: (values) => ({ name: values.name, clientId: values.clientId, owner: values.owner, scopes: values.scopes, redirectUri: values.redirectUri, status: values.status }),
  stats: [{ label: "应用", value: (items) => items.length, detail: "演示客户端" }, { label: "已启用", value: (items) => items.filter((item) => item.status === "启用").length, detail: "非真实授权", tone: "success" }, { label: "Scope 数", value: (items) => items.reduce((sum, item) => sum + item.scopes.split(/\s+/).filter(Boolean).length, 0), detail: "演示配置", tone: "info" }]
};
export function OAuthAppsPage() {
  const [demoSecret, setDemoSecret] = useState("");
  return <><AdminCollectionPage config={{ ...config, onCreated: () => setDemoSecret("demo-only-not-a-real-secret") }} />
    <Dialog open={Boolean(demoSecret)} onOpenChange={(open) => { if (!open) setDemoSecret(""); }} title="一次性演示 Secret" description="这只是前端展示流程示意，不是真实 OAuth 凭据。关闭后不会再显示。" size="sm">
      <div className="admin-info-panel"><code>{demoSecret}</code></div>
    </Dialog>
  </>;
}
