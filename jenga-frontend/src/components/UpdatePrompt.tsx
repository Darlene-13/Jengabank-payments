import { usePwaUpdate } from "../hooks/usePwaUpdate";
import { Button } from "./ui/Button";

export function UpdatePrompt() {
  const { needRefresh, reload, dismiss } = usePwaUpdate();
  if (!needRefresh) return null;
  return (
    <div role="status" className="fixed inset-x-4 top-4 z-50 mx-auto flex max-w-md items-center gap-3 border border-rule bg-surface px-4 py-3 shadow-lg">
      <p className="flex-1 text-sm">A new version of JengaBank is ready.</p>
      <Button variant="quiet" className="min-h-9 px-3 text-sm" onClick={dismiss}>Later</Button>
      <Button className="min-h-9 px-3 text-sm" onClick={reload}>Reload</Button>
    </div>
  );
}
