import { createContext, useContext, useMemo, useState, useEffect, type ReactNode } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { api, ApiError, clearSession } from "../api";
import type { User } from "../types";

type SessionContextValue = {
  user: User | null;
  signIn: (user: User) => void;
  signOut: () => Promise<void>;
};

const SessionContext = createContext<SessionContextValue | null>(null);

export function SessionProvider({ children }: { children: ReactNode }) {
  const client = useQueryClient();
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    let active = true;
    clearSession(); // Remove credentials left by pre-Session versions.
    setLoading(true);
    setError(null);
    api.session().then((next) => { if (active) setUser(next); }).catch((cause) => {
      if (active && !(cause instanceof ApiError && cause.status === 401)) {
        setError(cause instanceof Error ? cause.message : "无法验证登录状态");
      }
    }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [attempt]);
  useEffect(() => {
    const expire = () => { setUser(null); clearSession(); client.clear(); };
    window.addEventListener("ylcloud-session-expired", expire);
    return () => window.removeEventListener("ylcloud-session-expired", expire);
  }, [client]);
  const value = useMemo<SessionContextValue>(() => ({
    user,
    signIn(nextUser) {
      clearSession();
      client.clear();
      setUser(nextUser);
    },
    async signOut() {
      try {
        await api.logout();
      } catch (cause) {
        if (!(cause instanceof ApiError && cause.status === 401)) {
          toast.error(cause instanceof Error ? cause.message : "退出失败，请重试");
          throw cause;
        }
      }
      clearSession(); setUser(null); client.clear();
    }
  }), [user, client]);

  if (loading) return <main role="status">正在恢复登录状态…</main>;
  if (error) return <main role="alert"><p>{error}</p><button onClick={() => setAttempt((value) => value + 1)}>重试</button></main>;

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession() {
  const value = useContext(SessionContext);
  if (!value) throw new Error("useSession must be used within SessionProvider");
  return value;
}
