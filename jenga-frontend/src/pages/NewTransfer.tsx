import { useMemo, useRef, useState, type FormEvent } from "react";
import { Link } from "react-router-dom";
import { accountsApi } from "../api/accounts";
import { transfersApi } from "../api/transfers";
import { ApiError, toApiError } from "../api/client";
import { newIdempotencyKey } from "../api/idempotency";
import { useAsync } from "../hooks/useAsync";
import { useToast } from "../hooks/useToast";
import { useOnlineStatus } from "../hooks/useOnlineStatus";
import { formatMinor, parseAmountToMinor } from "../lib/money";
import type { Account, Transfer } from "../types/api";
import { PageHeader } from "../components/PageHeader";
import { Field, Select } from "../components/ui/Field";
import { Button } from "../components/ui/Button";
import { Money } from "../components/Money";
import { Voucher } from "../components/Voucher";
import { ErrorNotice } from "../components/ErrorNotice";
import { LoadingRows } from "../components/ui/Skeleton";
import { cn } from "../lib/cn";

type Step = "details" | "review" | "result";
type Outcome = { kind: "sent"; transfer: Transfer } | { kind: "stopped"; error: ApiError };

interface Form {
  fromAccountId: string;
  toAccountNumber: string;
  amount: string;
  reference: string;
}

const steps: { id: Step; label: string }[] = [
  { id: "details", label: "Details" },
  { id: "review", label: "Check and send" },
  { id: "result", label: "Receipt" },
];

const cleanAccount = (v: string) => v.replace(/\s+/g, "");
// Strip control characters; the backend validates again.
const cleanText = (v: string) => v.replace(/[\u0000-\u001f\u007f]/g, "").trim();

export default function NewTransfer() {
  const { notify } = useToast();
  const online = useOnlineStatus();
  const accounts = useAsync((s) => accountsApi.list(s), []);
  const active = useMemo(() => accounts.data?.items.filter((a) => a.status === "ACTIVE") ?? [], [accounts.data]);

  const [step, setStep] = useState<Step>("details");
  const [form, setForm] = useState<Form>({ fromAccountId: "", toAccountNumber: "", amount: "", reference: "" });
  const [errors, setErrors] = useState<Partial<Record<keyof Form, string>>>({});
  const [sendError, setSendError] = useState<ApiError | null>(null);
  const [sending, setSending] = useState(false);
  const [outcome, setOutcome] = useState<Outcome | null>(null);

  // One key per intended transfer. Retries reuse it, so the bank can never post it twice.
  const keyRef = useRef<string | null>(null);
  const inFlight = useRef(false);

  const from: Account | undefined = active.find((a) => a.id === form.fromAccountId) ?? active[0];
  const amountMinor = parseAmountToMinor(form.amount);

  function update<K extends keyof Form>(k: K, v: Form[K]) {
    setForm((f) => ({ ...f, [k]: v }));
    setErrors((e) => ({ ...e, [k]: undefined }));
  }

  function validate(): boolean {
    const next: typeof errors = {};
    const to = cleanAccount(form.toAccountNumber);
    if (!from) next.fromAccountId = "Choose the account to send from.";
    if (!/^\d{6,20}$/.test(to)) next.toAccountNumber = "Enter the 6 to 20 digit account number.";
    else if (from && to === cleanAccount(from.accountNumber)) next.toAccountNumber = "You can't send money to the same account.";
    if (amountMinor === null) next.amount = "Enter an amount above zero, with up to 2 decimal places.";
    else if (from && amountMinor > from.balanceMinor) next.amount = `That's more than the balance of ${formatMinor(from.balanceMinor)}.`;
    if (cleanText(form.reference).length > 140) next.reference = "Keep the reference under 140 characters.";
    setErrors(next);
    return Object.keys(next).length === 0;
  }

  function toReview(e: FormEvent) {
    e.preventDefault();
    if (!validate()) return;
    keyRef.current = newIdempotencyKey();
    setSendError(null);
    setStep("review");
  }

  function edit() {
    keyRef.current = null; // details may change, so this becomes a new transfer
    setSendError(null);
    setStep("details");
  }

  async function send() {
    if (inFlight.current || !from || amountMinor === null || !keyRef.current) return;
    inFlight.current = true;
    setSending(true);
    setSendError(null);
    try {
      const transfer = await transfersApi.create(
        {
          fromAccountId: from.id,
          toAccountNumber: cleanAccount(form.toAccountNumber),
          amountMinor,
          currency: "KES",
          reference: cleanText(form.reference),
        },
        keyRef.current,
      );
      setOutcome({ kind: "sent", transfer });
      setStep("result");
      notify(transfer.status === "PENDING_APPROVAL" ? "Transfer sent for approval." : "Money sent.", "success");
      accounts.reload();
    } catch (err) {
      const e = toApiError(err);
      if (e.status === 422) {
        setOutcome({ kind: "stopped", error: e });
        setStep("result");
      } else if (e.status === 409) {
        keyRef.current = null;
        setSendError(new ApiError(409, e.code, "This request was already used for a different transfer. Go back to the details and send it again.", e.requestId));
      } else {
        setSendError(e); // network or server trouble: retrying with the same key is safe
      }
    } finally {
      inFlight.current = false;
      setSending(false);
    }
  }

  function reset() {
    keyRef.current = null;
    setForm({ fromAccountId: from?.id ?? "", toAccountNumber: "", amount: "", reference: "" });
    setOutcome(null);
    setSendError(null);
    setStep("details");
  }

  const retryable = sendError && (sendError.status === 0 || sendError.status >= 500);

  return (
    <>
      <PageHeader title="Send money" description="Move money from your account to any JengaBank account." />

      <ol className="mb-10 flex gap-6 text-sm" aria-label="Progress">
        {steps.map((s, i) => {
          const current = s.id === step;
          const done = steps.findIndex((x) => x.id === step) > i;
          return (
            <li key={s.id} aria-current={current ? "step" : undefined} className={cn("flex items-center gap-2", current ? "text-ink" : "text-muted")}>
              <span className={cn("grid size-6 place-items-center rounded-full border text-xs", current && "border-ink bg-ink text-paper", done && "border-note text-note", !current && !done && "border-rule")}>
                {i + 1}
              </span>
              <span className={cn(!current && "hidden sm:inline")}>{s.label}</span>
            </li>
          );
        })}
      </ol>

      {accounts.loading && <LoadingRows rows={3} />}
      {accounts.error && <ErrorNotice error={accounts.error} onRetry={accounts.reload} />}

      {step === "details" && accounts.data && (
        active.length === 0 ? (
          <p className="text-muted">You don't have an active account to send from.</p>
        ) : (
          <form onSubmit={toReview} noValidate className="grid max-w-xl gap-6">
            <Select label="From" value={from?.id ?? ""} onChange={(e) => update("fromAccountId", e.target.value)} error={errors.fromAccountId}>
              {active.map((a) => (
                <option key={a.id} value={a.id}>
                  {a.name} ({a.accountNumber}), {formatMinor(a.balanceMinor)}
                </option>
              ))}
            </Select>
            <Field
              label="To account number"
              inputMode="numeric"
              autoComplete="off"
              maxLength={24}
              value={form.toAccountNumber}
              onChange={(e) => update("toAccountNumber", e.target.value)}
              error={errors.toAccountNumber}
              hint="Ask the person you're paying for their JengaBank account number."
              className="font-mono"
            />
            <Field
              label="Amount in KES"
              inputMode="decimal"
              autoComplete="off"
              maxLength={16}
              placeholder="0.00"
              value={form.amount}
              onChange={(e) => update("amount", e.target.value)}
              error={errors.amount}
              hint={from ? `Available: ${formatMinor(from.balanceMinor)}. Large amounts are checked by a second person before they move.` : undefined}
              className="figures text-lg"
            />
            <Field
              label="Reference (optional)"
              maxLength={140}
              value={form.reference}
              onChange={(e) => update("reference", e.target.value)}
              error={errors.reference}
              hint="Shown to you and the person you're paying."
            />
            <div>
              <Button type="submit">Check details</Button>
            </div>
          </form>
        )
      )}

      {step === "review" && from && amountMinor !== null && (
        <div className="max-w-xl">
          <div className="border-y-2 border-ink/70 py-6">
            <p className="text-sm text-muted">You're sending</p>
            <Money minor={amountMinor} size="xl" />
            <dl className="mt-6 grid grid-cols-[8rem_1fr] gap-y-3 text-sm">
              <dt className="text-muted">From</dt>
              <dd>{from.name} <span className="font-mono text-muted">{from.accountNumber}</span></dd>
              <dt className="text-muted">To</dt>
              <dd className="font-mono">{cleanAccount(form.toAccountNumber)}</dd>
              <dt className="text-muted">Reference</dt>
              <dd className="break-words">{cleanText(form.reference) || "None"}</dd>
              <dt className="text-muted">Balance after</dt>
              <dd><Money minor={from.balanceMinor - amountMinor} size="sm" /></dd>
            </dl>
          </div>

          <p className="mt-5 text-sm text-muted">
            Sending is protected against duplicates. If the connection drops or you press send twice, this transfer still goes through only once.
          </p>

          {sendError && (
            <div className="mt-5">
              <ErrorNotice error={sendError} />
              {retryable && <p className="mt-2 text-sm text-muted">It's safe to try again. You won't be charged twice.</p>}
            </div>
          )}

          <div className="mt-7 flex flex-wrap gap-3">
            <Button onClick={send} busy={sending} busyLabel="Sending" disabled={!online || !keyRef.current}>
              {retryable ? "Try sending again" : `Send ${formatMinor(amountMinor)}`}
            </Button>
            <Button variant="secondary" onClick={edit} disabled={sending}>Change details</Button>
          </div>
          {!online && <p className="mt-3 text-sm text-review">You're offline. Reconnect to send.</p>}
        </div>
      )}

      {step === "result" && outcome && from && amountMinor !== null && (
        <div className="max-w-3xl">
          {outcome.kind === "sent" ? (
            <Voucher
              data={outcome.transfer}
              footer={
                <>
                  <Button onClick={reset}>Send another</Button>
                  <Link to="/transfers" className="inline-flex min-h-11 items-center px-2 text-note underline-offset-4 hover:underline">See all transfers</Link>
                </>
              }
            />
          ) : (
            <Voucher
              data={{
                status: "BLOCKED",
                amountMinor,
                fromAccountNumber: from.accountNumber,
                toAccountNumber: cleanAccount(form.toAccountNumber),
                reference: cleanText(form.reference),
                fraud: outcome.error.reasons.length ? { decision: "BLOCK", reasons: outcome.error.reasons } : null,
                note: `${outcome.error.message}${outcome.error.requestId ? ` Reference for support: ${outcome.error.requestId}.` : ""}`,
              }}
              footer={<Button variant="secondary" onClick={reset}>Start a new transfer</Button>}
            />
          )}
        </div>
      )}
    </>
  );
}
