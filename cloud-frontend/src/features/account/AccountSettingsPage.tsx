import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AlertTriangle, Archive, Database, Download, FileDown, IdCard, RefreshCw, Shield, UserRound, Users, UserX } from "lucide-react";
import { useState } from "react";
import { toast } from "sonner";
import { api } from "../../api";
import { Button } from "../../components/ui/Button";
import { Dialog } from "../../components/ui/Dialog";
import { ManagementFacts, ManagementMetric, ManagementMetrics, ManagementPageHeader, ManagementSection } from "../../components/ui/ManagementPage";
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
  const [exportCredential, setExportCredential] = useState<DataExportJob | null>(null);

  const accountStatus = useQuery({ queryKey: ["account-status"], queryFn: api.accountStatus });
  const exports = useQuery({ queryKey: ["data-exports"], queryFn: api.listDataExports, refetchInterval: 10_000 });

  const cancelAccount = useMutation({
    mutationFn: () => api.cancelAccount({ reason: cancelReason, confirmTeamOwnerTransfer: confirmTeams }),
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
  const loadExportCredential = useMutation({
    mutationFn: (jobId: number) => api.getDataExport(jobId),
    onSuccess: setExportCredential,
    onError: (error) => toast.error(error instanceof Error ? error.message : "获取下载凭据失败")
  });

  if (accountStatus.isLoading) return <LoadingState label="正在加载账号状态" />;
  if (accountStatus.isError) return <ErrorState message={accountStatus.error instanceof Error ? accountStatus.error.message : "无法加载账号状态"} onRetry={() => accountStatus.refetch()} />;

  const status = accountStatus.data;
  const isCancelled = status?.accountStatus === "CANCELLED";
  const isPurging = status?.accountStatus === "PURGING";
  const isPurged = status?.accountStatus === "PURGED";
  const accountLabel = status?.accountStatus === "ACTIVE" ? "正常" : isCancelled ? "已注销" : isPurging ? "清理中" : "已删除";
  const exportJobs = exports.data || [];
  const activeExports = exportJobs.filter((job) => job.status === "PENDING" || job.status === "RUNNING").length;
  const lastExport = exportJobs[0];

  return <div className="management-page account-settings-page">
    <ManagementPageHeader eyebrow="SETTINGS · PROFILE" title="个人账户" description="查看身份信息，管理个人数据副本与账号生命周期。"
      actions={<Button variant="ghost" onClick={() => { accountStatus.refetch(); exports.refetch(); }} loading={accountStatus.isFetching || exports.isFetching}><RefreshCw size={16} />刷新</Button>} />

    <ManagementMetrics>
      <ManagementMetric icon={<Shield size={18} />} label="账号状态" value={accountLabel} detail={isCancelled && status?.recoverableUntil ? `${formatTime(status.recoverableUntil)} 前可恢复` : "账号访问状态"} tone={isCancelled ? "warning" : isPurging || isPurged ? "danger" : "success"} />
      <ManagementMetric icon={<UserRound size={18} />} label="用户名" value={status?.username || "—"} detail={`用户 ID #${status?.userId ?? "—"}`} tone="info" />
      <ManagementMetric icon={<Users size={18} />} label="名下团队" value={status?.ownedTeamCount ?? 0} detail={status?.isTeamOwner ? "注销前必须完成转让" : "当前不是团队所有者"} tone={status?.ownedTeamCount ? "warning" : "neutral"} />
      <ManagementMetric icon={<Archive size={18} />} label="数据导出" value={exports.isError ? "不可用" : exportJobs.length} detail={activeExports ? `${activeExports} 个任务处理中` : lastExport ? `最近请求 ${formatTime(lastExport.createdAt)}` : "尚无导出记录"} tone={exports.isError ? "danger" : activeExports ? "info" : "neutral"} />
    </ManagementMetrics>

    {isCancelled && status?.recoverableUntil && (
      <section className="management-alert management-alert--warning" role="alert">
        <AlertTriangle size={20} /><div><strong>账号已注销</strong><p>将在 {formatTime(status.recoverableUntil)} 后永久删除；恢复账号需要联系管理员。</p></div>
      </section>
    )}

    <div className="management-layout account-management-layout">
      <div className="management-layout__main">
        <ManagementSection icon={<IdCard size={18} />} title="个人资料" description="用于登录、资源归属和安全审计的基础身份信息。">
          <div className="account-profile-summary"><span aria-hidden="true">{(status?.username || "U").slice(0, 1).toUpperCase()}</span><div><strong>{status?.username || "未知用户"}</strong><small>个人账号 · ID #{status?.userId ?? "—"}</small></div><StatusBadge tone={isCancelled ? "warning" : isPurging || isPurged ? "danger" : "success"}>{accountLabel}</StatusBadge></div>
          <ManagementFacts items={[
            { label: "用户名", value: status?.username || "—" },
            { label: "用户标识", value: `#${status?.userId ?? "—"}` },
            { label: "账号类型", value: status?.isTeamOwner ? "个人账号 · 团队所有者" : "个人账号" },
            { label: "恢复资格", value: status?.canRecover ? "可由管理员恢复" : "不适用" }
          ]} />
        </ManagementSection>

        <ManagementSection icon={<Database size={18} />} title="个人数据导出" description="生成个人文件、知识库、AI 记忆与会话历史的完整副本。"
          actions={<Button variant="confirm" onClick={() => setExportDialogOpen(true)} disabled={isPurging || isPurged || activeExports > 0}><FileDown size={16} />{activeExports ? "导出处理中" : "请求导出"}</Button>}>
          <ManagementFacts items={[
            { label: "导出范围", value: "全部个人数据（FULL）" },
            { label: "归档加密", value: "AES-256-GCM" },
            { label: "下载有效期", value: "完成后 24 小时" },
            { label: "最近任务", value: lastExport ? <StatusBadge tone={exportStatusTone[lastExport.status] || "neutral"}>{exportStatusLabel[lastExport.status] || lastExport.status}</StatusBadge> : "尚无记录" }
          ]} />
          <p className="management-inline-note"><Shield size={15} />API Key 与 Webhook 只导出配置，不包含密钥明文。</p>
        </ManagementSection>
      </div>

      <aside className="management-layout__aside">
        <ManagementSection icon={<Shield size={18} />} title="账号生命周期" description="账号注销后进入 3 天恢复期，随后异步清理个人数据。">
          <ManagementFacts items={[
            { label: "当前阶段", value: accountLabel },
            { label: "团队所有权", value: status?.ownedTeamCount ? `${status.ownedTeamCount} 个空间` : "无" },
            { label: "恢复截止", value: formatTime(status?.recoverableUntil) }
          ]} />
        </ManagementSection>
        <ManagementSection icon={<UserX size={18} />} title="注销账号" description="高风险且不能自行撤销；恢复需要管理员介入。" danger>
          {status && status.ownedTeamCount > 0 && <div className="management-inline-warning"><AlertTriangle size={16} /><span>请先转让或解散名下 {status.ownedTeamCount} 个团队空间。</span></div>}
          <Button className="full-width" variant="danger" onClick={() => setCancelDialogOpen(true)} disabled={isCancelled || isPurging || isPurged}><UserX size={16} />注销账号</Button>
        </ManagementSection>
      </aside>
    </div>

    <ManagementSection icon={<Download size={18} />} title="导出历史" description="数据归档的处理状态、保留期限与下载入口。" actions={<Button variant="ghost" size="sm" onClick={() => exports.refetch()} loading={exports.isFetching}><RefreshCw size={15} />刷新</Button>}>
      {exports.isLoading ? <LoadingState label="加载导出历史" /> : exports.isError ? <ErrorState message={exports.error instanceof Error ? exports.error.message : "无法加载导出历史"} onRetry={() => exports.refetch()} /> : exportJobs.length === 0 ? (
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
              {exportJobs.map((job) => (
                <ExportRow key={job.id} job={job} onDownload={() => loadExportCredential.mutate(job.id)}
                  loading={loadExportCredential.isPending && loadExportCredential.variables === job.id} />
              ))}
            </tbody>
          </table>
        </div>
      )}
    </ManagementSection>

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
      open={exportCredential != null}
      onOpenChange={(open) => { if (!open) setExportCredential(null); }}
      title="数据导出下载凭据"
      description="解密密钥属于敏感凭据，请通过安全渠道保存。"
      footer={
        <>
          <Button onClick={() => setExportCredential(null)}>关闭</Button>
          {exportCredential?.downloadUrl && <Button variant="confirm" asChild>
            <a href={exportCredential.downloadUrl}>下载加密归档</a>
          </Button>}
        </>
      }
    >
      <div className="dialog-form">
        <label htmlFor="export-decryption-key">AES-256-GCM 解密密钥（Base64）</label>
        <textarea id="export-decryption-key" readOnly rows={3}
          value={exportCredential?.decryptionKey || "凭据不可用或已过期"} />
        <p className="hint">下载链接有效期至 {formatTime(exportCredential?.downloadExpiresAt)}。请勿在日志、工单或聊天中粘贴此密钥。</p>
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

function ExportRow({ job, onDownload, loading }: { job: DataExportJob; onDownload: () => void; loading: boolean }) {
  const canDownload = job.status === "COMPLETED" && job.downloadUrl;
  return (
    <tr>
      <td><code>#{job.id}</code></td>
      <td><StatusBadge tone={exportStatusTone[job.status] || "neutral"}>{exportStatusLabel[job.status] || job.status}</StatusBadge></td>
      <td>{job.exportScope || "FULL"}</td>
      <td>{formatTime(job.createdAt)}</td>
      <td>{formatTime(job.downloadExpiresAt)}</td>
      <td>
        {canDownload ? (
          <Button variant="confirm" size="sm" onClick={onDownload} loading={loading}>
            <Download size={15} />获取下载凭据
          </Button>
        ) : job.status === "FAILED" ? (
          <span className="muted-text">导出失败</span>
        ) : null}
      </td>
    </tr>
  );
}
