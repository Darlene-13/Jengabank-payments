import { useState } from "react";
import { transfersApi } from "../api/transfers";
import { usePaged } from "../hooks/useAsync";
import { useAuth } from "../hooks/useAuth";
import type { TransferStatus } from "../types/api";
import { PageHeader } from "../components/PageHeader";
import { TransferRow } from "../components/TransferRow";
import { ErrorNotice } from "../components/ErrorNotice";
import { EmptyState } from "../components/EmptyState";
import { LoadingRows } from "../components/ui/Skeleton";
import { Button } from "../components/ui/Button";
import { statusLabel } from "../components/StatusBadge";
import { cn } from "../lib/cn";

const filters: Array<TransferStatus | "ALL"> = ["ALL", "COMPLETED", "PENDING_APPROVAL", "BLOCKED", "REJECTED", "REVERSED"];

export default function Transfers() {
  const { hasRole } = useAuth();
  const staff = hasRole("MAKER", "CHECKER");
  const [status, setStatus] = useState<TransferStatus | "ALL">("ALL");
  const list = usePaged(
    (cursor, s) => transfersApi.list({ cursor, status: status === "ALL" ? undefined : status }, s),
    [status],
  );

  return (
    <>
      <PageHeader
        title="Transfers"
        description={staff ? "Every transfer at the bank, newest first." : "Every transfer from your accounts, newest first."}
      />

      <div role="group" aria-label="Filter by status" className="mb-6 flex gap-2 overflow-x-auto pb-1">
        {filters.map((f) => (
          <button
            key={f}
            onClick={() => setStatus(f)}
            aria-pressed={status === f}
            className={cn(
              "whitespace-nowrap rounded-full border px-3.5 py-1.5 text-sm",
              status === f ? "border-ink bg-ink text-paper" : "border-rule text-muted hover:border-ink hover:text-ink",
            )}
          >
            {f === "ALL" ? "All" : statusLabel[f]}
          </button>
        ))}
      </div>

      {list.loading && <LoadingRows rows={6} />}
      {list.error && <ErrorNotice error={list.error} onRetry={list.reload} />}
      {!list.loading && !list.error && list.items.length === 0 && (
        <EmptyState title="No transfers here" body={status === "ALL" ? "Transfers appear here as soon as they're made." : "Try another filter."} />
      )}
      {list.items.length > 0 && <ul className="border-t-2 border-ink/70">{list.items.map((t) => <TransferRow key={t.id} t={t} />)}</ul>}
      {list.hasMore && (
        <div className="mt-6 flex justify-center">
          <Button variant="secondary" busy={list.loadingMore} onClick={list.loadMore}>Show older transfers</Button>
        </div>
      )}
    </>
  );
}
