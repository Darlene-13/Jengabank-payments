import { Link, useParams } from "react-router-dom";
import { accountsApi } from "../api/accounts";
import { useAsync, usePaged } from "../hooks/useAsync";
import { Money } from "../components/Money";
import { LedgerTable } from "../components/LedgerTable";
import { ErrorNotice } from "../components/ErrorNotice";
import { EmptyState } from "../components/EmptyState";
import { LoadingRows, Skeleton } from "../components/ui/Skeleton";
import { Button } from "../components/ui/Button";

export default function AccountDetail() {
  const { accountId = "" } = useParams();
  const account = useAsync((s) => accountsApi.get(accountId, s), [accountId]);
  const entries = usePaged((cursor, s) => accountsApi.entries(accountId, cursor, s), [accountId]);

  return (
    <>
      <Link to="/" className="text-sm text-muted underline-offset-4 hover:underline">Accounts</Link>

      {account.loading && <Skeleton className="mt-4 h-24 w-full" />}
      {account.error && <div className="mt-4"><ErrorNotice error={account.error} onRetry={account.reload} /></div>}
      {account.data && (
        <header className="mt-3 flex flex-col gap-4 border-b border-rule pb-8 md:flex-row md:items-end md:justify-between">
          <div>
            <h1 className="text-3xl font-semibold tracking-tight">{account.data.name}</h1>
            <p className="mt-1 font-mono text-muted">{account.data.accountNumber}</p>
          </div>
          <div className="md:text-right">
            <p className="text-sm text-muted">Balance from ledger</p>
            <Money minor={account.data.balanceMinor} size="xl" />
          </div>
        </header>
      )}

      <section aria-labelledby="entries-h" className="mt-10">
        <h2 id="entries-h" className="mb-4 text-lg font-semibold">Ledger entries</h2>
        {entries.loading && <LoadingRows rows={5} />}
        {entries.error && <ErrorNotice error={entries.error} onRetry={entries.reload} />}
        {!entries.loading && !entries.error && entries.items.length === 0 && (
          <EmptyState title="No entries on this account" body="Every payment in or out adds a line here. Lines are never edited or deleted." />
        )}
        {entries.items.length > 0 && <LedgerTable entries={entries.items} />}
        {entries.hasMore && (
          <div className="mt-6 flex justify-center">
            <Button variant="secondary" busy={entries.loadingMore} onClick={entries.loadMore}>Show older entries</Button>
          </div>
        )}
      </section>
    </>
  );
}
