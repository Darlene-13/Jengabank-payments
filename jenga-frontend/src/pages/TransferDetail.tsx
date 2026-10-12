import { Link, useParams } from "react-router-dom";
import { transfersApi } from "../api/transfers";
import { useAsync } from "../hooks/useAsync";
import { useAuth } from "../hooks/useAuth";
import { Voucher } from "../components/Voucher";
import { ErrorNotice } from "../components/ErrorNotice";
import { Skeleton } from "../components/ui/Skeleton";

export default function TransferDetail() {
  const { transferId = "" } = useParams();
  const { hasRole } = useAuth();
  const t = useAsync((s) => transfersApi.get(transferId, s), [transferId]);

  return (
    <div className="max-w-3xl">
      <Link to="/transfers" className="text-sm text-muted underline-offset-4 hover:underline">Transfers</Link>
      <div className="mt-4">
        {t.loading && <Skeleton className="h-96 w-full" />}
        {t.error && <ErrorNotice error={t.error} onRetry={t.reload} />}
        {t.data && (
          <Voucher
            data={t.data}
            footer={
              hasRole("MAKER") && t.data.status === "COMPLETED" ? (
                <Link
                  to={`/staff/reversals?transfer=${encodeURIComponent(t.data.id)}`}
                  className="inline-flex min-h-11 items-center rounded-[3px] border border-rule px-5 font-medium hover:border-ink"
                >
                  Reverse this transfer
                </Link>
              ) : undefined
            }
          />
        )}
      </div>
    </div>
  );
}
