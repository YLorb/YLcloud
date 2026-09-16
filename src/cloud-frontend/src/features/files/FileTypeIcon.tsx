import { Folder } from "lucide-react";
import { extOf } from "../../fileUtils";
import type { FileItem } from "../../types";

const labels: Record<string, { mark: string; tone: string }> = {
  doc: { mark: "W", tone: "word" }, docx: { mark: "W", tone: "word" },
  pdf: { mark: "PDF", tone: "pdf" }, xls: { mark: "X", tone: "sheet" },
  xlsx: { mark: "X", tone: "sheet" }, csv: { mark: "X", tone: "sheet" },
  png: { mark: "◩", tone: "image" }, jpg: { mark: "◩", tone: "image" },
  jpeg: { mark: "◩", tone: "image" }, webp: { mark: "◩", tone: "image" },
  mp4: { mark: "▶", tone: "video" }, mov: { mark: "▶", tone: "video" },
  md: { mark: "M", tone: "markdown" }, txt: { mark: "≡", tone: "text" },
  zip: { mark: "▣", tone: "archive" }, rar: { mark: "▣", tone: "archive" },
  ppt: { mark: "P", tone: "slides" }, pptx: { mark: "P", tone: "slides" }
};

export function FileTypeIcon({ item, large = false }: { item: FileItem; large?: boolean }) {
  if (item.isDir) return <Folder className="file-type-icon file-type-icon--folder" size={large ? 38 : 22} fill="currentColor" strokeWidth={1.3} aria-hidden="true" />;
  const { mark, tone } = labels[extOf(item)] || { mark: "•", tone: "text" };
  return <span className={`file-type-icon file-type-icon--${tone}${large ? " file-type-icon--large" : ""}`} aria-hidden="true">{mark}</span>;
}
