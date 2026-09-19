import { request } from "../../api";

export type ShareTarget = { sourceType: "PERSONAL" | "SPACE"; sourceId: number; spaceId?: number; name: string };
export type ShareLink = ShareTarget & {
  id: number; shortCode: string; creatorId: number; directory: boolean; expiresAt: string | null;
  maxDownloads: number | null; downloadCount: number; passwordEnabled: boolean; forceDownload: boolean;
  state: "ACTIVE" | "EXPIRED"; version: number;
};
export type ShareConditions = {
  expiryMode: string; days?: number; expiresAt?: string; maxDownloads: number | null;
  passwordEnabled: boolean; password?: string; forceDownload: boolean; version?: number;
};
export type ShareOpen = {
  state: "READY" | "EXPIRED" | "PASSWORD_REQUIRED"; message?: string; name?: string; directory: boolean;
  size?: number; forceDownload: boolean; visitToken?: string; downloadToken?: string;
};
export type Visit = { id: number; shortCode: string; username?: string; userId?: number; ip?: string; city: string; visitedAt: string; outcome: string };
type Page<T> = { items: T[]; total: number; page: number; size: number };
const json = (body: unknown, method = "POST") => ({ method, body: JSON.stringify(body) });
export const shareUrl = (code: string) => `${window.location.origin}/s/${code}`;
export const shareApi = {
  create: (target: ShareTarget, conditions: ShareConditions) =>
    request<ShareLink>("/api/share-links", json({ ...target, conditions })),
  update: (link: ShareLink, conditions: ShareConditions) =>
    request<ShareLink>(`/api/share-links/${link.id}`, json({ ...conditions, version: link.version }, "PATCH")),
  revoke: (id: number) => request<void>(`/api/share-links/${id}/revoke`, { method: "POST" }),
  list: (admin: boolean, page: number) => request<Page<ShareLink>>(`/api/${admin ? "admin/" : ""}share-links?page=${page}&size=20`),
  visits: (id: number | null, page: number) => request<Page<Visit>>(`/api/admin/${id == null ? "share-link-visits" : `share-links/${id}/visits`}?page=${page}&size=20`),
  open: (code: string, legacy = false) => request<ShareOpen>(`/api/public/share-links/${encodeURIComponent(code)}/open?legacy=${legacy}`, { method: "POST" }),
  verify: (code: string, password: string, visitToken: string) =>
    request<ShareOpen>(`/api/public/share-links/${encodeURIComponent(code)}/verify`, json({ password, visitToken })),
};
