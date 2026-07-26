import * as Select from "@radix-ui/react-select";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AlertCircle, Ban, Check, CheckCircle2, ChevronDown, Clock3, Filter, RefreshCw, RotateCcw, Search, ServerCog } from "lucide-react";
import { useMemo, useState } from "react";
import { toast } from "sonner";
import { api } from "../../api";
import { Button } from "../../components/ui/Button";
import { Dialog } from "../../components/ui/Dialog";
import { EmptyState, ErrorState, LoadingState } from "../../components/ui/PageState";
import { StatusBadge, type StatusTone } from "../../components/ui/StatusBadge";
import { formatTime } from "../../fileUtils";
import type { AsyncTask, AsyncTaskDetail } from "../../types";

export function TasksPage() {
  const client = useQueryClient();
  const [spaceFilter, setSpaceFilter] = useState("all");
  const [statusFilter, setStatusFilter] = useState("all");
  const [query, setQuery] = useState("");
  const [selectedTask, setSelectedTask] = useState<AsyncTask | null>(null);
  const spaces = useQuery({ queryKey: ["spaces"], queryFn: api.listSpaces });
  const tasks = useQuery({
    queryKey: ["async-tasks", spaceFilter],
    queryFn: () => api.listAsyncTasks(spaceFilter === "all" ? undefined : Number(spaceFilter)),
    refetchInterval: (result) => normalizeTasks(result.state.data).some(isActive) ? 3_000 : 15_000
  });
  const normalized = useMemo(() => normalizeTasks(tasks.data), [tasks.data]);
  const selectedTaskId = Number(selectedTask?.taskId ?? selectedTask?.id);
  const detail = useQuery({
    queryKey: ["async-task-detail", selectedTask?.source, selectedTaskId],
    queryFn: () => api.getAsyncTask(selectedTask!.source!,selectedTaskId),
    enabled: Boolean(selectedTask && ["rag","knowledge","unified"].includes(selectedTask.source || "") && Number.isFinite(selectedTaskId)),
    refetchInterval: (result) => result.state.data && isActive(result.state.data) ? 3_000 : false
  });
  const visible = useMemo(() => normalized.filter((task) => statusFilter === "all" || taskStatus(task).key === statusFilter).filter((task) => `${task.name || task.title || task.type || ""} ${task.message || ""}`.toLocaleLowerCase().includes(query.toLocaleLowerCase())), [normalized, query, statusFilter]);
  const retry = useMutation({
    mutationFn: async (task: AsyncTask) => {
      const taskId = Number(task.taskId ?? task.id);
      const spaceId = Number(task.spaceId);
      if (!Number.isFinite(taskId)) throw new Error("任务缺少可重试的任务 ID");
      if (task.source === "unified") return api.retryUnifiedTask(taskId,"控制台人工重试");
      if (!spaceId) throw new Error("任务缺少可重试的空间 ID");
      const target = getTaskRetryTarget(task);
      if (target === "knowledge") return api.retryKnowledgeTask(spaceId, taskId);
      if (target === "rag") return api.retryRagTask(spaceId, taskId);
      throw new Error("无法识别任务来源，已阻止错误重试");
    },
    onSuccess: () => { client.invalidateQueries({ queryKey: ["async-tasks"] }); toast.success("任务已重新提交"); },
    onError: (error) => toast.error(error instanceof Error ? error.message : "任务重试失败")
  });
  const cancel = useMutation({
    mutationFn: async (task: AsyncTask) => api.cancelUnifiedTask(Number(task.taskId ?? task.id),"控制台取消"),
    onSuccess: () => { client.invalidateQueries({ queryKey: ["async-tasks"] }); client.invalidateQueries({ queryKey: ["async-task-detail"] }); toast.success("已提交取消请求"); },
    onError: (error) => toast.error(error instanceof Error ? error.message : "任务取消失败")
  });

  if (tasks.isLoading) return <LoadingState label="正在加载后台任务" />;
  if (tasks.isError) return <ErrorState message={tasks.error instanceof Error ? tasks.error.message : "无法加载后台任务"} onRetry={() => tasks.refetch()} />;

  const counts = normalized.reduce((acc, task) => { acc[taskStatus(task).key] += 1; return acc; }, { active: 0, success: 0, failed: 0, pending: 0, canceled: 0, stale: 0 });
  return <div className="tasks-page">
    <div className="metrics-grid"><Metric label="正在处理" value={counts.active} tone={counts.active ? "running" : "neutral"} /><Metric label="等待中" value={counts.pending} tone={counts.pending ? "warning" : "neutral"} /><Metric label="已成功" value={counts.success} tone="success" /><Metric label="失败" value={counts.failed} tone={counts.failed ? "danger" : "neutral"} /></div>
    <section className="panel">
      <div className="content-toolbar"><div className="task-filters"><label className="search-box"><Search size={17} /><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="搜索任务" /></label><FilterSelect value={spaceFilter} onValueChange={setSpaceFilter} label="空间" options={[{ value: "all", label: "全部空间" }, ...(spaces.data || []).map((space) => ({ value: String(space.id), label: space.name }))]} /><FilterSelect value={statusFilter} onValueChange={setStatusFilter} label="状态" options={[{ value: "all", label: "全部状态" }, { value: "active", label: "处理中" }, { value: "pending", label: "等待中" }, { value: "success", label: "成功" }, { value: "failed", label: "失败" }]} /></div><Button variant="ghost" onClick={() => tasks.refetch()}><RefreshCw size={16} />刷新</Button></div>
      {visible.length === 0 ? <EmptyState title="没有匹配的任务" message="后台任务会在上传文档、构建索引或生成知识画像时出现。" /> : <div className="data-table-wrap"><table className="data-table"><thead><tr><th>任务</th><th>来源</th><th>状态</th><th>进度</th><th>更新时间</th><th><span className="sr-only">操作</span></th></tr></thead><tbody>{visible.map((task, index) => { const status = taskStatus(task); const id = String(task.taskId ?? task.id ?? index); const canRetry = status.key === "failed" && task.retryable !== false && (task.source === "rag" || task.source === "knowledge" || task.source === "unified"); const canCancel = task.source === "unified" && ["active","pending"].includes(status.key); return <tr key={`${task.source}-${id}`}><td><button className="task-detail-trigger" onClick={() => setSelectedTask(task)} aria-label={`查看 ${task.name || task.title || task.type || `后台任务 ${id}`} 的执行详情`}><span><ServerCog size={17} /></span><span><strong>{task.name || task.title || task.type || `后台任务 #${id}`}</strong><small className={status.key === "failed" ? "inline-error" : ""}>{task.errorMessage || task.error || task.message || task.phase || "点击查看执行详情"}</small></span></button></td><td><StatusBadge tone="info">{task.source === "knowledge" ? "知识画像" : task.source === "rag" ? "RAG 索引" : task.taskDomain || "统一任务"}</StatusBadge></td><td><StatusBadge tone={status.tone}>{status.label}</StatusBadge></td><td><div className="progress-cell"><progress max={100} value={taskProgress(task)} /><span>{taskProgress(task)}%</span></div></td><td>{formatTime(task.updateTime || task.updatedAt || task.createTime || task.createdAt)}</td><td><div className="table-actions">{canRetry && <Button variant="danger" size="sm" loading={retry.isPending && retry.variables === task} onClick={() => retry.mutate(task)}><RotateCcw size={15} />重试</Button>}{canCancel && <Button variant="ghost" size="sm" loading={cancel.isPending && cancel.variables === task} onClick={() => cancel.mutate(task)}><Ban size={15} />取消</Button>}{!canRetry && !canCancel && status.key === "failed" ? <span className="muted-text">不可重试</span> : null}</div></td></tr>; })}</tbody></table></div>}
    </section>
    <Dialog open={Boolean(selectedTask)} onOpenChange={(open) => { if(!open) setSelectedTask(null); }} title={selectedTask?.title || selectedTask?.name || "任务执行详情"} description={`任务 ${selectedTask?.source || "system"}-${selectedTask?.taskId ?? selectedTask?.id ?? "—"}`} footer={<><Button onClick={() => detail.refetch()} disabled={!selectedTask}><RefreshCw size={15} />刷新详情</Button><Button variant="confirm" onClick={() => setSelectedTask(null)}>关闭</Button></>}>
      {detail.isLoading ? <LoadingState label="正在加载任务详情" /> : detail.isError ? <ErrorState message={detail.error instanceof Error ? detail.error.message : "无法加载任务详情"} onRetry={() => detail.refetch()} /> : detail.data ? <TaskDetail task={detail.data} onRetry={() => retry.mutate(detail.data)} onCancel={() => cancel.mutate(detail.data)} retrying={retry.isPending} canceling={cancel.isPending} /> : <EmptyState title="没有任务详情" message="该任务来源暂不支持详情查询。" />}
    </Dialog>
  </div>;
}

function normalizeTasks(value: Awaited<ReturnType<typeof api.listAsyncTasks>> | undefined): AsyncTask[] {
  if (!value) return [];
  if (Array.isArray(value)) return value;
  return value.records || value.list || value.items || value.tasks || [];
}
export function getTaskRetryTarget(task: AsyncTask): "rag" | "knowledge" | "unified" | null { return task.source === "rag" || task.source === "knowledge" || task.source === "unified" ? task.source : null; }
function taskProgress(task: AsyncTask) { if (typeof task.progress === "number") return Math.max(0, Math.min(100, Math.round(task.progress))); if (task.total && typeof task.current === "number") return Math.round((task.current / task.total) * 100); return taskStatus(task).key === "success" ? 100 : 0; }
function taskStatus(task: AsyncTask): { key: "active" | "pending" | "success" | "failed" | "canceled" | "stale"; tone: StatusTone; label: string } { const value = String(task.status ?? task.phase ?? "").toUpperCase(); if (["SUCCESS", "COMPLETED", "DONE", "2"].includes(value)) return { key: "success", tone: "success", label: "成功" }; if (value === "CANCELED") return { key: "canceled", tone: "neutral", label: "已取消" }; if (value === "SKIPPED_STALE") return { key: "stale", tone: "warning", label: "已跳过（资源过期）" }; if (["FAILED", "ERROR", "ROLLED_BACK", "PARTIAL_SUCCESS", "-1"].includes(value)) return { key: "failed", tone: value === "PARTIAL_SUCCESS" ? "warning" : "danger", label: value === "ROLLED_BACK" ? "失败并回滚" : value === "PARTIAL_SUCCESS" ? "部分成功" : "失败" }; if (["RUNNING", "PROCESSING", "1"].includes(value)) return { key: "active", tone: "running", label: "处理中" }; return { key: "pending", tone: "warning", label: value === "RETRY_WAIT" ? "等待重试" : "等待中" }; }
function isActive(task: AsyncTask) { const key = taskStatus(task).key; return key === "active" || key === "pending"; }
function Metric({ label, value, tone }: { label: string; value: number; tone: StatusTone }) { return <article className={`metric-card metric-card--${tone}`}><div><small>{label}</small><strong>{value}</strong></div></article>; }
function FilterSelect({ value, onValueChange, label, options }: { value: string; onValueChange: (value: string) => void; label: string; options: Array<{ value: string; label: string }> }) { return <Select.Root value={value} onValueChange={onValueChange}><Select.Trigger className="select-trigger select-trigger--compact" aria-label={label}><Filter size={15} /><Select.Value /><Select.Icon><ChevronDown size={15} /></Select.Icon></Select.Trigger><Select.Portal><Select.Content className="select-content" position="popper"><Select.Viewport>{options.map((option) => <Select.Item className="select-item" value={option.value} key={option.value}><Select.ItemText>{option.label}</Select.ItemText><Select.ItemIndicator><Check size={14} /></Select.ItemIndicator></Select.Item>)}</Select.Viewport></Select.Content></Select.Portal></Select.Root>; }
function TaskDetail({ task, onRetry, onCancel, retrying, canceling }: { task: AsyncTaskDetail; onRetry: () => void; onCancel: () => void; retrying: boolean; canceling: boolean }) {
  const status = taskStatus(task);
  const attempts = task.attempts || [];
  return <div className="task-detail">
    <section className={`task-detail__summary task-detail__summary--${status.tone}`}>{status.key === "success" ? <CheckCircle2 size={22} /> : status.key === "failed" ? <AlertCircle size={22} /> : <Clock3 size={22} />}<div><StatusBadge tone={status.tone}>{status.label}</StatusBadge><strong>{task.completionSummary || task.message || status.label}</strong>{task.terminalReason && <p>{task.terminalReason}</p>}</div></section>
    <dl className="task-detail__facts"><div><dt>执行阶段</dt><dd>{task.terminalStage || task.phase || "—"}</dd></div><div><dt>进度</dt><dd>{taskProgress(task)}%</dd></div><div><dt>执行轮次</dt><dd>{task.attemptVersion ?? attempts.length}</dd></div><div><dt>耗时</dt><dd>{formatDuration(task.durationMs)}</dd></div><div><dt>资源版本</dt><dd>{task.resourceVersion ?? "—"}</dd></div><div><dt>资源标识</dt><dd>{task.resourceKey || "—"}</dd></div><div><dt>开始时间</dt><dd>{formatTime(task.startedTime || task.createTime)}</dd></div><div><dt>完成时间</dt><dd>{formatTime(task.finishedTime || task.updateTime)}</dd></div></dl>
    {task.errorMessage && <section className="task-detail__error" role="alert"><AlertCircle size={18} /><div><strong>失败原因</strong><p>{task.errorMessage}</p></div></section>}
    {attempts.length > 0 && <section className="task-detail__timeline" aria-label="执行时间线"><h4>执行时间线</h4><ol>{attempts.map((attempt, index) => <li key={`${attempt.attemptVersion ?? index}-${attempt.startedAt ?? ""}`}><span aria-hidden="true" /><div><header><strong>第 {attempt.attemptVersion ?? index + 1} 次执行</strong><StatusBadge tone={attempt.status === "SUCCESS" ? "success" : attempt.status === "FAILED" ? "danger" : "running"}>{attempt.status || "RUNNING"}</StatusBadge></header><p>{attempt.failureMessage || `${attempt.triggerType || "SYSTEM"} · ${formatDuration(attempt.durationMs)}`}</p><small>{formatTime(attempt.startedAt)}{attempt.finishedAt ? ` → ${formatTime(attempt.finishedAt)}` : ""}{attempt.nextRetryAt ? ` · 下次重试 ${formatTime(attempt.nextRetryAt)}` : ""}</small></div></li>)}</ol></section>}
    {(task.canRetry || task.canCancel) && <div className="task-detail__actions">{task.canCancel && <Button variant="ghost" loading={canceling} onClick={onCancel}><Ban size={15} />取消任务</Button>}{task.canRetry && <Button variant="danger" loading={retrying} onClick={onRetry}><RotateCcw size={15} />重试任务</Button>}</div>}
  </div>;
}
function formatDuration(durationMs?: number) { if(durationMs == null) return "—"; if(durationMs < 1000) return `${durationMs} ms`; const seconds = Math.round(durationMs / 1000); if(seconds < 60) return `${seconds} 秒`; const minutes = Math.floor(seconds / 60); return `${minutes} 分 ${seconds % 60} 秒`; }
