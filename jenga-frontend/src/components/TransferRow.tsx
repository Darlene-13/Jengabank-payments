import { Link } from "react-router-dom";
import type { Transfer } from "../types/api";
import { Money } from "./Money";
import { StatusBadge } from "./StatusBadge";
import { formatDateTime } from "../lib/dates";

export function TransferRow({ t, to }: { t: Transfer; to?: string }) {
  return (
    <li>
      <Link
        to={to ?? `/transfers/${encodeURIComponent(t.id)}`}
        className="grid grid-cols-[1fr_auto] items-center gap-x-4 gap-y-1 border-b border-rule px-1 py-4 hover:bg-surface md:grid-cols-[9rem_1fr_auto_10rem]"
      >
        <span className="order-3 text-sm text-muted md:order-none">{formatDateTime(t.createdAt)}</span>
        <span className="min-w-0">
          <span className="block truncate font-medium">{t.reference || "No reference"}</span>
          <span className="block truncate font-mono text-xs text-muted">
            {t.fromAccountNumber} to {t.toAccountNumber}
          </span>
        </span>
        <span className="order-4 justify-self-end md:order-none">
          <StatusBadge status={t.status} />
        </span>
        <span className="row-span-1 justify-self-end text-right">
          <Money minor={t.amountMinor} />
        </span>
      </Link>
    </li>
  );
}
