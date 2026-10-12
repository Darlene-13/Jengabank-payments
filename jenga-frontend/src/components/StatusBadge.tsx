import type { TransferStatus } from "../types/api";
import { cn } from "../lib/cn";

export const statusLabel: Record<TransferStatus, string> = {
  COMPLETED: "Completed",
  PENDING_APPROVAL: "Awaiting approval",
  REJECTED: "Rejected",
  BLOCKED: "Blocked",
  REVERSED: "Reversed",
};

const tone: Record<TransferStatus, string> = {
  COMPLETED: "bg-note-soft text-note",
  PENDING_APPROVAL: "bg-review-soft text-review",
  REJECTED: "bg-debit-soft text-debit",
  BLOCKED: "bg-debit-soft text-debit",
  REVERSED: "bg-surface text-muted border border-rule",
};

export function StatusBadge({ status, className }: { status: TransferStatus; className?: string }) {
  return (
    <span className={cn("inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-xs font-medium", tone[status], className)}>
      <span aria-hidden className="size-1.5 rounded-full bg-current" />
      {statusLabel[status]}
    </span>
  );
}
