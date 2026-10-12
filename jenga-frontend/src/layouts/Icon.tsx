import type { NavItem } from "./nav";

const paths: Record<NavItem["icon"], string> = {
  home: "M4 10.5 12 4l8 6.5V20a1 1 0 0 1-1 1h-5v-6h-4v6H5a1 1 0 0 1-1-1z",
  send: "M4 12h13M12 6l6 6-6 6",
  list: "M5 6h14M5 12h14M5 18h9",
  check: "M5 12.5 10 17l9-10",
  undo: "M9 5 4 10l5 5M4 10h10a5 5 0 0 1 0 10h-3",
  scale: "M12 4v16M6 20h12M5 8h14M5 8l-2.5 6h5zM19 8l-2.5 6h5z",
  chain: "M9.5 14.5 14.5 9.5M8 11l-2 2a3.5 3.5 0 0 0 5 5l2-2M16 13l2-2a3.5 3.5 0 0 0-5-5l-2 2",
};

export function Icon({ name, className }: { name: NavItem["icon"]; className?: string }) {
  return (
    <svg aria-hidden viewBox="0 0 24 24" className={className ?? "size-5"} fill="none" stroke="currentColor" strokeWidth={1.6} strokeLinecap="round" strokeLinejoin="round">
      <path d={paths[name]} />
    </svg>
  );
}
