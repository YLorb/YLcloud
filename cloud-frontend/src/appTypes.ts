import type { ReactNode } from "react";

export type AuthMode = "login" | "sign";
export type MainView = "files" | "spaces" | "knowledge" | "settings" | "chat" | "async";
export type Category = "all" | "images" | "documents" | "videos" | "recycle";
export type Notice = { type: "success" | "error" | "info"; text: string } | null;
export type Crumb = { id: number; name: string };
export type ChatMessage = { id: string; role: "user" | "assistant"; content: string };
export type CategoryMeta = Array<{ key: Category; label: string; icon: ReactNode }>;
