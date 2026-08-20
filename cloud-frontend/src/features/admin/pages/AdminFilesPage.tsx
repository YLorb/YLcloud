import { Filter, RefreshCw, Search, Upload } from "lucide-react";
import { useState } from "react";
import { Button } from "../../../components/ui/Button";

export function AdminFilesPage() {
  const [filterOpen, setFilterOpen] = useState(false);
  return (
    <div className="admin-list-page">
      <header className="panel-header">
        <div>
          <span className="section-eyebrow">文件管理</span>
          <h2>文件</h2>
          <p>管理系统文件</p>
        </div>
        <div className="button-row">
          <Button variant="ghost" onClick={() => setFilterOpen((v) => !v)}><Filter size={16} />过滤</Button>
          <Button variant="ghost"><RefreshCw size={16} />刷新</Button>
          <Button variant="primary"><Upload size={16} />导入</Button>
        </div>
      </header>
      {filterOpen && <div className="admin-filter-bar"><div className="search-box"><Search size={17} /><input placeholder="搜索文件名或 UUID" /></div></div>}
      <div className="data-table-wrap">
        <table className="data-table">
          <thead>
            <tr>
              <th><input type="checkbox" /></th>
              <th>文件 UUID</th>
              <th>文件名</th>
              <th>大小</th>
              <th>占用空间</th>
              <th>所有人</th>
              <th>创建人 &amp; 创建时间</th>
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
