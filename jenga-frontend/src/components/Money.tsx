import { splitMinor } from "../lib/money";
import { cn } from "../lib/cn";

interface Props {
  minor: number;
  size?: "sm" | "md" | "lg" | "xl";
  sign?: boolean;
  tone?: "ink" | "credit" | "debit";
  className?: string;
}

/* Shillings carry the weight; cents sit smaller and raised, the way amounts are written on a cheque. */
export function Money({ minor, size = "md", sign, tone = "ink", className }: Props) {
  const { negative, whole, cents } = splitMinor(minor);
  const prefix = negative ? "\u2212" : sign && minor !== 0 ? "+" : "";
  const sizes = {
    sm: ["text-sm", "text-[0.7em]"],
    md: ["text-base", "text-[0.7em]"],
    lg: ["text-2xl font-semibold tracking-tight", "text-[0.55em]"],
    xl: ["text-5xl font-semibold tracking-tight md:text-6xl", "text-[0.42em]"],
  }[size];

  return (
    <span
      className={cn(
        "figures whitespace-nowrap",
        sizes[0],
        tone === "credit" && "text-note",
        tone === "debit" && "text-debit",
        className,
      )}
      aria-label={`${negative ? "minus " : ""}${whole} shillings ${cents} cents`}
    >
      <span className="mr-[0.25em] text-[0.55em] font-medium text-muted">KES</span>
      {prefix}
      {whole}
      <span className={cn("align-[0.35em] font-normal", sizes[1])}>.{cents}</span>
    </span>
  );
}
