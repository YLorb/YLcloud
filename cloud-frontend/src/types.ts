export type ApiResult<T> = {
  code: number;
  message: string;
  data: T;
};

export type User = {
  id: number;
  username: string;
  nickname: string;
  role?: string;
  token: string;
};

export type SiteSetting = {
  key: string;
  value: string;
  maskedValue?: string;
  valueType: string;
  groupName: string;
  label: string;
  description?: string;
  secret: boolean;
  editable: boolean;
  createTime?: string;
  updateTime?: string;
};

export type PublicSiteSettings = {
  siteName: string;
  siteDescription: string;
  logoUrl?: string;
  publicUrl?: string;
  allowRegister: boolean;
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

export type ChunkUploadInit = {
  uploadId?: string;
  instantUpload: boolean;
  uploadedChunks?: number[];
  chunkSize?: number;
  totalChunks?: number;
  file?: FileItem;
};

export type ChunkStatus = {
  uploadId: string;
  totalChunks: number;
  uploadedCount: number;
  uploadedChunks: number[];
};

export type FileVersion = {
  id: number;
  fileUuid?: string;
  versionNo?: number;
  minioVersionId?: string;
  fileName?: string;
  fileHash?: string;
  fileMd5?: string;
  fileType?: string;
  fileSize?: number;
  changeNote?: string;
  createdBy?: number;
  current?: number;
  createtime?: string;
  previewUrl?: string;
  streamUrl?: string;
  downloadUrl?: string;
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
  temperature?: number;
  scoreThreshold?: number;
  enabled?: number;
  status?: number;
  createtime?: string;
  updatetime?: string;
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

export type SpaceDocumentSearch = {
  documentId: number;
  spaceId: number;
  spaceFileId: number;
  fileUuid?: string;
  fileName: string;
  fileType?: string;
  path?: string;
  indexStatus?: string;
  chunkCount?: number;
  hitContents?: string[];
  hitChunkIds?: number[];
  previewUrl?: string;
  streamUrl?: string;
  downloadUrl?: string;
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

export type KnowledgePipelineTask = {
  id: number;
  spaceId: number;
  documentId?: number | null;
  taskType?: string;
  taskStatus?: string;
  stage?: string;
  progress?: number;
  totalCount?: number;
  successCount?: number;
  failedCount?: number;
  errorMessage?: string;
  forceRebuild?: boolean;
  terminalStage?: string;
  terminalReason?: string;
  incrementalAction?: string;
  incrementalDetail?: string;
  createdBy?: number;
  startedTime?: string;
  finishedTime?: string;
  createtime?: string;
  updatetime?: string;
};

export type KnowledgePipelineEvent = {
  id: number;
  taskId: number;
  spaceId: number;
  documentId?: number | null;
  stage?: string;
  eventType?: string;
  eventStatus?: string;
  message?: string;
  inputSummary?: string;
  outputSummary?: string;
  errorCode?: string;
  errorMessage?: string;
  eventTime?: string;
  durationMs?: number;
  traceId?: string;
  attemptNo?: number;
  createdAt?: string;
};

export type KnowledgeFacet = {
  name: string;
  count: number;
};

export type KnowledgeDocument = {
  documentId: number;
  spaceId: number;
  spaceFileId: number;
  fileUuid?: string;
  fileName: string;
  fileType?: string;
  indexStatus?: string;
  chunkCount?: number;
  profileStatus?: string;
  reviewStatus?: string;
  reviewReason?: string;
  qualityIssueJson?: string;
  sourceChunkCount?: number;
  repairAttempt?: number;
  title?: string;
  summary?: string;
  category?: string;
  tags?: string[];
  keywords?: string[];
  qualityScore?: number;
  errorMessage?: string;
  updatetime?: string;
};

export type KnowledgeDashboard = {
  spaceId: number;
  documentCount: number;
  indexedCount: number;
  profiledCount: number;
  failedProfileCount: number;
  needsReviewCount: number;
  averageQualityScore: number;
  categoryCount: number;
  tagCount: number;
  pendingTaskCount: number;
  runningTaskCount: number;
  failedTaskCount: number;
  categories?: KnowledgeFacet[];
  tags?: KnowledgeFacet[];
  recentFailedTasks?: KnowledgePipelineTask[];
};

export type KnowledgeProfile = {
  id: number;
  spaceId: number;
  documentId: number;
  spaceFileId: number;
  title?: string;
  summary?: string;
  keywords?: string[];
  tags?: string[];
  category?: string;
  language?: string;
  documentType?: string;
  qualityScore?: number;
  profileStatus?: string;
  qualityDetailJson?: string;
  qualityIssueJson?: string;
  scoreBeforeRepair?: number;
  scoreAfterRepair?: number;
  reviewStatus?: string;
  reviewReason?: string;
  sourceChunkCount?: number;
  sourceCharacterCount?: number;
  schemaValid?: boolean;
  repairAttempt?: number;
  repairReason?: string;
  profileVersion?: number;
  currentVersionId?: number;
  latestVersionId?: number;
  sourceFileHash?: string;
  sourceParserVersion?: string;
  profileSchemaVersion?: string;
  errorMessage?: string;
  questions?: string[];
  createtime?: string;
  updatetime?: string;
};

export type KnowledgeProfileVersion = {
  id: number;
  profileId: number;
  spaceId: number;
  documentId: number;
  versionNo: number;
  documentVersionId?: number | null;
  sourceType?: string;
  modelName?: string;
  promptVersion?: string;
  schemaVersion?: string;
  qualityScore?: number;
  profileSnapshot?: string;
  changeSummary?: string;
  createdBy?: number;
  createdTime?: string;
};

export type KnowledgeProfileDiff = {
  beforeVersionId?: number | null;
  afterVersionId?: number | null;
  summaryChanged?: boolean;
  categoryBefore?: string;
  categoryAfter?: string;
  tagsAdded?: string[];
  tagsRemoved?: string[];
  keywordsAdded?: string[];
  keywordsRemoved?: string[];
  questionsAdded?: string[];
  questionsRemoved?: string[];
  qualityScoreBefore?: string;
  qualityScoreAfter?: string;
};

export type AsyncTask = {
  id?: string | number;
  taskId?: string | number;
  name?: string;
  title?: string;
  type?: string;
  status?: string | number;
  phase?: string;
  progress?: number;
  total?: number;
  current?: number;
  message?: string;
  error?: string;
  errorMessage?: string;
  createTime?: string;
  updateTime?: string;
  createdAt?: string;
  updatedAt?: string;
};

export type RagCitation = {
  index?: number;
  spaceId?: number;
  spaceName?: string;
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

export type RagChatMessage = {
  role: "user" | "assistant" | "system";
  content: string;
};

export type RagQuery = {
  spaceId?: number;
  spaceName?: string;
  question: string;
  answer?: string;
  hitChunkIds?: number[];
  contexts?: string[];
  citations?: RagCitation[];
};

export type StorageQuota = {
  usedBytes: number;
  totalBytes: number;
  availableBytes: number;
  usagePercent: number;
  fileCount: number;
  policyName?: string;
};

export type KnowledgeRagQuery = {
  question: string;
  answer?: string;
  spaceIds: number[];
  results?: RagQuery[];
  citations?: RagCitation[];
};

export type KnowledgeChatMessage = {
  id: number;
  sessionId: number;
  role: "user" | "assistant" | "system";
  content: string;
  citationsJson?: string;
  createtime?: string;
};

export type KnowledgeChatSession = {
  id: number;
  userId: number;
  title: string;
  scopeMode?: string;
  spaceIds: number[];
  messageCount?: number;
  messages?: KnowledgeChatMessage[];
  createtime?: string;
  updatetime?: string;
};

export type RagAnalyticsSummary = {
  spaceId: number;
  queryCount: number;
  successCount: number;
  failedCount: number;
  noAnswerCount: number;
  citedQueryCount: number;
  citationCoverage: number;
};

export type RagQueryLog = {
  id: number;
  spaceId: number;
  userId?: number;
  question: string;
  answer?: string;
  hitChunkIds?: string;
  modelName?: string;
  topK?: number;
  temperature?: number;
  promptTokens?: number;
  completionTokens?: number;
  totalTokens?: number;
  success?: number;
  errorMessage?: string;
  citationCount?: number;
  createtime?: string;
};

export type RagConfigLog = {
  id: number;
  spaceId: number;
  operatorId?: number;
  changedFields?: string;
  beforeJson?: string;
  afterJson?: string;
  createtime?: string;
};
