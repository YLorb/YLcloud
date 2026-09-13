import { AlertTriangle, Inbox, LoaderCircle, RefreshCw } from "lucide-react";
import type { ReactNode } from "react";
import { Button } from "./Button";

export function LoadingState({ label = "正在加载" }: { label?: string }) {
  return (
    <div className="page-state" aria-busy="true" aria-live="polite">
      <LoaderCircle className="ui-spinner" size={26} aria-hidden="true" />
      <strong>{label}</strong>
      <span>请稍候，数据正在同步。</span>
    </div>
  );
}

export function ErrorState({ title = "加载失败", message, onRetry }: { title?: string; message: string; onRetry?: () => void }) {
  return (
    <div className="page-state page-state--error" role="alert">
      <AlertTriangle size={28} aria-hidden="true" />
      <strong>{title}</strong>
      <span>{message}</span>
      {onRetry && <Button variant="danger" onClick={onRetry}><RefreshCw size={16} />重新尝试</Button>}
    </div>
  );
}

export function EmptyState({ title, message, action }: { title: string; message: string; action?: ReactNode }) {
  return (
    <div className="page-state" role="status">
      <Inbox size={28} aria-hidden="true" />
      <strong>{title}</strong>
      <span>{message}</span>
      {action}
    </div>
  );
}
