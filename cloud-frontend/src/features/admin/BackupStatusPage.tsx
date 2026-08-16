import { useQuery } from "@tanstack/react-query";
import { CheckCircle2, Clock, Database, HardDrive, RefreshCw, XCircle } from "lucide-react";
import { useState } from "react";
import { api } from "../../api";
import { Button } from "../../components/ui/Button";
import { EmptyState, ErrorState, LoadingState } from "../../components/ui/PageState";
import { StatusBadge, type StatusTone } from "../../components/ui/StatusBadge";
import { formatTime } from "../../fileUtils";
import type { BackupRun } from "../../types";

const statusTone: Record<string, StatusTone> = {
  PENDING: "warning",
  RUNNING: "running",
  VERIFYING: "info",
  READY: "success",
  FAILED: "danger",
  EXPIRED: "neutral"
};

const statusLabel: Record<string, string> = {
  PENDING: "等待中",
  RUNNING: "执行中",
  VERIFYING: "验证中",
  READY: "就绪",
  FAILED: "失败",
  EXPIRED: "已过期"
};

export function BackupStatusPage() {
  const [selectedBackup, setSelectedBackup] = useState<BackupRun | null>(null);

  const stats = useQuery({ queryKey: ["backup-stats"], queryFn: api.backupStats, refetchInterval: 60_000 });
  const readyBackups = useQuery({ queryKey: ["backup-ready"], queryFn: api.listReadyBackups, refetchInterval: 60_000 });
  const recentBackups = useQuery({ queryKey: ["backup-recent"], queryFn: () => api.listRecentBackups(20), refetchInterval: 30_000 });
  const restoreVerification = useQuery({
    queryKey: ["backup-restore-verification", selectedBackup?.id],
    queryFn: () => api.getRestoreVerification(selectedBackup!.id),
    enabled: selectedBackup != null
  });

  if (stats.isLoading || recentBackups.isLoading) return <LoadingState label="正在加载备份状态" />;
  if (stats.isError) return <ErrorState message={stats.error instanceof Error ? stats.error.message : "无法加载备份状态"} onRetry={() => stats.refetch()} />;

  return <div className="backup-status-page">
    <section className="admin-security-note">
      <Database size={22} />
      <div>
        <strong>备份与恢复</strong>
        <p>系统备份状态监控。升级前需要至少一个 READY 状态的备份。</p>
      </div>
      <StatusBadge tone={stats.data && stats.data.readyBackups > 0 ? "success" : "danger"}>
        {stats.data && stats.data.readyBackups > 0 ? `${stats.data.readyBackups} 个可用备份` : "无可用备份"}
      </StatusBadge>
    </section>

    {stats.data && <div className="metrics-grid">
      <Metric label="就绪备份" value={stats.data.readyBackups} tone={stats.data.readyBackups > 0 ? "success" : "danger"} icon={<CheckCircle2 size={18} />} />
      <Metric label="最近失败" value={(recentBackups.data || []).filter((backup) => backup.status === "FAILED").length} tone="warning" icon={<XCircle size={18} />} />
      <Metric label="最新就绪备份大小" value={formatBytes(stats.data.latestBackup.sizeBytes)} tone="info" icon={<HardDrive size={18} />} />
      <Metric label="最新发布时间" value={formatTime(stats.data.latestBackup.publishedAt)} tone="info" icon={<Clock size={18} />} />
    </div>}

    <section className="panel">
      <header className="panel-header">
        <div>
          <span className="section-eyebrow">Ready Backups</span>
          <h2>可用备份</h2>
          <p>可用于系统恢复的就绪备份列表。</p>
        </div>
        <Button variant="ghost" onClick={() => { readyBackups.refetch(); recentBackups.refetch(); stats.refetch(); }}>
          <RefreshCw size={16} />刷新
        </Button>
      </header>

      {readyBackups.isLoading ? <LoadingState label="加载可用备份" /> : (readyBackups.data || []).length === 0 ? (
        <EmptyState title="没有可用备份" message="运行 scripts/backup.sh 创建新备份。升级前必须有至少一个 READY 备份。" />
      ) : (
        <div className="data-table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>备份 ID</th>
                <th>类型</th>
                <th>状态</th>
                <th>大小</th>
                <th>加密</th>
                <th>完成时间</th>
                <th><span className="sr-only">操作</span></th>
              </tr>
            </thead>
            <tbody>
              {(readyBackups.data || []).map((backup) => (
                <tr key={backup.id}>
                  <td><code>#{backup.id}</code></td>
                  <td>{backup.backupType}</td>
                  <td><StatusBadge tone={statusTone[backup.status] || "neutral"}>{statusLabel[backup.status] || backup.status}</StatusBadge></td>
                  <td>{formatBytes(backup.archiveSizeBytes)}</td>
                  <td><StatusBadge tone="success">已加密</StatusBadge></td>
                  <td>{formatTime(backup.finishedAt || backup.createdAt)}</td>
                  <td><Button variant="ghost" size="sm" onClick={() => setSelectedBackup(backup)}>详情</Button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>

    <section className="panel">
      <header className="panel-header">
        <div>
          <span className="section-eyebrow">Recent Backups</span>
          <h2>最近备份记录</h2>
          <p>所有备份执行的历史记录，包括失败和过期的备份。</p>
        </div>
      </header>

      {recentBackups.isLoading ? <LoadingState label="加载备份历史" /> : (recentBackups.data || []).length === 0 ? (
        <EmptyState title="没有备份记录" message="备份执行后会显示在这里。" />
      ) : (
        <div className="data-table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>备份 ID</th>
                <th>类型</th>
                <th>状态</th>
                <th>大小</th>
                <th>开始时间</th>
                <th>完成时间</th>
                <th><span className="sr-only">操作</span></th>
              </tr>
            </thead>
            <tbody>
              {(recentBackups.data || []).map((backup) => (
                <tr key={backup.id}>
                  <td><code>#{backup.id}</code></td>
                  <td>{backup.backupType}</td>
                  <td><StatusBadge tone={statusTone[backup.status] || "neutral"}>{statusLabel[backup.status] || backup.status}</StatusBadge></td>
                  <td>{formatBytes(backup.archiveSizeBytes)}</td>
                  <td>{formatTime(backup.startedAt || backup.createdAt)}</td>
                  <td>{formatTime(backup.finishedAt)}</td>
                  <td><Button variant="ghost" size="sm" onClick={() => setSelectedBackup(backup)}>详情</Button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>

    {selectedBackup && (
      <div className="modal-overlay" onClick={() => setSelectedBackup(null)}>
        <div className="modal-content backup-detail-modal" onClick={(event) => event.stopPropagation()}>
          <header>
            <h3>备份详情 #{selectedBackup.id}</h3>
            <Button variant="ghost" size="sm" onClick={() => setSelectedBackup(null)}>关闭</Button>
          </header>
          <dl className="detail-grid">
            <div><dt>备份类型</dt><dd>{selectedBackup.backupType}</dd></div>
            <div><dt>状态</dt><dd><StatusBadge tone={statusTone[selectedBackup.status] || "neutral"}>{statusLabel[selectedBackup.status] || selectedBackup.status}</StatusBadge></dd></div>
            <div><dt>运行标识</dt><dd><code>{selectedBackup.runKey}</code></dd></div>
            <div><dt>归档路径</dt><dd><code>{selectedBackup.archivePath || "—"}</code></dd></div>
            <div><dt>加密</dt><dd>AES-256-CBC（PBKDF2）已启用</dd></div>
            <div><dt>大小</dt><dd>{formatBytes(selectedBackup.archiveSizeBytes)}</dd></div>
            <div><dt>SHA-256</dt><dd><code>{selectedBackup.archiveHash || "—"}</code></dd></div>
            <div><dt>恢复验证</dt><dd>{restoreVerification.isLoading ? "查询中" : restoreVerification.data?.status || "无记录"}</dd></div>
            <div><dt>验证环境</dt><dd>{restoreVerification.data?.restoreEnvironment || "—"}</dd></div>
            <div><dt>开始时间</dt><dd>{formatTime(selectedBackup.startedAt)}</dd></div>
            <div><dt>完成时间</dt><dd>{formatTime(selectedBackup.finishedAt)}</dd></div>
            <div><dt>验证时间</dt><dd>{formatTime(selectedBackup.verifiedAt)}</dd></div>
            <div><dt>发布时间</dt><dd>{formatTime(selectedBackup.publishedAt)}</dd></div>
          </dl>
          <div className="backup-restore-hint">
            <Clock size={16} />
            <p>恢复此备份请运行: <code>scripts/restore.sh --backup-id {selectedBackup.id}</code></p>
          </div>
        </div>
      </div>
    )}
  </div>;
}

function Metric({ label, value, tone, icon }: { label: string; value: string | number; tone: StatusTone; icon?: React.ReactNode }) {
  return <article className={`metric-card metric-card--${tone}`}>{icon}<div><small>{label}</small><strong>{value}</strong></div></article>;
}

function formatBytes(bytes?: number): string {
  if (bytes == null) return "—";
  if (bytes === 0) return "0 B";
  const units = ["B", "KB", "MB", "GB", "TB"];
  const i = Math.floor(Math.log(bytes) / Math.log(1024));
  return `${(bytes / Math.pow(1024, i)).toFixed(i > 0 ? 1 : 0)} ${units[i]}`;
}
