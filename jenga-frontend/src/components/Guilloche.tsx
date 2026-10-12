import { useMemo } from "react";
import { bandPaths, rosetteLayers } from "../lib/guilloche";
import { cn } from "../lib/cn";

export function Rosette({ seed, size = 160, className, draw, layers = 3 }: { seed: string; size?: number; className?: string; draw?: boolean; layers?: number }) {
  const paths = useMemo(() => rosetteLayers(seed, 200, layers), [seed, layers]);
  return (
    <svg
      aria-hidden
      viewBox="0 0 200 200"
      width={size}
      height={size}
      className={cn(draw && "guilloche-draw", className)}
      fill="none"
      stroke="currentColor"
    >
      {paths.map((d, i) => (
        <path key={i} d={d} strokeWidth={0.45} opacity={1 - i * 0.22} vectorEffect="non-scaling-stroke" />
      ))}
    </svg>
  );
}

export function Band({ seed, className, draw }: { seed: string; className?: string; draw?: boolean }) {
  const paths = useMemo(() => bandPaths(seed, 600, 28), [seed]);
  return (
    <svg
      aria-hidden
      viewBox="0 0 600 28"
      preserveAspectRatio="none"
      className={cn("block h-7 w-full", draw && "guilloche-draw", className)}
      fill="none"
      stroke="currentColor"
    >
      {paths.map((d, i) => (
        <path key={i} d={d} strokeWidth={0.7} opacity={0.85} vectorEffect="non-scaling-stroke" />
      ))}
    </svg>
  );
}
