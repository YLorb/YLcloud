import * as DropdownMenu from "@radix-ui/react-dropdown-menu";
import { useQuery } from "@tanstack/react-query";
import {
  Bell, Bot, BrainCircuit, ChevronDown, ChevronLeft, ChevronRight, Cloud, Database, FileImage, FileText,
  Film, Folder, ListTodo, LogOut, Menu, MessageSquarePlus,
  Moon, Music2, Search, Settings, Sun, Trash2, User, Users, X
} from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";
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
  { to: "/files", label: "我的文件", icon: Folder },
  { to: "/spaces", label: "共享空间", icon: Users },
  { to: "/knowledge", label: "知识库", icon: Database },
  { to: "/assistant", label: "AI 对话", icon: Bot },
  { to: "/tasks", label: "任务中心", icon: ListTodo }
];

const fileItems = [
  { key: "images", label: "图片", icon: FileImage },
  { key: "videos", label: "视频", icon: Film },
  { key: "music", label: "音乐", icon: Music2 },
  { key: "documents", label: "文档", icon: FileText }
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
  const [noticeOpen, setNoticeOpen] = useState(false);
  const searchInputRef = useRef<HTMLInputElement>(null);
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
  const fileQuery = new URLSearchParams(location.search).get("q") || "";

  useEffect(() => {
    document.documentElement.dataset.theme = dark ? "dark" : "light";
    localStorage.setItem("ylcloud_theme", dark ? "dark" : "light");
  }, [dark]);
  useEffect(() => setMobileOpen(false), [location.pathname, location.search]);
  useEffect(() => {
    if (!location.pathname.startsWith("/files")) return;
    const focusSearch = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "k") {
        event.preventDefault();
        searchInputRef.current?.focus();
      }
    };
    document.addEventListener("keydown", focusSearch);
    return () => document.removeEventListener("keydown", focusSearch);
  }, [location.pathname]);

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

  function searchFiles(value: string) {
    const next = new URLSearchParams(location.pathname.startsWith("/files") ? location.search : "");
    if (value) next.set("q", value); else next.delete("q");
    navigate(`/files${next.size ? `?${next.toString()}` : ""}`, { replace: true });
  }

  return (
    <div className={collapsed ? "app-shell app-shell--collapsed" : "app-shell"}>
      <a className="skip-link" href="#main-content">跳到主要内容</a>
      {mobileOpen && <button className="mobile-scrim" aria-label="关闭导航" onClick={() => setMobileOpen(false)} />}
      <Sidebar className={mobileOpen ? "app-sidebar app-sidebar--mobile-open" : "app-sidebar"} aria-label="主导航">
        <SidebarHeader>
          <NavLink className="brand-lockup" to="/files" aria-label="YL Cloud 首页">
            <span className="brand-mark"><Cloud size={28} strokeWidth={2.8} /></span><strong>YLCloud</strong>
          </NavLink>
          <button className="sidebar-mobile-close" onClick={() => setMobileOpen(false)} aria-label="关闭导航"><X size={20} /></button>
        </SidebarHeader>
        <SidebarBody>
          <SidebarSection>
            <SidebarItem asChild selected={location.pathname.startsWith("/files") && selectedFileCategory !== "recycle"} title={collapsed ? "我的文件" : undefined}>
              <NavLink to="/files"><Folder size={18} aria-hidden="true" /><span>我的文件</span></NavLink>
            </SidebarItem>
            <SidebarItem asChild selected={location.pathname.startsWith("/spaces")} title={collapsed ? "共享空间" : undefined}>
              <NavLink to="/spaces"><Users size={18} aria-hidden="true" /><span>共享空间</span></NavLink>
            </SidebarItem>
            <SidebarItem asChild selected={selectedFileCategory === "recycle" && location.pathname.startsWith("/files")} title={collapsed ? "回收站" : undefined}>
              <NavLink to="/files?category=recycle"><Trash2 size={18} aria-hidden="true" /><span>回收站</span></NavLink>
            </SidebarItem>
          </SidebarSection>

          {location.pathname.startsWith("/files") && <SidebarSection className="sidebar-context-section">
            <SidebarSectionLabel>文件分类</SidebarSectionLabel>
            {fileItems.map(({ key, label, icon: Icon }) => (
              <SidebarItem key={key} asChild level={2} selected={selectedFileCategory === key} title={collapsed ? label : undefined}>
                <NavLink to={`/files?category=${key}`}><Icon size={17} aria-hidden="true" /><span>{label}</span></NavLink>
              </SidebarItem>
            ))}
          </SidebarSection>}

          <SidebarSection>
            <SidebarSectionLabel>智能能力</SidebarSectionLabel>
            {primaryItems.filter(({ to }) => ["/knowledge", "/assistant", "/tasks"].includes(to)).map(({ to, label, icon: Icon }) => <SidebarItem key={to} asChild selected={location.pathname.startsWith(to)} title={collapsed ? label : undefined}>
              <NavLink to={to}><Icon size={18} aria-hidden="true" /><span>{label}</span></NavLink>
            </SidebarItem>)}
          </SidebarSection>

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
            {isAdmin && <SidebarItem asChild level={2} selected={location.pathname.startsWith("/admin")} title={collapsed ? "系统管理" : undefined}>
              <NavLink to="/admin"><Settings size={17} /><span>系统管理</span></NavLink>
            </SidebarItem>}
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
          <h1 className={location.pathname.startsWith("/files") ? "sr-only" : undefined}>{meta.title}</h1>
          {location.pathname.startsWith("/files") && <label className="topbar-search"><Search size={17} /><input ref={searchInputRef} aria-label="搜索文件" value={fileQuery} onChange={(event) => searchFiles(event.target.value)} placeholder="搜索文件或文件夹…" /><kbd>Ctrl K</kbd></label>}
          <div className="topbar-actions">
            <div className="topbar-notice"><Button variant="ghost" size="icon" aria-label="通知" aria-expanded={noticeOpen} onClick={() => setNoticeOpen((open) => !open)}><Bell size={19} /></Button>{noticeOpen && <div className="topbar-notice__popover" role="status">暂无新通知</div>}</div>
            <DropdownMenu.Root><DropdownMenu.Trigger className="topbar-account" aria-label="账户菜单"><span className="user-avatar">{(user?.nickname || user?.username || "U").slice(0, 1).toUpperCase()}</span><span className="topbar-account__copy"><strong>{user?.nickname || user?.username || "用户"}</strong><small>{isAdmin ? "管理员" : "个人用户"}</small></span><ChevronDown size={15} /></DropdownMenu.Trigger><DropdownMenu.Portal><DropdownMenu.Content className="dropdown-menu topbar-account-menu" align="end" sideOffset={8}><DropdownMenu.Item onSelect={() => navigate("/account")}><User size={16} />账户设置</DropdownMenu.Item><DropdownMenu.Item onSelect={() => setDark((value) => !value)}>{dark ? <Sun size={16} /> : <Moon size={16} />}{dark ? "浅色模式" : "深色模式"}</DropdownMenu.Item><DropdownMenu.Separator /><DropdownMenu.Item onSelect={logout}><LogOut size={16} />退出登录</DropdownMenu.Item></DropdownMenu.Content></DropdownMenu.Portal></DropdownMenu.Root>
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
