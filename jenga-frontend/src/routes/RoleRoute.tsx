import { Navigate, Outlet } from "react-router-dom";
import { useAuth } from "../hooks/useAuth";
import { homeFor } from "../layouts/nav";
import type { Role } from "../types/api";

/* UI convenience only. The backend enforces every role check on its own. */
export function RoleRoute({ roles }: { roles: Role[] }) {
  const { user, hasRole } = useAuth();
  if (!user) return <Navigate to="/login" replace />;
  if (!hasRole(...roles)) return <Navigate to={homeFor(user.roles)} replace />;
  return <Outlet />;
}
