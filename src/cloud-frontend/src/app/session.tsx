import { createContext, useContext, useMemo, useState, type ReactNode } from "react";
import { clearSession, getStoredUser, setSession } from "../api";
import type { User } from "../types";

type SessionContextValue = {
  user: User | null;
  signIn: (user: User) => void;
  signOut: () => void;
};

const SessionContext = createContext<SessionContextValue | null>(null);

export function SessionProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(() => getStoredUser());
  const value = useMemo<SessionContextValue>(() => ({
    user,
    signIn(nextUser) {
      setSession(nextUser);
      setUser(nextUser);
    },
    signOut() {
      clearSession();
      setUser(null);
    }
  }), [user]);

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession() {
  const value = useContext(SessionContext);
  if (!value) throw new Error("useSession must be used within SessionProvider");
  return value;
}
