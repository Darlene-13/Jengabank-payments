import { Rosette } from "./Guilloche";
import { cn } from "../lib/cn";

export function Wordmark({ className, inverse }: { className?: string; inverse?: boolean }) {
  return (
    <span className={cn("inline-flex items-center gap-2.5", className)}>
      <Rosette seed="jengabank" size={30} layers={1} className={inverse ? "text-on-rail" : "text-note"} />
      <span className="leading-none">
        <span className="block text-[1.05rem] font-semibold tracking-tight">JengaBank</span>
        <span className={cn("mt-1 block text-xs", inverse ? "text-on-rail/70" : "text-muted")}>Payments</span>
      </span>
    </span>
  );
}
