import type { CreateTransferRequest, Page, Reversal, ReversalRequest, Transfer, TransferStatus } from "../types/api";
import { api, query, seg } from "./client";

export const transfersApi = {
  create: (body: CreateTransferRequest, idempotencyKey: string) =>
    api<Transfer>("/api/v1/transfers", { method: "POST", body, idempotencyKey }),
  list: (opts: { cursor?: string; status?: TransferStatus; limit?: number } = {}, signal?: AbortSignal) =>
    api<Page<Transfer>>(`/api/v1/transfers${query({ limit: opts.limit ?? 50, cursor: opts.cursor, status: opts.status })}`, { signal }),
  get: (id: string, signal?: AbortSignal) => api<Transfer>(`/api/v1/transfers/${seg(id)}`, { signal }),
  reverse: (id: string, body: ReversalRequest, idempotencyKey: string) =>
    api<Reversal>(`/api/v1/transfers/${seg(id)}/reversals`, { method: "POST", body, idempotencyKey }),
};
