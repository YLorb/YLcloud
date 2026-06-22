import type {
  ApiResult,
  AsyncTask,
  ChunkStatus,
  ChunkUploadInit,
  FileItem,
  FilePreview,
  FileVersion,
  RagConfig,
  RagChatMessage,
  RagDocument,
  RagQuery,
  RagTask,
  ShareFile,
  Space,
  SpaceDocumentSearch,
  SpaceFile,
  SpaceMember,
  PublicSiteSettings,
  SiteSetting,
  User
} from "./types";

const TOKEN_KEY = "ylcloud_token";
const USER_KEY = "ylcloud_user";

export function getToken() {
  return localStorage.getItem(TOKEN_KEY);
}

export function getStoredUser(): User | null {
  const raw = localStorage.getItem(USER_KEY);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as User;
  } catch {
    return null;
  }
}

export function setSession(user: User) {
  localStorage.setItem(TOKEN_KEY, user.token);
  localStorage.setItem(USER_KEY, JSON.stringify(user));
}

export function clearSession() {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(USER_KEY);
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  const token = getToken();
  if (token) headers.set("Authorization", token.startsWith("Bearer ") ? token : `Bearer ${token}`);
  if (init.body && !(init.body instanceof FormData)) {
    headers.set("Content-Type", "application/json");
  }

  const response = await fetch(path, { ...init, headers });
  const contentType = response.headers.get("content-type") || "";
  if (!contentType.includes("application/json")) {
    if (!response.ok) throw new Error(`请求失败：${response.status}`);
    return undefined as T;
  }

  const result = (await response.json()) as ApiResult<T>;
  if (!response.ok || result.code !== 200) {
    throw new Error(result.message || `请求失败：${response.status}`);
  }
  return result.data;
}

async function download(path: string, filename: string) {
  const headers = new Headers();
  const token = getToken();
  if (token) headers.set("Authorization", token.startsWith("Bearer ") ? token : `Bearer ${token}`);

  const response = await fetch(path, { headers });
  if (!response.ok) {
    const contentType = response.headers.get("content-type") || "";
    if (contentType.includes("application/json")) {
      const result = (await response.json()) as ApiResult<unknown>;
      throw new Error(result.message || `下载失败：${response.status}`);
    }
    throw new Error(`下载失败：${response.status}`);
  }

  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}

function params(input: Record<string, string | number | boolean | null | undefined>) {
  const search = new URLSearchParams();
  Object.entries(input).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== "") search.set(key, String(value));
  });
  return search.toString();
}

export const api = {
  login: (username: string, password: string) =>
    request<User>("/api/login", {
      method: "POST",
      body: JSON.stringify({ username, password })
    }),
  sign: (payload: { username: string; password: string; nickname: string }) =>
    request<void>("/api/sign", {
      method: "POST",
      body: JSON.stringify(payload)
    }),
  publicSettings: () => request<PublicSiteSettings>("/api/site/public-settings"),
  adminSettings: () => request<SiteSetting[]>("/api/admin/settings"),
  updateAdminSettings: (settings: Array<{ key: string; value: string }>) =>
    request<void>("/api/admin/settings", {
      method: "PUT",
      body: JSON.stringify({ settings })
    }),
  listAsyncTasks: () => request<AsyncTask[] | { records?: AsyncTask[]; list?: AsyncTask[]; items?: AsyncTask[]; tasks?: AsyncTask[] }>("/api/async"),
  currentUser: () => request<number>("/api/user/current"),
  listFiles: (parentId = 0) => request<FileItem[]>(`/api/file/list?${params({ parentId })}`),
  uploadFile: (file: File, parentId = 0) => {
    const body = new FormData();
    body.set("file", file);
    body.set("parentId", String(parentId));
    return request<FileItem>("/api/file/upload", { method: "POST", body });
  },
  createFile: (payload: { isDir: 0 | 1; parentId?: number; name: string; type?: string }) =>
    request<FileItem>(
      `/api/file/${payload.isDir}?${params({
        parentId: payload.parentId ?? 0,
        name: payload.name,
        type: payload.type ?? ""
      })}`,
      { method: "POST" }
    ),
  renameFile: (fileUuid: string, parentId: number, newName: string) =>
    request<FileItem>(`/api/file/rename/${fileUuid}?${params({ parentId, newName })}`, {
      method: "PUT"
    }),
  deleteFile: (fileUuid: string, parentId: number) =>
    request<boolean>(`/api/file/${fileUuid}?${params({ parentId })}`, { method: "DELETE" }),
  shareFile: (fileUuid: string, parentId: number) =>
    request<string>(`/api/file/share/${fileUuid}?${params({ parentId })}`, { method: "POST" }),
  previewFile: (fileUuid: string, parentId: number) =>
    request<FilePreview>(`/api/file/preview/${fileUuid}?${params({ parentId })}`),
  downloadFile: (fileUuid: string, parentId: number, filename: string) =>
    download(`/api/file/download/${fileUuid}?${params({ parentId })}`, filename),
  bucketExists: () => request<boolean>("/api/file/bucket"),
  moveFiles: (sourceplace: number, targetplace: number) =>
    request<boolean>(`/api/file/move?${params({ sourceplace, targetplace })}`, { method: "PUT" }),
  copyFiles: (sourceplace: number, targetplace: number) =>
    request<boolean>(`/api/file/copy?${params({ sourceplace, targetplace })}`, { method: "PUT" }),
  initMultipartUpload: (payload: {
    uploadId?: string;
    fileName: string;
    fileMd5?: string;
    fileSha1?: string;
    fileHash: string;
    fileSize: number;
    chunkSize?: number;
    totalChunks?: number;
    parentId?: number;
  }) =>
    request<ChunkUploadInit>("/api/file/multipart/init", {
      method: "POST",
      body: JSON.stringify(payload)
    }),
  uploadChunk: (payload: { file: Blob; uploadId: string; chunkIndex: number; chunkMd5?: string }) => {
    const body = new FormData();
    body.set("file", payload.file);
    body.set("uploadId", payload.uploadId);
    body.set("chunkIndex", String(payload.chunkIndex));
    if (payload.chunkMd5) body.set("chunkMd5", payload.chunkMd5);
    return request<boolean>("/api/file/multipart/chunk", { method: "POST", body });
  },
  multipartStatus: (uploadId: string) => request<ChunkStatus>(`/api/file/multipart/status/${uploadId}`),
  mergeMultipartUpload: (payload: { uploadId: string; fileUuid?: string; fileName?: string; partNames: string[] }) =>
    request<FileItem>("/api/file/multipart/merge", {
      method: "POST",
      body: JSON.stringify(payload)
    }),
  listRecycle: () => request<FileItem[]>("/api/file/recycle"),
  restoreRecycle: (fileId: number) =>
    request<boolean>(`/api/file/recycle/${fileId}/restore`, { method: "PUT" }),
  deleteRecycle: (fileId: number) =>
    request<boolean>(`/api/file/recycle/${fileId}`, { method: "DELETE" }),
  getShare: (shareCode: string) => request<ShareFile>(`/api/share/${shareCode}`),
  listSpaces: () => request<Space[]>("/api/space/list"),
  getSpace: (spaceId: number) => request<Space>(`/api/space/${spaceId}`),
  createSpace: (payload: { name: string; description?: string }) =>
    request<Space>("/api/space", { method: "POST", body: JSON.stringify(payload) }),
  updateSpace: (spaceId: number, payload: { name: string; description?: string }) =>
    request<Space>(`/api/space/${spaceId}`, { method: "PUT", body: JSON.stringify(payload) }),
  updateSpaceVersionSetting: (spaceId: number, versionEnabled: number) =>
    request<Space>(`/api/space/${spaceId}/version-setting`, {
      method: "PUT",
      body: JSON.stringify({ versionEnabled })
    }),
  deleteSpace: (spaceId: number) => request<boolean>(`/api/space/${spaceId}`, { method: "DELETE" }),
  listSpaceFiles: (spaceId: number, parentId?: number | null) =>
    request<SpaceFile[]>(`/api/space/${spaceId}/files/list?${params({ parentId })}`),
  treeSpaceFiles: (spaceId: number) => request<SpaceFile[]>(`/api/space/${spaceId}/files/tree`),
  listVersionEnabledSpaceFiles: (spaceId: number) =>
    request<SpaceFile[]>(`/api/space/${spaceId}/files/version-enabled`),
  createSpaceFolder: (spaceId: number, payload: { name: string; parentId?: number | null }) =>
    request<SpaceFile>(`/api/space/${spaceId}/files/folder`, {
      method: "POST",
      body: JSON.stringify(payload)
    }),
  importSpaceFile: (spaceId: number, payload: { userFileId: number; parentId?: number | null; name?: string }) =>
    request<SpaceFile>(`/api/space/${spaceId}/files/import`, {
      method: "POST",
      body: JSON.stringify(payload)
    }),
  uploadSpaceFile: (spaceId: number, file: File, parentId?: number | null, name?: string) => {
    const body = new FormData();
    body.set("file", file);
    if (parentId !== undefined && parentId !== null) body.set("parentId", String(parentId));
    if (name) body.set("name", name);
    return request<SpaceFile>(`/api/space/${spaceId}/files/upload`, { method: "POST", body });
  },
  removeSpaceFile: (spaceId: number, fileId: number) =>
    request<boolean>(`/api/space/${spaceId}/files/${fileId}`, { method: "DELETE" }),
  updateSpaceFileVersionSetting: (spaceId: number, fileId: number, versionEnabled: number) =>
    request<SpaceFile>(`/api/space/${spaceId}/files/${fileId}/version-setting`, {
      method: "PUT",
      body: JSON.stringify({ versionEnabled })
    }),
  previewSpaceFile: (spaceId: number, fileId: number) =>
    request<FilePreview>(`/api/space/${spaceId}/files/${fileId}/preview`),
  downloadSpaceFile: (spaceId: number, fileId: number, filename: string) =>
    download(`/api/space/${spaceId}/files/${fileId}/download`, filename),
  uploadSpaceFileVersion: (spaceId: number, fileId: number, file: File, changeNote?: string) => {
    const body = new FormData();
    body.set("file", file);
    if (changeNote) body.set("changeNote", changeNote);
    return request<FileVersion>(`/api/space/${spaceId}/files/${fileId}/versions`, { method: "POST", body });
  },
  listSpaceFileVersions: (spaceId: number, fileId: number) =>
    request<FileVersion[]>(`/api/space/${spaceId}/files/${fileId}/versions`),
  previewSpaceFileVersion: (spaceId: number, fileId: number, versionRecordId: number) =>
    request<FilePreview>(`/api/space/${spaceId}/files/${fileId}/versions/${versionRecordId}/preview`),
  downloadSpaceFileVersion: (spaceId: number, fileId: number, versionRecordId: number, filename: string) =>
    download(`/api/space/${spaceId}/files/${fileId}/versions/${versionRecordId}/download`, filename),
  restoreSpaceFileVersion: (spaceId: number, fileId: number, versionRecordId: number, changeNote?: string) =>
    request<FileVersion>(
      `/api/space/${spaceId}/files/${fileId}/versions/${versionRecordId}/restore?${params({ changeNote })}`,
      { method: "POST" }
    ),
  listMembers: (spaceId: number) => request<SpaceMember[]>(`/api/space/${spaceId}/members`),
  addMember: (spaceId: number, payload: { userId: number; role?: string }) =>
    request<boolean>(`/api/space/${spaceId}/members`, { method: "POST", body: JSON.stringify(payload) }),
  updateMemberRole: (spaceId: number, userId: number, role: string) =>
    request<boolean>(`/api/space/${spaceId}/members/${userId}/role`, {
      method: "PUT",
      body: JSON.stringify({ role })
    }),
  removeMember: (spaceId: number, userId: number) =>
    request<boolean>(`/api/space/${spaceId}/members/${userId}`, { method: "DELETE" }),
  ragConfig: (spaceId: number) => request<RagConfig>(`/api/space/${spaceId}/rag/config`),
  updateRagConfig: (spaceId: number, payload: Partial<RagConfig>) =>
    request<RagConfig>(`/api/space/${spaceId}/rag/config`, {
      method: "PUT",
      body: JSON.stringify(payload)
    }),
  queryRag: (spaceId: number, question: string, retrievalMode?: "precise" | "balanced" | "broad", history?: RagChatMessage[]) =>
    request<RagQuery>(`/api/space/${spaceId}/rag/query`, {
      method: "POST",
      body: JSON.stringify({ question, retrievalMode, history })
    }),
  importSpaceWebLink: (spaceId: number, payload: { url: string; parentId?: number | null; name?: string }) =>
    request<SpaceFile>(`/api/space/${spaceId}/rag/links`, {
      method: "POST",
      body: JSON.stringify(payload)
    }),
  rebuildSpaceRag: (spaceId: number) =>
    request<boolean>(`/api/space/${spaceId}/rag/rebuild`, { method: "POST" }),
  rebuildFileRag: (spaceId: number, spaceFileId: number) =>
    request<boolean>(`/api/space/${spaceId}/rag/files/${spaceFileId}/rebuild`, { method: "POST" }),
  listRagDocuments: (spaceId: number) => request<RagDocument[]>(`/api/space/${spaceId}/rag/documents`),
  searchRagDocuments: (
    spaceId: number,
    payload: { keyword?: string; fileType?: string; indexStatus?: string; searchContent?: number; page?: number; pageSize?: number }
  ) => request<SpaceDocumentSearch[]>(`/api/space/${spaceId}/rag/documents/search?${params(payload)}`),
  listRagTasks: (spaceId: number) => request<RagTask[]>(`/api/space/${spaceId}/rag/tasks`),
  retryRagTask: (spaceId: number, taskId: number) =>
    request<boolean>(`/api/space/${spaceId}/rag/tasks/${taskId}/retry`, { method: "POST" }),
  retryFailedRagTasks: (spaceId: number) =>
    request<boolean>(`/api/space/${spaceId}/rag/tasks/retry-failed`, { method: "POST" }),
  repairSpaceVectors: (spaceId: number) =>
    request<boolean>(`/api/space/${spaceId}/rag/vectors/repair`, { method: "POST" }),
  repairFileVectors: (spaceId: number, spaceFileId: number) =>
    request<boolean>(`/api/space/${spaceId}/rag/files/${spaceFileId}/vectors/repair`, { method: "POST" })
};
