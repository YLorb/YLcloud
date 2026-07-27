import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AlertTriangle, CheckCircle2, Download, FileDown, RefreshCw, Shield, UserX } from "lucide-react";
import { useState } from "react";
import { toast } from "sonner";
import { api } from "../../api";
import { Button } from "../../components/ui/Button";
import { Dialog } from "../../components/ui/Dialog";
import { EmptyState, ErrorState, LoadingState } from "../../components/ui/PageState";
import { StatusBadge, type StatusTone } from "../../components/ui/StatusBadge";
import { formatTime } from "../../fileUtils";
import type { DataExportJob } from "../../types";

const exportStatusTone: Record<string, StatusTone> = {
  PENDING: "warning",
  RUNNING: "running",
  COMPLETED: "success",
  FAILED: "danger",
  EXPIRED: "neutral"
};

const exportStatusLabel: Record<string, string> = {
  PENDING: "等待中",
  RUNNING: "处理中",
  COMPLETED: "已完成",
  FAILED: "失败",
  EXPIRED: "已过期"
};

export function AccountSettingsPage() {
  const client = useQueryClient();
  const [cancelDialogOpen, setCancelDialogOpen] = useState(false);
  const [exportDialogOpen, setExportDialogOpen] = useState(false);
  const [cancelReason, setCancelReason] = useState("");
  const [confirmTeams, setConfirmTeams] = useState(false);

  const accountStatus = useQuery({ queryKey: ["account-status"], queryFn: api.accountStatus });
  const exports = useQuery({ queryKey: ["data-exports"], queryFn: api.listDataExports, refetchInterval: 10_000 });

  const cancelAccount = useMutation({
    mutationFn: () => api.cancelAccount({ reason: cancelReason, confirmTeams }),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ["account-status"] });
      setCancelDialogOpen(false);
      setCancelReason("");
      setConfirmTeams(false);
      toast.success("账号已注销，3 天内可由管理员恢复");
    },
    onError: (error) => toast.error(error instanceof Error ? error.message : "账号注销失败")
  });

  const requestExport = useMutation({
    mutationFn: () => api.requestDataExport({ exportScope: "FULL" }),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ["data-exports"] });
      setExportDialogOpen(false);
      toast.success("数据导出任务已提交");
    },
    onError: (error) => toast.error(error instanceof Error ? error.message : "数据导出请求失败")
  });

  if (accountStatus.isLoading) return <LoadingState label="正在加载账号状态" />;
  if (accountStatus.isError) return <ErrorState message={accountStatus.error instanceof Error ? accountStatus.error.message : "无法加载账号状态"} onRetry={() => accountStatus.refetch()} />;

  const status = accountStatus.data;
  const isCancelled = status?.accountStatus === "CANCELLED";
  const isPurging = status?.accountStatus === "PURGING";
  const isPurged = status?.accountStatus === "PURGED";

  return <div className="account-settings-page">
    <section className={`admin-security-note ${isCancelled ? "admin-security-note--warning" : isPurging || isPurged ? "admin-security-note--danger" : ""}`}>
      <Shield size={22} />
      <div>
        <strong>账号设置</strong>
        <p>管理你的账号状态、数据导出和注销选项。</p>
      </div>
      <StatusBadge tone={isCancelled ? "warning" : isPurging || isPurged ? "danger" : "success"}>
        {status?.accountStatus === "ACTIVE" ? "正常" : status?.accountStatus === "CANCELLED" ? "已注销" : status?.accountStatus === "PURGING" ? "清理中" : "已删除"}
      </StatusBadge>
    </section>

    {isCancelled && status?.recoverableUntil && (
      <section className="maintenance-banner" role="alert">
        <AlertTriangle size={24} />
        <div>
          <strong>账号已注销</strong>
          <p>你的账号已被注销，将在 {formatTime(status.recoverableUntil)} 后永久删除。请联系管理员恢复账号。</p>
        </div>
      </section>
    )}

    <div className="settings-grid">
      <section className="panel settings-card">
        <header>
          <Download size={24} />
          <h2>数据导出</h2>
        </header>
        <p>导出你的所有个人数据，包括文件、知识库、记忆和会话历史。导出文件使用 AES-256-GCM 加密。</p>
        <div className="settings-actions">
          <Button variant="confirm" onClick={() => setExportDialogOpen(true)} disabled={isPurging || isPurged}>
            <FileDown size={16} />请求数据导出
          </Button>
        </div>
      </section>

      <section className="panel settings-card settings-card--danger">
        <header>
          <UserX size={24} />
          <h2>注销账号</h2>
        </header>
        <p>注销后账号立即封禁，3 天内可由管理员恢复。到期后系统将异步删除所有个人数据。</p>
        {status && status.ownedTeamCount > 0 && (
          <div className="danger-callout">
            <AlertTriangle size={16} />
            <p>你拥有 {status.ownedTeamCount} 个团队空间。注销前需要转让或解散这些团队。</p>
          </div>
        )}
        <div className="settings-actions">
          <Button variant="danger" onClick={() => setCancelDialogOpen(true)} disabled={isCancelled || isPurging || isPurged}>
            <UserX size={16} />注销账号
          </Button>
        </div>
      </section>
    </div>

    <section className="panel">
      <header className="panel-header">
        <div>
          <span className="section-eyebrow">Export History</span>
          <h2>导出历史</h2>
          <p>你的数据导出请求记录。</p>
        </div>
        <Button variant="ghost" onClick={() => exports.refetch()}><RefreshCw size={16} />刷新</Button>
      </header>

      {exports.isLoading ? <LoadingState label="加载导出历史" /> : (exports.data || []).length === 0 ? (
        <EmptyState title="没有导出记录" message="请求数据导出后会显示在这里。" />
      ) : (
        <div className="data-table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>任务 ID</th>
                <th>状态</th>
                <th>范围</th>
                <th>请求时间</th>
                <th>过期时间</th>
                <th><span className="sr-only">操作</span></th>
              </tr>
            </thead>
            <tbody>
              {(exports.data || []).map((job) => (
                <ExportRow key={job.jobId} job={job} />
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>

    <Dialog
      open={exportDialogOpen}
      onOpenChange={setExportDialogOpen}
      title="请求数据导出"
      description="系统将打包你的所有个人数据并加密。"
      footer={
        <>
          <Button onClick={() => setExportDialogOpen(false)}>取消</Button>
          <Button variant="confirm" loading={requestExport.isPending} onClick={() => requestExport.mutate()}>
            确认导出
          </Button>
        </>
      }
    >
      <div className="dialog-info">
        <p>导出内容包括：</p>
        <ul>
          <li>个人文件（云盘）</li>
          <li>知识库文档和配置</li>
          <li>AI 记忆和会话历史</li>
          <li>API Key 和 Webhook 配置（不含密钥明文）</li>
        </ul>
        <p className="hint">导出完成后，下载链接有效期为 24 小时。</p>
      </div>
    </Dialog>

    <Dialog
      open={cancelDialogOpen}
      onOpenChange={setCancelDialogOpen}
      title="注销账号"
      description="此操作不可自行撤销，请谨慎决定。"
      footer={
        <>
          <Button onClick={() => setCancelDialogOpen(false)}>取消</Button>
          <Button
            variant="danger"
            loading={cancelAccount.isPending}
            disabled={status && status.ownedTeamCount > 0 && !confirmTeams}
            onClick={() => cancelAccount.mutate()}
          >
            确认注销
          </Button>
        </>
      }
    >
      <div className="danger-callout">
        <AlertTriangle size={18} />
        <div>
          <strong>注销后果：</strong>
          <ul>
            <li>账号立即封禁，无法登录</li>
            <li>3 天内可由管理员恢复</li>
            <li>到期后所有个人数据将被永久删除</li>
            <li>团队引用数据会保留，但个人数据不会</li>
          </ul>
        </div>
      </div>
      {status && status.ownedTeamCount > 0 && (
        <label className="checkbox-label">
          <input
            type="checkbox"
            checked={confirmTeams}
            onChange={(event) => setConfirmTeams(event.target.checked)}
          />
          <span>我确认已处理名下 {status.ownedTeamCount} 个团队空间（转让或解散）</span>
        </label>
      )}
      <div className="dialog-form">
        <label htmlFor="cancel-reason">注销原因（可选）</label>
        <input
          id="cancel-reason"
          type="text"
          value={cancelReason}
          onChange={(event) => setCancelReason(event.target.value)}
          placeholder="帮助我们改进服务"
        />
      </div>
    </Dialog>
  </div>;
}

function ExportRow({ job }: { job: DataExportJob }) {
  const canDownload = job.status === "COMPLETED" && job.downloadUrl;
  return (
    <tr>
      <td><code>#{job.jobId}</code></td>
      <td><StatusBadge tone={exportStatusTone[job.status] || "neutral"}>{exportStatusLabel[job.status] || job.status}</StatusBadge></td>
      <td>{job.exportScope || "FULL"}</td>
      <td>{formatTime(job.createTime)}</td>
      <td>{formatTime(job.expiresAt)}</td>
      <td>
        {canDownload ? (
          <Button variant="confirm" size="sm" asChild>
            <a href={job.downloadUrl} target="_blank" rel="noopener noreferrer">
              <Download size={15} />下载
            </a>
          </Button>
        ) : job.status === "FAILED" ? (
          <span className="muted-text">{job.errorMessage || "导出失败"}</span>
        ) : null}
      </td>
    </tr>
  );
}
