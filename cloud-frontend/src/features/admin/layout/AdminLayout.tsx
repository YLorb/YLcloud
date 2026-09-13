import {
  BarChart3, Bell, ChevronLeft, ChevronRight, Database, FileCog, FileText,
  HardDrive, HelpCircle, ListTodo, LogOut, Menu, MessageSquarePlus,
  Moon, Search, Settings, Shield, Sun, User, Users, X
} from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { NavLink, Outlet, useLocation, useNavigate } from "react-router-dom";
import { Button } from "../../../components/ui/Button";
import {
  Sidebar, SidebarBody, SidebarFooter, SidebarHeader, SidebarItem, SidebarSection,
  SidebarSectionLabel
} from "../../../components/ui/Sidebar";
import { useSession } from "../../../app/session";
import { AdminDataSourceProvider } from "../core/AdminDataSource";

const sidebarItems = [
  { to: "/admin/dashboard", label: "面板首页", icon: BarChart3 },
  { to: "/admin/settings", label: "参数设置", icon: Settings },
  { to: "/admin/fs", label: "文件系统", icon: FileCog },
  { to: "/admin/storage", label: "存储策略", icon: Database },
  { to: "/admin/nodes", label: "节点", icon: HardDrive },
  { to: "/admin/user-groups", label: "用户组", icon: Users },
  { to: "/admin/user-list", label: "用户", icon: User },
  { to: "/admin/file-list", label: "文件", icon: FileText },
  { to: "/admin/shares", label: "分享", icon: MessageSquarePlus },
  { to: "/admin/tasks", label: "后台任务", icon: ListTodo },
  { to: "/admin/orders", label: "订单", icon: ListTodo },
  { to: "/admin/events", label: "事件", icon: Bell },
  { to: "/admin/reports", label: "滥用举报", icon: Shield },
  { to: "/admin/oauth", label: "OAuth 应用", icon: Shield }
];

function matchTitle(pathname: string): string {
  for (const item of sidebarItems) {
    if (pathname === item.to || pathname.startsWith(item.to + "/")) return item.label;
  }
  return "系统管理";
}

function isActive(pathname: string, to: string): boolean {
  return pathname === to || pathname.startsWith(to + "/");
}

export function AdminLayout() {
  const { user, signOut } = useSession();
  const navigate = useNavigate();
  const location = useLocation();
  const [collapsed, setCollapsed] = useState(() => localStorage.getItem("ylcloud_admin_nav_collapsed") === "true");
  const [mobileOpen, setMobileOpen] = useState(false);
  const [dark, setDark] = useState(() => localStorage.getItem("ylcloud_theme") === "dark");
  const title = useMemo(() => matchTitle(location.pathname), [location.pathname]);

  useEffect(() => {
    document.documentElement.dataset.theme = dark ? "dark" : "light";
    localStorage.setItem("ylcloud_theme", dark ? "dark" : "light");
  }, [dark]);
  useEffect(() => setMobileOpen(false), [location.pathname, location.search]);

  function toggleCollapsed() {
    setCollapsed((current) => {
      localStorage.setItem("ylcloud_admin_nav_collapsed", String(!current));
      return !current;
    });
  }

  function logout() {
    signOut();
    navigate("/login", { replace: true });
  }

  function returnToMain() {
    navigate("/files", { replace: true });
  }

  return (
    <AdminDataSourceProvider>
    <div className={collapsed ? "app-shell app-shell--collapsed" : "app-shell"}>
      <a className="skip-link" href="#main-content">跳到主要内容</a>
      {mobileOpen && <button className="mobile-scrim" aria-label="关闭导航" onClick={() => setMobileOpen(false)} />}
      <Sidebar className={mobileOpen ? "app-sidebar app-sidebar--mobile-open" : "app-sidebar"} aria-label="Admin 导航">
        <SidebarHeader>
          <NavLink className="brand-lockup" to="/admin/dashboard" aria-label="YL Cloud Admin">
            <span className="brand-mark"><HardDrive size={20} /></span><strong>YL Cloud Admin</strong>
          </NavLink>
          <button className="sidebar-mobile-close" onClick={() => setMobileOpen(false)} aria-label="关闭导航"><X size={20} /></button>
        </SidebarHeader>
        <SidebarBody>
          <SidebarSection>
            <SidebarSectionLabel>管理</SidebarSectionLabel>
            {sidebarItems.map(({ to, label, icon: Icon }) => (
              <SidebarItem key={to} asChild selected={isActive(location.pathname, to)} title={collapsed ? label : undefined}>
                <NavLink to={to}><Icon size={18} aria-hidden="true" /><span>{label}</span></NavLink>
              </SidebarItem>
            ))}
          </SidebarSection>
        </SidebarBody>
        <SidebarFooter>
          {!collapsed && <div className="sidebar-quota"><span>演示存储 <strong>0%</strong></span><progress max={100} value={0} /><small>暂无服务器数据</small></div>}
          <SidebarItem onClick={toggleCollapsed} aria-expanded={!collapsed} title={collapsed ? "展开侧边栏" : undefined}>
            {collapsed ? <ChevronRight size={18} /> : <><ChevronLeft size={18} /><span>收起导航</span></>}
          </SidebarItem>
          <SidebarItem onClick={returnToMain} title={collapsed ? "返回主页" : undefined}>
            <HardDrive size={18} /><span>返回主页</span>
          </SidebarItem>
        </SidebarFooter>
      </Sidebar>

      <section className="app-workspace">
        <header className="app-topbar">
          <button className="mobile-menu-button" onClick={() => setMobileOpen(true)} aria-label="打开导航"><Menu size={20} /></button>
          <h1>{title}</h1>
          <div className="topbar-actions">
            <Button variant="ghost" size="icon" aria-label="帮助"><HelpCircle size={18} /></Button>
            <Button variant="ghost" size="icon" aria-label="通知"><Bell size={18} /></Button>
            <Button variant="ghost" size="icon" aria-label={dark ? "切换到浅色主题" : "切换到深色主题"} onClick={() => setDark((value) => !value)}>{dark ? <Sun size={18} /> : <Moon size={18} />}</Button>
            <span className="user-avatar" title={user?.nickname || user?.username}>{(user?.nickname || user?.username || "U").slice(0, 1).toUpperCase()}</span>
            <Button variant="ghost" size="icon" aria-label="退出登录" onClick={logout}><LogOut size={18} /></Button>
          </div>
        </header>
        <main id="main-content" className="route-content" tabIndex={-1}><Outlet /></main>
      </section>
      <nav className="mobile-bottom-nav" aria-label="移动端Admin导航">
        {sidebarItems.slice(0, 5).map(({ to, label, icon: Icon }) => <NavLink key={to} to={to} className={({ isActive }) => isActive ? "mobile-nav-item mobile-nav-item--active" : "mobile-nav-item"}><Icon size={20} /><span>{label}</span></NavLink>)}
      </nav>
    </div>
    </AdminDataSourceProvider>
  );
}
