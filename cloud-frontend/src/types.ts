export type ApiResult<T> = {
  code: number;
  message: string;
  data: T;
};

export type User = {
  id: number;
  username: string;
  nickname: string;
  token: string;
};

export type FileItem = {
  fileId: number;
  fileUuid: string;
  isDir: boolean;
  userId?: number;
  parentId: number;
  name: string;
  type?: string;
  size?: number;
  hash?: string;
  createTime?: string;
  updateTime?: string;
};

export type FilePreview = {
  fileUuid?: string;
  name: string;
  previewType?: string;
  contentType?: string;
  size?: number;
  previewUrl?: string;
  textContent?: string;
};

export type ShareFile = {
  fileId: number;
  name: string;
  dir: boolean;
  previewType?: string;
  contentType?: string;
  size?: number;
  previewUrl?: string;
  downloadUrl?: string;
  textContent?: string;
  children?: ShareFile[];
};

export type Space = {
  id: number;
  name: string;
  description?: string;
  type?: string;
  ownerId?: number;
  rootDirId?: number;
  role?: string;
  ragStatus?: number;
  versionEnabled?: number;
  createtime?: string;
  updatetime?: string;
};

export type SpaceFile = {
  id: number;
  spaceId: number;
  fileUuid?: string;
  name: string;
  dir?: boolean;
  parentId?: number | null;
  path?: string;
  type?: string;
  size?: number;
  versionEnabled?: number | null;
  effectiveVersionEnabled?: boolean;
  createtime?: string;
  updatetime?: string;
  children?: SpaceFile[];
};

export type SpaceMember = {
  id: number;
  spaceId: number;
  userId: number;
  role?: string;
  status?: number;
  createtime?: string;
  updatetime?: string;
};

export type RagConfig = {
  id?: number;
  spaceId?: number;
  embeddingModel?: string;
  chatModel?: string;
  vectorCollection?: string;
  chunkSize?: number;
  chunkOverlap?: number;
  topK?: number;
  scoreThreshold?: number;
  enabled?: number;
  status?: number;
};

export type RagDocument = {
  id: number;
  spaceId: number;
  spaceFileId: number;
  fileUuid?: string;
  fileName: string;
  fileType?: string;
  indexStatus?: string;
  chunkCount?: number;
  errorMessage?: string;
  updatetime?: string;
};

export type RagTask = {
  id: number;
  spaceId: number;
  spaceFileId?: number;
  documentId?: number;
  taskType?: string;
  taskStatus?: string;
  totalCount?: number;
  successCount?: number;
  failedCount?: number;
  errorMessage?: string;
  updatetime?: string;
};

export type RagCitation = {
  index?: number;
  chunkId?: number;
  documentId?: number;
  spaceFileId?: number;
  fileName?: string;
  contentSummary?: string;
  previewUrl?: string;
  downloadUrl?: string;
  vectorScore?: number;
  rerankScore?: number;
};

export type RagQuery = {
  question: string;
  answer?: string;
  hitChunkIds?: number[];
  contexts?: string[];
  citations?: RagCitation[];
};
