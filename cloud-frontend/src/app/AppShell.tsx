import { useQuery } from "@tanstack/react-query";
import {
  Bot, BrainCircuit, ChevronLeft, ChevronRight, Database, Files, HardDrive, LayoutGrid,
  ListTodo, LogOut, Menu, Moon, Settings, Shield, Sun, User, Users, Wrench, X
} from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { NavLink, Outlet, useLocation, useNavigate } from "react-router-dom";
import { api } from "../api";
import { Button } from "../components/ui/Button";
import { useSession } from "./session";
import { formatSize } from "../fileUtils";

const navItems = [
  { to: "/files", label: "我的文件", icon: Files },
  { to: "/spaces", label: "团队空间", icon: Users },
  { to: "/knowledge", label: "知识库", icon: Database },
  { to: "/assistant", label: "AI Assistant", icon: Bot },
  { to: "/memories", label: "记忆管理", icon: BrainCircuit },
  { to: "/tasks", label: "后台任务", icon: ListTodo },
  { to: "/account", label: "账号设置", icon: User }
];

const routeMeta = [
  { test: (path: string) => path.startsWith("/memories"), title: "记忆管理", description: "查看、修正或删除 AI 为跨会话连续性保存的个人长期记忆。" },
  { test: (path: string) => path.startsWith("/files"), title: "我的文件", description: "浏览、上传和管理你的云端文件。" },
  { test: (path: string) => path.startsWith("/spaces"), title: "团队空间", description: "协作管理文档、成员与版本。" },
  { test: (path: string) => path.startsWith("/knowledge"), title: "知识库", description: "管理 RAG 索引、知识画像和检索质量。" },
  { test: (path: string) => path.startsWith("/assistant"), title: "AI Assistant", description: "基于一个或多个知识库进行可信、可恢复的问答。" },
  { test: (path: string) => path.startsWith("/tasks"), title: "后台任务", description: "查看执行阶段、结果并恢复失败任务。" },
  { test: (path: string) => path.startsWith("/account"), title: "账号设置", description: "管理账号状态、数据导出和注销选项。" },
  { test: (path: string) => path.startsWith("/admin/audit"), title: "安全审计", description: "查看不可变的安全事件日志和保留策略。" },
  { test: (path: string) => path.startsWith("/admin/backup"), title: "备份状态", description: "监控系统备份和恢复就绪状态。" },
  { test: (path: string) => path.startsWith("/admin/operations"), title: "系统运维", description: "维护模式控制和升级检查清单。" },
  { test: (path: string) => path.startsWith("/admin"), title: "Admin Settings", description: "管理站点信息、权限、存储配额和 AI/RAG 配置。" }
];

export function AppShell() {
  const { user, signOut } = useSession();
  const navigate = useNavigate();
  const location = useLocation();
  const [collapsed, setCollapsed] = useState(() => localStorage.getItem("ylcloud_nav_collapsed") === "true");
  const [mobileOpen, setMobileOpen] = useState(false);
  const [dark, setDark] = useState(() => localStorage.getItem("ylcloud_theme") === "dark");
  const isAdmin = user?.role?.toUpperCase() === "ADMIN";
  const meta = useMemo(() => routeMeta.find((item) => item.test(location.pathname)) || routeMeta[0], [location.pathname]);
  const quota = useQuery({ queryKey: ["storage-quota"], queryFn: api.storageQuota, staleTime: 60_000, retry: 1 });

  useEffect(() => {
    document.documentElement.dataset.theme = dark ? "dark" : "light";
    localStorage.setItem("ylcloud_theme", dark ? "dark" : "light");
  }, [dark]);
  useEffect(() => {
    setMobileOpen(false);
  }, [location.pathname]);

  function toggleCollapsed() {
    setCollapsed((current) => {
      const next = !current;
      localStorage.setItem("ylcloud_nav_collapsed", String(next));
      return next;
    });
  }

  function logout() {
    signOut();
    navigate("/login", { replace: true });
  }

  return (
    <div className={collapsed ? "app-shell app-shell--collapsed" : "app-shell"}>
      <a className="skip-link" href="#main-content">跳到主要内容</a>
      {mobileOpen && <button className="mobile-scrim" aria-label="关闭导航" onClick={() => setMobileOpen(false)} />}
      <aside className={mobileOpen ? "app-sidebar app-sidebar--mobile-open" : "app-sidebar"} aria-label="主导航">
        <div className="brand-lockup">
          <span className="brand-mark"><HardDrive size={21} /></span>
          <span className="brand-text"><strong>YLCloud</strong><small>知识资产云</small></span>
          <button className="sidebar-mobile-close" onClick={() => setMobileOpen(false)} aria-label="关闭导航"><X size={20} /></button>
        </div>
        <nav className="primary-nav">
          <span className="nav-eyebrow">工作空间</span>
          {navItems.map(({ to, label, icon: Icon }) => (
            <NavLink key={to} to={to} aria-label={label} title={collapsed ? label : undefined} className={({ isActive }) => isActive ? "nav-item nav-item--active" : "nav-item"}>
              <Icon size={19} aria-hidden="true" /><span>{label}</span>
            </NavLink>
          ))}
          {isAdmin && (
            <>
              <span className="nav-eyebrow nav-eyebrow--spaced">管理</span>
              <NavLink to="/admin/settings" aria-label="Admin Settings" title={collapsed ? "Admin Settings" : undefined} className={({ isActive }) => isActive ? "nav-item nav-item--active" : "nav-item"}>
                <Settings size={19} aria-hidden="true" /><span>Admin Settings</span>
              </NavLink>
              <NavLink to="/admin/audit" aria-label="安全审计" title={collapsed ? "安全审计" : undefined} className={({ isActive }) => isActive ? "nav-item nav-item--active" : "nav-item"}>
                <Shield size={19} aria-hidden="true" /><span>安全审计</span>
              </NavLink>
              <NavLink to="/admin/backup" aria-label="备份状态" title={collapsed ? "备份状态" : undefined} className={({ isActive }) => isActive ? "nav-item nav-item--active" : "nav-item"}>
                <Database size={19} aria-hidden="true" /><span>备份状态</span>
              </NavLink>
              <NavLink to="/admin/operations" aria-label="系统运维" title={collapsed ? "系统运维" : undefined} className={({ isActive }) => isActive ? "nav-item nav-item--active" : "nav-item"}>
                <Wrench size={19} aria-hidden="true" /><span>系统运维</span>
              </NavLink>
            </>
          )}
        </nav>
        <div className="sidebar-footer">
          <div className="quota-card">
            <div><span>存储空间</span><strong>{Math.round(quota.data?.usagePercent || 0)}%</strong></div>
            <progress max={100} value={quota.data?.usagePercent || 0}>存储已使用 {quota.data?.usagePercent || 0}%</progress>
            <small>{formatSize(quota.data?.usedBytes)} / {formatSize(quota.data?.totalBytes)}</small>
          </div>
          <button className="collapse-button" onClick={toggleCollapsed} aria-expanded={!collapsed} aria-label={collapsed ? "展开侧边栏" : "收起侧边栏"}>
            {collapsed ? <ChevronRight size={18} /> : <><ChevronLeft size={18} /><span>收起导航</span></>}
          </button>
        </div>
      </aside>
      <section className="app-workspace">
        <header className="app-topbar">
          <button className="mobile-menu-button" onClick={() => setMobileOpen(true)} aria-label="打开导航"><Menu size={21} /></button>
          <div className="page-heading"><h1>{meta.title}</h1><p>{meta.description}</p></div>
          <div className="topbar-actions">
            <Button variant="ghost" size="icon" aria-label={dark ? "切换到浅色主题" : "切换到深色主题"} onClick={() => setDark((value) => !value)}>
              {dark ? <Sun size={18} /> : <Moon size={18} />}
            </Button>
            <div className="user-menu-label"><span>{(user?.nickname || user?.username || "U").slice(0, 1).toUpperCase()}</span><div><strong>{user?.nickname || user?.username}</strong><small>{isAdmin ? "管理员" : "用户"}</small></div></div>
            <Button variant="ghost" size="icon" aria-label="退出登录" onClick={logout}><LogOut size={18} /></Button>
          </div>
        </header>
        <main id="main-content" className="route-content" tabIndex={-1}><Outlet /></main>
      </section>
      <nav className="mobile-bottom-nav" aria-label="移动端主导航">
        {navItems.slice(0, 4).map(({ to, label, icon: Icon }) => (
          <NavLink key={to} to={to} className={({ isActive }) => isActive ? "mobile-nav-item mobile-nav-item--active" : "mobile-nav-item"}>
            <Icon size={20} /><span>{label}</span>
          </NavLink>
        ))}
        <button className="mobile-nav-item" onClick={() => setMobileOpen(true)}><LayoutGrid size={20} /><span>更多</span></button>
      </nav>
    </div>
  );
}
