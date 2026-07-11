import { useEffect, useMemo, useState } from "react";
import {
  Activity,
  AlertCircle,
  BarChart3,
  BookOpen,
  CheckCircle2,
  Database,
  FileSearch,
  FileText,
  FolderTree,
  Loader2,
  RefreshCw,
  RotateCcw,
  Search,
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
  RagAnalyticsSummary,
  RagConfigLog,
  RagQueryLog,
  Space
} from "../../types";
import { formatTime } from "../../fileUtils";

type KnowledgeSection = "dashboard" | "documents" | "pipeline" | "analytics";
type FilterMode = "all" | "review" | "failed" | "category" | "tag";

const sections: Array<{ key: KnowledgeSection; label: string; icon: React.ReactNode }> = [
  { key: "dashboard", label: "知识库概览", icon: <BarChart3 size={17} /> },
  { key: "documents", label: "文档管理", icon: <FileText size={17} /> },
  { key: "pipeline", label: "索引任务", icon: <Activity size={17} /> },
  { key: "analytics", label: "检索分析", icon: <FileSearch size={17} /> }
];

function sectionFromPath(path: string): KnowledgeSection {
  if (path.includes("/knowledge/documents")) return "documents";
  if (path.includes("/knowledge/pipeline")) return "pipeline";
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

function AnalyticsView({
  summary,
  queries,
  noAnswers,
  configLogs,
  loading,
  error
}: {
  summary: RagAnalyticsSummary | null;
  queries: RagQueryLog[];
  noAnswers: RagQueryLog[];
  configLogs: RagConfigLog[];
  loading: boolean;
  error: string;
}) {
  return (
    <div className="kb-page">
      <div className="kb-stat-grid">
        <StatTile label="累计查询" value={summary?.queryCount ?? 0} icon={<FileSearch size={18} />} />
        <StatTile label="成功回答" value={summary?.successCount ?? 0} icon={<CheckCircle2 size={18} />} />
        <StatTile label="无答案" value={summary?.noAnswerCount ?? 0} icon={<AlertCircle size={18} />} />
        <StatTile label="引用覆盖率" value={`${Math.round((summary?.citationCoverage || 0) * 100)}%`} icon={<BookOpen size={18} />} />
      </div>
      {loading && <div className="kb-panel muted-line">正在读取检索日志...</div>}
      {error && <div className="form-error">{error}</div>}
      {!loading && !error && (
        <div className="kb-analytics-layout">
          <section className="kb-panel">
            <div className="kb-panel-head">
              <h3><FileSearch size={18} />最近查询</h3>
              <span>{queries.length} 条</span>
            </div>
            <div className="kb-query-log-list">
              {queries.slice(0, 20).map((query) => (
                <article key={query.id}>
                  <div>
                    <strong>{query.question}</strong>
                    <span>{formatTime(query.createtime)} · {query.modelName || "默认模型"}</span>
                  </div>
                  <p>{query.answer || query.errorMessage || "未生成回答"}</p>
                  <small>{query.citationCount || 0} 条引用 · Top K {query.topK ?? "-"}</small>
                </article>
              ))}
              {!queries.length && <p className="muted-line">还没有查询记录</p>}
            </div>
          </section>
          <div className="kb-analytics-side">
            <section className="kb-panel">
              <div className="kb-panel-head">
                <h3><AlertCircle size={18} />无答案问题</h3>
                <span>{noAnswers.length} 条</span>
              </div>
              <div className="kb-compact-log-list">
                {noAnswers.slice(0, 10).map((query) => (
                  <div key={query.id}>
                    <strong>{query.question}</strong>
                    <span>{formatTime(query.createtime)}</span>
                  </div>
                ))}
                {!noAnswers.length && <p className="muted-line">当前没有无答案记录</p>}
              </div>
            </section>
            <section className="kb-panel">
              <div className="kb-panel-head">
                <h3><Activity size={18} />配置变更</h3>
                <span>{configLogs.length} 条</span>
              </div>
              <div className="kb-compact-log-list">
                {configLogs.slice(0, 10).map((log) => (
                  <div key={log.id}>
                    <strong>{log.changedFields || "RAG 配置"}</strong>
                    <span>{formatTime(log.createtime)} · 操作人 {log.operatorId || "-"}</span>
                  </div>
                ))}
                {!configLogs.length && <p className="muted-line">还没有配置变更</p>}
              </div>
            </section>
          </div>
        </div>
      )}
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
  const [analyticsSummary, setAnalyticsSummary] = useState<RagAnalyticsSummary | null>(null);
  const [analyticsQueries, setAnalyticsQueries] = useState<RagQueryLog[]>([]);
  const [analyticsNoAnswers, setAnalyticsNoAnswers] = useState<RagQueryLog[]>([]);
  const [analyticsConfigLogs, setAnalyticsConfigLogs] = useState<RagConfigLog[]>([]);
  const [analyticsLoading, setAnalyticsLoading] = useState(false);
  const [analyticsError, setAnalyticsError] = useState("");

  const activeSpace = useMemo(() => spaces.find((space) => space.id === spaceId) || null, [spaces, spaceId]);
  const canViewAnalytics = activeSpace?.role === "OWNER" || activeSpace?.role === "ADMIN";
  const visibleSections = useMemo(
    () => sections.filter((item) => item.key !== "analytics" || canViewAnalytics),
    [canViewAnalytics]
  );

  useEffect(() => {
    const next = sectionFromPath(path);
    setSection(next);
  }, [path]);

  useEffect(() => {
    if (section !== "analytics" || !activeSpace || canViewAnalytics) return;
    setSection("dashboard");
    onNavigate("/knowledge/dashboard");
    showNotice({ type: "info", text: "只有知识库所有者或管理员可以查看检索分析" });
  }, [section, activeSpace?.id, activeSpace?.role, canViewAnalytics]);

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

  async function loadAnalytics(targetSpaceId = spaceId) {
    if (!targetSpaceId) {
      setAnalyticsSummary(null);
      setAnalyticsQueries([]);
      setAnalyticsNoAnswers([]);
      setAnalyticsConfigLogs([]);
      setAnalyticsError("请选择一个知识库查看检索分析");
      return;
    }
    setAnalyticsLoading(true);
    setAnalyticsError("");
    try {
      const [summary, queries, noAnswers, configLogs] = await Promise.all([
        api.ragAnalyticsSummary(targetSpaceId),
        api.ragAnalyticsQueries(targetSpaceId, 50),
        api.ragAnalyticsNoAnswer(targetSpaceId, 50),
        api.ragAnalyticsConfigLogs(targetSpaceId, 50)
      ]);
      setAnalyticsSummary(summary);
      setAnalyticsQueries(queries || []);
      setAnalyticsNoAnswers(noAnswers || []);
      setAnalyticsConfigLogs(configLogs || []);
    } catch (err) {
      setAnalyticsError(err instanceof Error ? err.message : "检索分析加载失败");
    } finally {
      setAnalyticsLoading(false);
    }
  }

  useEffect(() => {
    if (section === "analytics" && canViewAnalytics) void loadAnalytics(spaceId);
  }, [section, spaceId, canViewAnalytics]);

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

  return (
    <section className="kb-shell">
      <header className="kb-header">
        <div>
          <p>Knowledge Base</p>
          <h2>{visibleSections.find((item) => item.key === section)?.label || "知识库"}</h2>
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
          <button
            className="icon-button"
            type="button"
            onClick={() => section === "analytics" ? void loadAnalytics() : void loadKnowledge()}
            title="刷新知识库"
          >
            <RefreshCw size={18} />
          </button>
        </div>
      </header>

      <div className="kb-workspace">
        <nav className="kb-subnav" aria-label="Knowledge Base navigation">
          {visibleSections.map((item) => (
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
          {section === "analytics" && canViewAnalytics && (
            <AnalyticsView
              summary={analyticsSummary}
              queries={analyticsQueries}
              noAnswers={analyticsNoAnswers}
              configLogs={analyticsConfigLogs}
              loading={analyticsLoading}
              error={analyticsError}
            />
          )}
        </div>
      </div>

      {detailProfile && <ProfileDrawer profile={detailProfile} onClose={() => setDetailProfile(null)} />}
    </section>
  );
}
