import { useOnlineStatus } from "../hooks/useOnlineStatus";

export function OfflineBanner() {
  const online = useOnlineStatus();
  if (online) return null;
  return (
    <div role="status" className="bg-review-soft px-4 py-2 text-center text-sm text-review">
      You're offline. Balances and transfers need a connection, so they're paused until you're back.
    </div>
  );
}
