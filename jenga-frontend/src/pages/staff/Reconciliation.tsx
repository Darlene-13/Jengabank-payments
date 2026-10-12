import { useState } from "react";
import { reconciliationApi } from "../../api/reconciliation";
import { ApiError, toApiError } from "../../api/client";
import { useAsync } from "../../hooks/useAsync";
import { useAuth } from "../../hooks/useAuth";
import { useToast } from "../../hooks/useToast";
import { PageHeader } from "../../components/PageHeader";
import { Button } from "../../components/ui/Button";
import { ErrorNotice } from "../../components/ErrorNotice";
import { EmptyState } from "../../components/EmptyState";
import { Skeleton } from "../../components/ui/Skeleton";
import { Rosette } from "../../components/Guilloche";
import { formatDateTime } from "../../lib/dates";
import { cn } from "../../lib/cn";

export default function Reconciliation() {
  const { hasRole } = useAuth();
  const { notify } = useToast();
  const latest = useAsync((s) => reconciliationApi.latest(s), []);
  const [running, setRunning] = useState(false);
  const [runError, setRunError] = useState<ApiError | null>(null);

  async function run() {
    setRunning(true);
    setRunError(null);
    try {
      const r = await reconciliationApi.run();
      latest.setData(r);
      notify(r.status === "PASSED" ? "Reconciliation passed. The books balance." : "Reconciliation failed. Check the results.", r.status === "PASSED" ? "success" : "danger");
    } catch (e) {
      setRunError(toApiError(e));
    } finally {
      setRunning(false);
    }
  }

  const r = latest.data;
  const none = latest.error?.status === 404;

  return (
    <>
      <PageHeader
        title="Reconciliation"
        description="Checks that every journal balances, every account adds up from its entries, and the audit trail hasn't been touched. Runs automatically at the end of each day."
        actions={hasRole("CHECKER") ? <Button onClick={run} busy={running} busyLabel="Running checks">Run now</Button> : undefined}
      />

      {runError && <div className="mb-6"><ErrorNotice error={runError} /></div>}
      {latest.loading && <Skeleton className="h-64 w-full" />}
      {latest.error && !none && <ErrorNotice error={latest.error} onRetry={latest.reload} />}
      {none && <EmptyState title="No reconciliation has run yet" body={hasRole("CHECKER") ? "Run one now to check the books." : "An approver can run one now, or wait for the end-of-day run."} />}

      {r && (
        <section aria-label="Latest result">
          <div className={cn("relative flex items-center gap-6 overflow-hidden border-y-2 py-8", r.status === "PASSED" ? "border-note" : "border-debit")}>
            <Rosette seed={r.id} size={96} className={cn("shrink-0", r.status === "PASSED" ? "text-note" : "text-debit")} />
            <div>
              <p className={cn("text-3xl font-semibold tracking-tight", r.status === "PASSED" ? "text-note" : "text-debit")}>
                {r.status === "PASSED" ? "The books balance" : "The books don't balance"}
              </p>
              <p className="mt-1 text-muted">Last checked {formatDateTime(r.runAt)}</p>
            </div>
          </div>

          <ul className="mt-6">
            {r.checks.map((c) => (
              <li key={c.name} className="grid grid-cols-[1.75rem_1fr] gap-x-3 border-b border-rule py-4">
                <span aria-hidden className={cn("mt-0.5 grid size-6 place-items-center rounded-full text-sm", c.passed ? "bg-note-soft text-note" : "bg-debit-soft text-debit")}>
                  {c.passed ? "\u2713" : "\u2715"}
                </span>
                <div>
                  <p className="font-medium">
                    {c.name}
                    <span className="sr-only">{c.passed ? ": passed" : ": failed"}</span>
                  </p>
                  <p className="text-sm text-muted">{c.detail}</p>
                </div>
              </li>
            ))}
          </ul>
        </section>
      )}
    </>
  );
}
