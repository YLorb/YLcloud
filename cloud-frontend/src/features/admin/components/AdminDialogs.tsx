import type { FormEvent, ReactNode } from "react";
import { Button } from "../../../components/ui/Button";
import { Dialog } from "../../../components/ui/Dialog";

export function AdminFormDialog({ open, onOpenChange, title, description, submitLabel = "保存到演示数据", pending, onSubmit, children, size = "md" }: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  description: string;
  submitLabel?: string;
  pending?: boolean;
  onSubmit: (event: FormEvent<HTMLFormElement>) => void;
  children: ReactNode;
  size?: "sm" | "md" | "lg";
}) {
  const formId = `admin-demo-form-${title.replace(/\s/g, "-")}`;
  return (
    <Dialog open={open} onOpenChange={onOpenChange} title={title} description={description} size={size} footer={<><Button onClick={() => onOpenChange(false)}>取消</Button><Button type="submit" form={formId} variant="confirm" loading={pending}>{submitLabel}</Button></>}>
      <form id={formId} className="admin-demo-form" onSubmit={onSubmit}>{children}</form>
    </Dialog>
  );
}

export function AdminConfirmDialog({ open, onOpenChange, title, description, confirmLabel, onConfirm, danger = true }: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  description: string;
  confirmLabel: string;
  onConfirm: () => void;
  danger?: boolean;
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange} title={title} description="这是前端演示操作，不会写入服务器。" size="sm" footer={<><Button onClick={() => onOpenChange(false)}>取消</Button><Button variant={danger ? "danger" : "confirm"} onClick={onConfirm}>{confirmLabel}</Button></>}>
      <div className={danger ? "danger-callout" : "info-callout"}>{description}</div>
    </Dialog>
  );
}

export function AdminField({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return <label className="admin-demo-field"><span>{label}</span>{children}{hint && <small>{hint}</small>}</label>;
}

