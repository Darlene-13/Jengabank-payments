import { NavLink } from "react-router-dom";
import { useAuth } from "../hooks/useAuth";
import { navFor } from "./nav";
import { Icon } from "./Icon";
import { Wordmark } from "../components/Wordmark";
import { cn } from "../lib/cn";

const roleName = { CUSTOMER: "Customer", MAKER: "Operations (maker)", CHECKER: "Approver (checker)" } as const;

export function Sidebar() {
  const { user, logout } = useAuth();
  if (!user) return null;
  const items = navFor(user.roles);

  return (
    <aside className="sticky top-0 hidden h-dvh w-64 shrink-0 flex-col bg-rail text-on-rail md:flex">
      <div className="px-6 pb-8 pt-7">
        <Wordmark inverse />
      </div>
      <nav aria-label="Main" className="flex-1 px-3">
        <ul className="flex flex-col gap-0.5">
          {items.map((n) => (
            <li key={n.to}>
              <NavLink
                to={n.to}
                end={n.end}
                className={({ isActive }) =>
                  cn(
                    "flex items-center gap-3 border-l-2 px-3 py-2.5 text-[0.95rem] transition-colors",
                    isActive ? "border-note bg-white/[0.06] text-white" : "border-transparent text-on-rail/80 hover:text-white",
                  )
                }
              >
                <Icon name={n.icon} />
                {n.label}
              </NavLink>
            </li>
          ))}
        </ul>
      </nav>
      <div className="border-t border-white/10 px-6 py-5 text-sm">
        <p className="font-medium text-white">{user.displayName}</p>
        <p className="text-on-rail/70">{user.roles.map((r) => roleName[r]).join(", ")}</p>
        <button onClick={() => logout("manual")} className="mt-3 text-on-rail underline-offset-4 hover:text-white hover:underline">
          Sign out
        </button>
      </div>
    </aside>
  );
}
