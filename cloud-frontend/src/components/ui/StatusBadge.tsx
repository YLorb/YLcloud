import { AlertCircle, CheckCircle2, Clock3, LoaderCircle, MinusCircle, TriangleAlert } from "lucide-react";
import type { ReactNode } from "react";
import { cn } from "../../lib/cn";

export type StatusTone = "success" | "danger" | "warning" | "info" | "neutral" | "running";

const icons = {
  success: CheckCircle2,
  danger: AlertCircle,
  warning: TriangleAlert,
  info: Clock3,
  neutral: MinusCircle,
  running: LoaderCircle
};

export function StatusBadge({ tone = "neutral", children, className }: { tone?: StatusTone; children: ReactNode; className?: string }) {
  const Icon = icons[tone];
  return (
    <span className={cn("status-badge", `status-badge--${tone}`, className)}>
      <Icon className={tone === "running" ? "ui-spinner" : undefined} size={14} aria-hidden="true" />
      {children}
    </span>
  );
}
