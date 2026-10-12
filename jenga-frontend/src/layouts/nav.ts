import type { Role } from "../types/api";

export interface NavItem {
  to: string;
  label: string;
  roles: Role[];
  icon: "home" | "send" | "list" | "check" | "undo" | "scale" | "chain";
  end?: boolean;
}

export const NAV: NavItem[] = [
  { to: "/", label: "Accounts", roles: ["CUSTOMER"], icon: "home", end: true },
  { to: "/send", label: "Send money", roles: ["CUSTOMER"], icon: "send" },
  { to: "/transfers", label: "Transfers", roles: ["CUSTOMER", "MAKER", "CHECKER"], icon: "list" },
  { to: "/staff/approvals", label: "Approvals", roles: ["CHECKER"], icon: "check" },
  { to: "/staff/reversals", label: "Reversals", roles: ["MAKER"], icon: "undo" },
  { to: "/staff/reconciliation", label: "Reconciliation", roles: ["MAKER", "CHECKER"], icon: "scale" },
  { to: "/staff/audit", label: "Audit trail", roles: ["MAKER", "CHECKER"], icon: "chain" },
];

export function navFor(roles: Role[]): NavItem[] {
  return NAV.filter((n) => n.roles.some((r) => roles.includes(r)));
}

export function homeFor(roles: Role[]): string {
  if (roles.includes("CUSTOMER")) return "/";
  if (roles.includes("CHECKER")) return "/staff/approvals";
  if (roles.includes("MAKER")) return "/staff/reversals";
  return "/transfers";
}
