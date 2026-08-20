import { Filter, Plus, RefreshCw, Search } from "lucide-react";
import { useState } from "react";
import { Button } from "../../../components/ui/Button";

export function UsersPage() {
  const [filterOpen, setFilterOpen] = useState(false);
  return (
    <div className="admin-list-page">
      <header className="panel-header">
        <div>
          <span className="section-eyebrow">用户管理</span>
          <h2>用户</h2>
          <p>管理系统用户</p>
        </div>
        <div className="button-row">
          <Button variant="ghost" onClick={() => setFilterOpen((v) => !v)}><Filter size={16} />过滤</Button>
          <Button variant="ghost"><RefreshCw size={16} />刷新</Button>
          <Button variant="primary"><Plus size={16} />新建</Button>
        </div>
      </header>
      {filterOpen && <div className="admin-filter-bar"><div className="search-box"><Search size={17} /><input placeholder="搜索用户昵称或邮箱" /></div></div>}
      <div className="data-table-wrap">
        <table className="data-table">
          <thead>
            <tr>
              <th><input type="checkbox" /></th>
              <th>用户 ID</th>
              <th>昵称</th>
              <th>Email</th>
              <th>用户组</th>
              <th>已用空间</th>
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
