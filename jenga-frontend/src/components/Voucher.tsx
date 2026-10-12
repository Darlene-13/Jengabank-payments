import type { ReactNode } from "react";
import type { FraudDecision, TransferStatus } from "../types/api";
import { Band, Rosette } from "./Guilloche";
import { Money } from "./Money";
import { StatusBadge } from "./StatusBadge";
import { formatDateTime } from "../lib/dates";
import { formatMinor } from "../lib/money";
import { cn } from "../lib/cn";

/*
  The receipt for every transfer, drawn like a security-printed voucher.
  Its guilloché is generated from the transfer ID, and it shows both sides of
  the double entry so anyone can see the books balance.
*/

export interface VoucherData {
  status: TransferStatus;
  amountMinor: number;
  fromAccountNumber: string;
  toAccountNumber: string;
  reference: string;
  id?: string | null;
  journalId?: string | null;
  reversalJournalId?: string | null;
  createdAt?: string | null;
  createdBy?: string | null;
  approvedBy?: string | null;
  rejectionReason?: string | null;
  fraud?: FraudDecision | null;
  note?: string | null;
}

const headline: Record<TransferStatus, string> = {
  COMPLETED: "Money sent",
  PENDING_APPROVAL: "Waiting for approval",
  BLOCKED: "Transfer stopped",
  REJECTED: "Transfer rejected",
  REVERSED: "Transfer reversed",
};

const ink: Record<TransferStatus, string> = {
  COMPLETED: "text-note",
  PENDING_APPROVAL: "text-review",
  BLOCKED: "text-debit",
  REJECTED: "text-debit",
  REVERSED: "text-muted",
};

const fraudLabel = { ALLOW: "Passed", REVIEW: "Held for review", BLOCK: "Blocked" } as const;

export function Voucher({ data, footer }: { data: VoucherData; footer?: ReactNode }) {
  const seed = data.id ?? `${data.fromAccountNumber}-${data.toAccountNumber}-${data.amountMinor}-${data.reference}`;
  const posted = data.status === "COMPLETED" || data.status === "REVERSED";

  return (
    <article
      aria-label={`${headline[data.status]}: ${formatMinor(data.amountMinor)}`}
      className="voucher-in relative overflow-hidden border border-rule bg-surface shadow-[0_1px_0_var(--rule),0_18px_40px_-28px_color-mix(in_srgb,var(--ink)_55%,transparent)]"
    >
      <Band seed={seed} draw className={cn(ink[data.status], "opacity-70")} />

      <div className="grid md:grid-cols-[13rem_1fr]">
        {/* Stub */}
        <div className="relative flex flex-row items-center gap-4 border-b border-dashed border-rule p-5 md:flex-col md:items-start md:border-r md:border-b-0 md:p-6">
          <Rosette seed={seed} size={112} draw className={cn(ink[data.status], "shrink-0 opacity-90")} />
          <dl className="min-w-0 text-sm">
            <dt className="text-muted">Voucher</dt>
            <dd className="truncate font-mono text-[0.8rem]">{data.id ? shortId(data.id) : "Not issued"}</dd>
            {data.createdAt && (
              <>
                <dt className="mt-3 text-muted">Issued</dt>
                <dd>{formatDateTime(data.createdAt)}</dd>
              </>
            )}
          </dl>
          <span aria-hidden className="absolute -right-2 -top-2 hidden size-4 rounded-full bg-paper md:block" />
          <span aria-hidden className="absolute -bottom-2 -right-2 hidden size-4 rounded-full bg-paper md:block" />
        </div>

        {/* Body */}
        <div className="p-5 md:p-8">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <h2 className={cn("text-lg font-semibold", ink[data.status])}>{headline[data.status]}</h2>
            <StatusBadge status={data.status} />
          </div>

          <div className="mt-4">
            <Money minor={data.amountMinor} size="xl" />
          </div>

          <dl className="mt-6 grid gap-x-8 gap-y-3 text-sm sm:grid-cols-2">
            <Item label="From account" mono>{data.fromAccountNumber}</Item>
            <Item label="To account" mono>{data.toAccountNumber}</Item>
            <Item label="Reference">{data.reference || "None"}</Item>
            {data.createdBy && <Item label="Requested by">{data.createdBy}</Item>}
            {data.approvedBy && <Item label="Approved by">{data.approvedBy}</Item>}
          </dl>

          <section className="mt-7" aria-label="Ledger entries">
            <h3 className="mb-2 text-sm font-medium">Ledger entries</h3>
            {posted ? (
              <table className="figures w-full text-sm">
                <thead className="sr-only">
                  <tr><th>Side</th><th>Account</th><th>Amount</th></tr>
                </thead>
                <tbody>
                  <EntryRow side="Debit" account={data.fromAccountNumber} minor={data.amountMinor} />
                  <EntryRow side="Credit" account={data.toAccountNumber} minor={data.amountMinor} />
                  <tr className="border-t-2 border-double border-ink/60">
                    <td className="pt-2 font-medium">Net</td>
                    <td className="pt-2 text-muted">Books balance</td>
                    <td className="pt-2 text-right font-medium">{formatMinor(0, { currency: false })}</td>
                  </tr>
                </tbody>
              </table>
            ) : (
              <p className="border border-dashed border-rule px-4 py-3 text-sm text-muted">
                {data.status === "PENDING_APPROVAL"
                  ? "Nothing has been posted yet. Money moves only after a second person approves it."
                  : "No money moved and no ledger entries were written."}
              </p>
            )}
            {data.journalId && (
              <p className="mt-3 text-xs text-muted">
                Journal <span className="font-mono text-ink">{data.journalId}</span>
              </p>
            )}
            {data.reversalJournalId && (
              <p className="mt-1 text-xs text-muted">
                Reversed by journal <span className="font-mono text-ink">{data.reversalJournalId}</span>. The original entries stay on the books; the reversal posts the opposite.
              </p>
            )}
          </section>

          {(data.fraud || data.rejectionReason || data.note) && (
            <section className="mt-6 border-t border-rule pt-5 text-sm">
              {data.fraud && (
                <div>
                  <p>
                    <span className="font-medium">Fraud check: </span>
                    <span className={cn(data.fraud.decision === "ALLOW" ? "text-note" : data.fraud.decision === "REVIEW" ? "text-review" : "text-debit")}>
                      {fraudLabel[data.fraud.decision]}
                    </span>
                  </p>
                  {data.fraud.reasons.length > 0 && (
                    <ul className="mt-2 list-disc space-y-1 pl-5 text-muted">
                      {data.fraud.reasons.map((r) => <li key={r}>{r}</li>)}
                    </ul>
                  )}
                </div>
              )}
              {data.rejectionReason && <p className="mt-3"><span className="font-medium">Reason for rejection: </span>{data.rejectionReason}</p>}
              {data.note && <p className="mt-3 text-muted">{data.note}</p>}
            </section>
          )}

          {footer && <div className="mt-7 flex flex-wrap gap-3">{footer}</div>}
        </div>
      </div>
    </article>
  );
}

function Item({ label, children, mono }: { label: string; children: ReactNode; mono?: boolean }) {
  return (
    <div>
      <dt className="text-muted">{label}</dt>
      <dd className={cn("mt-0.5 break-words", mono && "font-mono")}>{children}</dd>
    </div>
  );
}

function EntryRow({ side, account, minor }: { side: "Debit" | "Credit"; account: string; minor: number }) {
  return (
    <tr className="border-t border-rule">
      <td className={cn("py-2 pr-3 font-medium", side === "Debit" ? "text-debit" : "text-note")}>{side}</td>
      <td className="py-2 pr-3 font-mono">{account}</td>
      <td className="py-2 text-right">{formatMinor(side === "Debit" ? -minor : minor, { currency: false, sign: true })}</td>
    </tr>
  );
}

function shortId(id: string): string {
  return id.replace(/-/g, "").slice(0, 12).toUpperCase().replace(/(.{4})(?=.)/g, "$1 ");
}
