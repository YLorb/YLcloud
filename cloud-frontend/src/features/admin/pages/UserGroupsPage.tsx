import { Plus, RefreshCw } from "lucide-react";
import { Button } from "../../../components/ui/Button";

export function UserGroupsPage() {
  return (
    <div className="admin-list-page">
      <header className="panel-header">
        <div>
          <span className="section-eyebrow">用户管理</span>
          <h2>用户组</h2>
          <p>管理系统用户组</p>
        </div>
        <div className="button-row">
          <Button variant="ghost"><RefreshCw size={16} />刷新</Button>
          <Button variant="primary"><Plus size={16} />新建</Button>
        </div>
      </header>
      <div className="data-table-wrap">
        <table className="data-table">
          <thead>
            <tr>
              <th><input type="checkbox" /></th>
              <th>用户组 ID</th>
              <th>名称</th>
              <th>存储策略</th>
              <th>容量</th>
              <th>用户数</th>
            </tr>
          </thead>
          <tbody>
            <tr><td colSpan={6} className="admin-table-empty">暂无数据</td></tr>
          </tbody>
        </table>
      </div>
    </div>
  );
}
