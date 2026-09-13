import { AlertTriangle, Beaker, Search, X } from "lucide-react";
import type { ReactNode } from "react";
import { Button } from "../../../components/ui/Button";
import { cn } from "../../../lib/cn";
import { useAdminDataSource } from "../core/AdminDataSource";

export function AdminPage({ eyebrow, title, description, actions, children }: {
  eyebrow: string;
  title: string;
  description: string;
  actions?: ReactNode;
  children: ReactNode;
}) {
  return (
    <div className="admin-page">
      <header className="admin-page__header">
        <div><span>{eyebrow}</span><h2>{title}</h2><p>{description}</p></div>
        {actions && <div className="admin-page__actions">{actions}</div>}
      </header>
      <AdminPrototypeNotice />
      {children}
    </div>
  );
}

export function AdminPrototypeNotice() {
  const { mode } = useAdminDataSource();
  if (mode === "live") return <div className="admin-prototype-notice" role="status"><div><strong>真实 API</strong><span>数据来自服务器，确认后的操作会持久保存；未接入能力不提供模拟成功。</span></div></div>;
  return (
    <div className="admin-prototype-notice" role="status">
      <Beaker size={17} aria-hidden="true" />
      <div><strong>前端演示模式</strong><span>当前页面使用临时数据，操作不会保存到服务器；重新加载页面后会重置。</span></div>
    </div>
  );
}

export function AdminStats({ children }: { children: ReactNode }) {
  return <section className="admin-stats" aria-label="状态概览">{children}</section>;
}

export function AdminStat({ label, value, detail, tone = "neutral", icon }: {
  label: string;
  value: ReactNode;
  detail: string;
  tone?: "neutral" | "info" | "success" | "warning" | "danger";
  icon?: ReactNode;
}) {
  return (
    <article className={cn("admin-stat", `admin-stat--${tone}`)}>
      {icon && <span className="admin-stat__icon">{icon}</span>}
      <div><small>{label}</small><strong>{value}</strong><p>{detail}</p></div>
    </article>
  );
}

export function AdminSection({ title, description, actions, children, className }: {
  title: string;
  description?: string;
  actions?: ReactNode;
  children: ReactNode;
  className?: string;
}) {
  return (
    <section className={cn("admin-section", className)}>
      <header className="admin-section__header">
        <div><h3>{title}</h3>{description && <p>{description}</p>}</div>
        {actions && <div className="admin-section__actions">{actions}</div>}
      </header>
      <div className="admin-section__body">{children}</div>
    </section>
  );
}

export function AdminFilterBar({ children }: { children: ReactNode }) {
  return <div className="admin-filter-toolbar" role="search">{children}</div>;
}

export function AdminSearch({ value, onChange, label = "搜索", placeholder = "搜索..." }: {
  value: string;
  onChange: (value: string) => void;
  label?: string;
  placeholder?: string;
}) {
  return (
    <label className="admin-search-field">
      <span className="sr-only">{label}</span><Search size={16} aria-hidden="true" />
      <input type="search" value={value} onChange={(event) => onChange(event.target.value)} placeholder={placeholder} />
      {value && <button type="button" aria-label={`清除${label}`} onClick={() => onChange("")}><X size={14} /></button>}
    </label>
  );
}

export function AdminSelect({ label, value, onChange, options }: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  options: Array<{ value: string; label: string }>;
}) {
  return (
    <label className="admin-select-field"><span>{label}</span><select value={value} onChange={(event) => onChange(event.target.value)}>
      {options.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
    </select></label>
  );
}

export function AdminUnavailableHint({ children }: { children: ReactNode }) {
  return <div className="admin-inline-warning"><AlertTriangle size={16} aria-hidden="true" /><span>{children}</span></div>;
}

export function AdminClearFilters({ onClick }: { onClick: () => void }) {
  return <Button size="sm" variant="ghost" onClick={onClick}>清除筛选</Button>;
}
