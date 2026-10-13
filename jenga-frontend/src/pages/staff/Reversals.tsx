import { useRef, useState, type FormEvent } from "react";
import { useSearchParams } from "react-router-dom";
import { transfersApi } from "../../api/transfers";
import { ApiError, toApiError } from "../../api/client";
import { newIdempotencyKey } from "../../api/idempotency";
import { useAsync, usePaged } from "../../hooks/useAsync";
import { useToast } from "../../hooks/useToast";
import { PageHeader } from "../../components/PageHeader";
import { Voucher } from "../../components/Voucher";
import { TransferRow } from "../../components/TransferRow";
import { Button } from "../../components/ui/Button";
import { Dialog } from "../../components/ui/Dialog";
import { Field, TextArea } from "../../components/ui/Field";
import { ErrorNotice } from "../../components/ErrorNotice";
import { EmptyState } from "../../components/EmptyState";
import { LoadingRows, Skeleton } from "../../components/ui/Skeleton";
import { formatMinor } from "../../lib/money";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export default function Reversals() {
  const { notify } = useToast();
  const [params, setParams] = useSearchParams();
  const selectedId = params.get("transfer");
  const [lookup, setLookup] = useState("");
  const [lookupError, setLookupError] = useState<string | null>(null);

  const recent = usePaged((cursor, s) => transfersApi.list({ cursor, status: "COMPLETED", limit: 20 }, s), []);
  const selected = useAsync(
    (s) => (selectedId && UUID.test(selectedId) ? transfersApi.get(selectedId, s) : Promise.resolve(null)),
    [selectedId],
  );

  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const keyRef = useRef<string | null>(null);

  function find(e: FormEvent) {
    e.preventDefault();
    const id = lookup.trim();
    if (!UUID.test(id)) {
      setLookupError("Paste the full transfer ID, for example 3f2b8c1e-....");
      return;
    }
    setLookupError(null);
    setParams({ transfer: id });
  }

  function start() {
    keyRef.current = newIdempotencyKey();
    setReason("");
    setError(null);
    setOpen(true);
  }

  async function confirm() {
    const t = selected.data;
    if (!t || !keyRef.current || busy) return;
    if (reason.trim().length < 10) {
      setError(new ApiError(400, "VALIDATION", "Explain the reversal in at least 10 characters. It goes on the audit trail.", null));
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const result = await transfersApi.reverse(t.id, { reason: reason.trim() }, keyRef.current);
      setOpen(false);
      selected.reload();
      recent.reload();
      notify(
        result.status === "PENDING_APPROVAL"
          ? "Reversal sent for approval. A checker must approve it before money moves."
          : "Reversal posted.",
        "success",
      );
    } catch (e) {
      setError(toApiError(e));
    } finally {
      setBusy(false);
    }
  }

  const t = selected.data;

  return (
    <>
      <PageHeader
        title="Reversals"
        description="A reversal never edits or deletes the original transfer. It posts a new journal with the entries swapped, so both stay on the books. Each transfer can be reversed once."
      />

      <form onSubmit={find} noValidate className="mb-10 flex max-w-2xl flex-col gap-3 sm:flex-row sm:items-start">
        <div className="flex-1">
          <Field
            label="Find a transfer by ID"
            value={lookup}
            onChange={(e) => setLookup(e.target.value)}
            error={lookupError}
            spellCheck={false}
            autoComplete="off"
            maxLength={36}
            className="font-mono"
          />
        </div>
        <Button type="submit" variant="secondary" className="sm:mt-7">Find</Button>
      </form>

      {selectedId && (
        <section aria-label="Transfer to reverse" className="mb-12 max-w-3xl">
          {selected.loading && <Skeleton className="h-96 w-full" />}
          {selected.error && <ErrorNotice error={selected.error} onRetry={selected.reload} />}
          {t && (
            <Voucher
              data={t}
              footer={
                t.status === "COMPLETED" ? (
                  <Button variant="danger" onClick={start}>Reverse this transfer</Button>
                ) : (
                  <p className="text-sm text-muted">Only completed transfers can be reversed.</p>
                )
              }
            />
          )}
        </section>
      )}

      <section aria-labelledby="recent-h">
        <h2 id="recent-h" className="mb-3 text-lg font-semibold">Recent completed transfers</h2>
        {recent.loading && <LoadingRows rows={5} />}
        {recent.error && <ErrorNotice error={recent.error} onRetry={recent.reload} />}
        {!recent.loading && !recent.error && recent.items.length === 0 && <EmptyState title="No completed transfers" />}
        <ul className="border-t-2 border-ink/70">
          {recent.items.map((x) => (
            <TransferRow key={x.id} t={x} to={`/staff/reversals?transfer=${encodeURIComponent(x.id)}`} />
          ))}
        </ul>
        {recent.hasMore && (
          <div className="mt-6 flex justify-center">
            <Button variant="secondary" busy={recent.loadingMore} onClick={recent.loadMore}>Show more</Button>
          </div>
        )}
      </section>

      <Dialog open={open} title="Reverse this transfer?" onClose={() => setOpen(false)}>
        {t && (
          <div className="flex flex-col gap-4">
            <p>
              {formatMinor(t.amountMinor)} goes back from {t.toAccountNumber} to {t.fromAccountNumber}. Large reversals wait for an approver.
            </p>
            <TextArea label="Reason" maxLength={280} value={reason} onChange={(e) => setReason(e.target.value)} hint="Recorded on the audit trail. Up to 280 characters." />
            {error && <ErrorNotice error={error} />}
            <div className="flex flex-wrap gap-3 pt-2">
              <Button variant="danger" busy={busy} onClick={confirm}>Post reversal</Button>
              <Button variant="secondary" onClick={() => setOpen(false)} disabled={busy}>Cancel</Button>
            </div>
          </div>
        )}
      </Dialog>
    </>
  );
}
