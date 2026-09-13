import { Lock } from "lucide-react";

export function PlaceholderPage({ title, description }: { title: string; description?: string }) {
  return (
    <div className="admin-page-placeholder">
      <Lock size={36} />
      <strong>{title}</strong>
      <p>{description || "功能尚未开放，敬请期待"}</p>
    </div>
  );
}
