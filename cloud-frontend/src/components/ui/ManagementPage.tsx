import type { ReactNode } from "react";
import { cn } from "../../lib/cn";

export function ManagementPageHeader({ eyebrow, title, description, actions }: {
  eyebrow: string;
  title: string;
  description: string;
  actions?: ReactNode;
}) {
  return <header className="management-page-header">
    <div><span>{eyebrow}</span><h1>{title}</h1><p>{description}</p></div>
    {actions && <div className="management-page-header__actions">{actions}</div>}
  </header>;
}

export function ManagementMetrics({ children }: { children: ReactNode }) {
  return <section className="management-metrics" aria-label="状态概览">{children}</section>;
}

export function ManagementMetric({ icon, label, value, detail, tone = "neutral" }: {
  icon: ReactNode;
  label: string;
  value: ReactNode;
  detail: string;
  tone?: "neutral" | "success" | "warning" | "danger" | "info";
}) {
  return <article className={cn("management-metric", `management-metric--${tone}`)}>
    <span className="management-metric__icon">{icon}</span>
    <div><small>{label}</small><strong>{value}</strong><p>{detail}</p></div>
  </article>;
}

export function ManagementSection({ icon, title, description, actions, danger, children, className }: {
  icon?: ReactNode;
  title: string;
  description?: string;
  actions?: ReactNode;
  danger?: boolean;
  children: ReactNode;
  className?: string;
}) {
  return <section className={cn("management-section", danger && "management-section--danger", className)}>
    <header className="management-section__header">
      {icon && <span className="management-section__icon">{icon}</span>}
      <div><h2>{title}</h2>{description && <p>{description}</p>}</div>
      {actions && <div className="management-section__actions">{actions}</div>}
    </header>
    <div className="management-section__body">{children}</div>
  </section>;
}

export function ManagementFacts({ items }: { items: Array<{ label: string; value: ReactNode }> }) {
  return <dl className="management-facts">{items.map((item) => <div key={item.label}><dt>{item.label}</dt><dd>{item.value}</dd></div>)}</dl>;
}
