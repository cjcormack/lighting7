import { useState } from "react"
import { Loader2, ShieldAlert, UserPlus } from "lucide-react"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card } from "@/components/ui/card"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { useAuthStatusQuery } from "@/store/auth"
import { useUsersQuery, type DeskUser } from "@/store/users"
import { CreateUserSheet } from "./CreateUserSheet"
import { RoleBadge } from "./RoleBadge"
import { UserDetailSheet } from "./UserDetailSheet"

/**
 * Desk accounts, as the Users tab of Install Settings.
 *
 * The role check here is a courtesy, not the enforcement: `/api/rest/users` is admin-only
 * in the backend's auth gate, so an operator who types the URL gets 403 regardless. What
 * the check buys is not firing that 403 at all — hence the query is skipped rather than
 * merely hidden.
 */
export function UsersTab() {
  const { data: authStatus } = useAuthStatusQuery()
  const signedInUuid = authStatus?.user?.uuid
  const role = authStatus?.user?.role
  // An absent role means "still resolving", not "anonymous": bootstrap-open desks never
  // reach this tab, because AuthGate puts the setup screen in front of the whole app.
  const isAdmin = role === "ADMIN"

  // `refetchOnFocus` is the repair path for the one way the WS bridge in store/users.ts can miss
  // an edit: `tryEmit` drops a frame if a collector's buffer is full, and there is no replay, so
  // a dropped frame leaves this list stale until the socket reconnects. Coming back to a settings
  // tab is the natural gesture for "show me the truth". `useAuthStatusQuery` does the same.
  const { data: users, isLoading } = useUsersQuery(undefined, {
    skip: !isAdmin,
    refetchOnFocus: true,
  })
  const [createOpen, setCreateOpen] = useState(false)
  const [selected, setSelected] = useState<DeskUser | null>(null)

  if (role === undefined) {
    return (
      <div className="flex justify-center p-4">
        <Loader2 className="size-5 animate-spin" />
      </div>
    )
  }

  if (!isAdmin) {
    return (
      <Card className="max-w-2xl p-4">
        <div className="flex items-start gap-3">
          <ShieldAlert className="size-5 shrink-0 text-muted-foreground" />
          <div className="space-y-1">
            <h2 className="font-semibold">Managing users requires an administrator account</h2>
            <p className="text-sm text-muted-foreground">
              Ask an administrator on this desk to add or change accounts. If nobody can sign
              in as one, see the break-glass recovery in the desk accounts documentation.
            </p>
          </div>
        </div>
      </Card>
    )
  }

  // The selected row is re-read from the list so an edit is reflected without the sheet
  // holding a stale copy of the account it is editing.
  const selectedUser = selected == null ? null : users?.find((u) => u.id === selected.id) ?? selected

  return (
    <div className="max-w-3xl space-y-4">
      <Card className="p-4 space-y-4">
        <div className="flex items-start justify-between gap-4">
          <p className="text-sm text-muted-foreground">
            Accounts that can sign in to this desk. They belong to this machine — never
            exported, cloned, or cloud-synced with your projects.
          </p>
          <Button onClick={() => setCreateOpen(true)}>
            <UserPlus className="size-4" />
            Add user
          </Button>
        </div>

        {isLoading && !users ? (
          <div className="flex justify-center p-4">
            <Loader2 className="size-5 animate-spin" />
          </div>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Name</TableHead>
                <TableHead>Username</TableHead>
                <TableHead>Role</TableHead>
                <TableHead>Last signed in</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {(users ?? []).map((user) => (
                <TableRow
                  key={user.id}
                  className="cursor-pointer"
                  onClick={() => setSelected(user)}
                >
                  <TableCell>
                    <div className="flex items-center gap-2">
                      <span className={user.disabled ? "text-muted-foreground" : undefined}>
                        {user.displayName}
                      </span>
                      {user.uuid === signedInUuid && <Badge variant="secondary">You</Badge>}
                      {user.disabled && <Badge variant="outline">Disabled</Badge>}
                    </div>
                  </TableCell>
                  <TableCell className="font-mono text-xs">{user.username}</TableCell>
                  <TableCell>
                    <RoleBadge role={user.role} />
                  </TableCell>
                  <TableCell className="text-sm text-muted-foreground">
                    {user.lastLoginAt
                      ? new Date(user.lastLoginAt).toLocaleString()
                      : "Never"}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </Card>

      <CreateUserSheet open={createOpen} onOpenChange={setCreateOpen} />
      <UserDetailSheet
        user={selectedUser}
        open={selected !== null}
        onOpenChange={(next) => {
          if (!next) setSelected(null)
        }}
        isSelf={selectedUser?.uuid === signedInUuid}
      />
    </div>
  )
}
