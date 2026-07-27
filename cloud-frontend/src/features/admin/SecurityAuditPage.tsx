import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AlertTriangle, CheckCircle2, Database, Download, RefreshCw, Search, Shield, XCircle } from "lucide-react";
import { useMemo, useState } from "react";
import { toast } from "sonner";
import { api } from "../../api";
import { Button } from "../../components/ui/Button";
import { EmptyState, ErrorState, LoadingState } from "../../components/ui/PageState";
import { StatusBadge, type StatusTone } from "../../components/ui/StatusBadge";
import { formatTime } from "../../fileUtils";
import type { AuditRetentionConfig, SecurityAuditEvent } from "../../types";

const outcomeTone: Record<string, StatusTone> = { SUCCESS: "success", FAILURE: "danger", DENIED: "warning" };
const severityTone: Record<string, StatusTone> = { INFO: "info", WARNING: "warning", CRITICAL: "danger" };

export function SecurityAuditPage() {
  const client = useQueryClient();
  const [query, setQuery] = useState("");
  const [outcomeFilter, setOutcomeFilter] = useState("all");
  const [severityFilter, setSeverityFilter] = useState("all");
  const [page, setPage] = useState(1);
  const [selectedEvent, setSelectedEvent] = useState<SecurityAuditEvent | null>(null);

  const events = useQuery({
    queryKey: ["audit-events", outcomeFilter, severityFilter, page],
    queryFn: () => api.queryAuditEvents({
      outcome: outcomeFilter === "all" ? undefined : outcomeFilter,
      severity: severityFilter === "all" ? undefined : severityFilter,
      page,
      pageSize: 50
    }),
    refetchInterval: 30_000
  });

  const stats = useQuery({ queryKey: ["audit-stats"], queryFn: () => api.auditStats(), refetchInterval: 60_000 });
  const retentionConfigs = useQuery({ queryKey: ["audit-retention"], queryFn: api.listRetentionConfigs });

  const filtered = useMemo(() => {
    const records = events.data?.records || [];
    if (!query) return records;
    const lower = query.toLowerCase();
    return records.filter((event) =>
      event.eventType.toLowerCase().includes(lower) ||
      event.actorName?.toLowerCase().includes(lower) ||
      event.targetName?.toLowerCase().includes(lower) ||
      event.traceId?.toLowerCase().includes(lower)
    );
  }, [events.data, query]);

  const updateRetention = useMutation({
    mutationFn: ({ configKey, retentionDays }: { configKey: string; retentionDays: number }) =>
      api.updateRetentionConfig(configKey, retentionDays),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ["audit-retention"] });
      toast.success("保留策略已更新");
    },
    onError: (error) => toast.error(error instanceof Error ? error.message : "更新失败")
  });

  if (events.isLoading) return <LoadingState label="正在加载安全审计日志" />;
  if (events.isError) return <ErrorState message={events.error instanceof Error ? events.error.message : "无法加载审计日志"} onRetry={() => events.refetch()} />;

  return <div className="security-audit-page">
    <section className="admin-security-note">
      <Shield size={22} />
      <div>
        <strong>安全审计</strong>
        <p>不可变的安全事件记录。CRITICAL 事件永久保留，STANDARD 事件按策略清理。</p>
      </div>
      <StatusBadge tone="success">审计系统运行中</StatusBadge>
    </section>

    {stats.data && <div className="metrics-grid">
      <Metric label="总事件数" value={stats.data.totalEvents} tone="info" />
      <Metric label="成功" value={stats.data.successCount} tone="success" />
      <Metric label="失败" value={stats.data.failureCount} tone={stats.data.failureCount ? "danger" : "neutral"} />
      <Metric label="拒绝" value={stats.data.deniedCount} tone={stats.data.deniedCount ? "warning" : "neutral"} />
      <Metric label="CRITICAL" value={stats.data.criticalCount} tone={stats.data.criticalCount ? "danger" : "neutral"} />
      <Metric label="永久保留" value={stats.data.permanentCount} tone="info" />
    </div>}

    <section className="panel">
      <div className="content-toolbar">
        <label className="search-box">
          <Search size={17} />
          <input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="搜索事件类型、用户、目标或 Trace ID" />
        </label>
        <select value={outcomeFilter} onChange={(event) => { setOutcomeFilter(event.target.value); setPage(1); }} aria-label="结果筛选">
          <option value="all">全部结果</option>
          <option value="SUCCESS">成功</option>
          <option value="FAILURE">失败</option>
          <option value="DENIED">拒绝</option>
        </select>
        <select value={severityFilter} onChange={(event) => { setSeverityFilter(event.target.value); setPage(1); }} aria-label="严重级别筛选">
          <option value="all">全部级别</option>
          <option value="INFO">INFO</option>
          <option value="WARNING">WARNING</option>
          <option value="CRITICAL">CRITICAL</option>
        </select>
        <Button variant="ghost" onClick={() => { events.refetch(); stats.refetch(); }}><RefreshCw size={16} />刷新</Button>
      </div>

      {filtered.length === 0 ? (
        <EmptyState title="没有匹配的审计事件" message="安全相关操作会自动记录在此。" />
      ) : (
        <div className="data-table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>事件类型</th>
                <th>结果</th>
                <th>级别</th>
                <th>操作者</th>
                <th>目标</th>
                <th>时间</th>
                <th><span className="sr-only">操作</span></th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((event) => (
                <tr key={event.eventId}>
                  <td><code>{event.eventType}</code></td>
                  <td><StatusBadge tone={outcomeTone[event.outcome] || "neutral"}>{event.outcome}</StatusBadge></td>
                  <td><StatusBadge tone={severityTone[event.severity] || "neutral"}>{event.severity}</StatusBadge></td>
                  <td>{event.actorName || `#${event.actorId || "—"}`}</td>
                  <td>{event.targetName || (event.targetType ? `${event.targetType}#${event.targetId}` : "—")}</td>
                  <td>{formatTime(event.createTime)}</td>
                  <td><Button variant="ghost" size="sm" onClick={() => setSelectedEvent(event)}>详情</Button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <div className="pagination-bar">
        <Button variant="ghost" size="sm" disabled={page <= 1} onClick={() => setPage((p) => p - 1)}>上一页</Button>
        <span>第 {page} 页 / 共 {Math.ceil((events.data?.total || 0) / 50)} 页</span>
        <Button variant="ghost" size="sm" disabled={page >= Math.ceil((events.data?.total || 0) / 50)} onClick={() => setPage((p) => p + 1)}>下一页</Button>
      </div>
    </section>

    <section className="panel">
      <header className="panel-header">
        <div>
          <span className="section-eyebrow">Retention Policy</span>
          <h2>保留策略配置</h2>
          <p>配置不同类别审计事件的保留天数。PERMANENT 策略的事件不会被自动清理。</p>
        </div>
      </header>
      {retentionConfigs.isLoading ? <LoadingState label="加载保留策略" /> : (
        <div className="retention-config-list">
          {(retentionConfigs.data || []).map((config) => (
            <RetentionConfigRow key={config.configKey} config={config} onUpdate={(days) => updateRetention.mutate({ configKey: config.configKey, retentionDays: days })} updating={updateRetention.isPending} />
          ))}
        </div>
      )}
    </section>

    {selectedEvent && (
      <div className="modal-overlay" onClick={() => setSelectedEvent(null)}>
        <div className="modal-content audit-detail-modal" onClick={(event) => event.stopPropagation()}>
          <header>
            <h3>审计事件详情</h3>
            <Button variant="ghost" size="sm" onClick={() => setSelectedEvent(null)}>关闭</Button>
          </header>
          <dl className="detail-grid">
            <div><dt>事件 ID</dt><dd>{selectedEvent.eventId}</dd></div>
            <div><dt>事件类型</dt><dd><code>{selectedEvent.eventType}</code></dd></div>
            <div><dt>事件类别</dt><dd>{selectedEvent.eventCategory}</dd></div>
            <div><dt>结果</dt><dd><StatusBadge tone={outcomeTone[selectedEvent.outcome] || "neutral"}>{selectedEvent.outcome}</StatusBadge></dd></div>
            <div><dt>严重级别</dt><dd><StatusBadge tone={severityTone[selectedEvent.severity] || "neutral"}>{selectedEvent.severity}</StatusBadge></dd></div>
            <div><dt>保留策略</dt><dd>{selectedEvent.retentionPolicy}</dd></div>
            <div><dt>操作者</dt><dd>{selectedEvent.actorName || `#${selectedEvent.actorId || "—"}`} ({selectedEvent.actorType || "—"})</dd></div>
            <div><dt>目标</dt><dd>{selectedEvent.targetName || `${selectedEvent.targetType || "—"}#${selectedEvent.targetId || "—"}`}</dd></div>
            <div><dt>IP 地址</dt><dd>{selectedEvent.ipAddress || "—"}</dd></div>
            <div><dt>Trace ID</dt><dd><code>{selectedEvent.traceId || "—"}</code></dd></div>
            <div><dt>时间</dt><dd>{formatTime(selectedEvent.createTime)}</dd></div>
          </dl>
          {selectedEvent.detailJson && (
            <details className="detail-json">
              <summary>详细信息 (JSON)</summary>
              <pre>{JSON.stringify(JSON.parse(selectedEvent.detailJson), null, 2)}</pre>
            </details>
          )}
        </div>
      </div>
    )}
  </div>;
}

function Metric({ label, value, tone }: { label: string; value: number; tone: StatusTone }) {
  return <article className={`metric-card metric-card--${tone}`}><div><small>{label}</small><strong>{value}</strong></div></article>;
}

function RetentionConfigRow({ config, onUpdate, updating }: { config: AuditRetentionConfig; onUpdate: (days: number) => void; updating: boolean }) {
  const [days, setDays] = useState(config.retentionDays);
  const changed = days !== config.retentionDays;
  return <div className="retention-config-row">
    <div>
      <strong>{config.configKey}</strong>
      <p>{config.description || "—"}</p>
    </div>
    <div className="retention-control">
      <label className="unit-input">
        <input type="number" min={1} max={3650} value={days} onChange={(event) => setDays(Number(event.target.value))} />
        <span>天</span>
      </label>
      <Button variant="confirm" size="sm" disabled={!changed || updating} loading={updating} onClick={() => onUpdate(days)}>保存</Button>
    </div>
  </div>;
}
