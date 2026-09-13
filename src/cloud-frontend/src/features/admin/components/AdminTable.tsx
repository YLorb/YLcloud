import { Inbox, RefreshCw } from "lucide-react";
import type { ReactNode } from "react";
import { Button } from "../../../components/ui/Button";

export type AdminColumn<T> = {
  key: string;
  label: string;
  render: (item: T) => ReactNode;
  className?: string;
};

export function AdminTable<T>({ columns, items, getKey, loading, error, emptyTitle, emptyMessage, onRetry }: {
  columns: AdminColumn<T>[];
  items: T[];
  getKey: (item: T) => string | number;
  loading?: boolean;
  error?: string | null;
  emptyTitle: string;
  emptyMessage: string;
  onRetry?: () => void;
}) {
  return (
    <div className="data-table-wrap admin-data-table-wrap">
      <table className="data-table">
        <thead><tr>{columns.map((column) => <th key={column.key} className={column.className}>{column.label}</th>)}</tr></thead>
        <tbody>
          {loading && <tr><td colSpan={columns.length}><div className="admin-table-state" aria-busy="true">正在加载数据…</div></td></tr>}
          {!loading && error && <tr><td colSpan={columns.length}><div className="admin-table-state admin-table-state--error" role="alert"><strong>无法加载数据</strong><span>{error}</span>{onRetry && <Button size="sm" variant="danger" onClick={onRetry}><RefreshCw size={14} />重试</Button>}</div></td></tr>}
          {!loading && !error && items.length === 0 && <tr><td colSpan={columns.length}><div className="admin-table-state" role="status"><Inbox size={24} aria-hidden="true" /><strong>{emptyTitle}</strong><span>{emptyMessage}</span></div></td></tr>}
          {!loading && !error && items.map((item) => <tr key={getKey(item)}>{columns.map((column) => <td key={column.key} className={column.className}>{column.render(item)}</td>)}</tr>)}
        </tbody>
      </table>
    </div>
  );
}

export function AdminPagination({ page = 1, pageSize = 20, total = 0, onPageChange }: {
  page?: number;
  pageSize?: number;
  total?: number;
  onPageChange?: (page: number) => void;
}) {
  const pages = Math.max(1, Math.ceil(total / pageSize));
  return (
    <nav className="admin-pagination" aria-label="分页">
      <span>共 {total} 条 · 第 {Math.min(page, pages)} / {pages} 页</span>
      <div><Button size="sm" variant="ghost" disabled={page <= 1} onClick={() => onPageChange?.(page - 1)}>上一页</Button><Button size="sm" variant="ghost" disabled={page >= pages} onClick={() => onPageChange?.(page + 1)}>下一页</Button></div>
    </nav>
  );
}
