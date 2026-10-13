import { useRef, useState } from "react";
import { approvalsApi } from "../../api/approvals";
import { ApiError, toApiError } from "../../api/client";
import { newIdempotencyKey } from "../../api/idempotency";
import { usePaged } from "../../hooks/useAsync";
import { useAuth } from "../../hooks/useAuth";
import { useToast } from "../../hooks/useToast";
import type { Transfer } from "../../types/api";
import { PageHeader } from "../../components/PageHeader";
import { Voucher } from "../../components/Voucher";
import { Money } from "../../components/Money";
import { Button } from "../../components/ui/Button";
import { Dialog } from "../../components/ui/Dialog";
import { TextArea } from "../../components/ui/Field";
import { ErrorNotice } from "../../components/ErrorNotice";
import { EmptyState } from "../../components/EmptyState";
import { LoadingRows } from "../../components/ui/Skeleton";
import { formatDateTime } from "../../lib/dates";
import { formatMinor } from "../../lib/money";
import { cn } from "../../lib/cn";
import { PendingReversals } from "./PendingReversals";

type Action = "approve" | "reject" | null;

export default function Approvals() {
  const { user } = useAuth();
  const { notify } = useToast();
  const queue = usePaged((cursor, s) => approvalsApi.list(cursor, s), []);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [decided, setDecided] = useState<Transfer | null>(null);
  const [action, setAction] = useState<Action>(null);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const keyRef = useRef<string | null>(null);

  const selected = queue.items.find((t) => t.id === selectedId) ?? null;
  const ownTransfer = !!selected && !!user && selected.createdBy === user.username;

  function open(a: Exclude<Action, null>) {
    keyRef.current = newIdempotencyKey();
    setReason("");
    setError(null);
    setAction(a);
  }

  async function confirm() {
    if (!selected || !keyRef.current || busy) return;
    if (action === "reject" && reason.trim().length < 5) {
      setError(new ApiError(400, "VALIDATION", "Give a reason of at least 5 characters.", null));
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const result =
        action === "approve"
          ? await approvalsApi.approve(selected.id, keyRef.current)
          : await approvalsApi.reject(selected.id, { reason: reason.trim() }, keyRef.current);
      queue.setItems((items) => items.filter((t) => t.id !== selected.id));
      setDecided(result);
      setSelectedId(null);
      setAction(null);
      notify(action === "approve" ? "Transfer approved. Money has moved." : "Transfer rejected. No money moved.", "success");
    } catch (e) {
      setError(toApiError(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <PageHeader
        title="Approvals"
        description="Transfers held for a second pair of eyes: large amounts and anything the fraud check flagged. You can't approve a transfer you started."
      />

      <div className="grid gap-8 lg:grid-cols-[minmax(0,22rem)_1fr]">
        <section aria-labelledby="queue-h">
          <h2 id="queue-h" className="mb-3 text-sm font-medium text-muted">
            Waiting {queue.items.length > 0 && `(${queue.items.length}${queue.hasMore ? "+" : ""})`}
          </h2>
          {queue.loading && <LoadingRows rows={4} />}
          {queue.error && <ErrorNotice error={queue.error} onRetry={queue.reload} />}
          {!queue.loading && !queue.error && queue.items.length === 0 && (
            <EmptyState title="Nothing waiting" body="New transfers that need approval appear here." />
          )}
          <ul className="border-t-2 border-ink/70">
            {queue.items.map((t) => (
              <li key={t.id}>
                <button
                  onClick={() => {
                    setSelectedId(t.id);
                    setDecided(null);
                    requestAnimationFrame(() => document.getElementById("approval-detail")?.scrollIntoView({ behavior: "smooth", block: "start" }));
                  }}
                  aria-current={t.id === selectedId || undefined}
                  className={cn(
                    "flex w-full items-start justify-between gap-3 border-b border-l-2 border-rule px-3 py-4 text-left hover:bg-surface",
                    t.id === selectedId ? "border-l-review bg-surface" : "border-l-transparent",
                  )}
                >
                  <span className="min-w-0">
                    <span className="block truncate font-medium">{t.reference || "No reference"}</span>
                    <span className="block text-xs text-muted">{formatDateTime(t.createdAt)} by {t.createdBy}</span>
                    {t.fraud && t.fraud.decision !== "ALLOW" && (
                      <span className="mt-1 block text-xs text-review">Flagged: {t.fraud.reasons[0] ?? "review"}</span>
                    )}
                  </span>
                  <Money minor={t.amountMinor} size="sm" />
                </button>
              </li>
            ))}
          </ul>
          {queue.hasMore && (
            <Button variant="quiet" className="mt-3" busy={queue.loadingMore} onClick={queue.loadMore}>Show more</Button>
          )}
        </section>

        <section id="approval-detail" aria-label="Selected transfer" className="scroll-mt-6">
          {selected ? (
            <Voucher
              data={selected}
              footer={
                ownTransfer ? (
                  <p className="text-sm text-review">You started this transfer, so another approver has to decide on it.</p>
                ) : (
                  <>
                    <Button onClick={() => open("approve")}>Approve and move money</Button>
                    <Button variant="secondary" onClick={() => open("reject")}>Reject</Button>
                  </>
                )
              }
            />
          ) : decided ? (
            <Voucher data={decided} />
          ) : (
            <div className="hidden border border-dashed border-rule px-6 py-16 text-center text-muted lg:block">
              Choose a transfer from the list to review it.
            </div>
          )}
        </section>
      </div>

      <PendingReversals />

      <Dialog open={action !== null} title={action === "approve" ? "Approve this transfer?" : "Reject this transfer?"} onClose={() => setAction(null)}>
        {selected && (
          <div className="flex flex-col gap-4">
            <p>
              {action === "approve"
                ? `${formatMinor(selected.amountMinor)} moves from ${selected.fromAccountNumber} to ${selected.toAccountNumber} as soon as you approve.`
                : `No money moves. ${selected.createdBy} will see your reason.`}
            </p>
            {action === "reject" && (
              <TextArea label="Reason" maxLength={280} value={reason} onChange={(e) => setReason(e.target.value)} hint="Up to 280 characters." />
            )}
            {error && <ErrorNotice error={error} />}
            <div className="flex flex-wrap gap-3 pt-2">
              <Button variant={action === "approve" ? "primary" : "danger"} busy={busy} onClick={confirm}>
                {action === "approve" ? "Approve" : "Reject transfer"}
              </Button>
              <Button variant="secondary" onClick={() => setAction(null)} disabled={busy}>Cancel</Button>
            </div>
          </div>
        )}
      </Dialog>
    </>
  );
}
