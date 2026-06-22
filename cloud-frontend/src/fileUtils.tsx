import { File, FileArchive, FileAudio, FileImage, FileText, FileVideo, Folder, HardDrive, Image, Trash2 } from "lucide-react";
import type { ReactNode } from "react";
import type { Category } from "./appTypes";
import type { FileItem } from "./types";

export const categoryMeta: Array<{ key: Category; label: string; icon: ReactNode }> = [
  { key: "all", label: "全部文件", icon: <HardDrive size={18} /> },
  { key: "images", label: "图片", icon: <Image size={18} /> },
  { key: "documents", label: "文档", icon: <FileText size={18} /> },
  { key: "videos", label: "视频", icon: <FileVideo size={18} /> },
  { key: "recycle", label: "回收站", icon: <Trash2 size={18} /> }
];

export const imageTypes = new Set(["jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic"]);
export const documentTypes = new Set(["doc", "docx", "xls", "xlsx", "ppt", "pptx", "pdf", "txt", "md", "csv", "json"]);
export const videoTypes = new Set(["mp4", "mov", "mkv", "webm", "avi", "flv", "m4v"]);

export function extOf(item: FileItem) {
  if (item.isDir) return "folder";
  return (item.type || item.name.split(".").pop() || "file").toLowerCase();
}

export function matchesCategory(item: FileItem, category: Category) {
  if (category === "all" || category === "recycle") return true;
  if (item.isDir) return false;
  const ext = extOf(item);
  if (category === "images") return imageTypes.has(ext);
  if (category === "documents") return documentTypes.has(ext);
  if (category === "videos") return videoTypes.has(ext);
  return true;
}

export function formatSize(size?: number) {
  if (!size) return "-";
  const units = ["B", "KB", "MB", "GB", "TB"];
  let value = size;
  let index = 0;
  while (value >= 1024 && index < units.length - 1) {
    value /= 1024;
    index += 1;
  }
  return `${value.toFixed(index === 0 || value >= 10 ? 0 : 1)} ${units[index]}`;
}

export function formatTime(value?: string) {
  if (!value) return "-";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString("zh-CN", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit"
  });
}

export function fileTypeLabel(item: FileItem) {
  if (item.isDir) return "文件夹";
  const ext = extOf(item);
  if (imageTypes.has(ext)) return "图片";
  if (documentTypes.has(ext)) return "文档";
  if (videoTypes.has(ext)) return "视频";
  return ext.toUpperCase();
}

export function fileIcon(item: FileItem, size = 20) {
  if (item.isDir) return <Folder size={size} />;
  const ext = extOf(item);
  if (imageTypes.has(ext)) return <FileImage size={size} />;
  if (videoTypes.has(ext)) return <FileVideo size={size} />;
  if (documentTypes.has(ext)) return <FileText size={size} />;
  if (["zip", "rar", "7z", "tar", "gz"].includes(ext)) return <FileArchive size={size} />;
  if (["mp3", "wav", "flac", "aac"].includes(ext)) return <FileAudio size={size} />;
  return <File size={size} />;
}
