import { Badge } from "@/components/ui/badge"
import type { UserRole } from "@/store/auth"

export const ROLE_LABELS: Record<UserRole, string> = {
  ADMIN: "Admin",
  OPERATOR: "Operator",
}

export const ROLE_DESCRIPTIONS: Record<UserRole, string> = {
  ADMIN: "Everything an operator can do, plus user accounts and install settings.",
  OPERATOR: "All lighting control and all project content. No account administration.",
}

/** Role chip. ADMIN gets the emphasised variant because it's the role that carries risk. */
export function RoleBadge({ role }: { role: UserRole }) {
  return <Badge variant={role === "ADMIN" ? "default" : "secondary"}>{ROLE_LABELS[role]}</Badge>
}
