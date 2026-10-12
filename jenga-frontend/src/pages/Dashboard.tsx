import { Link } from "react-router-dom";
import { accountsApi } from "../api/accounts";
import { transfersApi } from "../api/transfers";
import { useAsync } from "../hooks/useAsync";
import { useAuth } from "../hooks/useAuth";
import { greeting } from "../lib/dates";
import { Money } from "../components/Money";
import { TransferRow } from "../components/TransferRow";
import { ErrorNotice } from "../components/ErrorNotice";
import { EmptyState } from "../components/EmptyState";
import { LoadingRows } from "../components/ui/Skeleton";
import { PageHeader } from "../components/PageHeader";

export default function Dashboard() {
  const { user } = useAuth();
  const accounts = useAsync((s) => accountsApi.list(s), []);
  const recent = useAsync((s) => transfersApi.list({ limit: 5 }, s), []);

  const total = accounts.data?.items.reduce((sum, a) => sum + a.balanceMinor, 0) ?? 0;

  return (
    <>
      <PageHeader
        title={`${greeting()}, ${user?.displayName.split(" ")[0] ?? ""}`}
        description="Your accounts and the money moving through them."
        actions={
          <Link to="/send" className="inline-flex min-h-11 items-center rounded-[3px] bg-note px-5 font-medium text-on-note hover:brightness-110">
            Send money
          </Link>
        }
      />

      <section aria-labelledby="accounts-h">
        <div className="flex items-baseline justify-between">
          <h2 id="accounts-h" className="text-lg font-semibold">Accounts</h2>
          {accounts.data && accounts.data.items.length > 1 && (
            <p className="text-sm text-muted">
              Total <Money minor={total} size="sm" />
            </p>
          )}
        </div>

        {accounts.loading && <LoadingRows rows={2} />}
        {accounts.error && <div className="mt-4"><ErrorNotice error={accounts.error} onRetry={accounts.reload} /></div>}
        {accounts.data && accounts.data.items.length === 0 && (
          <div className="mt-4"><EmptyState title="No accounts yet" body="When the bank opens an account for you, it appears here." /></div>
        )}

        {accounts.data && accounts.data.items.length > 0 && (
          <ul className="mt-4 border-t-2 border-ink/70">
            {accounts.data.items.map((a) => (
              <li key={a.id}>
                <Link
                  to={`/accounts/${encodeURIComponent(a.id)}`}
                  className="flex flex-col gap-2 border-b border-rule px-1 py-5 hover:bg-surface sm:flex-row sm:items-center sm:justify-between"
                >
                  <span>
                    <span className="block font-medium">{a.name}</span>
                    <span className="block font-mono text-sm text-muted">{a.accountNumber}</span>
                    {a.status !== "ACTIVE" && (
                      <span className="mt-1 inline-block text-sm text-debit">{a.status === "FROZEN" ? "Frozen" : "Closed"}</span>
                    )}
                  </span>
                  <Money minor={a.balanceMinor} size="lg" />
                </Link>
              </li>
            ))}
          </ul>
        )}
        <p className="mt-3 text-sm text-muted">
          Balances are added up from your ledger entries every time you look. They're never typed in or stored on their own.
        </p>
      </section>

      <section aria-labelledby="recent-h" className="mt-14">
        <div className="flex items-baseline justify-between">
          <h2 id="recent-h" className="text-lg font-semibold">Recent transfers</h2>
          <Link to="/transfers" className="text-sm text-note underline-offset-4 hover:underline">All transfers</Link>
        </div>
        {recent.loading && <LoadingRows rows={3} />}
        {recent.error && <div className="mt-4"><ErrorNotice error={recent.error} onRetry={recent.reload} /></div>}
        {recent.data && recent.data.items.length === 0 && (
          <div className="mt-4">
            <EmptyState
              title="No transfers yet"
              body="Send money to another JengaBank account and it shows up here."
              action={<Link to="/send" className="text-note underline underline-offset-4">Send money</Link>}
            />
          </div>
        )}
        {recent.data && recent.data.items.length > 0 && (
          <ul className="mt-2">{recent.data.items.map((t) => <TransferRow key={t.id} t={t} />)}</ul>
        )}
      </section>
    </>
  );
}
