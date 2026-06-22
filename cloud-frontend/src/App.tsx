import { useEffect, useState } from "react";
import { api, getStoredUser } from "./api";
import { AuthPage } from "./features/auth/AuthPage";
import { DriveApp } from "./features/files/DriveApp";
import type { PublicSiteSettings, User } from "./types";

export function App() {
  const [user, setUser] = useState<User | null>(() => getStoredUser());
  const [path, setPath] = useState(() => window.location.pathname);
  const [publicSettings, setPublicSettings] = useState<PublicSiteSettings | null>(null);

  useEffect(() => {
    api.publicSettings()
      .then(setPublicSettings)
      .catch(() => setPublicSettings(null));
  }, []);

  useEffect(() => {
    const syncPath = () => setPath(window.location.pathname);
    window.addEventListener("popstate", syncPath);
    return () => window.removeEventListener("popstate", syncPath);
  }, []);

  function navigate(nextPath: string) {
    if (window.location.pathname !== nextPath) {
      window.history.pushState(null, "", nextPath);
    }
    setPath(nextPath);
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
        onLogout={() => {
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
        setUser(nextUser);
        navigate("/");
      }}
    />
  );
}
