import { useMutation, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { api } from "../../../api";
import type { AdminTaskRow } from "../../../types";
import { Button } from "../../../components/ui/Button";
import { Dialog } from "../../../components/ui/Dialog";
import { AdminField } from "../components/AdminDialogs";
import { AdminFilterBar, AdminPage, AdminSelect, AdminUnavailableHint } from "../components/AdminPage";
import { AdminPagination, AdminTable } from "../components/AdminTable";

const terminal = new Set(["SUCCESS", "FAILED", "CANCELED", "SKIPPED_STALE"]);
type Command = { task: AdminTaskRow; action: "archive" | "retry" | "cancel" };
const labels = { archive: "归档", retry: "重试", cancel: "取消任务" };
export function LiveTasksPage() {
  const [archived, setArchived] = useState(false);
  const [status, setStatus] = useState("");
  const [type, setType] = useState("");
  const [creator, setCreator] = useState("");
  const [page, setPage] = useState(1);
  const [command, setCommand] = useState<Command | null>(null);
  const [reason, setReason] = useState("");
  const validCreator = !creator || (/^[1-9]\d*$/.test(creator) && Number.isSafeInteger(Number(creator)));
  const filters = { archived, status, type: type.trim(), creator: creator ? Number(creator) : undefined, page, pageSize: 20 };
  const tasks = useQuery({ queryKey: ["admin-tasks", filters], queryFn: () => api.adminTasks(filters), enabled: validCreator, refetchInterval: 15000 });
  const run = useMutation({ mutationFn: (value: Command) => value.action === "archive" ? api.archiveAdminTask(value.task.id) : value.action === "retry" ? api.retryUnifiedTask(value.task.id, reason) : api.cancelUnifiedTask(value.task.id, reason),
    onSuccess: async () => { setCommand(null); await tasks.refetch(); } });
  function open(task: AdminTaskRow, action: Command["action"]) { run.reset(); setReason(""); setCommand({ task, action }); }
  return <AdminPage eyebrow="运行与调度" title="后台任务" description="从服务器分页查询全站统一任务；重试和取消遵从既有任务状态机。"
    actions={<Button disabled={tasks.isFetching || !validCreator} onClick={() => void tasks.refetch()}>刷新</Button>}>
    <AdminUnavailableHint>任务步骤持久化、暂停及人工续跑尚未接入，本页不提供模拟恢复。归档只隐藏已结束任务，保留记录与执行证据。</AdminUnavailableHint>
    <AdminFilterBar><AdminSelect label="记录范围" value={String(archived)} onChange={(value) => { setArchived(value === "true"); setPage(1); }} options={[{ value: "false", label: "未归档" }, { value: "true", label: "已归档" }]} />
      <AdminSelect label="状态" value={status} onChange={(value) => { setStatus(value); setPage(1); }} options={[{ value: "", label: "全部" }, ...["PENDING_PUBLISH", "PENDING", "RUNNING", "RETRY_WAIT", ...terminal].map((value) => ({ value, label: value }))]} />
      <AdminField label="任务类型"><input value={type} maxLength={80} onChange={(event) => { setType(event.target.value); setPage(1); }} placeholder="精确类型，例如 ASYNC_DEMO" /></AdminField>
      <AdminField label="创建者 ID"><input value={creator} inputMode="numeric" onChange={(event) => { setCreator(event.target.value); setPage(1); }} /></AdminField>
    </AdminFilterBar>
    {!validCreator && <p role="alert">创建者 ID 必须是正整数。</p>}
    <AdminTable items={validCreator ? tasks.data?.items || [] : []} getKey={(task) => task.id} loading={validCreator && tasks.isPending} error={tasks.isError ? tasks.error.message : null} onRetry={() => void tasks.refetch()} emptyTitle="暂无匹配任务" emptyMessage="调整筛选条件或切换已归档范围。" columns={[
      { key: "id", label: "任务 ID", render: (task) => task.id }, { key: "type", label: "类型", render: (task) => task.task_type },
      { key: "domain", label: "领域", render: (task) => task.task_domain }, { key: "status", label: "状态", render: (task) => task.status },
      { key: "creator", label: "创建者", render: (task) => task.created_by ?? "系统" }, { key: "attempt", label: "执行次数", render: (task) => task.attempt_version },
      { key: "updated", label: "更新时间", render: (task) => task.updated_at },
      { key: "actions", label: "操作", render: (task) => archived ? <span>已归档</span> : <div className="admin-row-actions">
        {task.status === "FAILED" && <Button size="sm" onClick={() => open(task, "retry")}>重试</Button>}
        {!terminal.has(task.status) && task.task_type !== "SPACE_FILE_DELETE" && <Button size="sm" onClick={() => open(task, "cancel")}>取消任务</Button>}
        {terminal.has(task.status) && <Button size="sm" onClick={() => open(task, "archive")}>归档</Button>}
      </div> }
    ]} />
    <AdminPagination page={page} total={tasks.data?.total || 0} onPageChange={setPage} />
    <Dialog open={command !== null} onOpenChange={(open) => !open && !run.isPending && setCommand(null)} title={`${command ? labels[command.action] : "操作"}？`} description="操作会提交服务器，任务状态可能在确认前变化；执行中的取消需等待任务安全检查点。" footer={<><Button disabled={run.isPending} onClick={() => setCommand(null)}>返回</Button><Button variant="danger" loading={run.isPending} onClick={() => command && !run.isPending && run.mutate(command)}>确认执行</Button></>}>
      <p>任务 ID：{command?.task.id}</p>{command?.action !== "archive" && <AdminField label="操作原因"><input maxLength={500} value={reason} onChange={(event) => setReason(event.target.value)} /></AdminField>}
      {run.isError && <p role="alert">{run.error.message}</p>}
    </Dialog>
  </AdminPage>;
}
