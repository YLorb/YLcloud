import { useQuery } from "@tanstack/react-query";
import { lazy, Suspense, type ReactNode } from "react";
import { Navigate, Outlet, RouterProvider, createBrowserRouter, useLocation, useParams } from "react-router-dom";
import { api } from "./api";
import { AppShell } from "./app/AppShell";
import { RouteError } from "./app/RouteError";
import { useSession } from "./app/session";
import { Button } from "./components/ui/Button";
import { AuthPage } from "./features/auth/AuthPage";
import { PublicShareView } from "./features/share/PublicShareView";
import { LoadingState } from "./components/ui/PageState";

const FilesPage = lazy(() => import("./features/files/FilesPage").then((module) => ({ default: module.FilesPage })));
const SpacesPage = lazy(() => import("./features/spaces/SpacesPage").then((module) => ({ default: module.SpacesPage })));
const KnowledgePage = lazy(() => import("./features/knowledge/KnowledgePage").then((module) => ({ default: module.KnowledgePage })));
const AssistantPage = lazy(() => import("./features/assistant/AssistantPage").then((module) => ({ default: module.AssistantPage })));
const MemoryPage = lazy(() => import("./features/memory/MemoryPage").then((module) => ({ default: module.MemoryPage })));
const TasksPage = lazy(() => import("./features/async/TasksPage").then((module) => ({ default: module.TasksPage })));
const AdminSettingsPage = lazy(() => import("./features/settings/AdminSettingsPage").then((module) => ({ default: module.AdminSettingsPage })));
const SecurityAuditPage = lazy(() => import("./features/admin/SecurityAuditPage").then((module) => ({ default: module.SecurityAuditPage })));
const BackupStatusPage = lazy(() => import("./features/admin/BackupStatusPage").then((module) => ({ default: module.BackupStatusPage })));
const SystemOperationsPage = lazy(() => import("./features/admin/SystemOperationsPage").then((module) => ({ default: module.SystemOperationsPage })));
const AccountSettingsPage = lazy(() => import("./features/account/AccountSettingsPage").then((module) => ({ default: module.AccountSettingsPage })));

function LazyPage({ children }: { children: ReactNode }) {
  return <Suspense fallback={<LoadingState label="正在加载页面" />}>{children}</Suspense>;
}

function ProtectedRoute() {
  const { user } = useSession();
  const location = useLocation();
  return user ? <Outlet /> : <Navigate to="/login" replace state={{ from: `${location.pathname}${location.search}` }} />;
}

function AdminRoute() {
  const { user } = useSession();
  return user?.role?.toUpperCase() === "ADMIN" ? <Outlet /> : <main className="route-error"><span className="route-error__code">403</span><h1>需要管理员权限</h1><p>当前账号不能访问管理设置。请使用管理员账号登录或联系现有管理员授权。</p><Button asChild><a href="/files">返回文件</a></Button></main>;
}

function PublicShareRoute() {
  const { shareCode = "" } = useParams();
  const settings = useQuery({ queryKey: ["public-settings"], queryFn: api.publicSettings, staleTime: 300_000 });
  return <PublicShareView shareCode={shareCode} settings={settings.data || null} />;
}

function NotFound() {
  return <main className="route-error"><span className="route-error__code">404</span><h1>没有找到这个页面</h1><p>链接可能已失效，或页面地址已更改。</p><Button asChild variant="primary"><a href="/files">返回文件</a></Button></main>;
}

const router = createBrowserRouter([
  { path: "/login", element: <AuthPage mode="login" />, errorElement: <RouteError /> },
  { path: "/sign", element: <AuthPage mode="sign" />, errorElement: <RouteError /> },
  { path: "/share/:shareCode", element: <PublicShareRoute />, errorElement: <RouteError /> },
  {
    element: <ProtectedRoute />,
    errorElement: <RouteError />,
    children: [{
      element: <AppShell />,
      children: [
        { index: true, element: <Navigate to="/files" replace /> },
        { path: "/files", element: <LazyPage><FilesPage /></LazyPage> },
        { path: "/spaces", element: <LazyPage><SpacesPage /></LazyPage> },
        { path: "/knowledge", element: <LazyPage><KnowledgePage /></LazyPage> },
        { path: "/assistant", element: <LazyPage><AssistantPage /></LazyPage> },
        { path: "/memories", element: <LazyPage><MemoryPage /></LazyPage> },
        { path: "/tasks", element: <LazyPage><TasksPage /></LazyPage> },
        { path: "/account", element: <LazyPage><AccountSettingsPage /></LazyPage> },
        { element: <AdminRoute />, children: [
          { path: "/admin/settings", element: <LazyPage><AdminSettingsPage /></LazyPage> },
          { path: "/admin/audit", element: <LazyPage><SecurityAuditPage /></LazyPage> },
          { path: "/admin/backup", element: <LazyPage><BackupStatusPage /></LazyPage> },
          { path: "/admin/operations", element: <LazyPage><SystemOperationsPage /></LazyPage> }
        ] }
      ]
    }]
  },
  { path: "*", element: <NotFound /> }
]);

export function App() { return <RouterProvider router={router} />; }
