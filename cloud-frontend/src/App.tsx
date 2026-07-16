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
const TasksPage = lazy(() => import("./features/async/TasksPage").then((module) => ({ default: module.TasksPage })));
const AdminSettingsPage = lazy(() => import("./features/settings/AdminSettingsPage").then((module) => ({ default: module.AdminSettingsPage })));

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
  return user?.role?.toUpperCase() === "ADMIN" ? <Outlet /> : <Navigate to="/files" replace />;
}

function PublicShareRoute() {
  const { shareCode = "" } = useParams();
  const settings = useQuery({ queryKey: ["public-settings"], queryFn: api.publicSettings, staleTime: 300_000 });
  return <PublicShareView shareCode={shareCode} settings={settings.data || null} />;
}

function NotFound() {
  return <main className="route-error"><span className="route-error__code">404</span><h1>没有找到这个页面</h1><p>链接可能已失效，或页面地址已更改。</p><Button asChild variant="confirm"><a href="/files">返回我的文件</a></Button></main>;
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
        { path: "/tasks", element: <LazyPage><TasksPage /></LazyPage> },
        { element: <AdminRoute />, children: [{ path: "/admin/settings", element: <LazyPage><AdminSettingsPage /></LazyPage> }] }
      ]
    }]
  },
  { path: "*", element: <NotFound /> }
]);

export function App() { return <RouterProvider router={router} />; }
