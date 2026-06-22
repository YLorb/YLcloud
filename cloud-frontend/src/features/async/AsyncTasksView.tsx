import { AlertCircle, CheckCircle2, Clock3, Loader2, RefreshCw, X } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { api } from "../../api";
import type { Notice } from "../../appTypes";
import type { AsyncTask } from "../../types";
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
    raw: task
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

function AsyncTaskWindow({ task, onClose }: { task: NormalizedAsyncTask; onClose: () => void }) {
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
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  async function loadTasks(silent = false) {
    if (!silent) setLoading(true);
    setError("");
    try {
      const payload = await api.listAsyncTasks();
      setTasks(pickTasks(payload).map(normalizeTask));
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
    void loadTasks();
  }, []);

  const selectedTask = useMemo(() => tasks.find((task) => task.id === selectedId) || null, [tasks, selectedId]);

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

      {selectedTask && <AsyncTaskWindow task={selectedTask} onClose={() => setSelectedId(null)} />}
    </section>
  );
}
