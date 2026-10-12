import type { ApiError } from "../api/client";
import { Button } from "./ui/Button";

export function ErrorNotice({ error, onRetry }: { error: ApiError; onRetry?: () => void }) {
  return (
    <div role="alert" className="border-l-4 border-debit bg-debit-soft px-4 py-3">
      <p className="font-medium text-debit">{error.message}</p>
      {error.requestId && (
        <p className="mt-1 text-xs text-muted">
          Reference for support: <span className="font-mono">{error.requestId}</span>
        </p>
      )}
      {onRetry && (
        <Button variant="secondary" className="mt-3 min-h-9 px-3 text-sm" onClick={onRetry}>
          Try again
        </Button>
      )}
    </div>
  );
}
