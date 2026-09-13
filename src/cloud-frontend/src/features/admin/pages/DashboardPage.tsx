import { Activity, ArrowUpRight, FileText, HardDrive, RefreshCw, Server, ShieldCheck, Users, Zap } from "lucide-react";
import { toast } from "sonner";
import { Button } from "../../../components/ui/Button";
import { AdminPage, AdminSection, AdminStat, AdminStats, AdminUnavailableHint } from "../components/AdminPage";
import { useAdminDataSource } from "../core/AdminDataSource";
import { LiveDashboardPage } from "./LiveDashboardPage";

const metrics = [
  { key: "files", label: "新增文件数", icon: FileText, color: "primary" },
  { key: "users", label: "新增用户数", icon: Users, color: "success" },
  { key: "space", label: "新增 Space", icon: HardDrive, color: "info" },
  { key: "tokens", label: "Token 消耗量", icon: Zap, color: "warning" }
];

export function DashboardPage() {
  return useAdminDataSource().mode === "live" ? <LiveDashboardPage /> : <MockDashboardPage />;
}

function MockDashboardPage() {
  return (
    <AdminPage eyebrow="管理后台" title="面板首页" description="集中查看平台活动、容量和运行状态。" actions={<><label className="admin-compact-select"><span className="sr-only">统计时间范围</span><select defaultValue="7d"><option value="24h">最近 24 小时</option><option value="7d">最近 7 天</option><option value="30d">最近 30 天</option></select></label><Button variant="ghost" onClick={() => toast.message("当前为前端演示模式，暂无可刷新的服务器统计")}><RefreshCw size={16} />刷新</Button></>}>
      <AdminStats>{metrics.map(({ key, label, icon: Icon, color }) => <AdminStat key={key} label={label} value="0" detail="暂无服务器统计" tone={color as "info" | "success" | "warning"} icon={<Icon size={17} />} />)}</AdminStats>
      <div className="admin-dashboard-layout">
        <AdminSection title="活动趋势" description="文件、用户、Space 与 Token 的时间序列将在接入统计接口后显示。">
          <div className="admin-empty-chart" role="img" aria-label="暂无活动趋势数据">
            <div className="admin-empty-chart__grid" aria-hidden="true"><i /><i /><i /><i /></div>
            <Activity size={24} aria-hidden="true" /><strong>暂无趋势数据</strong><span>选择时间范围后，图表结构会保持不变。</span>
          </div>
          <div className="admin-chart-legend" aria-label="图例"><span><i className="is-primary" />文件</span><span><i className="is-success" />用户</span><span><i className="is-info" />Space</span><span><i className="is-warning" />Token</span></div>
        </AdminSection>
        <div className="admin-dashboard-side">
          <AdminSection title="系统状态" description="此处不会探测真实服务。">
            <ul className="admin-health-list"><li><Server size={16} /><span>应用服务</span><strong>未连接</strong></li><li><HardDrive size={16} /><span>对象存储</span><strong>未连接</strong></li><li><ShieldCheck size={16} /><span>安全审计</span><strong>未连接</strong></li></ul>
          </AdminSection>
          <AdminSection title="最近活动" description="管理员操作与系统事件摘要。"><div className="admin-compact-empty"><Activity size={20} /><strong>暂无活动</strong><span>接入事件数据源后显示。</span></div></AdminSection>
        </div>
      </div>
      <AdminSection title="快捷入口" description="直接进入常用管理工作台。"><nav className="admin-shortcuts" aria-label="管理快捷入口"><a href="/admin/user-list"><Users size={17} />用户管理<ArrowUpRight size={14} /></a><a href="/admin/file-list"><FileText size={17} />文件管理<ArrowUpRight size={14} /></a><a href="/admin/events"><Activity size={17} />系统事件<ArrowUpRight size={14} /></a><a href="/admin/storage"><HardDrive size={17} />存储策略<ArrowUpRight size={14} /></a></nav></AdminSection>
      <AdminUnavailableHint>统计与健康状态均为前端结构预览，不代表服务当前运行状况。</AdminUnavailableHint>
    </AdminPage>
  );
}
