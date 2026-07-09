import { FormEvent, useEffect, useMemo, useState } from "react";
import {
  Activity,
  AlertCircle,
  BarChart3,
  BookOpen,
  Bot,
  CheckCircle2,
  Database,
  FileSearch,
  FileText,
  FolderTree,
  Loader2,
  MessageSquare,
  RefreshCw,
  RotateCcw,
  Search,
  Send,
  Tags,
  X
} from "lucide-react";
import { api } from "../../api";
import type { Notice } from "../../appTypes";
import type {
  KnowledgeDashboard,
  KnowledgeDocument,
  KnowledgeFacet,
  KnowledgePipelineEvent,
  KnowledgePipelineTask,
  KnowledgeProfile,
  RagCitation,
  Space
} from "../../types";
import { formatTime } from "../../fileUtils";

type KnowledgeSection = "dashboard" | "documents" | "pipeline" | "chat" | "analytics";
type FilterMode = "all" | "review" | "failed" | "category" | "tag";

type ChatMessage = {
  id: string;
  role: "user" | "assistant";
  content: string;
  citations?: RagCitation[];
};

type ChatConversation = {
  id: string;
  title: string;
  messages: ChatMessage[];
  spaceId?: number;
  updatedAt: number;
};

const CHAT_STORAGE_KEY = "ylcloud_knowledge_chats";

const sections: Array<{ key: KnowledgeSection; label: string; icon: React.ReactNode }> = [
  { key: "dashboard", label: "知识库概览", icon: <BarChart3 size={17} /> },
  { key: "documents", label: "文档管理", icon: <FileText size={17} /> },
  { key: "pipeline", label: "索引任务", icon: <Activity size={17} /> },
  { key: "chat", label: "智能问答", icon: <Bot size={17} /> },
  { key: "analytics", label: "检索分析", icon: <FileSearch size={17} /> }
];

function sectionFromPath(path: string): KnowledgeSection {
  if (path === "/chat") return "chat";
  if (path.includes("/knowledge/documents")) return "documents";
  if (path.includes("/knowledge/pipeline")) return "pipeline";
  if (path.includes("/knowledge/chat")) return "chat";
  if (path.includes("/knowledge/analytics")) return "analytics";
  return "dashboard";
}

function statusText(status?: string) {
  if (status === "VALID" || status === "SUCCESS") return "已画像";
  if (status === "NEEDS_REVIEW") return "待审核";
  if (status === "INVALID") return "不可用";
  if (status === "FAILED") return "失败";
  if (status === "PENDING") return "待生成";
  return status || "未知";
}

function reviewText(status?: string) {
  if (status === "NOT_REQUIRED") return "无需审核";
  if (status === "PENDING_REVIEW") return "待人工审核";
  if (status === "APPROVED") return "已确认";
  if (status === "REJECTED") return "已驳回";
  if (status === "AUTO_FIXED") return "自动修复";
  return status || "未记录";
}

function taskStatusText(status?: string) {
  if (status === "SUCCESS") return "完成";
  if (status === "PARTIAL_SUCCESS") return "部分完成";
  if (status === "FAILED") return "失败";
  if (status === "RUNNING") return "执行中";
  return "等待中";
}

function loadConversations(): ChatConversation[] {
  const raw = localStorage.getItem(CHAT_STORAGE_KEY);
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw) as ChatConversation[];
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

function saveConversations(items: ChatConversation[]) {
  localStorage.setItem(CHAT_STORAGE_KEY, JSON.stringify(items.slice(0, 30)));
}

function StatTile({ label, value, icon }: { label: string; value: string | number; icon: React.ReactNode }) {
  return (
    <div className="kb-stat-card">
      <span>{icon}</span>
      <small>{label}</small>
      <strong>{value}</strong>
    </div>
  );
}

function FacetPills({ title, icon, items }: { title: string; icon: React.ReactNode; items: KnowledgeFacet[] }) {
  return (
    <section className="kb-panel">
      <div className="kb-panel-head">
        <h3>
          {icon}
          {title}
        </h3>
        <span>{items.length} 项</span>
      </div>
      <div className="kb-chip-list">
        {items.slice(0, 12).map((item) => (
          <span key={item.name}>
            {item.name}
            <b>{item.count}</b>
          </span>
        ))}
        {!items.length && <p className="muted-line">暂无数据</p>}
      </div>
    </section>
  );
}

function ProfileDrawer({ profile, onClose }: { profile: KnowledgeProfile; onClose: () => void }) {
  return (
    <div className="knowledge-detail-backdrop" onClick={onClose}>
      <aside className="knowledge-detail-drawer" onClick={(event) => event.stopPropagation()}>
        <header>
          <div>
            <h3>{profile.title || "知识画像"}</h3>
            <p>
              v{profile.profileVersion || 1} · {statusText(profile.profileStatus)} · {reviewText(profile.reviewStatus)}
            </p>
          </div>
          <button type="button" onClick={onClose} aria-label="关闭详情">
            <X size={18} />
          </button>
        </header>
        <section className="knowledge-detail-section">
          <h4>摘要</h4>
          <p>{profile.summary || "暂无摘要"}</p>
        </section>
        <section className="knowledge-detail-section">
          <h4>标签与分类</h4>
          <div className="knowledge-detail-grid">
            <span>分类</span>
            <strong>{profile.category || "未分类"}</strong>
            <span>标签</span>
            <strong>{(profile.tags || []).join(" / ") || "-"}</strong>
            <span>关键词</span>
            <strong>{(profile.keywords || []).join(" / ") || "-"}</strong>
            <span>问题数</span>
            <strong>{(profile.questions || []).length}</strong>
          </div>
        </section>
        <section className="knowledge-detail-section">
          <h4>质量</h4>
          <div className="knowledge-score-row">
            <strong>{Number(profile.qualityScore || 0).toFixed(1)}</strong>
            <span>
              修复前 {Number(profile.scoreBeforeRepair || 0).toFixed(1)} / 修复后{" "}
              {Number(profile.scoreAfterRepair || 0).toFixed(1)}
            </span>
          </div>
          {profile.errorMessage && <p>{profile.errorMessage}</p>}
        </section>
      </aside>
    </div>
  );
}

function DashboardView({
  dashboard,
  tasks,
  documents,
  loading
}: {
  dashboard: KnowledgeDashboard | null;
  tasks: KnowledgePipelineTask[];
  documents: KnowledgeDocument[];
  loading: boolean;
}) {
  if (loading && !dashboard) {
    return (
      <div className="loading-state">
        <Loader2 className="spin" size={24} />
        正在加载知识库概览
      </div>
    );
  }

  return (
    <div className="kb-page">
      <div className="kb-stat-grid">
        <StatTile label="文档" value={dashboard?.documentCount ?? documents.length} icon={<FileText size={18} />} />
        <StatTile label="已索引" value={dashboard?.indexedCount ?? 0} icon={<Database size={18} />} />
        <StatTile label="待审核" value={dashboard?.needsReviewCount ?? 0} icon={<AlertCircle size={18} />} />
        <StatTile label="平均质量" value={Number(dashboard?.averageQualityScore || 0).toFixed(1)} icon={<CheckCircle2 size={18} />} />
        <StatTile label="运行中任务" value={dashboard?.runningTaskCount ?? 0} icon={<Activity size={18} />} />
        <StatTile label="失败任务" value={dashboard?.failedTaskCount ?? 0} icon={<RotateCcw size={18} />} />
      </div>

      <div className="kb-two-col">
        <FacetPills title="分类" icon={<FolderTree size={18} />} items={dashboard?.categories || []} />
        <FacetPills title="标签" icon={<Tags size={18} />} items={dashboard?.tags || []} />
      </div>

      <section className="kb-panel">
        <div className="kb-panel-head">
          <h3>
            <Activity size={18} />
            最近任务
          </h3>
          <span>{tasks.length} 条</span>
        </div>
        <div className="kb-task-list">
          {tasks.slice(0, 6).map((task) => (
            <div className="kb-task-row" key={task.id}>
              <span className={`knowledge-status-dot ${(task.taskStatus || "").toLowerCase()}`} />
              <strong>{task.taskType || "PROFILE"}</strong>
              <small>{task.stage || task.terminalStage || "PENDING"}</small>
              <b>{taskStatusText(task.taskStatus)}</b>
            </div>
          ))}
          {!tasks.length && <p className="muted-line">暂无任务</p>}
        </div>
      </section>
    </div>
  );
}

function DocumentsView({
  documents,
  dashboard,
  mode,
  facetValue,
  loading,
  onPickFilter,
  onRunAction,
  onOpenProfile
}: {
  documents: KnowledgeDocument[];
  dashboard: KnowledgeDashboard | null;
  mode: FilterMode;
  facetValue: string;
  loading: boolean;
  onPickFilter: (mode: FilterMode, value?: string) => void;
  onRunAction: (action: () => Promise<unknown>, success: string) => void;
  onOpenProfile: (document: KnowledgeDocument) => void;
}) {
  return (
    <div className="kb-documents-layout">
      <aside className="kb-filter-panel">
        <button className={mode === "all" ? "active" : ""} type="button" onClick={() => onPickFilter("all")}>
          <FileText size={16} />
          全部文档
        </button>
        <button className={mode === "review" ? "active" : ""} type="button" onClick={() => onPickFilter("review")}>
          <AlertCircle size={16} />
          待审核
        </button>
        <button className={mode === "failed" ? "active" : ""} type="button" onClick={() => onPickFilter("failed")}>
          <AlertCircle size={16} />
          失败
        </button>
        <h4>分类</h4>
        {(dashboard?.categories || []).map((item) => (
          <button
            className={mode === "category" && facetValue === item.name ? "active" : ""}
            key={item.name}
            type="button"
            onClick={() => onPickFilter("category", item.name)}
          >
            <span>{item.name}</span>
            <small>{item.count}</small>
          </button>
        ))}
        <h4>标签</h4>
        {(dashboard?.tags || []).map((item) => (
          <button
            className={mode === "tag" && facetValue === item.name ? "active" : ""}
            key={item.name}
            type="button"
            onClick={() => onPickFilter("tag", item.name)}
          >
            <span>{item.name}</span>
            <small>{item.count}</small>
          </button>
        ))}
      </aside>

      <section className="kb-panel kb-table-panel">
        <div className="kb-panel-head">
          <h3>
            <FileText size={18} />
            文档管理
          </h3>
          <span>{documents.length} 条</span>
        </div>
        {loading ? (
          <div className="loading-state compact-loading">
            <Loader2 className="spin" size={22} />
            正在加载文档
          </div>
        ) : (
          <div className="knowledge-file-table">
            <div className="knowledge-file-row head">
              <span>文件</span>
              <span>状态</span>
              <span>分类/标签</span>
              <span>质量</span>
              <span>更新时间</span>
              <span>操作</span>
            </div>
            {documents.map((document) => (
              <div className="knowledge-file-row" key={document.documentId}>
                <span>
                  <FileText size={18} />
                  <span>
                    <strong>{document.fileName}</strong>
                    <small>{document.fileType || "document"}</small>
                  </span>
                </span>
                <span>
                  <b className={`profile-state ${(document.profileStatus || "").toLowerCase()}`}>
                    {statusText(document.profileStatus)}
                  </b>
                  <small>{reviewText(document.reviewStatus)}</small>
                </span>
                <span>
                  <strong>{document.category || "uncategorized"}</strong>
                  <small>{(document.tags || []).join(" / ") || document.reviewReason || "暂无标签"}</small>
                </span>
                <span>
                  <strong>{Number(document.qualityScore || 0).toFixed(1)}</strong>
                  <small>{document.sourceChunkCount || document.chunkCount || 0} chunks</small>
                </span>
                <span>{formatTime(document.updatetime)}</span>
                <span className="knowledge-row-actions">
                  <button
                    type="button"
                    onClick={() => onRunAction(() => api.regenerateKnowledgeProfile(document.spaceId, document.documentId), "已提交画像重建")}
                  >
                    重建
                  </button>
                  <button
                    type="button"
                    onClick={() => onRunAction(() => api.reclassifyKnowledgeDocument(document.spaceId, document.documentId), "已提交重新分类")}
                  >
                    分类
                  </button>
                  <button type="button" onClick={() => onOpenProfile(document)}>
                    详情
                  </button>
                </span>
              </div>
            ))}
            {!documents.length && <div className="empty-state compact-empty">暂无知识库文档</div>}
          </div>
        )}
      </section>
    </div>
  );
}

function PipelineView({
  tasks,
  events,
  loading,
  selectedTaskId,
  onSelectTask,
  onRunAction,
  spaceId
}: {
  tasks: KnowledgePipelineTask[];
  events: KnowledgePipelineEvent[];
  loading: boolean;
  selectedTaskId: number | null;
  onSelectTask: (task: KnowledgePipelineTask) => void;
  onRunAction: (action: () => Promise<unknown>, success: string) => void;
  spaceId: number | null;
}) {
  return (
    <div className="kb-pipeline-layout">
      <section className="kb-panel">
        <div className="kb-panel-head">
          <h3>
            <Activity size={18} />
            索引任务
          </h3>
          <div className="knowledge-actions">
            <button
              type="button"
              disabled={!spaceId}
              onClick={() => spaceId && onRunAction(() => api.runKnowledgePipeline(spaceId), "已提交全量知识画像任务")}
            >
              <RotateCcw size={16} />
              重建
            </button>
            <button
              type="button"
              disabled={!spaceId}
              onClick={() => spaceId && onRunAction(() => api.retryFailedKnowledgeTasks(spaceId), "已提交失败任务重试")}
            >
              <RefreshCw size={16} />
              重试失败
            </button>
          </div>
        </div>
        {loading ? (
          <div className="loading-state compact-loading">
            <Loader2 className="spin" size={22} />
            正在加载任务
          </div>
        ) : (
          <div className="kb-task-list">
            {tasks.map((task) => (
              <button
                className={`kb-task-row as-button ${selectedTaskId === task.id ? "active" : ""}`}
                key={task.id}
                type="button"
                onClick={() => onSelectTask(task)}
              >
                <span className={`knowledge-status-dot ${(task.taskStatus || "").toLowerCase()}`} />
                <strong>{task.documentId ? `文档 #${task.documentId}` : "空间任务"}</strong>
                <small>{task.stage || task.terminalStage || "PENDING"}</small>
                <b>{taskStatusText(task.taskStatus)}</b>
              </button>
            ))}
            {!tasks.length && <p className="muted-line">暂无索引任务</p>}
          </div>
        )}
      </section>

      <section className="kb-panel">
        <div className="kb-panel-head">
          <h3>
            <Search size={18} />
            任务事件
          </h3>
          <span>{events.length} 条</span>
        </div>
        <div className="knowledge-event-timeline">
          {events.map((event) => (
            <div className={`knowledge-event-item ${(event.eventStatus || "").toLowerCase()}`} key={event.id}>
              <span />
              <div>
                <strong>{event.stage || event.eventType}</strong>
                <small>
                  {event.eventType || "-"} · {event.eventStatus || "-"}
                </small>
                {(event.errorMessage || event.outputSummary || event.message) && (
                  <p>{event.errorMessage || event.outputSummary || event.message}</p>
                )}
              </div>
            </div>
          ))}
          {!events.length && <p className="muted-line">选择一个任务后查看事件详情</p>}
        </div>
      </section>
    </div>
  );
}

function ChatView({
  spaces,
  activeSpace,
  conversations,
  activeConversation,
  input,
  loading,
  onNewChat,
  onPickConversation,
  onInput,
  onSubmit,
  onPickSpace
}: {
  spaces: Space[];
  activeSpace: Space | null;
  conversations: ChatConversation[];
  activeConversation: ChatConversation;
  input: string;
  loading: boolean;
  onNewChat: () => void;
  onPickConversation: (id: string) => void;
  onInput: (value: string) => void;
  onSubmit: (event: FormEvent<HTMLFormElement>) => void;
  onPickSpace: (spaceId: number | null) => void;
}) {
  const hasMessages = activeConversation.messages.length > 0;

  return (
    <section className="kb-chat-page">
      <aside className="kb-chat-sidebar">
        <button className="primary-button full" type="button" onClick={onNewChat}>
          <MessageSquare size={17} />
          新对话
        </button>
        <label>
          知识库范围
          <select value={activeSpace?.id || ""} onChange={(event) => onPickSpace(event.target.value ? Number(event.target.value) : null)}>
            <option value="">不选择</option>
            {spaces.map((space) => (
              <option key={space.id} value={space.id}>
                {space.name}
              </option>
            ))}
          </select>
        </label>
        <div className="kb-chat-history">
          <strong>最近</strong>
          {conversations.map((conversation) => (
            <button
              className={conversation.id === activeConversation.id ? "active" : ""}
              key={conversation.id}
              type="button"
              onClick={() => onPickConversation(conversation.id)}
            >
              {conversation.title}
            </button>
          ))}
        </div>
      </aside>

      <div className={`kb-chat-canvas ${hasMessages ? "with-thread" : ""}`}>
        {!hasMessages ? (
          <div className="kb-chat-empty">
            <h2>今天想查什么资料？</h2>
            <p>{activeSpace ? `当前范围：${activeSpace.name}` : "选择一个知识库后，可以基于文档内容提问。"}</p>
            <form className="kb-center-composer" onSubmit={onSubmit}>
              <BookOpen size={20} />
              <input value={input} onChange={(event) => onInput(event.target.value)} placeholder="向知识库提问" />
              <span>{activeSpace?.name || "未选择"}</span>
              <button type="submit" disabled={loading || !input.trim()} aria-label="发送">
                {loading ? <Loader2 className="spin" size={18} /> : <Send size={18} />}
              </button>
            </form>
            <div className="kb-quick-actions">
              <button type="button" onClick={() => onInput("总结当前知识库的核心内容")}>总结文档</button>
              <button type="button" onClick={() => onInput("查找和这个问题相关的资料：")}>查找资料</button>
              <button type="button" onClick={() => onInput("提取最近导入文档的要点")}>提取要点</button>
            </div>
          </div>
        ) : (
          <>
            <div className="kb-message-thread">
              {activeConversation.messages.map((message) => (
                <article className={`kb-message ${message.role}`} key={message.id}>
                  <div className="kb-message-avatar">{message.role === "user" ? "我" : "AI"}</div>
                  <div>
                    <p>{message.content}</p>
                    {!!message.citations?.length && (
                      <div className="citation-list">
                        {message.citations.map((citation, index) => (
                          <span key={`${citation.chunkId}-${index}`}>{citation.fileName || `引用 ${index + 1}`}</span>
                        ))}
                      </div>
                    )}
                  </div>
                </article>
              ))}
              {loading && (
                <article className="kb-message assistant">
                  <div className="kb-message-avatar">AI</div>
                  <div>
                    <p>
                      <Loader2 className="spin inline-spinner" size={16} />
                      正在检索知识库
                    </p>
                  </div>
                </article>
              )}
            </div>
            <form className="kb-dock-composer" onSubmit={onSubmit}>
              <BookOpen size={18} />
              <input value={input} onChange={(event) => onInput(event.target.value)} placeholder="继续提问" />
              <span>{activeSpace?.name || "未选择"}</span>
              <button type="submit" disabled={loading || !input.trim()} aria-label="发送">
                {loading ? <Loader2 className="spin" size={18} /> : <Send size={18} />}
              </button>
            </form>
          </>
        )}
      </div>
    </section>
  );
}

function AnalyticsView({
  dashboard,
  documents,
  tasks
}: {
  dashboard: KnowledgeDashboard | null;
  documents: KnowledgeDocument[];
  tasks: KnowledgePipelineTask[];
}) {
  const failedDocs = documents.filter((doc) => doc.profileStatus === "FAILED" || doc.profileStatus === "INVALID");
  const lowQualityDocs = documents.filter((doc) => Number(doc.qualityScore || 0) > 0 && Number(doc.qualityScore || 0) < 60);

  return (
    <div className="kb-page">
      <div className="kb-stat-grid">
        <StatTile label="分类覆盖" value={dashboard?.categoryCount ?? 0} icon={<FolderTree size={18} />} />
        <StatTile label="标签覆盖" value={dashboard?.tagCount ?? 0} icon={<Tags size={18} />} />
        <StatTile label="低质量文档" value={lowQualityDocs.length} icon={<AlertCircle size={18} />} />
        <StatTile label="失败文档" value={failedDocs.length} icon={<RotateCcw size={18} />} />
      </div>
      <section className="kb-panel">
        <div className="kb-panel-head">
          <h3>
            <FileSearch size={18} />
            检索分析
          </h3>
          <span>基于现有 dashboard 和文档状态聚合</span>
        </div>
        <div className="kb-analysis-grid">
          <div>
            <strong>待补充能力</strong>
            <p>当前后端尚未提供查询日志、低分查询、无答案问题、引用覆盖率等专用分析接口。</p>
          </div>
          <div>
            <strong>可用信号</strong>
            <p>可以先通过文档质量、失败任务、分类和标签覆盖度判断知识库健康度。</p>
          </div>
          <div>
            <strong>建议动作</strong>
            <p>优先处理失败画像、低质量文档和待审核内容，再观察问答命中质量。</p>
          </div>
        </div>
      </section>
      <section className="kb-panel">
        <div className="kb-panel-head">
          <h3>异常项</h3>
          <span>{failedDocs.length + tasks.filter((task) => task.taskStatus === "FAILED").length} 条</span>
        </div>
        <div className="kb-task-list">
          {failedDocs.slice(0, 8).map((doc) => (
            <div className="kb-task-row" key={doc.documentId}>
              <span className="knowledge-status-dot failed" />
              <strong>{doc.fileName}</strong>
              <small>{doc.errorMessage || doc.reviewReason || "画像异常"}</small>
              <b>{statusText(doc.profileStatus)}</b>
            </div>
          ))}
          {!failedDocs.length && <p className="muted-line">暂无异常文档</p>}
        </div>
      </section>
    </div>
  );
}

export function KnowledgeBaseView({
  path,
  onNavigate,
  showNotice
}: {
  path: string;
  onNavigate: (path: string) => void;
  showNotice: (notice: Notice) => void;
}) {
  const [section, setSection] = useState<KnowledgeSection>(() => sectionFromPath(path));
  const [spaces, setSpaces] = useState<Space[]>([]);
  const [spaceId, setSpaceId] = useState<number | null>(null);
  const [dashboard, setDashboard] = useState<KnowledgeDashboard | null>(null);
  const [documents, setDocuments] = useState<KnowledgeDocument[]>([]);
  const [tasks, setTasks] = useState<KnowledgePipelineTask[]>([]);
  const [events, setEvents] = useState<KnowledgePipelineEvent[]>([]);
  const [selectedTaskId, setSelectedTaskId] = useState<number | null>(null);
  const [mode, setMode] = useState<FilterMode>("all");
  const [facetValue, setFacetValue] = useState("");
  const [loading, setLoading] = useState(false);
  const [detailProfile, setDetailProfile] = useState<KnowledgeProfile | null>(null);
  const [conversations, setConversations] = useState<ChatConversation[]>(() => loadConversations());
  const [activeConversationId, setActiveConversationId] = useState("");
  const [chatInput, setChatInput] = useState("");
  const [chatLoading, setChatLoading] = useState(false);

  const activeSpace = useMemo(() => spaces.find((space) => space.id === spaceId) || null, [spaces, spaceId]);

  const activeConversation = useMemo(() => {
    const existing = conversations.find((item) => item.id === activeConversationId);
    if (existing) return existing;
    return {
      id: "draft",
      title: "新对话",
      messages: [],
      spaceId: spaceId || undefined,
      updatedAt: Date.now()
    };
  }, [activeConversationId, conversations, spaceId]);

  useEffect(() => {
    const next = sectionFromPath(path);
    setSection(next);
  }, [path]);

  useEffect(() => {
    api.listSpaces()
      .then((items) => {
        const next = items || [];
        setSpaces(next);
        setSpaceId((current) => current || next[0]?.id || null);
      })
      .catch((err) => showNotice({ type: "error", text: err instanceof Error ? err.message : "知识库列表加载失败" }));
  }, []);

  async function loadKnowledge(targetSpaceId = spaceId, nextMode = mode, nextFacetValue = facetValue) {
    if (!targetSpaceId) {
      setDashboard(null);
      setDocuments([]);
      setTasks([]);
      return;
    }
    setLoading(true);
    try {
      const filter =
        nextMode === "category"
          ? { category: nextFacetValue }
          : nextMode === "tag"
            ? { tag: nextFacetValue }
            : nextMode === "review"
              ? { profileStatus: "NEEDS_REVIEW" }
              : nextMode === "failed"
                ? { profileStatus: "INVALID" }
                : {};
      const [nextDashboard, nextDocuments, nextTasks] = await Promise.all([
        api.knowledgeDashboard(targetSpaceId),
        api.listKnowledgeDocuments(targetSpaceId, filter),
        api.listKnowledgeTasks(targetSpaceId)
      ]);
      setDashboard(nextDashboard);
      setDocuments(nextDocuments || []);
      setTasks(nextTasks || []);
    } catch (err) {
      showNotice({ type: "error", text: err instanceof Error ? err.message : "知识库数据加载失败" });
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void loadKnowledge(spaceId);
  }, [spaceId]);

  useEffect(() => {
    saveConversations(conversations);
  }, [conversations]);

  function navigateSection(next: KnowledgeSection) {
    setSection(next);
    onNavigate(`/knowledge/${next}`);
  }

  function pickFilter(nextMode: FilterMode, value = "") {
    setMode(nextMode);
    setFacetValue(value);
    void loadKnowledge(spaceId, nextMode, value);
  }

  async function runAction(action: () => Promise<unknown>, success: string) {
    try {
      await action();
      showNotice({ type: "success", text: success });
      await loadKnowledge();
    } catch (err) {
      showNotice({ type: "error", text: err instanceof Error ? err.message : "操作失败" });
    }
  }

  async function openProfile(document: KnowledgeDocument) {
    try {
      setDetailProfile(await api.getKnowledgeProfile(document.spaceId, document.documentId));
    } catch (err) {
      showNotice({ type: "error", text: err instanceof Error ? err.message : "详情加载失败" });
    }
  }

  async function pickTask(task: KnowledgePipelineTask) {
    setSelectedTaskId(task.id);
    try {
      setEvents(await api.listKnowledgeTaskEvents(task.spaceId, task.id));
    } catch {
      setEvents([]);
    }
  }

  function newChat() {
    const conversation: ChatConversation = {
      id: `chat-${Date.now()}`,
      title: "新对话",
      messages: [],
      spaceId: spaceId || undefined,
      updatedAt: Date.now()
    };
    setConversations((current) => [conversation, ...current]);
    setActiveConversationId(conversation.id);
    setChatInput("");
  }

  function updateConversation(next: ChatConversation) {
    setConversations((current) => {
      const rest = current.filter((item) => item.id !== next.id);
      return [next, ...rest];
    });
    setActiveConversationId(next.id);
  }

  async function submitChat(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const question = chatInput.trim();
    if (!question) return;

    const base =
      activeConversation.id === "draft"
        ? {
            ...activeConversation,
            id: `chat-${Date.now()}`,
            title: question.slice(0, 24),
            spaceId: spaceId || undefined
          }
        : activeConversation;
    const userMessage: ChatMessage = { id: `u-${Date.now()}`, role: "user", content: question };
    const pending = {
      ...base,
      title: base.title === "新对话" ? question.slice(0, 24) : base.title,
      messages: [...base.messages, userMessage],
      updatedAt: Date.now()
    };
    updateConversation(pending);
    setChatInput("");

    if (!spaceId) {
      updateConversation({
        ...pending,
        messages: [
          ...pending.messages,
          {
            id: `a-${Date.now()}`,
            role: "assistant",
            content: "当前未选择知识库。请选择一个 Knowledge Base 范围后再提问。"
          }
        ],
        updatedAt: Date.now()
      });
      return;
    }

    setChatLoading(true);
    try {
      const history = pending.messages
        .slice(-8)
        .filter((message) => message.content.trim())
        .map((message) => ({ role: message.role, content: message.content.trim() }));
      const answer = await api.queryRag(spaceId, question, undefined, history);
      updateConversation({
        ...pending,
        messages: [
          ...pending.messages,
          {
            id: `a-${Date.now()}`,
            role: "assistant",
            content: answer.answer || "暂无回答",
            citations: answer.citations || []
          }
        ],
        updatedAt: Date.now()
      });
    } catch (err) {
      showNotice({ type: "error", text: err instanceof Error ? err.message : "知识库问答失败" });
      updateConversation({
        ...pending,
        messages: [...pending.messages, { id: `a-${Date.now()}`, role: "assistant", content: "这次查询失败了，请稍后重试。" }],
        updatedAt: Date.now()
      });
    } finally {
      setChatLoading(false);
    }
  }

  return (
    <section className="kb-shell">
      <header className="kb-header">
        <div>
          <p>Knowledge Base</p>
          <h2>{sections.find((item) => item.key === section)?.label || "知识库"}</h2>
        </div>
        <div className="kb-header-actions">
          <select value={spaceId || ""} onChange={(event) => setSpaceId(event.target.value ? Number(event.target.value) : null)}>
            <option value="">选择知识库</option>
            {spaces.map((space) => (
              <option key={space.id} value={space.id}>
                {space.name}
              </option>
            ))}
          </select>
          <button className="icon-button" type="button" onClick={() => void loadKnowledge()} title="刷新知识库">
            <RefreshCw size={18} />
          </button>
        </div>
      </header>

      <div className="kb-workspace">
        <nav className="kb-subnav" aria-label="Knowledge Base navigation">
          {sections.map((item) => (
            <button className={section === item.key ? "active" : ""} key={item.key} type="button" onClick={() => navigateSection(item.key)}>
              {item.icon}
              {item.label}
            </button>
          ))}
        </nav>

        <div className="kb-content">
          {section === "dashboard" && <DashboardView dashboard={dashboard} tasks={tasks} documents={documents} loading={loading} />}
          {section === "documents" && (
            <DocumentsView
              dashboard={dashboard}
              documents={documents}
              facetValue={facetValue}
              loading={loading}
              mode={mode}
              onOpenProfile={(document) => void openProfile(document)}
              onPickFilter={pickFilter}
              onRunAction={(action, success) => void runAction(action, success)}
            />
          )}
          {section === "pipeline" && (
            <PipelineView
              events={events}
              loading={loading}
              selectedTaskId={selectedTaskId}
              spaceId={spaceId}
              tasks={tasks}
              onRunAction={(action, success) => void runAction(action, success)}
              onSelectTask={(task) => void pickTask(task)}
            />
          )}
          {section === "chat" && (
            <ChatView
              activeConversation={activeConversation}
              activeSpace={activeSpace}
              conversations={conversations}
              input={chatInput}
              loading={chatLoading}
              spaces={spaces}
              onInput={setChatInput}
              onNewChat={newChat}
              onPickConversation={setActiveConversationId}
              onPickSpace={setSpaceId}
              onSubmit={(event) => void submitChat(event)}
            />
          )}
          {section === "analytics" && <AnalyticsView dashboard={dashboard} documents={documents} tasks={tasks} />}
        </div>
      </div>

      {detailProfile && <ProfileDrawer profile={detailProfile} onClose={() => setDetailProfile(null)} />}
    </section>
  );
}
