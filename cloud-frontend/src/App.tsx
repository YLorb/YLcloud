import { useEffect, useRef, useState } from "react";
import { api, getStoredUser } from "./api";
import { AuthPage } from "./features/auth/AuthPage";
import { DriveApp } from "./features/files/DriveApp";
import type { PublicSiteSettings, User } from "./types";

export function App() {
  const [user, setUser] = useState<User | null>(() => getStoredUser());
  const [path, setPath] = useState(() => window.location.pathname);
  const [publicSettings, setPublicSettings] = useState<PublicSiteSettings | null>(null);
  const pathRef = useRef(window.location.pathname);
  const navigationGuardRef = useRef<(() => boolean) | null>(null);

  async function refreshPublicSettings() {
    try {
      setPublicSettings(await api.publicSettings());
    } catch {
      setPublicSettings(null);
    }
  }

  useEffect(() => {
    void refreshPublicSettings();
  }, []);

  useEffect(() => {
    const syncPath = () => {
      const nextPath = window.location.pathname;
      if (nextPath !== pathRef.current && navigationGuardRef.current && !navigationGuardRef.current()) {
        window.history.pushState(null, "", pathRef.current);
        return;
      }
      pathRef.current = nextPath;
      setPath(nextPath);
    };
    window.addEventListener("popstate", syncPath);
    return () => window.removeEventListener("popstate", syncPath);
  }, []);

  function navigate(nextPath: string) {
    if (window.location.pathname !== nextPath && navigationGuardRef.current && !navigationGuardRef.current()) {
      return false;
    }
    if (window.location.pathname !== nextPath) {
      window.history.pushState(null, "", nextPath);
    }
    pathRef.current = nextPath;
    setPath(nextPath);
    return true;
  }

  useEffect(() => {
    if (!user && path !== "/login" && path !== "/sign") {
      navigate("/login");
    }
    if (user && (path === "/login" || path === "/sign")) {
      navigate("/");
    }
  }, [path, user]);

  if (user) {
    return (
      <DriveApp
        user={user}
        publicSettings={publicSettings}
        path={path}
        onNavigate={navigate}
        onNavigationGuardChange={(guard) => {
          navigationGuardRef.current = guard;
        }}
        onSettingsSaved={refreshPublicSettings}
        onLogout={() => {
          if (navigationGuardRef.current && !navigationGuardRef.current()) return;
          navigationGuardRef.current = null;
          setUser(null);
          navigate("/login");
        }}
      />
    );
  }

  return (
    <AuthPage
      mode={path === "/sign" ? "sign" : "login"}
      publicSettings={publicSettings}
      onNavigate={navigate}
      onSignedIn={(nextUser) => {
        navigationGuardRef.current = null;
        setUser(nextUser);
        navigate("/");
      }}
    />
  );
}
