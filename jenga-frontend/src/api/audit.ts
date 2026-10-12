import type { AuditRecord, AuditVerification, Page } from "../types/api";
import { api, query } from "./client";

export const auditApi = {
  list: (cursor?: string, signal?: AbortSignal) =>
    api<Page<AuditRecord>>(`/api/v1/audit${query({ limit: 50, cursor })}`, { signal }),
  verify: () => api<AuditVerification>("/api/v1/audit/verify"),
};
