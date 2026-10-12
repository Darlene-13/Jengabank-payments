import { useId, type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes, type TextareaHTMLAttributes } from "react";
import { cn } from "../../lib/cn";

interface Shell {
  label: string;
  hint?: ReactNode;
  error?: string | null;
}

const control =
  "w-full rounded-[3px] border bg-surface px-3.5 py-2.5 text-ink placeholder:text-muted/70 focus:outline-none focus-visible:border-focus focus-visible:outline-2 focus-visible:outline-offset-0";

function Wrap({ id, label, hint, error, children }: Shell & { id: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={id} className="text-sm font-medium text-ink">
        {label}
      </label>
      {children}
      {error ? (
        <p id={`${id}-msg`} className="text-sm text-debit">
          {error}
        </p>
      ) : hint ? (
        <p id={`${id}-msg`} className="text-sm text-muted">
          {hint}
        </p>
      ) : null}
    </div>
  );
}

export function Field({ label, hint, error, className, ...rest }: Shell & InputHTMLAttributes<HTMLInputElement>) {
  const id = useId();
  return (
    <Wrap id={id} label={label} hint={hint} error={error}>
      <input
        id={id}
        aria-invalid={!!error || undefined}
        aria-describedby={hint || error ? `${id}-msg` : undefined}
        className={cn(control, error ? "border-debit" : "border-rule", className)}
        {...rest}
      />
    </Wrap>
  );
}

export function TextArea({ label, hint, error, className, ...rest }: Shell & TextareaHTMLAttributes<HTMLTextAreaElement>) {
  const id = useId();
  return (
    <Wrap id={id} label={label} hint={hint} error={error}>
      <textarea
        id={id}
        aria-invalid={!!error || undefined}
        aria-describedby={hint || error ? `${id}-msg` : undefined}
        className={cn(control, "min-h-24 resize-y", error ? "border-debit" : "border-rule", className)}
        {...rest}
      />
    </Wrap>
  );
}

export function Select({ label, hint, error, className, children, ...rest }: Shell & SelectHTMLAttributes<HTMLSelectElement>) {
  const id = useId();
  return (
    <Wrap id={id} label={label} hint={hint} error={error}>
      <select
        id={id}
        aria-invalid={!!error || undefined}
        aria-describedby={hint || error ? `${id}-msg` : undefined}
        className={cn(control, "appearance-none", error ? "border-debit" : "border-rule", className)}
        {...rest}
      >
        {children}
      </select>
    </Wrap>
  );
}
