import { useState, type FormEvent } from "react";
import { Navigate, useLocation } from "react-router-dom";
import { useAuth } from "../hooks/useAuth";
import { homeFor } from "../layouts/nav";
import { Field } from "../components/ui/Field";
import { Button } from "../components/ui/Button";
import { Rosette } from "../components/Guilloche";
import { Wordmark } from "../components/Wordmark";
import { toApiError } from "../api/client";

const reasonText = {
  idle: "You were signed out after 10 minutes without activity.",
  expired: "Your session ended. Sign in again to continue.",
  manual: "You've signed out.",
} as const;

function loginMessage(status: number, fallback: string): string {
  switch (status) {
    case 401: return "That username and password don't match. Check them and try again.";
    case 423: return "This account is locked after too many failed attempts. Try again later or contact the bank.";
    case 429: return "Too many sign-in attempts. Wait a minute, then try again.";
    default: return fallback;
  }
}

export default function Login() {
  const { user, login, signOutReason } = useAuth();
  const location = useLocation();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [show, setShow] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (user) {
    const from = (location.state as { from?: string } | null)?.from;
    return <Navigate to={from && from !== "/login" ? from : homeFor(user.roles)} replace />;
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!username.trim() || !password) {
      setError("Enter your username and password.");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await login(username.trim(), password);
    } catch (err) {
      const apiErr = toApiError(err);
      setError(loginMessage(apiErr.status, apiErr.message));
      setPassword("");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="grid min-h-dvh lg:grid-cols-[1.1fr_1fr]">
      <section className="relative hidden overflow-hidden bg-rail text-on-rail lg:flex lg:flex-col lg:justify-between lg:p-14">
        <Wordmark inverse />
        <Rosette
          seed="jengabank-ledger"
          size={760}
          draw
          className="voucher-in pointer-events-none absolute -bottom-56 -right-56 text-note opacity-80"
        />
        <div className="relative my-auto max-w-md">
          <p className="text-[2.6rem] font-semibold leading-[1.1] tracking-tight text-white">
            Every shilling is written twice.
          </p>
          <p className="mt-4 text-lg text-on-rail/80">
            Once where it leaves, once where it lands. If the two sides ever disagree, we know before you do.
          </p>
        </div>
      </section>

      <section className="flex items-center justify-center px-6 py-12">
        <div className="w-full max-w-sm">
          <div className="mb-10 lg:hidden">
            <Wordmark />
          </div>
          <h1 className="text-3xl font-semibold tracking-tight">Welcome back to the ledger</h1>
          <p className="mt-2 text-muted">One door for customers and bank staff. Your role decides which rooms open.</p>

          {signOutReason && !error && (
            <p role="status" className="mt-6 border-l-4 border-rule bg-surface px-4 py-3 text-sm">
              {reasonText[signOutReason]}
            </p>
          )}
          {error && (
            <p role="alert" className="mt-6 border-l-4 border-debit bg-debit-soft px-4 py-3 text-sm text-debit">
              {error}
            </p>
          )}

          <form onSubmit={submit} noValidate className="mt-8 flex flex-col gap-5">
            <Field
              label="Username"
              name="username"
              autoComplete="username"
              autoCapitalize="none"
              spellCheck={false}
              maxLength={64}
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              required
            />
            <div className="relative">
              <Field
                label="Password"
                name="password"
                type={show ? "text" : "password"}
                autoComplete="current-password"
                maxLength={128}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                className="pr-16"
                required
              />
              <button
                type="button"
                onClick={() => setShow((s) => !s)}
                aria-pressed={show}
                className="absolute bottom-2 right-2 px-2 py-1 text-sm text-muted hover:text-ink"
              >
                {show ? "Hide" : "Show"}
              </button>
            </div>
            <Button type="submit" busy={busy} busyLabel="Checking your keys" className="mt-2 w-full">
              Enter JengaBank
            </Button>
          </form>
          <p className="mt-8 text-sm text-muted">
            We lock the door behind you: after 10 quiet minutes, or the moment you close or reload this page.
          </p>
        </div>
      </section>
    </div>
  );
}
