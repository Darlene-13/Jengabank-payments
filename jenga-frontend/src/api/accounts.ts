import type { Account, LedgerEntry, Page } from "../types/api";
import { api, query, seg } from "./client";

export const accountsApi = {
  list: (signal?: AbortSignal) => api<Page<Account>>(`/api/v1/accounts${query({ limit: 100 })}`, { signal }),
  get: (id: string, signal?: AbortSignal) => api<Account>(`/api/v1/accounts/${seg(id)}`, { signal }),
  entries: (id: string, cursor?: string, signal?: AbortSignal) =>
    api<Page<LedgerEntry>>(`/api/v1/accounts/${seg(id)}/entries${query({ limit: 50, cursor })}`, { signal }),
};
