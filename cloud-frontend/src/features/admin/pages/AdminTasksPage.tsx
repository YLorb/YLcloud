import { Filter, RefreshCw, Search, Trash2 } from "lucide-react";
import { useState } from "react";
import { Button } from "../../../components/ui/Button";

export function AdminTasksPage() {
  const [filterOpen, setFilterOpen] = useState(false);
  return (
    <div className="admin-list-page">
      <header className="panel-header">
        <div>
          <span className="section-eyebrow">任务管理</span>
          <h2>后台任务</h2>
          <p>管理系统后台任务队列</p>
        </div>
        <div className="button-row">
          <Button variant="ghost" onClick={() => setFilterOpen((v) => !v)}><Filter size={16} />过滤</Button>
          <Button variant="ghost"><RefreshCw size={16} />刷新</Button>
          <Button variant="danger"><Trash2 size={16} />清理</Button>
        </div>
      </header>
      {filterOpen && <div className="admin-filter-bar"><div className="search-box"><Search size={17} /><input placeholder="搜索任务 ID 或内容" /></div></div>}
      <div className="data-table-wrap">
        <table className="data-table">
          <thead>
            <tr>
              <th><input type="checkbox" /></th>
              <th>任务 ID</th>
              <th>任务内容</th>
              <th>任务状态</th>
              <th>创建者</th>
              <th>处理节点</th>
              <th>创建时间</th>
              <th>最新动作时间</th>
            </tr>
          </thead>
          <tbody>
            <tr><td colSpan={8} className="admin-table-empty">暂无数据</td></tr>
          </tbody>
        </table>
      </div>
    </div>
  );
}
