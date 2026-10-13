import { useState } from "react";
import { reversalsApi } from "../../api/reversals";
import { ApiError, toApiError } from "../../api/client";
import { newIdempotencyKey } from "../../api/idempotency";
import { usePaged } from "../../hooks/useAsync";
import { useAuth } from "../../hooks/useAuth";
import { useToast } from "../../hooks/useToast";
import type { Reversal } from "../../types/api";
import { Money } from "../../components/Money";
import { Button } from "../../components/ui/Button";
import { Dialog } from "../../components/ui/Dialog";
import { TextArea } from "../../components/ui/Field";
import { ErrorNotice } from "../../components/ErrorNotice";
import { LoadingRows } from "../../components/ui/Skeleton";
import { formatDateTime } from "../../lib/dates";
import { formatMinor } from "../../lib/money";

/** Large reversals (KES 250,000 and above) wait here for a second person. */
export function PendingReversals() {
  const { user } = useAuth();
  const { notify } = useToast();
  const list = usePaged((cursor, s) => reversalsApi.list("PENDING_APPROVAL", cursor, s), []);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [target, setTarget] = useState<{ r: Reversal; action: "approve" | "reject" } | null>(null);
  const [reason, setReason] = useState("");
  const [key, setKey] = useState<string>("");

  function open(r: Reversal, action: "approve" | "reject") {
    setKey(newIdempotencyKey());
    setReason("");
    setError(null);
    setTarget({ r, action });
  }

  async function confirm() {
    if (!target || busyId) return;
    if (target.action === "reject" && reason.trim().length < 5) {
      setError(new ApiError(400, "VALIDATION", "Give a reason of at least 5 characters.", null));
      return;
    }
    setBusyId(target.r.id);
    setError(null);
    try {
      if (target.action === "approve") {
        await reversalsApi.approve(target.r.id, key);
        notify("Reversal approved. The opposite entries are posted.", "success");
      } else {
        await reversalsApi.reject(target.r.id, reason.trim(), key);
        notify("Reversal rejected. No money moved.", "success");
      }
      list.setItems((items) => items.filter((x) => x.id !== target.r.id));
      setTarget(null);
    } catch (e) {
      setError(toApiError(e));
    } finally {
      setBusyId(null);
    }
  }

  return (
    <section aria-labelledby="rev-h" className="mt-14">
      <h2 id="rev-h" className="text-lg font-semibold">Reversals waiting</h2>
      <p className="mt-1 text-sm text-muted">Reversals of KES 250,000 and above need a second person. You can't approve one you requested.</p>

      {list.loading && <LoadingRows rows={2} />}
      {list.error && <div className="mt-4"><ErrorNotice error={list.error} onRetry={list.reload} /></div>}
      {!list.loading && !list.error && list.items.length === 0 && (
        <p className="mt-4 border border-dashed border-rule px-4 py-6 text-center text-sm text-muted">No reversals waiting.</p>
      )}

      <ul className="mt-4 border-t-2 border-ink/70">
        {list.items.map((r) => {
          const own = !!user && r.requestedBy === user.username;
          return (
            <li key={r.id} className="flex flex-col gap-3 border-b border-rule px-1 py-4 md:flex-row md:items-center md:justify-between">
              <div className="min-w-0">
                <Money minor={r.amountMinor} />
                <p className="font-mono text-xs text-muted">Back from {r.toAccountNumber} to {r.fromAccountNumber}</p>
                <p className="mt-1 text-sm">{r.reason}</p>
                <p className="text-xs text-muted">Requested by {r.requestedBy}, {formatDateTime(r.requestedAt)}</p>
              </div>
              {own ? (
                <p className="text-sm text-review">You requested this, so another approver must decide.</p>
              ) : (
                <div className="flex gap-2">
                  <Button className="min-h-9 px-3 text-sm" onClick={() => open(r, "approve")}>Approve</Button>
                  <Button variant="secondary" className="min-h-9 px-3 text-sm" onClick={() => open(r, "reject")}>Reject</Button>
                </div>
              )}
            </li>
          );
        })}
      </ul>

      <Dialog open={target !== null} title={target?.action === "approve" ? "Approve this reversal?" : "Reject this reversal?"} onClose={() => setTarget(null)}>
        {target && (
          <div className="flex flex-col gap-4">
            <p>
              {target.action === "approve"
                ? `${formatMinor(target.r.amountMinor)} goes back from ${target.r.toAccountNumber} to ${target.r.fromAccountNumber}. The original entries stay on the books.`
                : "No money moves. The original transfer stays completed."}
            </p>
            {target.action === "reject" && (
              <TextArea label="Reason" maxLength={280} value={reason} onChange={(e) => setReason(e.target.value)} hint="Recorded on the audit trail." />
            )}
            {error && <ErrorNotice error={error} />}
            <div className="flex flex-wrap gap-3 pt-2">
              <Button variant={target.action === "approve" ? "primary" : "danger"} busy={busyId === target.r.id} onClick={confirm}>
                {target.action === "approve" ? "Approve reversal" : "Reject reversal"}
              </Button>
              <Button variant="secondary" onClick={() => setTarget(null)} disabled={!!busyId}>Cancel</Button>
            </div>
          </div>
        )}
      </Dialog>
    </section>
  );
}
