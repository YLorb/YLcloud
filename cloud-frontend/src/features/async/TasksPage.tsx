import * as Select from "@radix-ui/react-select";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Check, ChevronDown, Filter, RefreshCw, RotateCcw, Search, ServerCog } from "lucide-react";
import { useMemo, useState } from "react";
import { toast } from "sonner";
import { api } from "../../api";
import { Button } from "../../components/ui/Button";
import { EmptyState, ErrorState, LoadingState } from "../../components/ui/PageState";
import { StatusBadge, type StatusTone } from "../../components/ui/StatusBadge";
import { formatTime } from "../../fileUtils";
import type { AsyncTask } from "../../types";

export function TasksPage() {
  const client = useQueryClient();
  const [spaceFilter, setSpaceFilter] = useState("all");
  const [statusFilter, setStatusFilter] = useState("all");
  const [query, setQuery] = useState("");
  const spaces = useQuery({ queryKey: ["spaces"], queryFn: api.listSpaces });
  const tasks = useQuery({
    queryKey: ["async-tasks", spaceFilter],
    queryFn: () => api.listAsyncTasks(spaceFilter === "all" ? undefined : Number(spaceFilter)),
    refetchInterval: (result) => normalizeTasks(result.state.data).some(isActive) ? 3_000 : 15_000
  });
  const normalized = useMemo(() => normalizeTasks(tasks.data), [tasks.data]);
  const visible = useMemo(() => normalized.filter((task) => statusFilter === "all" || taskStatus(task).key === statusFilter).filter((task) => `${task.name || task.title || task.type || ""} ${task.message || ""}`.toLocaleLowerCase().includes(query.toLocaleLowerCase())), [normalized, query, statusFilter]);
  const retry = useMutation({
    mutationFn: async (task: AsyncTask) => {
      const taskId = Number(task.taskId ?? task.id);
      const spaceId = Number(task.spaceId);
      if (!Number.isFinite(taskId) || !spaceId) throw new Error("任务缺少可重试的任务 ID 或空间 ID");
      const target = getTaskRetryTarget(task);
      if (target === "knowledge") return api.retryKnowledgeTask(spaceId, taskId);
      if (target === "rag") return api.retryRagTask(spaceId, taskId);
      throw new Error("无法识别任务来源，已阻止错误重试");
    },
    onSuccess: () => { client.invalidateQueries({ queryKey: ["async-tasks"] }); toast.success("任务已重新提交"); },
    onError: (error) => toast.error(error instanceof Error ? error.message : "任务重试失败")
  });

  if (tasks.isLoading) return <LoadingState label="正在加载后台任务" />;
  if (tasks.isError) return <ErrorState message={tasks.error instanceof Error ? tasks.error.message : "无法加载后台任务"} onRetry={() => tasks.refetch()} />;

  const counts = normalized.reduce((acc, task) => { acc[taskStatus(task).key] += 1; return acc; }, { active: 0, success: 0, failed: 0, pending: 0 });
  return <div className="tasks-page"><div className="metrics-grid"><Metric label="正在处理" value={counts.active} tone={counts.active ? "running" : "neutral"} /><Metric label="等待中" value={counts.pending} tone={counts.pending ? "warning" : "neutral"} /><Metric label="已成功" value={counts.success} tone="success" /><Metric label="失败" value={counts.failed} tone={counts.failed ? "danger" : "neutral"} /></div><section className="panel"><div className="content-toolbar"><div className="task-filters"><label className="search-box"><Search size={17} /><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="搜索任务" /></label><FilterSelect value={spaceFilter} onValueChange={setSpaceFilter} label="空间" options={[{ value: "all", label: "全部空间" }, ...(spaces.data || []).map((space) => ({ value: String(space.id), label: space.name }))]} /><FilterSelect value={statusFilter} onValueChange={setStatusFilter} label="状态" options={[{ value: "all", label: "全部状态" }, { value: "active", label: "处理中" }, { value: "pending", label: "等待中" }, { value: "success", label: "成功" }, { value: "failed", label: "失败" }]} /></div><Button variant="ghost" onClick={() => tasks.refetch()}><RefreshCw size={16} />刷新</Button></div>{visible.length === 0 ? <EmptyState title="没有匹配的任务" message="后台任务会在上传文档、构建索引或生成知识画像时出现。" /> : <div className="data-table-wrap"><table className="data-table"><thead><tr><th>任务</th><th>来源</th><th>状态</th><th>进度</th><th>更新时间</th><th><span className="sr-only">操作</span></th></tr></thead><tbody>{visible.map((task, index) => { const status = taskStatus(task); const id = String(task.taskId ?? task.id ?? index); const canRetry = status.key === "failed" && task.retryable !== false && (task.source === "rag" || task.source === "knowledge"); return <tr key={`${task.source}-${id}`}><td><div className="task-title"><span><ServerCog size={17} /></span><div><strong>{task.name || task.title || task.type || `后台任务 #${id}`}</strong><small className={status.key === "failed" ? "inline-error" : ""}>{task.errorMessage || task.error || task.message || task.phase || "—"}</small></div></div></td><td><StatusBadge tone="info">{task.source === "knowledge" ? "知识画像" : task.source === "rag" ? "RAG 索引" : "系统"}</StatusBadge></td><td><StatusBadge tone={status.tone}>{status.label}</StatusBadge></td><td><div className="progress-cell"><progress max={100} value={taskProgress(task)} /><span>{taskProgress(task)}%</span></div></td><td>{formatTime(task.updateTime || task.updatedAt || task.createTime || task.createdAt)}</td><td>{canRetry ? <Button variant="danger" size="sm" loading={retry.isPending && retry.variables === task} onClick={() => retry.mutate(task)}><RotateCcw size={15} />重试任务</Button> : status.key === "failed" ? <span className="muted-text">不可重试</span> : null}</td></tr>; })}</tbody></table></div>}</section></div>;
}

function normalizeTasks(value: Awaited<ReturnType<typeof api.listAsyncTasks>> | undefined): AsyncTask[] {
  if (!value) return [];
  if (Array.isArray(value)) return value;
  return value.records || value.list || value.items || value.tasks || [];
}
export function getTaskRetryTarget(task: AsyncTask): "rag" | "knowledge" | null { return task.source === "rag" || task.source === "knowledge" ? task.source : null; }
function taskProgress(task: AsyncTask) { if (typeof task.progress === "number") return Math.max(0, Math.min(100, Math.round(task.progress))); if (task.total && typeof task.current === "number") return Math.round((task.current / task.total) * 100); return taskStatus(task).key === "success" ? 100 : 0; }
function taskStatus(task: AsyncTask): { key: "active" | "pending" | "success" | "failed"; tone: StatusTone; label: string } { const value = String(task.status ?? task.phase ?? "").toUpperCase(); if (["SUCCESS", "COMPLETED", "DONE", "2"].includes(value)) return { key: "success", tone: "success", label: "成功" }; if (["FAILED", "ERROR", "ROLLED_BACK", "-1"].includes(value)) return { key: "failed", tone: "danger", label: value === "ROLLED_BACK" ? "失败并回滚" : "失败" }; if (["RUNNING", "PROCESSING", "1"].includes(value)) return { key: "active", tone: "running", label: "处理中" }; return { key: "pending", tone: "warning", label: "等待中" }; }
function isActive(task: AsyncTask) { const key = taskStatus(task).key; return key === "active" || key === "pending"; }
function Metric({ label, value, tone }: { label: string; value: number; tone: StatusTone }) { return <article className={`metric-card metric-card--${tone}`}><div><small>{label}</small><strong>{value}</strong></div></article>; }
function FilterSelect({ value, onValueChange, label, options }: { value: string; onValueChange: (value: string) => void; label: string; options: Array<{ value: string; label: string }> }) { return <Select.Root value={value} onValueChange={onValueChange}><Select.Trigger className="select-trigger select-trigger--compact" aria-label={label}><Filter size={15} /><Select.Value /><Select.Icon><ChevronDown size={15} /></Select.Icon></Select.Trigger><Select.Portal><Select.Content className="select-content" position="popper"><Select.Viewport>{options.map((option) => <Select.Item className="select-item" value={option.value} key={option.value}><Select.ItemText>{option.label}</Select.ItemText><Select.ItemIndicator><Check size={14} /></Select.ItemIndicator></Select.Item>)}</Select.Viewport></Select.Content></Select.Portal></Select.Root>; }
