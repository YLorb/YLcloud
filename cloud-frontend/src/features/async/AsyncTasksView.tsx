import { AlertCircle, CheckCircle2, Clock3, Loader2, RefreshCw, RotateCcw, X } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { api } from "../../api";
import type { Notice } from "../../appTypes";
import type { AsyncTask, KnowledgePipelineEvent, KnowledgePipelineTask, Space } from "../../types";
import { formatTime } from "../../fileUtils";

type TaskState = "pending" | "running" | "success" | "failed";

type NormalizedAsyncTask = {
  id: string;
  title: string;
  type: string;
  state: TaskState;
  phase: string;
  progress: number;
  message: string;
  error: string;
  createTime?: string;
  updateTime?: string;
  raw: AsyncTask;
  source?: "async" | "knowledge";
  spaceId?: number;
  taskId?: number;
  retryable?: boolean;
};

function pickTasks(payload: AsyncTask[] | { records?: AsyncTask[]; list?: AsyncTask[]; items?: AsyncTask[]; tasks?: AsyncTask[] }) {
  if (Array.isArray(payload)) return payload;
  return payload.tasks || payload.items || payload.records || payload.list || [];
}

function normalizeStatus(status?: string | number): TaskState {
  if (status === 0) return "pending";
  if (status === 1) return "running";
  if (status === 2) return "success";
  if (status === 3) return "failed";
  const value = String(status || "").toUpperCase();
  if (["SUCCESS", "DONE", "FINISHED", "COMPLETED", "COMPLETE"].includes(value)) return "success";
  if (["FAILED", "FAIL", "ERROR", "CANCELED", "CANCELLED"].includes(value)) return "failed";
  if (["RUNNING", "PROCESSING", "WORKING", "EXECUTING"].includes(value)) return "running";
  return "pending";
}

function phaseText(task: AsyncTask, state: TaskState) {
  if (task.phase) return task.phase;
  if (task.message) return task.message;
  if (state === "success") return "解压完成";
  if (state === "failed") return "执行失败";
  if (state === "running") return "解压中";
  return "任务提交";
}

function normalizeProgress(task: AsyncTask, state: TaskState) {
  if (typeof task.progress === "number" && Number.isFinite(task.progress)) {
    return Math.max(0, Math.min(100, task.progress > 1 ? task.progress : task.progress * 100));
  }
  if (typeof task.current === "number" && typeof task.total === "number" && task.total > 0) {
    return Math.max(0, Math.min(100, (task.current / task.total) * 100));
  }
  if (state === "success") return 100;
  if (state === "running") return 48;
  return 0;
}

function normalizeTask(task: AsyncTask, index: number): NormalizedAsyncTask {
  let state = normalizeStatus(task.status);
  const id = String(task.id ?? task.taskId ?? index);
  const title = task.title || task.name || (task.type ? `${task.type} 任务` : "异步任务");
  const progress = normalizeProgress(task, state);
  if (!task.status && progress >= 100) state = "success";
  return {
    id,
    title,
    type: task.type || "async",
    state,
    phase: phaseText(task, state),
    progress,
    message: task.message || phaseText(task, state),
    error: task.error || task.errorMessage || "",
    createTime: task.createTime || task.createdAt,
    updateTime: task.updateTime || task.updatedAt,
    raw: task,
    source: "async"
  };
}

function normalizeKnowledgeTask(task: KnowledgePipelineTask): NormalizedAsyncTask {
  const state = normalizeStatus(task.taskStatus);
  const terminal = task.terminalStage || task.stage || task.taskStatus || "PENDING";
  const reason = task.terminalReason ? ` · 终止原因：${task.terminalReason}` : "";
  const incremental = task.incrementalAction ? ` · 增量策略：${task.incrementalAction}` : "";
  return {
    id: `knowledge-${task.id}`,
    title: task.documentId ? `知识画像任务 #${task.documentId}` : "空间知识画像任务",
    type: task.taskType || "knowledge",
    state,
    phase: terminal,
    progress: typeof task.progress === "number" ? task.progress : normalizeProgress({ progress: task.progress }, state),
    message: `终止节点：${terminal}${reason}${incremental}`,
    error: task.errorMessage || "",
    createTime: task.createtime,
    updateTime: task.updatetime,
    raw: task as unknown as AsyncTask,
    source: "knowledge",
    spaceId: task.spaceId,
    taskId: task.id,
    retryable: task.taskStatus === "FAILED" || task.taskStatus === "PARTIAL_SUCCESS"
  };
}

function statusLabel(state: TaskState) {
  if (state === "success") return "已完成";
  if (state === "failed") return "失败";
  if (state === "running") return "执行中";
  return "等待中";
}

function statusIcon(state: TaskState) {
  if (state === "success") return <CheckCircle2 size={18} />;
  if (state === "failed") return <AlertCircle size={18} />;
  if (state === "running") return <Loader2 className="spin" size={18} />;
  return <Clock3 size={18} />;
}

function isActive(state: TaskState) {
  return state === "pending" || state === "running";
}

function eventStatusText(status?: string) {
  if (status === "SUCCEEDED") return "成功";
  if (status === "FAILED") return "失败";
  if (status === "RUNNING") return "执行中";
  return status || "未知";
}

function AsyncTaskWindow({ task, events, onClose }: { task: NormalizedAsyncTask; events: KnowledgePipelineEvent[]; onClose: () => void }) {
  return (
    <div className="modal-backdrop" onClick={onClose}>
      <section className="async-task-window" onClick={(event) => event.stopPropagation()}>
        <header>
          <div>
            <h3>{task.title}</h3>
            <p>{task.type} · {statusLabel(task.state)}</p>
          </div>
          <button type="button" onClick={onClose} aria-label="关闭任务窗口">
            <X size={18} />
          </button>
        </header>
        <div className="async-window-body">
          <div className={`async-status-badge ${task.state}`}>
            {statusIcon(task.state)}
            <span>{task.phase}</span>
          </div>
          <div className="async-progress-head">
            <strong>{Math.round(task.progress)}%</strong>
            <span>{task.message}</span>
          </div>
          <div className="async-progress-bar" aria-label="任务进度">
            <span style={{ width: `${task.progress}%` }} />
          </div>
          {task.error && <div className="async-task-error">{task.error}</div>}
          {task.source === "knowledge" && (
            <dl className="async-task-meta">
              <div>
                <dt>终止节点</dt>
                <dd>{(task.raw as unknown as KnowledgePipelineTask).terminalStage || task.phase}</dd>
              </div>
              <div>
                <dt>终止原因</dt>
                <dd>{(task.raw as unknown as KnowledgePipelineTask).terminalReason || "-"}</dd>
              </div>
              <div>
                <dt>增量策略</dt>
                <dd>{(task.raw as unknown as KnowledgePipelineTask).incrementalAction || "-"}</dd>
              </div>
              <div>
                <dt>策略说明</dt>
                <dd>{(task.raw as unknown as KnowledgePipelineTask).incrementalDetail || "-"}</dd>
              </div>
            </dl>
          )}
          {!!events.length && (
            <div className="knowledge-event-timeline">
              {events.map((event) => (
                <div className={`knowledge-event-item ${(event.eventStatus || "").toLowerCase()}`} key={event.id}>
                  <span />
                  <div>
                    <strong>{event.stage}</strong>
                    <small>
                      {event.eventType} · {eventStatusText(event.eventStatus)}
                      {typeof event.durationMs === "number" ? ` · ${event.durationMs}ms` : ""}
                    </small>
                    {(event.errorCode || event.errorMessage || event.outputSummary) && (
                      <p>{event.errorCode || event.outputSummary || event.errorMessage}</p>
                    )}
                  </div>
                </div>
              ))}
            </div>
          )}
          <dl className="async-task-meta">
            <div>
              <dt>任务 ID</dt>
              <dd>{task.id}</dd>
            </div>
            <div>
              <dt>提交时间</dt>
              <dd>{formatTime(task.createTime)}</dd>
            </div>
            <div>
              <dt>更新时间</dt>
              <dd>{formatTime(task.updateTime)}</dd>
            </div>
          </dl>
        </div>
      </section>
    </div>
  );
}

export function AsyncTasksView({ showNotice }: { showNotice: (notice: Notice) => void }) {
  const [tasks, setTasks] = useState<NormalizedAsyncTask[]>([]);
  const [spaces, setSpaces] = useState<Space[]>([]);
  const [spaceId, setSpaceId] = useState<number | "">("");
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [selectedEvents, setSelectedEvents] = useState<KnowledgePipelineEvent[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  async function loadTasks(silent = false) {
    if (!silent) setLoading(true);
    setError("");
    try {
      const [payload, knowledgeTasks] = await Promise.all([
        api.listAsyncTasks(),
        spaceId ? api.listKnowledgeTasks(Number(spaceId)) : Promise.resolve([] as KnowledgePipelineTask[])
      ]);
      setTasks([
        ...pickTasks(payload).map(normalizeTask),
        ...(knowledgeTasks || []).map(normalizeKnowledgeTask)
      ]);
    } catch (err) {
      const message = err instanceof Error ? err.message : "异步任务加载失败";
      if (!silent) {
        setError(message);
        showNotice({ type: "error", text: message });
      }
    } finally {
      if (!silent) setLoading(false);
    }
  }

  useEffect(() => {
    api.listSpaces()
      .then((items) => {
        setSpaces(items || []);
        setSpaceId((current) => current || items?.[0]?.id || "");
      })
      .catch(() => setSpaces([]));
  }, []);

  useEffect(() => {
    void loadTasks();
  }, [spaceId]);

  async function retryTask(task: NormalizedAsyncTask) {
    if(task.source !== "knowledge" || !task.spaceId || !task.taskId) {
      return;
    }
    await api.retryKnowledgeTask(task.spaceId,task.taskId);
    showNotice({ type: "success", text: "已提交知识任务重试" });
    await loadTasks(true);
  }

  async function retryFailedKnowledgeTasks() {
    if(!spaceId) return;
    await api.retryFailedKnowledgeTasks(Number(spaceId));
    showNotice({ type: "success", text: "已提交知识任务批量重试" });
    await loadTasks(true);
  }

  const selectedTask = useMemo(() => tasks.find((task) => task.id === selectedId) || null, [tasks, selectedId]);

  useEffect(() => {
    if (!selectedTask || selectedTask.source !== "knowledge" || !selectedTask.spaceId || !selectedTask.taskId) {
      setSelectedEvents([]);
      return;
    }
    api.listKnowledgeTaskEvents(selectedTask.spaceId,selectedTask.taskId)
      .then((items) => setSelectedEvents(items || []))
      .catch(() => setSelectedEvents([]));
  }, [selectedTask?.id]);

  useEffect(() => {
    if (!selectedTask || !isActive(selectedTask.state)) return;
    const timer = window.setInterval(() => void loadTasks(true), 2000);
    return () => window.clearInterval(timer);
  }, [selectedTask?.id, selectedTask?.state]);

  return (
    <section className="async-view">
      <div className="async-head">
        <div>
          <h2>异步任务</h2>
          <p>查看后台任务的提交、执行和完成进度。</p>
        </div>
        <button className="icon-button" type="button" onClick={() => void loadTasks()} title="刷新任务">
          <RefreshCw size={18} />
        </button>
        <select value={spaceId} onChange={(event) => setSpaceId(event.target.value ? Number(event.target.value) : "")}>
          {spaces.map((space) => (
            <option key={space.id} value={space.id}>
              {space.name}
            </option>
          ))}
        </select>
        <button className="soft-button" type="button" disabled={!spaceId} onClick={() => void retryFailedKnowledgeTasks()}>
          <RotateCcw size={16} />
          批量重试知识任务
        </button>
      </div>

      {error && (
        <div className="error-state compact-error">
          <strong>加载失败</strong>
          <span>{error}</span>
          <button type="button" onClick={() => void loadTasks()}>
            重试
          </button>
        </div>
      )}

      {loading ? (
        <div className="loading-state">
          <Loader2 className="spin" size={24} />
          正在加载异步任务
        </div>
      ) : (
        !error && (
          <div className="async-task-list">
            {tasks.map((task) => (
              <button className="async-task-row" key={task.id} type="button" onClick={() => setSelectedId(task.id)}>
                <span className={`async-task-icon ${task.state}`}>{statusIcon(task.state)}</span>
                <span className="async-task-main">
                  <strong>{task.title}</strong>
                  <small>{task.phase}</small>
                </span>
                <span className="async-task-progress">
                  <span>{Math.round(task.progress)}%</span>
                  <i>
                    <b style={{ width: `${task.progress}%` }} />
                  </i>
                </span>
                <span className={`async-task-state ${task.state}`}>{statusLabel(task.state)}</span>
                {task.retryable && (
                  <span
                    className="async-task-inline-action"
                    role="button"
                    tabIndex={0}
                    onClick={(event) => {
                      event.stopPropagation();
                      void retryTask(task);
                    }}
                    onKeyDown={(event) => {
                      if(event.key === "Enter" || event.key === " ") {
                        event.stopPropagation();
                        void retryTask(task);
                      }
                    }}
                  >
                    重试
                  </span>
                )}
              </button>
            ))}
            {!tasks.length && (
              <div className="empty-state compact-empty">
                <div className="empty-icon">
                  <Clock3 size={32} />
                </div>
                <h3>暂无异步任务</h3>
                <p>提交解压、索引或其他后台任务后，会在这里展示执行进度。</p>
              </div>
            )}
          </div>
        )
      )}

      {selectedTask && <AsyncTaskWindow task={selectedTask} events={selectedEvents} onClose={() => setSelectedId(null)} />}
    </section>
  );
}
