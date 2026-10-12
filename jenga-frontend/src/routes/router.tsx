import { lazy } from "react";
import { createBrowserRouter, Navigate } from "react-router-dom";
import { AppShell } from "../layouts/AppShell";
import { ProtectedRoute } from "./ProtectedRoute";
import { RoleRoute } from "./RoleRoute";
import Login from "../pages/Login";
import NotFound from "../pages/NotFound";
import { useAuth } from "../hooks/useAuth";
import { homeFor } from "../layouts/nav";

const Dashboard = lazy(() => import("../pages/Dashboard"));
const AccountDetail = lazy(() => import("../pages/AccountDetail"));
const NewTransfer = lazy(() => import("../pages/NewTransfer"));
const Transfers = lazy(() => import("../pages/Transfers"));
const TransferDetail = lazy(() => import("../pages/TransferDetail"));
const Approvals = lazy(() => import("../pages/staff/Approvals"));
const Reversals = lazy(() => import("../pages/staff/Reversals"));
const Reconciliation = lazy(() => import("../pages/staff/Reconciliation"));
const Audit = lazy(() => import("../pages/staff/Audit"));

function Home() {
  const { user } = useAuth();
  if (user && !user.roles.includes("CUSTOMER")) return <Navigate to={homeFor(user.roles)} replace />;
  return <Dashboard />;
}

export const router = createBrowserRouter([
  { path: "/login", element: <Login /> },
  {
    element: <ProtectedRoute />,
    children: [
      {
        element: <AppShell />,
        children: [
          { index: true, element: <Home /> },
          { path: "transfers", element: <Transfers /> },
          { path: "transfers/:transferId", element: <TransferDetail /> },
          {
            element: <RoleRoute roles={["CUSTOMER"]} />,
            children: [
              { path: "accounts/:accountId", element: <AccountDetail /> },
              { path: "send", element: <NewTransfer /> },
            ],
          },
          { element: <RoleRoute roles={["CHECKER"]} />, children: [{ path: "staff/approvals", element: <Approvals /> }] },
          { element: <RoleRoute roles={["MAKER"]} />, children: [{ path: "staff/reversals", element: <Reversals /> }] },
          {
            element: <RoleRoute roles={["MAKER", "CHECKER"]} />,
            children: [
              { path: "staff/reconciliation", element: <Reconciliation /> },
              { path: "staff/audit", element: <Audit /> },
            ],
          },
          { path: "*", element: <NotFound /> },
        ],
      },
    ],
  },
]);
