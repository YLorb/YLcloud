import type {
  AdminUser,
  AgentRiskAuthorization,
  PermissionDefinition,
  PermissionGroup,
  ApiResult,
  AsyncTask,
  AsyncTaskDetail,
  ChunkStatus,
  ChunkUploadInit,
  FileItem,
  FilePreview,
  FileVersion,
  KnowledgeDashboard,
  KnowledgeChatMessage,
  KnowledgeChatSession,
  KnowledgeDocument,
  KnowledgeFacet,
  KnowledgePipelineEvent,
  KnowledgePipelineTask,
  KnowledgeProfile,
  KnowledgeProfileDiff,
  KnowledgeProfileVersion,
  KnowledgeRagQuery,
  RagAnalyticsSummary,
  RagConfigLog,
  RagConfig,
  RagChatMessage,
  RagDocument,
  RagQuery,
  RagQueryLog,
  RagTask,
  ShareFile,
  Space,
  SpaceDocumentSearch,
  SpaceFile,
  SpaceMember,
  PublicSiteSettings,
  SiteSetting,
  StorageQuota,
  User,
  UserApiKey,
  UserApiKeyCreated,
  WebhookEventType,
  WebhookSubscription,
  WebhookSubscriptionCreated,
  QuotaPolicy,
  QuotaUsage,
  UserMemory,
  UserMemorySetting,
  UserMemoryStats,
  KnowledgeChatEpisode,
  AccountStatus,
  DataExportJob,
  SecurityAuditEvent,
  AuditRetentionConfig,
  AuditStats,
  BackupRun,
  BackupStats,
  MaintenanceStatus,
  RestoreVerification
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

  let response: Response;
  try {
    response = await fetch(path, { ...init, headers });
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") {
      throw new Error("请求已取消");
    }
    throw new Error("无法连接服务器，请检查网络或服务状态");
  }
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

  let response: Response;
  try {
    response = await fetch(path, { headers });
  } catch {
    throw new Error("无法连接服务器，请检查网络或服务状态");
  }
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

function normalizeFileItems(items: FileItem[]) {
  return (items || []).map((item) => {
    const legacy = item as FileItem & { dir?: boolean };
    const normalized = { ...item, isDir: item.isDir ?? legacy.dir ?? false };
    if (normalized.isDir && (!Number.isFinite(normalized.fileId) || normalized.fileId <= 0)) {
      throw new Error("目录数据不完整，请刷新页面或联系管理员");
    }
    return normalized;
  });
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
  agentRiskAuthorizations: () => request<AgentRiskAuthorization[]>("/api/agent-risk-authorizations"),
  issueAgentRiskAuthorization: (payload: { mode: "ALLOW_ONCE" | "PERSISTENT"; expiresAt?: string; riskAcknowledged: true }) =>
    request<AgentRiskAuthorization>("/api/agent-risk-authorizations", { method: "POST", body: JSON.stringify(payload) }),
  revokeAgentRiskAuthorization: (authorizationId: number) =>
    request<boolean>(`/api/agent-risk-authorizations/${authorizationId}`, { method: "DELETE" }),
  userApiKeys: () => request<UserApiKey[]>("/api/api-keys"),
  createUserApiKey: (payload: {
    name: string;
    driveAccess: "NONE" | "READ" | "WRITE";
    driveRootFileId?: number;
    knowledgeRetrieve: boolean;
    knowledgeAgent: boolean;
    selectAllVisibleSpaces: boolean;
    spaceIds: number[];
    expiresAt?: string;
    neverExpires: boolean;
    allowHighRisk: boolean;
    riskAcknowledged: boolean;
  }) => request<UserApiKeyCreated>("/api/api-keys", { method: "POST", body: JSON.stringify(payload) }),
  revokeUserApiKey: (keyId: number) => request<boolean>(`/api/api-keys/${keyId}`, { method: "DELETE" }),
  webhookEventTypes: () => request<WebhookEventType[]>("/api/webhooks/event-types"),
  webhookSubscriptions: () => request<WebhookSubscription[]>("/api/webhooks"),
  createWebhookSubscription: (payload: {
    name: string;
    targetUrl: string;
    apiKeyId: number;
    eventTypes: WebhookEventType[];
    includeContent: boolean;
  }) => request<WebhookSubscriptionCreated>("/api/webhooks", { method: "POST", body: JSON.stringify(payload) }),
  rotateWebhookSecret: (subscriptionId: number) =>
    request<WebhookSubscriptionCreated>(`/api/webhooks/${subscriptionId}/rotate-secret`, { method: "POST" }),
  disableWebhookSubscription: (subscriptionId: number) =>
    request<boolean>(`/api/webhooks/${subscriptionId}`, { method: "DELETE" }),
  quotaUsage: () => request<QuotaUsage>("/api/quota/usage"),
  teamQuotaUsage: (spaceId: number) => request<QuotaUsage>(`/api/quota/teams/${spaceId}`),
  groupQuota: (groupId: number) => request<QuotaPolicy>(`/api/admin/quota/groups/${groupId}`),
  updateGroupQuota: (groupId: number, payload: Omit<QuotaPolicy, "groupId">) =>
    request<QuotaPolicy>(`/api/admin/quota/groups/${groupId}`, { method: "PUT", body: JSON.stringify(payload) }),
  reconcileQuota: () => request<boolean>("/api/admin/quota/reconcile", { method: "PUT" }),
  publicSettings: () => request<PublicSiteSettings>("/api/site/public-settings"),
  adminSettings: () => request<SiteSetting[]>("/api/admin/settings"),
  updateAdminSettings: (settings: Array<{ key: string; value: string }>) =>
    request<void>("/api/admin/settings", {
      method: "PUT",
      body: JSON.stringify({ settings })
    }),
  adminUsers: () => request<AdminUser[]>("/api/admin/users"),
  createAdminUser: (payload: { username: string; password: string; nickname: string; email?: string; role: "ADMIN" | "USER"; groupId?: number }) =>
    request<AdminUser>("/api/admin/users", { method: "POST", body: JSON.stringify(payload) }),
  updateAdminUser: (userId: number, payload: { role?: "ADMIN" | "USER"; status?: number }) =>
    request<AdminUser>(`/api/admin/users/${userId}`, { method: "PUT", body: JSON.stringify(payload) }),
  updateAdminUserAccess: (userId: number, payload: { groupId?: number; clearGroup?: boolean; overrides: Record<string, boolean> }) =>
    request<AdminUser>(`/api/admin/users/${userId}/access`, { method: "PUT", body: JSON.stringify(payload) }),
  permissionDefinitions: () => request<PermissionDefinition[]>("/api/admin/permission-groups/definitions"),
  permissionGroups: () => request<PermissionGroup[]>("/api/admin/permission-groups"),
  createPermissionGroup: (payload: { name: string; description?: string; permissions: Record<string, boolean> }) =>
    request<PermissionGroup>("/api/admin/permission-groups", { method: "POST", body: JSON.stringify(payload) }),
  updatePermissionGroup: (groupId: number, payload: { name: string; description?: string; permissions: Record<string, boolean> }) =>
    request<PermissionGroup>(`/api/admin/permission-groups/${groupId}`, { method: "PUT", body: JSON.stringify(payload) }),
  deletePermissionGroup: (groupId: number) => request<boolean>(`/api/admin/permission-groups/${groupId}`, { method: "DELETE" }),
  listAsyncTasks: (spaceId?: number) => request<AsyncTask[] | { records?: AsyncTask[]; list?: AsyncTask[]; items?: AsyncTask[]; tasks?: AsyncTask[] }>(
    `/api/async/page?${params({ spaceId, page: 1, pageSize: 100 })}`
  ),
  getAsyncTask: (source: "rag" | "knowledge" | "unified", taskId: number) => request<AsyncTaskDetail>(`/api/async/${source}/${taskId}`),
  retryUnifiedTask: (taskId: number, reason?: string) =>
    request<boolean>(`/api/async/${taskId}/retry`, { method: "POST", body: JSON.stringify({ reason }) }),
  cancelUnifiedTask: (taskId: number, reason?: string) =>
    request<boolean>(`/api/async/${taskId}/cancel`, { method: "POST", body: JSON.stringify({ reason }) }),
  currentUser: () => request<number>("/api/user/current"),
  storageQuota: () => request<StorageQuota>("/api/storage/quota"),
  listFiles: (parentId = 0) => request<FileItem[]>(`/api/file/list?${params({ parentId })}`).then(normalizeFileItems),
  listFilesByCategory: (category: string, keyword?: string) =>
    request<FileItem[]>(`/api/file/category?${params({ category, keyword })}`).then(normalizeFileItems),
  uploadFile: (file: File, parentId = 0, idempotencyKey = crypto.randomUUID()) => {
    const body = new FormData();
    body.set("file", file);
    body.set("parentId", String(parentId));
    return request<FileItem>("/api/file/upload", {
      method: "POST",
      body,
      headers: { "Idempotency-Key": idempotencyKey }
    });
  },
  createFile: (payload: { isDir: 0 | 1; parentId?: number; name: string; type?: string }) =>
    request<FileItem>(
      `/api/file/${payload.isDir}?${params({
        parentId: payload.parentId ?? 0,
        name: payload.name,
        type: payload.type ?? ""
      })}`,
      { method: "POST", headers: { "Idempotency-Key": crypto.randomUUID() } }
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
  batchDeleteFiles: (fileIds: number[]) =>
    request<boolean>("/api/file/batch", { method: "DELETE", body: JSON.stringify({ fileIds }) }),
  batchMoveFiles: (fileIds: number[], targetParentId: number) =>
    request<boolean>("/api/file/batch/move", { method: "PUT", body: JSON.stringify({ fileIds, targetParentId }) }),
  batchCopyFiles: (fileIds: number[], targetParentId: number) =>
    request<boolean>("/api/file/batch/copy", { method: "PUT", body: JSON.stringify({ fileIds, targetParentId }) }),
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
  uploadChunk: (payload: { file: Blob; uploadId: string; chunkIndex: number; chunkMd5?: string; signal?: AbortSignal }) => {
    const body = new FormData();
    body.set("file", payload.file);
    body.set("uploadId", payload.uploadId);
    body.set("chunkIndex", String(payload.chunkIndex));
    if (payload.chunkMd5) body.set("chunkMd5", payload.chunkMd5);
    return request<boolean>("/api/file/multipart/chunk", { method: "POST", body, signal: payload.signal });
  },
  multipartStatus: (uploadId: string) => request<ChunkStatus>(`/api/file/multipart/status/${uploadId}`),
  mergeMultipartUpload: (payload: { uploadId: string; fileUuid?: string; fileName?: string; partNames: string[] }) =>
    request<FileItem>("/api/file/multipart/merge", {
      method: "POST",
      body: JSON.stringify(payload)
    }),
  listRecycle: () => request<FileItem[]>("/api/file/recycle").then(normalizeFileItems),
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
  listSpaceFileAncestors: (spaceId: number, folderId: number) =>
    request<SpaceFile[]>(`/api/space/${spaceId}/files/${folderId}/ancestors`),
  searchSpaceFiles: (spaceId: number, query: string) =>
    request<SpaceFile[]>(`/api/space/${spaceId}/files/search?${params({ q: query, limit: 100 })}`),
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
  importSpaceFileBatch: (spaceId: number, payload: { sourceNodeIds: number[]; targetParentId?: number | null; failurePolicy: "ATOMIC" | "SKIP_FAILED" }) =>
    request<import("./types").SpaceFileImportBatch>(`/api/space/${spaceId}/files/import-batches`, {
      method: "POST", body: JSON.stringify(payload)
    }),
  uploadSpaceFile: (spaceId: number, file: File, parentId?: number | null, name?: string) => {
    const body = new FormData();
    body.set("file", file);
    if (parentId !== undefined && parentId !== null) body.set("parentId", String(parentId));
    if (name) body.set("name", name);
    return request<SpaceFile>(`/api/space/${spaceId}/files/upload`, {
      method: "POST",
      body,
      headers: { "Idempotency-Key": crypto.randomUUID() }
    });
  },
  previewSpaceFileDeletion: (spaceId: number, fileId: number) =>
    request<import("./types").SpaceFileDeletePreview>(`/api/space/${spaceId}/files/${fileId}/deletion-preview`, { method: "POST" }),
  removeSpaceFile: (spaceId: number, fileId: number, payload: { confirmationName: string; confirmationToken: string; expectedVersion: number; expiresAtEpochSecond: number }) =>
    request<import("./types").SpaceFileDeleteTask>(`/api/space/${spaceId}/files/${fileId}`, { method: "DELETE", body: JSON.stringify(payload) }),
  renameSpaceFile: (spaceId: number, fileId: number, name: string, expectedVersion: number) =>
    request<SpaceFile>(`/api/space/${spaceId}/files/${fileId}/rename`, { method: "PUT", body: JSON.stringify({ name, expectedVersion }) }),
  moveSpaceFile: (spaceId: number, fileId: number, targetParentId: number, expectedVersion: number) =>
    request<SpaceFile>(`/api/space/${spaceId}/files/${fileId}/move`, { method: "PUT", body: JSON.stringify({ targetParentId, expectedVersion }) }),
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
    return request<FileVersion>(`/api/space/${spaceId}/files/${fileId}/versions`, {
      method: "POST",
      body,
      headers: { "Idempotency-Key": crypto.randomUUID() }
    });
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
      { method: "POST", headers: { "Idempotency-Key": crypto.randomUUID() } }
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
  queryKnowledgeRag: (payload: {
    spaceIds: number[];
    question: string;
    retrievalMode?: "precise" | "balanced" | "broad";
    history?: RagChatMessage[];
  }) =>
    request<KnowledgeRagQuery>("/api/knowledge/rag/query", {
      method: "POST",
      body: JSON.stringify(payload)
    }),
  listKnowledgeChatSessions: (keyword?: string, limit = 50) =>
    request<KnowledgeChatSession[]>(`/api/knowledge/chat/sessions?${params({ keyword, limit })}`),
  createKnowledgeChatSession: (payload: { title?: string; scopeMode?: string; spaceIds?: number[] }) =>
    request<KnowledgeChatSession>("/api/knowledge/chat/sessions", {
      method: "POST",
      body: JSON.stringify(payload)
    }),
  getKnowledgeChatSession: (sessionId: number) =>
    request<KnowledgeChatSession>(`/api/knowledge/chat/sessions/${sessionId}`),
  updateKnowledgeChatSessionTitle: (sessionId: number, title: string) =>
    request<KnowledgeChatSession>(`/api/knowledge/chat/sessions/${sessionId}`, {
      method: "PUT",
      body: JSON.stringify({ title })
    }),
  updateKnowledgeChatSessionScope: (sessionId: number, spaceIds: number[]) =>
    request<KnowledgeChatSession>(`/api/knowledge/chat/sessions/${sessionId}/scope`, {
      method: "PUT",
      body: JSON.stringify({ spaceIds })
    }),
  deleteKnowledgeChatSession: (sessionId: number) =>
    request<boolean>(`/api/knowledge/chat/sessions/${sessionId}`, { method: "DELETE" }),
  appendKnowledgeChatMessage: (
    sessionId: number,
    payload: { role: "user" | "assistant" | "system"; content: string; citationsJson?: string }
  ) =>
    request<KnowledgeChatMessage>(`/api/knowledge/chat/sessions/${sessionId}/messages`, {
      method: "POST",
      body: JSON.stringify(payload)
    }),
  submitKnowledgeChatQuery: (
    sessionId: number,
    payload: { spaceIds: number[]; question: string; retrievalMode?: "precise" | "balanced" | "broad" },
    idempotencyKey = crypto.randomUUID()
  ) => request<KnowledgeChatMessage>(`/api/knowledge/chat/sessions/${sessionId}/queries`, {
    method: "POST",
    headers: { "Idempotency-Key": idempotencyKey },
    body: JSON.stringify(payload)
  }),
  retryKnowledgeChatQuery: (sessionId: number, messageId: number) =>
    request<KnowledgeChatMessage>(`/api/knowledge/chat/sessions/${sessionId}/queries/${messageId}/retry`, { method: "POST" }),
  cancelKnowledgeChatQuery: (sessionId: number, messageId: number) =>
    request<KnowledgeChatMessage>(`/api/knowledge/chat/sessions/${sessionId}/queries/${messageId}/cancel`, { method: "POST" }),
  listUserMemories: (type?: string, keyword?: string) =>
    request<UserMemory[]>(`/api/assistant/memories?${params({ type, keyword, limit: 500 })}`),
  userMemorySetting: () => request<UserMemorySetting>("/api/assistant/memories/setting"),
  updateUserMemorySetting: (payload: { enabled: boolean }) =>
    request<UserMemorySetting>("/api/assistant/memories/setting", { method: "PUT", body: JSON.stringify(payload) }),
  userMemoryStats: () => request<UserMemoryStats>("/api/assistant/memories/stats"),
  updateUserMemory: (id: number, payload: { memoryType: UserMemory["memoryType"]; content: string }) =>
    request<UserMemory>(`/api/assistant/memories/${id}`, { method: "PUT", body: JSON.stringify(payload) }),
  pinUserMemory: (id: number, pinned: boolean) =>
    request<UserMemory>(`/api/assistant/memories/${id}/pin?${params({ pinned })}`, { method: "PUT" }),
  forgetUserMemory: (id: number) => request<boolean>(`/api/assistant/memories/${id}`, { method: "DELETE" }),
  clearUserMemories: () => request<boolean>("/api/assistant/memories", { method: "DELETE" }),
  exportUserMemories: () => download("/api/assistant/memories/export", "ylcloud-memories.csv"),
  listKnowledgeChatEpisodes: (sessionId: number) => request<KnowledgeChatEpisode[]>(`/api/knowledge/chat/sessions/${sessionId}/episodes`),
  ragAnalyticsSummary: (spaceId: number) =>
    request<RagAnalyticsSummary>(`/api/space/${spaceId}/rag/analytics/summary`),
  ragAnalyticsQueries: (spaceId: number, limit = 50) =>
    request<RagQueryLog[]>(`/api/space/${spaceId}/rag/analytics/queries?${params({ limit })}`),
  ragAnalyticsNoAnswer: (spaceId: number, limit = 50) =>
    request<RagQueryLog[]>(`/api/space/${spaceId}/rag/analytics/no-answer?${params({ limit })}`),
  ragAnalyticsConfigLogs: (spaceId: number, limit = 50) =>
    request<RagConfigLog[]>(`/api/space/${spaceId}/rag/analytics/config-logs?${params({ limit })}`),
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
    request<boolean>(`/api/space/${spaceId}/rag/files/${spaceFileId}/vectors/repair`, { method: "POST" }),
  knowledgeDashboard: (spaceId: number) =>
    request<KnowledgeDashboard>(`/api/space/${spaceId}/knowledge/dashboard`),
  listKnowledgeDocuments: (
    spaceId: number,
    payload: { category?: string; tag?: string; profileStatus?: string } = {}
  ) => request<KnowledgeDocument[]>(`/api/space/${spaceId}/knowledge/documents?${params(payload)}`),
  listKnowledgeTasks: (spaceId: number) =>
    request<KnowledgePipelineTask[]>(`/api/space/${spaceId}/knowledge/pipeline/tasks`),
  listKnowledgeTaskEvents: (spaceId: number, taskId: number) =>
    request<KnowledgePipelineEvent[]>(`/api/space/${spaceId}/knowledge/pipeline/tasks/${taskId}/events`),
  retryKnowledgeTask: (spaceId: number, taskId: number) =>
    request<KnowledgePipelineTask>(`/api/space/${spaceId}/knowledge/pipeline/tasks/${taskId}/retry`, { method: "POST" }),
  retryFailedKnowledgeTasks: (spaceId: number) =>
    request<KnowledgePipelineTask[]>(`/api/space/${spaceId}/knowledge/pipeline/retry-failed`, { method: "POST" }),
  runKnowledgePipeline: (spaceId: number) =>
    request<KnowledgePipelineTask>(`/api/space/${spaceId}/knowledge/pipeline/run-all`, { method: "POST" }),
  regenerateKnowledgeProfile: (spaceId: number, documentId: number) =>
    request<KnowledgePipelineTask>(`/api/space/${spaceId}/knowledge/documents/${documentId}/regenerate`, { method: "POST" }),
  reclassifyKnowledgeDocument: (spaceId: number, documentId: number) =>
    request<KnowledgePipelineTask>(`/api/space/${spaceId}/knowledge/documents/${documentId}/reclassify`, { method: "POST" }),
  getKnowledgeProfile: (spaceId: number, documentId: number) =>
    request<KnowledgeProfile>(`/api/space/${spaceId}/knowledge/documents/${documentId}/profile`),
  listKnowledgeProfileVersions: (spaceId: number, documentId: number) =>
    request<KnowledgeProfileVersion[]>(`/api/space/${spaceId}/knowledge/documents/${documentId}/versions`),
  diffKnowledgeProfileVersion: (spaceId: number, documentId: number, versionId: number, compareTo?: number) =>
    request<KnowledgeProfileDiff>(
      `/api/space/${spaceId}/knowledge/documents/${documentId}/versions/${versionId}/diff${compareTo ? `?compareTo=${compareTo}` : ""}`
    ),
  restoreKnowledgeProfileVersion: (spaceId: number, documentId: number, versionId: number) =>
    request<KnowledgeProfile>(`/api/space/${spaceId}/knowledge/documents/${documentId}/versions/${versionId}/restore`, { method: "POST" }),
  updateKnowledgeProfile: (
    spaceId: number,
    documentId: number,
    payload: { title?: string; summary?: string; category?: string; tags?: string[]; keywords?: string[]; questions?: string[]; profileStatus?: string }
  ) =>
    request<KnowledgeProfile>(`/api/space/${spaceId}/knowledge/documents/${documentId}/profile`, {
      method: "PUT",
      body: JSON.stringify(payload)
    }),
  classifyKnowledgeDocument: (spaceId: number, documentId: number, payload: { category?: string; tags?: string[] }) =>
    request<KnowledgeProfile>(`/api/space/${spaceId}/knowledge/documents/${documentId}/classify`, {
      method: "PUT",
      body: JSON.stringify(payload)
    }),
  markKnowledgeProfileReviewed: (spaceId: number, documentId: number) =>
    request<KnowledgeProfile>(`/api/space/${spaceId}/knowledge/documents/${documentId}/reviewed`, { method: "POST" }),
  activateKnowledgeProfileVersion: (spaceId: number, documentId: number, versionId: number) =>
    request<KnowledgeProfile>(`/api/space/${spaceId}/knowledge/documents/${documentId}/versions/${versionId}/activate`, { method: "POST" }),
  listKnowledgeCategories: (spaceId: number) =>
    request<KnowledgeFacet[]>(`/api/space/${spaceId}/knowledge/facets/categories`),
  listKnowledgeTags: (spaceId: number) =>
    request<KnowledgeFacet[]>(`/api/space/${spaceId}/knowledge/facets/tags`),

  // Account Lifecycle
  accountStatus: () => request<AccountStatus>("/api/account/status"),
  cancelAccount: (payload: { reason?: string; confirmTeamOwnerTransfer?: boolean }) =>
    request<AccountStatus>("/api/account/cancel", { method: "POST", body: JSON.stringify(payload) }),
  recoverAccount: (payload: { userId: number; reason?: string }) =>
    request<AccountStatus>("/api/account/recover", { method: "POST", body: JSON.stringify(payload) }),
  requestDataExport: (payload: { exportScope?: string }) =>
    request<DataExportJob>("/api/account/export", { method: "POST", body: JSON.stringify(payload) }),
  listDataExports: () => request<DataExportJob[]>("/api/account/export/list"),
  getDataExport: (jobId: number) => request<DataExportJob>(`/api/account/export/${jobId}`),

  // Security Audit
  queryAuditEvents: (payload: {
    eventType?: string;
    subjectId?: number;
    targetType?: string;
    targetId?: string;
    traceId?: string;
    from?: string;
    to?: string;
    limit?: number;
  }) => request<SecurityAuditEvent[]>("/api/admin/audit/query", {
    method: "POST",
    body: JSON.stringify(payload)
  }),
  auditStats: (payload?: { startTime?: string; endTime?: string }) =>
    request<AuditStats>(`/api/admin/audit/stats?${params(payload || {})}`),
  listRetentionConfigs: () => request<AuditRetentionConfig[]>("/api/admin/audit/retention"),
  updateRetentionConfig: (configKey: string, retentionDays: number) =>
    request<AuditRetentionConfig>(`/api/admin/audit/retention/${configKey}?${params({ retentionDays })}`, { method: "PUT" }),

  // Backup
  listReadyBackups: () => request<BackupRun[]>("/api/admin/backup/ready"),
  listRecentBackups: (limit = 20) => request<BackupRun[]>(`/api/admin/backup/recent?${params({ limit })}`),
  getBackup: (backupId: number) => request<BackupRun>(`/api/admin/backup/${backupId}`),
  getRestoreVerification: (backupId: number) =>
    request<RestoreVerification>(`/api/admin/backup/${backupId}/restore-verification`),
  backupStats: () => request<BackupStats>("/api/admin/backup/stats"),

  // Maintenance Mode
  maintenanceStatus: () => request<MaintenanceStatus>("/api/admin/maintenance/status"),
  enableMaintenance: (payload: { reason: string }) =>
    request<{ status: string; message: string }>(
      `/api/admin/maintenance/enable?${params({ reason: payload.reason })}`, { method: "POST" }),
  disableMaintenance: () =>
    request<{ status: string; message: string }>("/api/admin/maintenance/disable", { method: "POST" })
};
