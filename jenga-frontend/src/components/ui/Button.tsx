import type { ButtonHTMLAttributes } from "react";
import { cn } from "../../lib/cn";

type Variant = "primary" | "secondary" | "quiet" | "danger";

interface Props extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  busy?: boolean;
  busyLabel?: string;
}

const styles: Record<Variant, string> = {
  primary: "bg-note text-on-note hover:brightness-110 border border-note",
  secondary: "bg-surface text-ink border border-rule hover:border-ink",
  quiet: "bg-transparent text-ink border border-transparent hover:bg-surface underline-offset-4 hover:underline",
  danger: "bg-debit text-surface border border-debit hover:brightness-110",
};

export function Button({ variant = "primary", busy, busyLabel, className, children, disabled, ...rest }: Props) {
  return (
    <button
      {...rest}
      disabled={disabled || busy}
      aria-busy={busy || undefined}
      className={cn(
        "inline-flex min-h-11 items-center justify-center gap-2 rounded-[3px] px-5 text-[0.95rem] font-medium transition-[filter,border-color,background-color] disabled:cursor-not-allowed disabled:opacity-50",
        styles[variant],
        className,
      )}
    >
      {busy && (
        <span aria-hidden className="size-3.5 animate-spin rounded-full border-2 border-current border-r-transparent" />
      )}
      {busy && busyLabel ? busyLabel : children}
    </button>
  );
}
