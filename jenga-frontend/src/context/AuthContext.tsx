import { createContext, useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { authApi } from "../api/auth";
import { setToken, setUnauthorizedHandler } from "../api/client";
import type { Me, Role } from "../types/api";

const IDLE_LIMIT_MS = 10 * 60 * 1000;

export type SignOutReason = "manual" | "idle" | "expired";

export interface AuthState {
  user: Me | null;
  signOutReason: SignOutReason | null;
  login: (username: string, password: string) => Promise<void>;
  logout: (reason?: SignOutReason) => void;
  hasRole: (...roles: Role[]) => boolean;
}

export const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<Me | null>(null);
  const [signOutReason, setSignOutReason] = useState<SignOutReason | null>(null);
  const idleTimer = useRef<number | null>(null);

  const clear = useCallback((reason: SignOutReason) => {
    setToken(null);
    setUser(null);
    setSignOutReason(reason);
  }, []);

  const logout = useCallback(
    (reason: SignOutReason = "manual") => {
      authApi.logout().catch(() => undefined);
      clear(reason);
    },
    [clear],
  );

  const login = useCallback(async (username: string, password: string) => {
    const res = await authApi.login({ username, password });
    setToken(res.token);
    setSignOutReason(null);
    setUser(res.user);
  }, []);

  useEffect(() => {
    setUnauthorizedHandler(() => clear("expired"));
    return () => setUnauthorizedHandler(null);
  }, [clear]);

  // Sign out after 10 minutes without activity.
  useEffect(() => {
    if (!user) return;
    const reset = () => {
      if (idleTimer.current) window.clearTimeout(idleTimer.current);
      idleTimer.current = window.setTimeout(() => logout("idle"), IDLE_LIMIT_MS);
    };
    const events = ["pointerdown", "keydown", "scroll", "visibilitychange"] as const;
    events.forEach((e) => window.addEventListener(e, reset, { passive: true }));
    reset();
    return () => {
      events.forEach((e) => window.removeEventListener(e, reset));
      if (idleTimer.current) window.clearTimeout(idleTimer.current);
    };
  }, [user, logout]);

  const hasRole = useCallback((...roles: Role[]) => !!user && roles.some((r) => user.roles.includes(r)), [user]);

  const value = useMemo(
    () => ({ user, signOutReason, login, logout, hasRole }),
    [user, signOutReason, login, logout, hasRole],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
