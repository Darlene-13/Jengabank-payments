/*
  Frontend copy of the API contract (docs/api.md).
  Backend DTOs must serialise to exactly these shapes. Money is always integer cents.
*/

export type Role = "CUSTOMER" | "MAKER" | "CHECKER";

export interface Me {
  id: string;
  username: string;
  displayName: string;
  roles: Role[];
}

export interface LoginRequest {
  username: string;
  password: string;
}

export interface LoginResponse {
  token: string;
  expiresAt: string;
  user: Me;
}

export interface Page<T> {
  items: T[];
  nextCursor: string | null;
}

export interface Account {
  id: string;
  accountNumber: string;
  name: string;
  currency: "KES";
  /** Derived server-side as SUM(ledger entries). Never stored. */
  balanceMinor: number;
  status: "ACTIVE" | "FROZEN" | "CLOSED";
}

export type Direction = "DEBIT" | "CREDIT";

export interface LedgerEntry {
  id: string;
  journalId: string;
  accountId: string;
  direction: Direction;
  amountMinor: number;
  currency: "KES";
  description: string;
  createdAt: string;
}

export type TransferStatus =
  | "COMPLETED"
  | "PENDING_APPROVAL"
  | "REJECTED"
  | "BLOCKED"
  | "REVERSED";

export type FraudOutcome = "ALLOW" | "REVIEW" | "BLOCK";

export interface FraudDecision {
  decision: FraudOutcome;
  reasons: string[];
}

export interface Transfer {
  id: string;
  fromAccountId: string;
  fromAccountNumber: string;
  toAccountNumber: string;
  amountMinor: number;
  currency: "KES";
  reference: string;
  status: TransferStatus;
  fraud: FraudDecision | null;
  journalId: string | null;
  createdBy: string;
  createdAt: string;
  approvedBy: string | null;
  approvedAt: string | null;
  rejectionReason: string | null;
  reversalJournalId: string | null;
}

export interface CreateTransferRequest {
  fromAccountId: string;
  toAccountNumber: string;
  amountMinor: number;
  currency: "KES";
  reference: string;
}

export interface ReversalRequest {
  reason: string;
}

export type ReversalStatus = "PENDING_APPROVAL" | "POSTED" | "REJECTED";

export interface Reversal {
  id: string;
  transferId: string;
  status: ReversalStatus;
  reason: string;
  amountMinor: number;
  fromAccountNumber: string;
  toAccountNumber: string;
  journalId: string | null;
  requestedBy: string;
  requestedAt: string;
  decidedBy: string | null;
  decidedAt: string | null;
}

export interface RejectRequest {
  reason: string;
}

export interface ReconciliationCheck {
  name: string;
  passed: boolean;
  detail: string;
}

export interface ReconciliationRun {
  id: string;
  runAt: string;
  status: "PASSED" | "FAILED";
  checks: ReconciliationCheck[];
}

export interface AuditRecord {
  sequence: number;
  ts: string;
  actor: string;
  action: string;
  target: string;
  hash: string;
  prevHash: string;
}

export interface AuditVerification {
  valid: boolean;
  checked: number;
  brokenAtSequence: number | null;
  verifiedAt: string;
}

export interface ApiErrorBody {
  code: string;
  message: string;
  requestId: string;
  /** Optional: fraud or limit reasons on a 422. */
  reasons?: string[];
}
