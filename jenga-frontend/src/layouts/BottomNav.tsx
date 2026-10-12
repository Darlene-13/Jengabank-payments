import { NavLink } from "react-router-dom";
import { useAuth } from "../hooks/useAuth";
import { navFor } from "./nav";
import { Icon } from "./Icon";
import { cn } from "../lib/cn";

export function BottomNav() {
  const { user } = useAuth();
  if (!user) return null;
  const items = navFor(user.roles).slice(0, 5);

  return (
    <nav aria-label="Main" className="fixed inset-x-0 bottom-0 z-40 border-t border-rule bg-surface pb-[env(safe-area-inset-bottom)] md:hidden">
      <ul className="grid" style={{ gridTemplateColumns: `repeat(${items.length}, minmax(0, 1fr))` }}>
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
