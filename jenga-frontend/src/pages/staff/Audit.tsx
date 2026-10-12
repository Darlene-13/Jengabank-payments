import { useMemo, useState } from "react";
import { auditApi } from "../../api/audit";
import { ApiError, toApiError } from "../../api/client";
import { usePaged } from "../../hooks/useAsync";
import type { AuditVerification } from "../../types/api";
import { PageHeader } from "../../components/PageHeader";
import { Button } from "../../components/ui/Button";
import { ErrorNotice } from "../../components/ErrorNotice";
import { EmptyState } from "../../components/EmptyState";
import { LoadingRows } from "../../components/ui/Skeleton";
import { formatDateTime } from "../../lib/dates";
import { cn } from "../../lib/cn";

const short = (h: string) => (h ? `${h.slice(0, 6)}\u2026${h.slice(-4)}` : "none");

export default function Audit() {
  const log = usePaged((cursor, s) => auditApi.list(cursor, s), []);
  const [result, setResult] = useState<AuditVerification | null>(null);
  const [verifying, setVerifying] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  // Second opinion in the browser: each record's prevHash must match the hash of the record before it.
  const brokenLinks = useMemo(() => {
    const bySeq = new Map(log.items.map((r) => [r.sequence, r.hash]));
    const broken = new Set<number>();
    for (const r of log.items) {
      const prev = bySeq.get(r.sequence - 1);
      if (prev !== undefined && prev !== r.prevHash) broken.add(r.sequence);
    }
    return broken;
  }, [log.items]);

  async function verify() {
    setVerifying(true);
    setError(null);
    try {
      setResult(await auditApi.verify());
    } catch (e) {
      setError(toApiError(e));
    } finally {
      setVerifying(false);
    }
  }

  return (
    <>
      <PageHeader
        title="Audit trail"
        description="Every action at the bank, in order. Records can't be edited or deleted, and each one carries the fingerprint of the one before it, so any tampering breaks the chain."
        actions={<Button variant="secondary" onClick={verify} busy={verifying} busyLabel="Checking chain">Check the whole chain</Button>}
      />

      {error && <div className="mb-6"><ErrorNotice error={error} /></div>}
      {result && (
        <p role="status" className={cn("mb-8 border-l-4 px-4 py-3", result.valid ? "border-note bg-note-soft text-note" : "border-debit bg-debit-soft text-debit")}>
          {result.valid
            ? `Chain intact. ${result.checked.toLocaleString("en-KE")} records checked at ${formatDateTime(result.verifiedAt)}.`
            : `Chain broken at record ${result.brokenAtSequence}. Records from that point can't be trusted. Escalate now.`}
        </p>
      )}
      {brokenLinks.size > 0 && (
        <p role="alert" className="mb-8 border-l-4 border-debit bg-debit-soft px-4 py-3 text-debit">
          {brokenLinks.size} record{brokenLinks.size > 1 ? "s don't" : " doesn't"} link to the record before {brokenLinks.size > 1 ? "them" : "it"} on this page.
        </p>
      )}

      {log.loading && <LoadingRows rows={8} />}
      {log.error && <ErrorNotice error={log.error} onRetry={log.reload} />}
      {!log.loading && !log.error && log.items.length === 0 && <EmptyState title="No records yet" />}

      {log.items.length > 0 && (
        <div className="overflow-x-auto">
          <table className="figures w-full min-w-[46rem] text-sm">
            <thead>
              <tr className="border-b-2 border-ink/70 text-left">
                <th scope="col" className="py-2 pr-4 font-medium">No.</th>
                <th scope="col" className="py-2 pr-4 font-medium">Time</th>
                <th scope="col" className="py-2 pr-4 font-medium">Who</th>
                <th scope="col" className="py-2 pr-4 font-medium">Action</th>
                <th scope="col" className="py-2 pr-4 font-medium">On</th>
                <th scope="col" className="py-2 font-medium">Chain</th>
              </tr>
            </thead>
            <tbody>
              {log.items.map((r) => {
                const broken = brokenLinks.has(r.sequence) || result?.brokenAtSequence === r.sequence;
                return (
                  <tr key={r.sequence} className={cn("border-b border-rule", broken && "bg-debit-soft")}>
                    <td className="py-3 pr-4 text-muted">{r.sequence}</td>
                    <td className="whitespace-nowrap py-3 pr-4">{formatDateTime(r.ts)}</td>
                    <td className="py-3 pr-4">{r.actor}</td>
                    <td className="py-3 pr-4 font-mono text-xs">{r.action}</td>
                    <td className="max-w-[12rem] truncate py-3 pr-4 font-mono text-xs text-muted" title={r.target}>{r.target}</td>
                    <td className="whitespace-nowrap py-3 font-mono text-xs" title={`previous ${r.prevHash}\nthis ${r.hash}`}>
                      <span className="text-muted">{short(r.prevHash)}</span>
                      <span aria-hidden className={cn("mx-1.5", broken ? "text-debit" : "text-note")}>{broken ? "\u2260" : "\u2192"}</span>
                      <span className="sr-only">{broken ? "does not link to" : "links to"}</span>
                      <span>{short(r.hash)}</span>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
      {log.hasMore && (
        <div className="mt-6 flex justify-center">
          <Button variant="secondary" busy={log.loadingMore} onClick={log.loadMore}>Show older records</Button>
        </div>
      )}
    </>
  );
}
