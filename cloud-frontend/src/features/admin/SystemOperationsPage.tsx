import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Activity, AlertTriangle, Archive, CheckCircle2, Circle, Clock3, ListChecks, Power, RefreshCw, Server, Shield, Wrench } from "lucide-react";
import { useState } from "react";
import { Link } from "react-router-dom";
import { toast } from "sonner";
import { api } from "../../api";
import { Button } from "../../components/ui/Button";
import { Dialog } from "../../components/ui/Dialog";
import { ManagementFacts, ManagementMetric, ManagementMetrics, ManagementPageHeader, ManagementSection } from "../../components/ui/ManagementPage";
import { ErrorState, LoadingState } from "../../components/ui/PageState";
import { StatusBadge } from "../../components/ui/StatusBadge";
import { formatTime } from "../../fileUtils";

export function SystemOperationsPage() {
  const client = useQueryClient();
  const [enableDialogOpen, setEnableDialogOpen] = useState(false);
  const [disableDialogOpen, setDisableDialogOpen] = useState(false);
  const [reason, setReason] = useState("");
  const maintenance = useQuery({ queryKey: ["maintenance-status"], queryFn: api.maintenanceStatus, refetchInterval: (result) => result.state.data?.active ? 5_000 : 30_000 });
  const backups = useQuery({ queryKey: ["backup-stats"], queryFn: api.backupStats, retry: 1 });
  const audit = useQuery({ queryKey: ["audit-stats", "operations"], queryFn: () => api.auditStats(), retry: 1 });

  const enableMaintenance = useMutation({
    mutationFn: () => api.enableMaintenance({ reason: reason.trim() || "计划内维护" }),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ["maintenance-status"] });
      setEnableDialogOpen(false);
      setReason("");
      toast.success("维护模式已启用");
    },
    onError: (error) => toast.error(error instanceof Error ? error.message : "启用维护模式失败")
  });
  const disableMaintenance = useMutation({
    mutationFn: api.disableMaintenance,
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ["maintenance-status"] });
      setDisableDialogOpen(false);
      toast.success("维护模式已禁用");
    },
    onError: (error) => toast.error(error instanceof Error ? error.message : "禁用维护模式失败")
  });

  if (maintenance.isLoading) return <LoadingState label="正在加载系统运行状态" />;
  if (maintenance.isError) return <ErrorState message={maintenance.error instanceof Error ? maintenance.error.message : "无法加载系统运行状态"} onRetry={() => maintenance.refetch()} />;

  const isEnabled = Boolean(maintenance.data?.active);
  const refreshAll = () => { maintenance.refetch(); backups.refetch(); audit.refetch(); };
  const latestBackup = backups.data?.latestBackup;
  const releaseChecks = [
    { done: Boolean(backups.data?.readyBackups), label: "至少存在一个 READY 状态备份" },
    { done: !backups.isError, label: "备份服务状态可读取" },
    { done: !audit.isError, label: "安全审计服务状态可读取" },
    { done: isEnabled, label: "发布前启用维护模式" }
  ];

  return <div className="management-page system-operations-page">
    <ManagementPageHeader eyebrow="ADMIN · OPERATIONS" title="系统运维" description="查看关键运行状态，并管理升级期间的写入保护。"
      actions={<Button variant="ghost" onClick={refreshAll} loading={maintenance.isFetching || backups.isFetching || audit.isFetching}><RefreshCw size={16} />刷新状态</Button>} />

    <ManagementMetrics>
      <ManagementMetric icon={<Activity size={18} />} label="平台状态" value={isEnabled ? "维护中" : "正常运行"} detail={isEnabled ? "写入与新任务已暂停" : "读写请求正常开放"} tone={isEnabled ? "warning" : "success"} />
      <ManagementMetric icon={<Archive size={18} />} label="可用备份" value={backups.isError ? "不可用" : backups.data?.readyBackups ?? "—"} detail={latestBackup?.publishedAt ? `最近发布 ${formatTime(latestBackup.publishedAt)}` : "尚无已发布备份"} tone={backups.isError ? "danger" : backups.data?.readyBackups ? "success" : "warning"} />
      <ManagementMetric icon={<Shield size={18} />} label="审计事件" value={audit.isError ? "不可用" : audit.data?.recentEvents ?? "—"} detail={audit.data ? `${audit.data.permanentEvents} 条永久保留` : "统计尚未加载"} tone={audit.isError ? "danger" : "info"} />
      <ManagementMetric icon={<Clock3 size={18} />} label="状态更新" value={formatTime(new Date(maintenance.dataUpdatedAt).toISOString())} detail={isEnabled ? "每 5 秒自动刷新" : "每 30 秒自动刷新"} />
    </ManagementMetrics>

    {isEnabled && <section className="management-alert management-alert--warning" role="alert">
      <AlertTriangle size={20} /><div><strong>系统正在维护中</strong><p>{maintenance.data?.reason || "未指定原因"}{maintenance.data?.startedAt ? ` · ${formatTime(maintenance.data.startedAt)} 启用` : ""}</p></div>
      <Button variant="danger" size="sm" onClick={() => setDisableDialogOpen(true)}>结束维护</Button>
    </section>}

    <div className="management-layout">
      <div className="management-layout__main">
        <ManagementSection icon={<Server size={18} />} title="维护模式" description="在升级、迁移或故障处置期间冻结所有写入操作和新任务提交。"
          actions={<StatusBadge tone={isEnabled ? "warning" : "success"}>{isEnabled ? "已启用" : "已禁用"}</StatusBadge>}>
          <ManagementFacts items={[
            { label: "当前状态", value: isEnabled ? "只读保护中" : "正常读写" },
            { label: "保护范围", value: "POST / PUT / PATCH / DELETE 与新异步任务" },
            { label: "维护原因", value: isEnabled ? maintenance.data?.reason || "未指定" : "—" },
            { label: "启用时间", value: isEnabled ? formatTime(maintenance.data?.startedAt) : "—" }
          ]} />
          <div className="management-section-footer">
            <p>{isEnabled ? "结束维护前，请先确认应用健康状态与关键业务抽样。" : "启用后不会中断读取、下载及健康检查。"}</p>
            {isEnabled ? <Button variant="danger" onClick={() => setDisableDialogOpen(true)} loading={disableMaintenance.isPending}><Power size={16} />禁用维护模式</Button>
              : <Button variant="confirm" onClick={() => setEnableDialogOpen(true)}><Wrench size={16} />启用维护模式</Button>}
          </div>
        </ManagementSection>

        <ManagementSection icon={<ListChecks size={18} />} title="升级准备度" description="根据当前可读取的运行数据生成检查结果，人工步骤仍需操作人确认。">
          <ul className="management-checklist">{releaseChecks.map((item) => <li key={item.label} className={item.done ? "is-complete" : ""}>{item.done ? <CheckCircle2 size={17} /> : <Circle size={17} />}<span>{item.label}</span><small>{item.done ? "通过" : "待确认"}</small></li>)}</ul>
          <div className="management-command"><span>发布命令</span><code>scripts/deploy.sh &lt;version&gt;</code></div>
        </ManagementSection>
      </div>

      <aside className="management-layout__aside">
        <ManagementSection title="运维入口" description="常用管理页面集中入口。">
          <nav className="management-link-list" aria-label="运维入口">
            <Link to="/admin/backup"><Archive size={17} /><span><strong>备份与恢复</strong><small>验证备份、查看恢复演练</small></span><span>进入</span></Link>
            <Link to="/admin/audit"><Shield size={17} /><span><strong>安全审计</strong><small>查询敏感操作与拒绝事件</small></span><span>进入</span></Link>
            <Link to="/admin/settings"><Wrench size={17} /><span><strong>系统设置</strong><small>模型、存储与平台策略</small></span><span>进入</span></Link>
          </nav>
        </ManagementSection>
        <div className="management-note"><Shield size={18} /><div><strong>操作会被审计</strong><p>维护模式切换属于高风险操作，将永久记录操作人、时间和结果。</p></div></div>
      </aside>
    </div>

    <Dialog open={enableDialogOpen} onOpenChange={setEnableDialogOpen} title="启用维护模式" description="启用后，所有写入操作和新任务提交将被拒绝。" footer={<><Button onClick={() => setEnableDialogOpen(false)}>取消</Button><Button variant="confirm" loading={enableMaintenance.isPending} onClick={() => enableMaintenance.mutate()}>确认启用</Button></>}>
      <div className="dialog-form"><label htmlFor="maintenance-reason">维护原因</label><input id="maintenance-reason" type="text" value={reason} maxLength={200} onChange={(event) => setReason(event.target.value)} placeholder="例如：版本升级、数据库迁移" /><p className="hint">未填写时将记录为“计划内维护”。维护期间只允许读取与健康检查。</p></div>
    </Dialog>
    <Dialog open={disableDialogOpen} onOpenChange={setDisableDialogOpen} title="禁用维护模式" description="确认系统已恢复正常，可以重新接受写入操作。" footer={<><Button onClick={() => setDisableDialogOpen(false)}>取消</Button><Button variant="danger" loading={disableMaintenance.isPending} onClick={() => disableMaintenance.mutate()}>确认禁用</Button></>}>
      <div className="danger-callout"><AlertTriangle size={18} /><p>请确认升级已完成、平台健康检查通过，并完成关键业务抽样。</p></div>
    </Dialog>
  </div>;
}
