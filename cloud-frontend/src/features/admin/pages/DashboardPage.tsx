import { FileText, HardDrive, Users, Zap } from "lucide-react";
import { useQuery } from "@tanstack/react-query";
import { api } from "../../../api";

const metrics = [
  { key: "files", label: "新增文件数", icon: FileText, color: "primary" },
  { key: "users", label: "新增用户数", icon: Users, color: "success" },
  { key: "space", label: "新增 Space", icon: HardDrive, color: "info" },
  { key: "tokens", label: "Token 消耗量", icon: Zap, color: "warning" }
];

export function DashboardPage() {
  return (
    <div className="admin-dashboard">
      <header className="panel-header">
        <div>
          <span className="section-eyebrow">管理后台</span>
          <h2>面板首页</h2>
          <p>系统统计数据概览</p>
        </div>
      </header>
      <div className="admin-metrics-grid">
        {metrics.map(({ key, label, icon: Icon, color }) => (
          <div key={key} className={`metric-card metric-card--${color}`}>
            <span><Icon size={19} /></span>
            <div>
              <small>{label}</small>
              <strong>--</strong>
            </div>
          </div>
        ))}
      </div>
      <div className="admin-chart-placeholder">
        <span>图表区域</span>
        <p>新增文件、新增用户、新增 Space、Token 消耗量趋势图表</p>
      </div>
    </div>
  );
}
