import type { Page, Reversal, ReversalStatus } from "../types/api";
import { api, query, seg } from "./client";

export const reversalsApi = {
  list: (status: ReversalStatus | undefined, cursor?: string, signal?: AbortSignal) =>
    api<Page<Reversal>>(`/api/v1/reversals${query({ status, limit: 50, cursor })}`, { signal }),
  approve: (id: string, idempotencyKey: string) =>
    api<Reversal>(`/api/v1/reversals/${seg(id)}/approve`, { method: "POST", body: {}, idempotencyKey }),
  reject: (id: string, reason: string, idempotencyKey: string) =>
    api<Reversal>(`/api/v1/reversals/${seg(id)}/reject`, { method: "POST", body: { reason }, idempotencyKey }),
};
