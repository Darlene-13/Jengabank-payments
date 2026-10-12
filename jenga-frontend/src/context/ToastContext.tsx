import { createContext, useCallback, useMemo, useState, type ReactNode } from "react";
import { cn } from "../lib/cn";

type Tone = "neutral" | "success" | "danger";
interface Toast {
  id: number;
  message: string;
  tone: Tone;
}

export interface ToastApi {
  notify: (message: string, tone?: Tone) => void;
}

export const ToastContext = createContext<ToastApi | null>(null);

let nextId = 1;

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);

  const notify = useCallback((message: string, tone: Tone = "neutral") => {
    const id = nextId++;
    setToasts((t) => [...t.slice(-2), { id, message, tone }]);
    window.setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), 5000);
  }, []);

  const value = useMemo(() => ({ notify }), [notify]);

  return (
    <ToastContext.Provider value={value}>
      {children}
      <div
        aria-live="polite"
        className="pointer-events-none fixed inset-x-0 bottom-20 z-50 flex flex-col items-center gap-2 px-4 md:bottom-6 md:items-end md:pr-6"
      >
        {toasts.map((t) => (
          <div
            key={t.id}
            className={cn(
              "pointer-events-auto max-w-sm border-l-4 bg-ink px-4 py-3 text-sm text-paper shadow-lg",
              t.tone === "success" && "border-note",
              t.tone === "danger" && "border-debit",
              t.tone === "neutral" && "border-rule",
            )}
          >
            {t.message}
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}
