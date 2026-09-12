import { Filter, RefreshCw, Search } from "lucide-react";
import { useState } from "react";
import { Button } from "../../../components/ui/Button";

export function SharesPage() {
  const [filterOpen, setFilterOpen] = useState(false);
  return (
    <div className="admin-list-page">
      <header className="panel-header">
        <div>
          <span className="section-eyebrow">分享管理</span>
          <h2>分享</h2>
          <p>管理系统分享记录</p>
        </div>
        <div className="button-row">
          <Button variant="ghost" onClick={() => setFilterOpen((v) => !v)}><Filter size={16} />过滤</Button>
          <Button variant="ghost"><RefreshCw size={16} />刷新</Button>
        </div>
      </header>
      {filterOpen && <div className="admin-filter-bar"><div className="search-box"><Search size={17} /><input placeholder="搜索分享 ID 或源文件" /></div></div>}
      <div className="data-table-wrap">
        <table className="data-table">
          <thead>
            <tr>
              <th><input type="checkbox" /></th>
              <th>Share ID</th>
              <th>源文件</th>
              <th>浏览量</th>
              <th>下载量</th>
              <th>过期时间</th>
              <th>分享人 &amp; 分享时间</th>
            </tr>
          </thead>
          <tbody>
            <tr><td colSpan={7} className="admin-table-empty">暂无数据</td></tr>
          </tbody>
        </table>
      </div>
    </div>
  );
}
