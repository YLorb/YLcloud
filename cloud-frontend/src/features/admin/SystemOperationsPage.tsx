import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AlertTriangle, CheckCircle2, Power, RefreshCw, Settings, Shield, Wrench } from "lucide-react";
import { useState } from "react";
import { toast } from "sonner";
import { api } from "../../api";
import { Button } from "../../components/ui/Button";
import { Dialog } from "../../components/ui/Dialog";
import { ErrorState, LoadingState } from "../../components/ui/PageState";
import { StatusBadge } from "../../components/ui/StatusBadge";
import { formatTime } from "../../fileUtils";

export function SystemOperationsPage() {
  const client = useQueryClient();
  const [enableDialogOpen, setEnableDialogOpen] = useState(false);
  const [disableDialogOpen, setDisableDialogOpen] = useState(false);
  const [reason, setReason] = useState("");

  const maintenance = useQuery({
    queryKey: ["maintenance-status"],
    queryFn: api.maintenanceStatus,
    refetchInterval: (result) => result.state.data?.active ? 5_000 : 30_000
  });

  const enableMaintenance = useMutation({
    mutationFn: () => api.enableMaintenance({ reason: reason || "计划内维护" }),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ["maintenance-status"] });
      setEnableDialogOpen(false);
      setReason("");
      toast.success("维护模式已启用");
    },
    onError: (error) => toast.error(error instanceof Error ? error.message : "启用维护模式失败")
  });

  const disableMaintenance = useMutation({
    mutationFn: () => api.disableMaintenance(),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ["maintenance-status"] });
      setDisableDialogOpen(false);
      toast.success("维护模式已禁用");
    },
    onError: (error) => toast.error(error instanceof Error ? error.message : "禁用维护模式失败")
  });

  if (maintenance.isLoading) return <LoadingState label="正在加载系统状态" />;
  if (maintenance.isError) return <ErrorState message={maintenance.error instanceof Error ? maintenance.error.message : "无法加载系统状态"} onRetry={() => maintenance.refetch()} />;

  const isEnabled = maintenance.data?.active;

  return <div className="system-operations-page">
    <section className={`admin-security-note ${isEnabled ? "admin-security-note--warning" : ""}`}>
      {isEnabled ? <AlertTriangle size={22} /> : <Shield size={22} />}
      <div>
        <strong>系统运维</strong>
        <p>维护模式会拒绝所有写入操作和新任务提交，仅允许读取和健康检查。</p>
      </div>
      <StatusBadge tone={isEnabled ? "warning" : "success"}>
        {isEnabled ? "维护模式已启用" : "系统正常运行"}
      </StatusBadge>
    </section>

    {isEnabled && (
      <section className="maintenance-banner" role="alert">
        <AlertTriangle size={24} />
        <div>
          <strong>系统正在维护中</strong>
          <p>
            原因: {maintenance.data?.reason || "未指定"}
            {maintenance.data?.startedAt && <span> · 启用时间: {formatTime(maintenance.data.startedAt)}</span>}
          </p>
        </div>
        <Button variant="danger" onClick={() => setDisableDialogOpen(true)}>
          <Power size={16} />禁用维护模式
        </Button>
      </section>
    )}

    <div className="operations-grid">
      <section className="panel operation-card">
        <header>
          <Wrench size={24} />
          <h2>维护模式</h2>
        </header>
        <p>启用维护模式后，系统将拒绝所有写入操作（POST/PUT/DELETE）和新的异步任务提交。读取操作和健康检查端点保持可用。</p>
        <dl className="operation-facts">
          <div><dt>当前状态</dt><dd><StatusBadge tone={isEnabled ? "warning" : "success"}>{isEnabled ? "已启用" : "已禁用"}</StatusBadge></dd></div>
          {isEnabled && maintenance.data?.reason && <div><dt>原因</dt><dd>{maintenance.data.reason}</dd></div>}
          {isEnabled && maintenance.data?.startedAt && <div><dt>启用时间</dt><dd>{formatTime(maintenance.data.startedAt)}</dd></div>}
        </dl>
        <div className="operation-actions">
          {isEnabled ? (
            <Button variant="danger" onClick={() => setDisableDialogOpen(true)} loading={disableMaintenance.isPending}>
              <Power size={16} />禁用维护模式
            </Button>
          ) : (
            <Button variant="confirm" onClick={() => setEnableDialogOpen(true)}>
              <Wrench size={16} />启用维护模式
            </Button>
          )}
        </div>
      </section>

      <section className="panel operation-card">
        <header>
          <Settings size={24} />
          <h2>升级检查清单</h2>
        </header>
        <p>执行系统升级前的标准检查流程，确保数据安全和可回滚。</p>
        <ol className="checklist">
          <li><CheckCircle2 size={16} />确认存在 READY 状态的备份</li>
          <li><CheckCircle2 size={16} />通知用户维护时间窗口</li>
          <li><CheckCircle2 size={16} />启用维护模式</li>
          <li><CheckCircle2 size={16} />执行 <code>scripts/deploy.sh &lt;version&gt;</code></li>
          <li><CheckCircle2 size={16} />验证平台健康状态</li>
          <li><CheckCircle2 size={16} />禁用维护模式</li>
        </ol>
        <div className="operation-actions">
          <Button variant="ghost" asChild>
            <a href="/admin/backup">查看备份状态</a>
          </Button>
        </div>
      </section>

      <section className="panel operation-card">
        <header>
          <Shield size={24} />
          <h2>安全审计</h2>
        </header>
        <p>查看系统安全事件日志，包括登录、权限变更、敏感操作等。CRITICAL 事件永久保留。</p>
        <div className="operation-actions">
          <Button variant="ghost" asChild>
            <a href="/admin/audit">查看审计日志</a>
          </Button>
        </div>
      </section>
    </div>

    <Dialog
      open={enableDialogOpen}
      onOpenChange={setEnableDialogOpen}
      title="启用维护模式"
      description="启用后，所有写入操作和新任务提交将被拒绝。"
      footer={
        <>
          <Button onClick={() => setEnableDialogOpen(false)}>取消</Button>
          <Button variant="confirm" loading={enableMaintenance.isPending} onClick={() => enableMaintenance.mutate()}>
            确认启用
          </Button>
        </>
      }
    >
      <div className="dialog-form">
        <label htmlFor="maintenance-reason">维护原因（可选）</label>
        <input
          id="maintenance-reason"
          type="text"
          value={reason}
          onChange={(event) => setReason(event.target.value)}
          placeholder="例如：版本升级、数据库迁移"
        />
        <p className="hint">维护期间，用户只能访问只读页面和健康检查端点。</p>
      </div>
    </Dialog>

    <Dialog
      open={disableDialogOpen}
      onOpenChange={setDisableDialogOpen}
      title="禁用维护模式"
      description="确认系统已恢复正常，可以接受写入操作。"
      footer={
        <>
          <Button onClick={() => setDisableDialogOpen(false)}>取消</Button>
          <Button variant="danger" loading={disableMaintenance.isPending} onClick={() => disableMaintenance.mutate()}>
            确认禁用
          </Button>
        </>
      }
    >
      <div className="danger-callout">
        <AlertTriangle size={18} />
        <p>禁用维护模式前，请确认所有升级步骤已完成，系统健康检查通过。</p>
      </div>
    </Dialog>
  </div>;
}
