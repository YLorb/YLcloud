import { X } from "lucide-react";
import type { Notice } from "../appTypes";

export function NoticeBar({ notice, onClose }: { notice: Notice; onClose: () => void }) {
  if (!notice) return null;
  const isError = notice.type === "error";
  return (
    <div
      className={`notice ${notice.type}`}
      role={isError ? "alert" : "status"}
      aria-live={isError ? "assertive" : "polite"}
    >
      <span>{notice.text}</span>
      <button type="button" aria-label="关闭提示" onClick={onClose}>
        <X size={14} />
      </button>
    </div>
  );
}
