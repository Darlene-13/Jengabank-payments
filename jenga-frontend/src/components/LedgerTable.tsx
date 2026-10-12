import type { LedgerEntry } from "../types/api";
import { formatDateTime } from "../lib/dates";
import { formatMinor } from "../lib/money";

/* Classic two-column ledger: money out on the left, money in on the right. */
export function LedgerTable({ entries }: { entries: LedgerEntry[] }) {
  return (
    <div className="overflow-x-auto">
      <table className="figures w-full min-w-[40rem] text-sm">
        <thead>
          <tr className="border-b-2 border-ink/70 text-left">
            <th scope="col" className="py-2 pr-4 font-medium">Date</th>
            <th scope="col" className="py-2 pr-4 font-medium">Description</th>
            <th scope="col" className="py-2 pr-4 font-medium">Journal</th>
            <th scope="col" className="py-2 pr-4 text-right font-medium">Money out</th>
            <th scope="col" className="py-2 text-right font-medium">Money in</th>
          </tr>
        </thead>
        <tbody>
          {entries.map((e) => (
            <tr key={e.id} className="border-b border-rule">
              <td className="whitespace-nowrap py-3 pr-4 text-muted">{formatDateTime(e.createdAt)}</td>
              <td className="py-3 pr-4">{e.description}</td>
              <td className="py-3 pr-4 font-mono text-xs text-muted" title={e.journalId}>{e.journalId.slice(0, 8)}</td>
              <td className="py-3 pr-4 text-right text-debit">
                {e.direction === "DEBIT" ? formatMinor(e.amountMinor, { currency: false }) : ""}
              </td>
              <td className="py-3 text-right text-note">
                {e.direction === "CREDIT" ? formatMinor(e.amountMinor, { currency: false }) : ""}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
