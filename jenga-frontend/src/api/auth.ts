import type { LoginRequest, LoginResponse, Me } from "../types/api";
import { api } from "./client";

export const authApi = {
  login: (body: LoginRequest) => api<LoginResponse>("/api/v1/auth/login", { method: "POST", body }),
  logout: () => api<void>("/api/v1/auth/logout", { method: "POST" }),
  me: (signal?: AbortSignal) => api<Me>("/api/v1/me", { signal }),
};
