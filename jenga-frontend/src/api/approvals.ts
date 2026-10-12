import type { Page, RejectRequest, Transfer } from "../types/api";
import { api, query, seg } from "./client";

export const approvalsApi = {
  list: (cursor?: string, signal?: AbortSignal) =>
    api<Page<Transfer>>(`/api/v1/approvals${query({ limit: 50, cursor })}`, { signal }),
  approve: (id: string, idempotencyKey: string) =>
    api<Transfer>(`/api/v1/approvals/${seg(id)}/approve`, { method: "POST", body: {}, idempotencyKey }),
  reject: (id: string, body: RejectRequest, idempotencyKey: string) =>
    api<Transfer>(`/api/v1/approvals/${seg(id)}/reject`, { method: "POST", body, idempotencyKey }),
};
