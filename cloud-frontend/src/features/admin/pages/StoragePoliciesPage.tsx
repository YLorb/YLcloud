import { Database, Plus } from "lucide-react";
import { Button } from "../../../components/ui/Button";

const policies = [
  { id: 1, name: "LocalStorage", description: "默认本地存储", type: "local", enabled: true }
];

export function StoragePoliciesPage() {
  return (
    <div className="admin-storage-page">
      <header className="panel-header">
        <div>
          <span className="section-eyebrow">存储管理</span>
          <h2>存储策略</h2>
          <p>管理系统文件存储策略</p>
        </div>
        <Button variant="primary"><Plus size={16} />添加存储策略</Button>
      </header>
      <div className="admin-storage-grid">
        {policies.map((policy) => (
          <div key={policy.id} className="admin-storage-card">
            <div className="admin-storage-card__header">
              <span className="admin-storage-card__icon"><Database size={22} /></span>
              <div>
                <strong>{policy.name}</strong>
                <small>{policy.description}</small>
              </div>
            </div>
            <div className="admin-storage-card__meta">
              <span>类型: {policy.type}</span>
              <span className={policy.enabled ? "success-text" : "muted-text"}>
                {policy.enabled ? "已启用" : "已禁用"}
              </span>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
