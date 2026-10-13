import { NavLink } from "react-router-dom";
import { useAuth } from "../hooks/useAuth";
import { navFor } from "./nav";
import { Icon } from "./Icon";
import { cn } from "../lib/cn";

// Literal class names so Tailwind emits them; no inline style keeps CSP style-src 'self'.
const COLS = ["grid-cols-1", "grid-cols-2", "grid-cols-3", "grid-cols-4", "grid-cols-5"];

export function BottomNav() {
  const { user } = useAuth();
  if (!user) return null;
  const items = navFor(user.roles).slice(0, 5);

  return (
    <nav aria-label="Main" className="fixed inset-x-0 bottom-0 z-40 border-t border-rule bg-surface pb-[env(safe-area-inset-bottom)] md:hidden">
      <ul className={cn("grid", COLS[Math.max(items.length, 1) - 1])}>
        {items.map((n) => (
          <li key={n.to}>
            <NavLink
              to={n.to}
              end={n.end}
              className={({ isActive }) =>
                cn("flex flex-col items-center gap-1 py-2.5 text-[0.7rem]", isActive ? "text-note" : "text-muted")
              }
            >
              <Icon name={n.icon} />
              {n.label}
            </NavLink>
          </li>
        ))}
      </ul>
    </nav>
  );
}
