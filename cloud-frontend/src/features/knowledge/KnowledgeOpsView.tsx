import { AlertCircle, BarChart3, CheckCircle2, FileText, FolderTree, History, Loader2, RefreshCw, RotateCcw, Tags, X } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { api } from "../../api";
import type { Notice } from "../../appTypes";
import type { KnowledgeDashboard, KnowledgeDocument, KnowledgeFacet, KnowledgePipelineEvent, KnowledgePipelineTask, KnowledgeProfile, KnowledgeProfileDiff, KnowledgeProfileVersion, Space } from "../../types";
import { formatTime } from "../../fileUtils";

type FilterMode = "all" | "category" | "tag" | "review" | "failed";

function statusText(status?: string) {
  if (status === "VALID") return "已画像";
  if (status === "SUCCESS") return "已画像";
  if (status === "NEEDS_REVIEW") return "待审核";
  if (status === "INVALID") return "不可用";
  if (status === "FAILED") return "失败";
  if (status === "PENDING") return "待生成";
  return status || "未知";
}

function reviewText(status?: string) {
  if (status === "NOT_REQUIRED") return "无需审查";
  if (status === "PENDING_REVIEW") return "待人工审查";
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

function StatTile({ label, value }: { label: string; value: string | number }) {
  return (
    <div className="knowledge-stat">
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

function FacetList({
  title,
  icon,
  items,
  active,
  onPick
}: {
  title: string;
  icon: React.ReactNode;
  items: KnowledgeFacet[];
  active: string;
  onPick: (value: string) => void;
}) {
  return (
    <section className="knowledge-facet-block">
      <h4>
        {icon}
        {title}
      </h4>
      <div className="knowledge-facet-list">
        {items.map((item) => (
          <button className={active === item.name ? "active" : ""} key={item.name} type="button" onClick={() => onPick(item.name)}>
            <span>{item.name}</span>
            <small>{item.count}</small>
          </button>
        ))}
        {!items.length && <p>暂无数据</p>}
      </div>
    </section>
  );
}

function KnowledgeTaskList({ tasks, onRetry }: { tasks: KnowledgePipelineTask[]; onRetry: (task: KnowledgePipelineTask) => void }) {
  return (
    <div className="knowledge-task-list">
      {tasks.map((task) => (
        <div className="knowledge-task-row" key={task.id}>
          <span className={`knowledge-status-dot ${(task.taskStatus || "").toLowerCase()}`} />
          <span>
            <strong>{task.taskType || "PROFILE"}</strong>
            <small>
              {task.stage || "PENDING"} · {task.successCount || 0}/{task.totalCount || 0} 成功
            </small>
            {(task.terminalReason || task.incrementalAction) && (
              <small>
                {task.terminalReason || "RUNNING"} · {task.incrementalAction || "NO_INCREMENTAL_DECISION"}
              </small>
            )}
            {task.incrementalDetail && <em>{task.incrementalDetail}</em>}
            {task.errorMessage && <em>{task.errorMessage}</em>}
          </span>
          <b>{taskStatusText(task.taskStatus)}</b>
          {(task.taskStatus === "FAILED" || task.taskStatus === "PARTIAL_SUCCESS") && (
            <button type="button" onClick={() => onRetry(task)}>
              重试
            </button>
          )}
        </div>
      ))}
      {!tasks.length && <p className="muted-line">暂无知识库运维任务</p>}
    </div>
  );
}

function parseJsonMap(json?: string) {
  if (!json) return {};
  try {
    return JSON.parse(json) as Record<string, number>;
  } catch {
    return {};
  }
}

function parseIssueCodes(json?: string) {
  if (!json) return [];
  try {
    const value = JSON.parse(json) as Array<{ code?: string; severity?: string; message?: string }>;
    return Array.isArray(value) ? value.slice(0, 6) : [];
  } catch {
    return [];
  }
}

function KnowledgeProfileDrawer({
  profile,
  versions,
  diff,
  events,
  onClose,
  onPickVersion,
  onRestore,
  onEdit,
  onRegenerate,
  onApprove
}: {
  profile: KnowledgeProfile;
  versions: KnowledgeProfileVersion[];
  diff: KnowledgeProfileDiff | null;
  events: KnowledgePipelineEvent[];
  onClose: () => void;
  onPickVersion: (version: KnowledgeProfileVersion) => void;
  onRestore: (version: KnowledgeProfileVersion) => void;
  onEdit: () => void;
  onRegenerate: () => void;
  onApprove: () => void;
}) {
  const scores = parseJsonMap(profile.qualityDetailJson);
  const issues = parseIssueCodes(profile.qualityIssueJson);
  return (
    <div className="knowledge-detail-backdrop" onClick={onClose}>
      <aside className="knowledge-detail-drawer" onClick={(event) => event.stopPropagation()}>
        <header>
          <div>
            <h3>{profile.title || "知识画像"}</h3>
            <p>v{profile.profileVersion || 1} · {statusText(profile.profileStatus)} · {reviewText(profile.reviewStatus)}</p>
          </div>
          <button type="button" onClick={onClose} aria-label="关闭">
            <X size={18} />
          </button>
        </header>
        <section className="knowledge-detail-section">
          <h4>操作</h4>
          <div className="knowledge-detail-actions">
            <button type="button" onClick={onEdit}>编辑画像</button>
            <button type="button" onClick={onRegenerate}>重新生成</button>
            {(profile.profileStatus === "NEEDS_REVIEW" || profile.reviewStatus === "PENDING_REVIEW") && (
              <button type="button" onClick={onApprove}>确认通过</button>
            )}
          </div>
        </section>
        <section className="knowledge-detail-section">
          <h4>画像</h4>
          <p>{profile.summary || "暂无摘要"}</p>
          <div className="knowledge-detail-grid">
            <span>分类</span><strong>{profile.category || "未分类"}</strong>
            <span>标签</span><strong>{(profile.tags || []).join(" / ") || "-"}</strong>
            <span>关键词</span><strong>{(profile.keywords || []).join(" / ") || "-"}</strong>
            <span>问题数</span><strong>{(profile.questions || []).length}</strong>
          </div>
        </section>
        <section className="knowledge-detail-section">
          <h4>质量</h4>
          <div className="knowledge-score-row">
            <strong>{Number(profile.qualityScore || 0).toFixed(1)}</strong>
            <span>修复前 {Number(profile.scoreBeforeRepair || 0).toFixed(1)} / 修复后 {Number(profile.scoreAfterRepair || 0).toFixed(1)}</span>
          </div>
          <div className="knowledge-score-chips">
            {Object.entries(scores).map(([name, value]) => <span key={name}>{name}: {value}</span>)}
          </div>
          <div className="knowledge-issue-list">
            {issues.map((issue) => <span key={`${issue.code}-${issue.message}`}>{issue.severity || "INFO"} · {issue.code || issue.message}</span>)}
            {!issues.length && <span>暂无质量问题</span>}
          </div>
        </section>
        <section className="knowledge-detail-section">
          <h4><History size={16} /> 版本</h4>
          <div className="knowledge-version-list">
            {versions.map((version) => (
              <button key={version.id} type="button" onClick={() => onPickVersion(version)}>
                <span>v{version.versionNo} · {version.sourceType}</span>
                <small>{Number(version.qualityScore || 0).toFixed(1)} · {formatTime(version.createdTime)}</small>
                <em>{version.changeSummary || "暂无说明"}</em>
              </button>
            ))}
            {!versions.length && <p>暂无版本</p>}
          </div>
        </section>
        {diff && (
          <section className="knowledge-detail-section">
            <h4>差异</h4>
            <div className="knowledge-detail-grid">
              <span>摘要</span><strong>{diff.summaryChanged ? "已变化" : "未变化"}</strong>
              <span>分类</span><strong>{diff.categoryBefore || "-"} → {diff.categoryAfter || "-"}</strong>
              <span>Tags +</span><strong>{(diff.tagsAdded || []).join(" / ") || "-"}</strong>
              <span>Tags -</span><strong>{(diff.tagsRemoved || []).join(" / ") || "-"}</strong>
              <span>问题 +</span><strong>{(diff.questionsAdded || []).join(" / ") || "-"}</strong>
              <span>质量</span><strong>{diff.qualityScoreBefore || "-"} → {diff.qualityScoreAfter || "-"}</strong>
            </div>
            {diff.afterVersionId && (
              <button type="button" className="soft-button" onClick={() => {
                const selected = versions.find((version) => version.id === diff.afterVersionId);
                if (selected) onRestore(selected);
              }}>
                恢复此版本
              </button>
            )}
          </section>
        )}
        <section className="knowledge-detail-section">
          <h4>Pipeline 历史</h4>
          <div className="knowledge-event-mini">
            {events.slice(0, 8).map((event) => <span key={event.id}>{event.stage} · {event.eventType} · {event.eventStatus}</span>)}
            {!events.length && <span>暂无 Pipeline 事件</span>}
          </div>
        </section>
      </aside>
    </div>
  );
}

export function KnowledgeOpsView({ showNotice }: { showNotice: (notice: Notice) => void }) {
  const [spaces, setSpaces] = useState<Space[]>([]);
  const [spaceId, setSpaceId] = useState<number | null>(null);
  const [dashboard, setDashboard] = useState<KnowledgeDashboard | null>(null);
  const [documents, setDocuments] = useState<KnowledgeDocument[]>([]);
  const [tasks, setTasks] = useState<KnowledgePipelineTask[]>([]);
  const [mode, setMode] = useState<FilterMode>("all");
  const [facetValue, setFacetValue] = useState("");
  const [loading, setLoading] = useState(false);
  const [detailProfile, setDetailProfile] = useState<KnowledgeProfile | null>(null);
  const [detailVersions, setDetailVersions] = useState<KnowledgeProfileVersion[]>([]);
  const [detailDiff, setDetailDiff] = useState<KnowledgeProfileDiff | null>(null);
  const [detailEvents, setDetailEvents] = useState<KnowledgePipelineEvent[]>([]);

  const activeSpace = useMemo(() => spaces.find((space) => space.id === spaceId) || null, [spaces, spaceId]);

  async function loadSpaces() {
    const next = await api.listSpaces();
    setSpaces(next || []);
    setSpaceId((current) => current || next?.[0]?.id || null);
  }

  async function loadKnowledge(targetSpaceId = spaceId, nextMode = mode, nextFacetValue = facetValue) {
    if (!targetSpaceId) return;
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
      showNotice({ type: "error", text: err instanceof Error ? err.message : "知识库运维数据加载失败" });
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void loadSpaces();
  }, []);

  useEffect(() => {
    void loadKnowledge(spaceId);
  }, [spaceId]);

  async function pickFilter(nextMode: FilterMode, value = "") {
    setMode(nextMode);
    setFacetValue(value);
    await loadKnowledge(spaceId,nextMode,value);
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

  async function classify(document: KnowledgeDocument) {
    if (!spaceId) return;
    const category = window.prompt("请输入分类", document.category || "");
    if (category === null) return;
    const tags = window.prompt("请输入标签，多个标签用逗号分隔", (document.tags || []).join(","));
    await runAction(
      () =>
        api.classifyKnowledgeDocument(spaceId,document.documentId,{
          category: category.trim() || "uncategorized",
          tags: tags === null ? document.tags : tags.split(",").map((item) => item.trim()).filter(Boolean)
        }),
      "分类已更新"
    );
  }

  async function openDetail(document: KnowledgeDocument) {
    if (!spaceId) return;
    try {
      const [profile, versions, taskList] = await Promise.all([
        api.getKnowledgeProfile(spaceId,document.documentId),
        api.listKnowledgeProfileVersions(spaceId,document.documentId),
        api.listKnowledgeTasks(spaceId)
      ]);
      setDetailProfile(profile);
      setDetailVersions(versions || []);
      setDetailDiff(null);
      const task = (taskList || []).find((item) => item.documentId === document.documentId);
      if (task) {
        const events = await api.listKnowledgeTaskEvents(spaceId,task.id);
        setDetailEvents(events || []);
      } else {
        setDetailEvents([]);
      }
    } catch (err) {
      showNotice({ type: "error", text: err instanceof Error ? err.message : "详情加载失败" });
    }
  }

  async function pickVersion(version: KnowledgeProfileVersion) {
    if (!spaceId || !detailProfile) return;
    const next = await api.diffKnowledgeProfileVersion(spaceId,detailProfile.documentId,version.id,detailProfile.currentVersionId || undefined);
    setDetailDiff(next);
  }

  async function restoreVersion(version: KnowledgeProfileVersion) {
    if (!spaceId || !detailProfile) return;
    await runAction(() => api.restoreKnowledgeProfileVersion(spaceId,detailProfile.documentId,version.id), "已恢复画像版本");
    setDetailProfile(null);
    setDetailVersions([]);
    setDetailDiff(null);
  }

  async function editDetailProfile() {
    if (!spaceId || !detailProfile) return;
    const summary = window.prompt("请输入摘要", detailProfile.summary || "");
    if (summary === null) return;
    const category = window.prompt("请输入分类", detailProfile.category || "uncategorized");
    if (category === null) return;
    const tags = window.prompt("请输入标签，多个标签用逗号分隔", (detailProfile.tags || []).join(","));
    await runAction(
      () => api.updateKnowledgeProfile(spaceId,detailProfile.documentId,{
        summary,
        category: category.trim() || "uncategorized",
        tags: tags === null ? detailProfile.tags : tags.split(",").map((item) => item.trim()).filter(Boolean),
        profileStatus: "VALID"
      }),
      "画像已更新"
    );
    setDetailProfile(null);
  }

  async function regenerateDetailProfile() {
    if (!spaceId || !detailProfile) return;
    await runAction(() => api.regenerateKnowledgeProfile(spaceId,detailProfile.documentId), "已提交画像重建");
    setDetailProfile(null);
  }

  async function approveDetailProfile() {
    if (!spaceId || !detailProfile) return;
    await runAction(() => api.markKnowledgeProfileReviewed(spaceId,detailProfile.documentId), "已确认画像");
    setDetailProfile(null);
  }

  return (
    <section className="knowledge-view">
      <header className="knowledge-toolbar">
        <div>
          <h2>知识库运维</h2>
          <p>按空间查看知识库文件、画像质量、标签分类和异步运维任务。</p>
        </div>
        <div className="knowledge-actions">
          <select value={spaceId || ""} onChange={(event) => setSpaceId(Number(event.target.value))}>
            {spaces.map((space) => (
              <option key={space.id} value={space.id}>
                {space.name}
              </option>
            ))}
          </select>
          <button className="icon-button" type="button" onClick={() => void loadKnowledge()} title="刷新">
            <RefreshCw size={18} />
          </button>
        </div>
      </header>

      {activeSpace && dashboard && (
        <div className="knowledge-dashboard">
          <StatTile label="文档" value={dashboard.documentCount} />
          <StatTile label="已索引" value={dashboard.indexedCount} />
          <StatTile label="已画像" value={dashboard.profiledCount} />
          <StatTile label="待审核" value={dashboard.needsReviewCount} />
          <StatTile label="画像失败" value={dashboard.failedProfileCount} />
          <StatTile label="平均质量" value={Number(dashboard.averageQualityScore || 0).toFixed(1)} />
        </div>
      )}

      <div className="knowledge-layout">
        <aside className="knowledge-left">
          <button className={mode === "all" ? "active" : ""} type="button" onClick={() => void pickFilter("all")}>
            <FileText size={16} />
            全部文档
          </button>
          <button className={mode === "review" ? "active" : ""} type="button" onClick={() => void pickFilter("review")}>
            <AlertCircle size={16} />
            待审核
          </button>
          <button className={mode === "failed" ? "active" : ""} type="button" onClick={() => void pickFilter("failed")}>
            <AlertCircle size={16} />
            失败
          </button>
          <FacetList
            active={mode === "category" ? facetValue : ""}
            icon={<FolderTree size={16} />}
            items={dashboard?.categories || []}
            title="分类"
            onPick={(value) => void pickFilter("category",value)}
          />
          <FacetList
            active={mode === "tag" ? facetValue : ""}
            icon={<Tags size={16} />}
            items={dashboard?.tags || []}
            title="标签"
            onPick={(value) => void pickFilter("tag",value)}
          />
        </aside>

        <section className="knowledge-main">
          <div className="knowledge-command-bar">
            <div>
              <strong>{activeSpace?.name || "知识库"}</strong>
              <span>{mode === "all" ? "全部文档" : facetValue || statusText(mode)}</span>
            </div>
            <button type="button" disabled={!spaceId} onClick={() => void runAction(() => api.runKnowledgePipeline(spaceId!), "已提交全空间画像重建任务")}>
              <RotateCcw size={16} />
              仅重建画像
            </button>
            <button type="button" disabled={!spaceId} onClick={() => void runAction(() => api.retryFailedKnowledgeTasks(spaceId!), "已提交失败任务重试")}>
              <RefreshCw size={16} />
              批量重试
            </button>
          </div>

          {loading ? (
            <div className="loading-state">
              <Loader2 className="spin" size={24} />
              正在加载知识库
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
                    <b className={`profile-state ${(document.profileStatus || "").toLowerCase()}`}>{statusText(document.profileStatus)}</b>
                    <small>{reviewText(document.reviewStatus)}</small>
                  </span>
                  <span>
                    <strong>{document.category || "uncategorized"}</strong>
                    <small>{document.reviewReason || (document.tags || []).join(" / ") || "暂无标签"}</small>
                  </span>
                  <span>
                    <strong>{Number(document.qualityScore || 0).toFixed(1)}</strong>
                    <small>{document.sourceChunkCount || 0} chunks · 修复 {document.repairAttempt || 0}</small>
                  </span>
                  <span>{formatTime(document.updatetime)}</span>
                  <span className="knowledge-row-actions">
                    <button type="button" onClick={() => void runAction(() => api.regenerateKnowledgeProfile(spaceId!,document.documentId), "已提交画像重建")}>
                      重建
                    </button>
                    <button type="button" onClick={() => void runAction(() => api.reclassifyKnowledgeDocument(spaceId!,document.documentId), "已提交重新分类")}>
                      重新分类
                    </button>
                    <button type="button" onClick={() => void classify(document)}>
                      手动分类
                    </button>
                    <button type="button" onClick={() => void openDetail(document)}>
                      详情
                    </button>
                    {(document.profileStatus === "NEEDS_REVIEW" || document.reviewStatus === "PENDING_REVIEW") && (
                      <button type="button" onClick={() => void runAction(() => api.markKnowledgeProfileReviewed(spaceId!,document.documentId), "已确认画像")}>
                        <CheckCircle2 size={14} />
                        确认
                      </button>
                    )}
                  </span>
                </div>
              ))}
              {!documents.length && <div className="empty-state compact-empty">暂无知识库文档</div>}
            </div>
          )}

          <section className="knowledge-task-panel">
            <h3>
              <BarChart3 size={18} />
              最近运维任务
            </h3>
            <KnowledgeTaskList
              tasks={tasks.slice(0,8)}
              onRetry={(task) => {
                if (!spaceId) return;
                void runAction(() => api.retryKnowledgeTask(spaceId,task.id), "已提交任务重试");
              }}
            />
          </section>
        </section>
      </div>
      {detailProfile && (
        <KnowledgeProfileDrawer
          profile={detailProfile}
          versions={detailVersions}
          diff={detailDiff}
          events={detailEvents}
          onClose={() => setDetailProfile(null)}
          onPickVersion={pickVersion}
          onRestore={restoreVersion}
          onEdit={editDetailProfile}
          onRegenerate={regenerateDetailProfile}
          onApprove={approveDetailProfile}
        />
      )}
    </section>
  );
}
