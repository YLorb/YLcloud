import { useQuery } from "@tanstack/react-query";
import {
  Bell, Bot, BrainCircuit, ChevronLeft, ChevronRight, Database, FileImage, FileText,
  Files, Film, HardDrive, HelpCircle, ListTodo, LogOut, Menu, MessageSquarePlus,
  Moon, Music2, Search, Settings, Shield, Sun, Trash2, User, Users, Wrench, X
} from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { NavLink, Outlet, useLocation, useNavigate } from "react-router-dom";
import { api } from "../api";
import { Button } from "../components/ui/Button";
import {
  Sidebar, SidebarBody, SidebarFooter, SidebarHeader, SidebarItem, SidebarSection,
  SidebarSectionLabel
} from "../components/ui/Sidebar";
import { formatSize } from "../fileUtils";
import { useSession } from "./session";

const primaryItems = [
  { to: "/files", label: "文件", icon: Files },
  { to: "/knowledge", label: "知识库", icon: Database },
  { to: "/assistant", label: "AI 问答", icon: Bot },
  { to: "/tasks", label: "任务", icon: ListTodo }
];

const fileItems = [
  { key: "all", label: "全部文件", icon: Files },
  { key: "images", label: "图片", icon: FileImage },
  { key: "videos", label: "视频", icon: Film },
  { key: "music", label: "音乐", icon: Music2 },
  { key: "documents", label: "文档", icon: FileText },
  { key: "recycle", label: "回收站", icon: Trash2 }
];

const settingItems = [
  { to: "/account", label: "Profile", icon: User },
  { to: "/spaces", label: "Members", icon: Users },
  { to: "/memories", label: "AI 记忆", icon: BrainCircuit }
];

const routeMeta = [
  { test: (path: string) => path.startsWith("/files"), title: "文件" },
  { test: (path: string) => path.startsWith("/knowledge"), title: "知识库" },
  { test: (path: string) => path.startsWith("/assistant"), title: "AI 问答" },
  { test: (path: string) => path.startsWith("/tasks"), title: "任务" },
  { test: (path: string) => path.startsWith("/spaces"), title: "团队成员" },
  { test: (path: string) => path.startsWith("/memories"), title: "AI 记忆" },
  { test: (path: string) => path.startsWith("/account"), title: "个人设置" },
  { test: (path: string) => path.startsWith("/admin"), title: "系统管理" }
];

export function AppShell() {
  const { user, signOut } = useSession();
  const navigate = useNavigate();
  const location = useLocation();
  const [collapsed, setCollapsed] = useState(() => localStorage.getItem("ylcloud_nav_collapsed") === "true");
  const [mobileOpen, setMobileOpen] = useState(false);
  const [dark, setDark] = useState(() => localStorage.getItem("ylcloud_theme") === "dark");
  const isAdmin = user?.role?.toUpperCase() === "ADMIN";
  const meta = useMemo(() => routeMeta.find((item) => item.test(location.pathname)) || { title: "YL Cloud" }, [location.pathname]);
  const quota = useQuery({ queryKey: ["storage-quota"], queryFn: api.storageQuota, staleTime: 60_000, retry: 1 });
  const recentChats = useQuery({
    queryKey: ["shell-recent-chats"],
    queryFn: () => api.listKnowledgeChatSessions("", 4),
    enabled: location.pathname.startsWith("/assistant"),
    staleTime: 30_000,
    retry: 1
  });
  const selectedFileCategory = new URLSearchParams(location.search).get("category") || "all";

  useEffect(() => {
    document.documentElement.dataset.theme = dark ? "dark" : "light";
    localStorage.setItem("ylcloud_theme", dark ? "dark" : "light");
  }, [dark]);
  useEffect(() => setMobileOpen(false), [location.pathname, location.search]);

  function toggleCollapsed() {
    setCollapsed((current) => {
      localStorage.setItem("ylcloud_nav_collapsed", String(!current));
      return !current;
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
      <Sidebar className={mobileOpen ? "app-sidebar app-sidebar--mobile-open" : "app-sidebar"} aria-label="主导航">
        <SidebarHeader>
          <NavLink className="brand-lockup" to="/files" aria-label="YL Cloud 首页">
            <span className="brand-mark"><HardDrive size={20} /></span><strong>YL Cloud</strong>
          </NavLink>
          <button className="sidebar-mobile-close" onClick={() => setMobileOpen(false)} aria-label="关闭导航"><X size={20} /></button>
        </SidebarHeader>
        <SidebarBody>
          <SidebarSection>
            <SidebarSectionLabel>工作台</SidebarSectionLabel>
            {primaryItems.map(({ to, label, icon: Icon }) => (
              <SidebarItem key={to} asChild selected={location.pathname.startsWith(to)} title={collapsed ? label : undefined}>
                <NavLink to={to}><Icon size={18} aria-hidden="true" /><span>{label}</span></NavLink>
              </SidebarItem>
            ))}
          </SidebarSection>

          {location.pathname.startsWith("/files") && <SidebarSection className="sidebar-context-section">
            <SidebarSectionLabel>文件分类</SidebarSectionLabel>
            {fileItems.map(({ key, label, icon: Icon }) => (
              <SidebarItem key={key} asChild level={2} selected={selectedFileCategory === key} title={collapsed ? label : undefined}>
                <NavLink to={`/files?category=${key}`}><Icon size={17} aria-hidden="true" /><span>{label}</span></NavLink>
              </SidebarItem>
            ))}
          </SidebarSection>}

          {location.pathname.startsWith("/assistant") && <SidebarSection className="sidebar-context-section">
            <SidebarSectionLabel>对话</SidebarSectionLabel>
            <SidebarItem asChild level={2} title={collapsed ? "新建问答" : undefined}>
              <NavLink to="/assistant?new=1"><MessageSquarePlus size={17} /><span>新建问答</span></NavLink>
            </SidebarItem>
            {!collapsed && <div className="sidebar-recent-list">
              {(recentChats.data || []).map((chat) => <SidebarItem key={chat.id} asChild level={2} selected={location.search.includes(`sessionId=${chat.id}`)}>
                <NavLink to={`/assistant?sessionId=${chat.id}`}><span className="sidebar-item-dot" /><span>{chat.title || "新会话"}</span></NavLink>
              </SidebarItem>)}
            </div>}
          </SidebarSection>}

          <SidebarSection>
            <SidebarSectionLabel>设置</SidebarSectionLabel>
            {settingItems.map(({ to, label, icon: Icon }) => <SidebarItem key={to} asChild level={2} selected={location.pathname.startsWith(to)} title={collapsed ? label : undefined}>
              <NavLink to={to}><Icon size={17} /><span>{label}</span></NavLink>
            </SidebarItem>)}
            {isAdmin && <>
              <SidebarItem asChild level={2} selected={location.pathname.startsWith("/admin/settings")}><NavLink to="/admin/settings"><Settings size={17} /><span>系统设置</span></NavLink></SidebarItem>
              <SidebarItem asChild level={3} selected={location.pathname.startsWith("/admin/audit")}><NavLink to="/admin/audit"><Shield size={16} /><span>安全审计</span></NavLink></SidebarItem>
              <SidebarItem asChild level={3} selected={location.pathname.startsWith("/admin/operations")}><NavLink to="/admin/operations"><Wrench size={16} /><span>系统运维</span></NavLink></SidebarItem>
            </>}
          </SidebarSection>
        </SidebarBody>
        <SidebarFooter>
          {!collapsed && <div className="sidebar-quota"><span>存储空间 <strong>{Math.round(quota.data?.usagePercent || 0)}%</strong></span><progress max={100} value={quota.data?.usagePercent || 0} /><small>{formatSize(quota.data?.usedBytes)} / {formatSize(quota.data?.totalBytes)}</small></div>}
          <SidebarItem onClick={toggleCollapsed} aria-expanded={!collapsed} title={collapsed ? "展开侧边栏" : undefined}>
            {collapsed ? <ChevronRight size={18} /> : <><ChevronLeft size={18} /><span>收起导航</span></>}
          </SidebarItem>
        </SidebarFooter>
      </Sidebar>

      <section className="app-workspace">
        <header className="app-topbar">
          <button className="mobile-menu-button" onClick={() => setMobileOpen(true)} aria-label="打开导航"><Menu size={20} /></button>
          <h1>{meta.title}</h1>
          <label className="topbar-search"><Search size={17} /><input aria-label="全局搜索" placeholder="搜索文件或知识库" /></label>
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
      <nav className="mobile-bottom-nav" aria-label="移动端主导航">
        {primaryItems.map(({ to, label, icon: Icon }) => <NavLink key={to} to={to} className={({ isActive }) => isActive ? "mobile-nav-item mobile-nav-item--active" : "mobile-nav-item"}><Icon size={20} /><span>{label}</span></NavLink>)}
      </nav>
    </div>
  );
}
