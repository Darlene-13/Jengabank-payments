import type { ReconciliationRun } from "../types/api";
import { api } from "./client";

export const reconciliationApi = {
  run: () => api<ReconciliationRun>("/api/v1/reconciliation/runs", { method: "POST", body: {} }),
  latest: (signal?: AbortSignal) => api<ReconciliationRun>("/api/v1/reconciliation/runs/latest", { signal }),
};
