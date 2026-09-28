import { useEffect, useState } from "react"
import { AlertTriangle, KeyRound, Loader2, QrCode } from "lucide-react"
import { toast } from "sonner"
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import {
  Sheet,
  SheetBody,
  SheetContent,
  SheetFooter,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet"
import { formatError } from "@/lib/formatError"
import { MIN_PASSWORD_LENGTH } from "@/lib/passwordPolicy"
import type { UserRole } from "@/store/auth"
import {
  useDeleteUserMutation,
  useSetUserPasswordMutation,
  useUpdateUserMutation,
  useUserQuery,
  type DeskUser,
} from "@/store/users"
import { ROLE_DESCRIPTIONS, ROLE_LABELS } from "./RoleBadge"
import { ResetQrSheet } from "./ResetQrSheet"
import { ResetTokenHistory } from "./ResetTokenHistory"

const ROLES: UserRole[] = ["OPERATOR", "ADMIN"]

export function UserDetailSheet({
  user: listRow,
  open,
  onOpenChange,
  isSelf,
}: {
  /** The row the admin clicked. Null while nothing is selected. */
  user: DeskUser | null
  open: boolean
  onOpenChange: (open: boolean) => void
  /**
   * Whether this is the signed-in admin's own account. Disable, delete, role changes and
   * password resets are all refused for it server-side (409 `SELF_TARGET`); this flag only
   * keeps the UI from offering actions that can't succeed.
   */
  isSelf: boolean
}) {
  const userId = listRow?.id

  // Re-read the row while the sheet is open. The list is fresh enough to render from, but
  // `lastLoginAt` moves without any local mutation, and the fetched copy is what makes
  // the `user` endpoint's cache entry (and so this sheet) authoritative after an edit.
  const { data: fetched, error: fetchError } = useUserQuery(
    { userId: userId ?? 0 },
    { skip: !open || userId == null },
  )

  // Another admin deleted this account while the sheet was open. Falling back to `listRow` here
  // would keep rendering it with live Save / Delete / Reset controls that can now only 404 —
  // and since the user list self-heals over the socket (store/users.ts), the row behind this
  // sheet is already gone, so the list and the sheet would visibly disagree.
  const deleted = fetchError != null && "status" in fetchError && fetchError.status === 404
  const user = deleted ? undefined : (fetched ?? listRow)

  const [displayName, setDisplayName] = useState("")
  const [role, setRole] = useState<UserRole>("OPERATOR")
  const [newPassword, setNewPassword] = useState("")
  const [error, setError] = useState<string | null>(null)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [resetOpen, setResetOpen] = useState(false)

  const [updateUser, { isLoading: isSaving }] = useUpdateUserMutation()
  const [deleteUser, { isLoading: isDeleting }] = useDeleteUserMutation()
  const [setUserPassword, { isLoading: isSettingPassword }] = useSetUserPasswordMutation()

  // Seed the form from the identity being edited, not from every cache refresh: depending
  // on `user` itself would clobber a half-typed display name the moment the refetch of
  // that same row lands. `user.id` and `open` are the only inputs that mean "start over",
  // so the narrow deps are complete — the fields read inside are all off the same row.
  useEffect(() => {
    if (!user) return
    setDisplayName(user.displayName)
    setRole(user.role)
    setNewPassword("")
    setError(null)
    // Hardening rather than a live fix: no path today closes this sheet while the QR sheet
    // is up (Radix won't dismiss the outer layer first), but if one appeared, a stale
    // `true` here would silently mint a reset link for whoever is opened next.
    setResetOpen(false)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [user?.id, open])

  if (deleted) {
    return (
      <Sheet open={open} onOpenChange={onOpenChange}>
        <SheetContent className="flex flex-col sm:max-w-md">
          <SheetHeader>
            <SheetTitle>Account deleted</SheetTitle>
          </SheetHeader>
          <SheetBody>
            <Alert>
              <AlertTriangle className="size-4" />
              <AlertDescription>
                This account no longer exists — someone deleted it from another device.
              </AlertDescription>
            </Alert>
          </SheetBody>
          <SheetFooter className="flex-row justify-end gap-2">
            <Button variant="outline" onClick={() => onOpenChange(false)}>
              Close
            </Button>
          </SheetFooter>
        </SheetContent>
      </Sheet>
    )
  }

  if (!user) return null

  const trimmedName = displayName.trim()
  const dirty = trimmedName !== user.displayName || role !== user.role
  const canSave = dirty && trimmedName !== "" && !isSaving

  const handleSave = async () => {
    if (!canSave) return
    setError(null)
    try {
      await updateUser({
        userId: user.id,
        // Only what changed: the backend treats an absent field as "leave alone", and
        // sending the unchanged role would hit the last-admin guard for no reason.
        ...(trimmedName !== user.displayName ? { displayName: trimmedName } : {}),
        ...(role !== user.role ? { role } : {}),
      }).unwrap()
    } catch (err) {
      setError(formatError(err))
      return
    }
    toast.success("User updated")
  }

  const handleToggleDisabled = async () => {
    setError(null)
    try {
      await updateUser({ userId: user.id, disabled: !user.disabled }).unwrap()
    } catch (err) {
      setError(formatError(err))
      return
    }
    toast.success(
      user.disabled
        ? `${user.displayName} can sign in again`
        : `${user.displayName} is disabled and has been signed out`,
    )
  }

  const handleSetPassword = async () => {
    if (newPassword.length < MIN_PASSWORD_LENGTH) return
    setError(null)
    try {
      await setUserPassword({ userId: user.id, newPassword }).unwrap()
    } catch (err) {
      setError(formatError(err))
      return
    }
    setNewPassword("")
    toast.success("Password set", {
      description: `${user.displayName} has been signed out everywhere.`,
    })
  }

  const handleDelete = async () => {
    setError(null)
    try {
      await deleteUser({ userId: user.id }).unwrap()
    } catch (err) {
      setConfirmDelete(false)
      setError(formatError(err))
      return
    }
    setConfirmDelete(false)
    toast.success(`${user.displayName} deleted`)
    onOpenChange(false)
  }

  return (
    <>
      <Sheet open={open} onOpenChange={onOpenChange}>
        <SheetContent className="flex flex-col sm:max-w-md">
          <SheetHeader>
            <SheetTitle className="flex items-center gap-2">
              <span className="truncate">{user.displayName}</span>
              {user.disabled && <Badge variant="outline">Disabled</Badge>}
            </SheetTitle>
          </SheetHeader>
          <SheetBody>
            {error && (
              <Alert variant="destructive">
                <AlertTriangle className="size-4" />
                <AlertDescription>{error}</AlertDescription>
              </Alert>
            )}

            <div className="space-y-1">
              <Label className="text-muted-foreground">Username</Label>
              <div className="font-mono text-sm">{user.username}</div>
            </div>
            <div className="space-y-1">
              <Label className="text-muted-foreground">Last signed in</Label>
              <div className="text-sm">
                {user.lastLoginAt ? new Date(user.lastLoginAt).toLocaleString() : "Never"}
              </div>
            </div>

            <div className="space-y-2">
              <Label htmlFor="user-display-name">Display name</Label>
              <Input
                id="user-display-name"
                value={displayName}
                onChange={(e) => setDisplayName(e.target.value)}
              />
            </div>
            <div className="space-y-2">
              <Label htmlFor="user-role">Role</Label>
              {/* Not editable for your own account: a self-demotion costs you your own
                  administration surfaces mid-session and needs the *other* admin to undo it.
                  The backend refuses it outright (409 SELF_TARGET) — this only stops the UI
                  offering an action that can't succeed. */}
              <Select
                value={role}
                onValueChange={(next) => setRole(next as UserRole)}
                disabled={isSelf}
              >
                <SelectTrigger id="user-role">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {ROLES.map((option) => (
                    <SelectItem key={option} value={option}>
                      {ROLE_LABELS[option]}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              <p className="text-xs text-muted-foreground">
                {isSelf
                  ? "You can't change your own role — another administrator has to do it."
                  : ROLE_DESCRIPTIONS[role]}
              </p>
            </div>

            <div className="space-y-2 border-t pt-4">
              <h3 className="text-sm font-medium">Password</h3>
              {/* Neither route to a password is offered for your own account, and hiding them
                  beats disabling them — three greyed-out controls with a paragraph pointing at
                  them reads as breakage.

                  The QR would put a link that re-passwords an admin account on the desk's own
                  screen, where anyone passing can photograph it. Setting a password directly
                  revokes every session of the target, which on yourself signs you out
                  mid-edit. The user menu's Profile sheet does neither. Both halves are
                  enforced server-side (409 SELF_TARGET on the mint; the user menu's own
                  endpoint for the change) — this is presentation, not the guard. */}
              {isSelf ? (
                <p className="text-xs text-muted-foreground">
                  Change your own password from <strong>Profile…</strong> in the user menu — it
                  keeps you signed in on this desk.
                </p>
              ) : (
                <>
                  <Button
                    variant="outline"
                    className="w-full"
                    onClick={() => setResetOpen(true)}
                  >
                    <QrCode className="size-4" />
                    Reset with a QR code…
                  </Button>
                  <p className="text-xs text-muted-foreground">
                    Sends them to a page on their own phone, where they choose the password.
                    Use the field below instead when they&apos;re standing next to you.
                  </p>
                  <div className="flex items-start gap-2 pt-1">
                    <Input
                      aria-label="New password"
                      type="password"
                      autoComplete="new-password"
                      placeholder="Set a password directly"
                      value={newPassword}
                      onChange={(e) => setNewPassword(e.target.value)}
                    />
                    <Button
                      variant="outline"
                      disabled={newPassword.length < MIN_PASSWORD_LENGTH || isSettingPassword}
                      onClick={() => void handleSetPassword()}
                    >
                      {isSettingPassword ? (
                        <Loader2 className="size-4 animate-spin" />
                      ) : (
                        <KeyRound className="size-4" />
                      )}
                      Set
                    </Button>
                  </div>
                  <ResetTokenHistory userId={user.id} />
                </>
              )}
            </div>

            <div className="space-y-2 border-t pt-4">
              <h3 className="text-sm font-medium">Access</h3>
              <Button
                variant="outline"
                className="w-full"
                disabled={isSelf || isSaving}
                onClick={() => void handleToggleDisabled()}
              >
                {user.disabled ? "Enable account" : "Disable account"}
              </Button>
              <p className="text-xs text-muted-foreground">
                {isSelf
                  ? "You can't disable or delete the account you're signed in as."
                  : "A disabled account keeps its history but can't sign in, and is signed out immediately."}
              </p>
            </div>
          </SheetBody>
          <SheetFooter className="flex-row justify-between">
            <Button
              variant="destructive"
              disabled={isSelf || isDeleting}
              onClick={() => setConfirmDelete(true)}
            >
              Delete
            </Button>
            <div className="flex gap-2">
              <Button variant="outline" onClick={() => onOpenChange(false)}>
                Cancel
              </Button>
              <Button onClick={() => void handleSave()} disabled={!canSave}>
                {isSaving && <Loader2 className="size-4 animate-spin" />}
                Save
              </Button>
            </div>
          </SheetFooter>
        </SheetContent>
      </Sheet>

      {/* Deleting an account is the one action here with no undo — every other control
          either reverses (disable/enable) or only affects a password. */}
      <AlertDialog
        open={confirmDelete}
        onOpenChange={(next) => {
          if (!next) setConfirmDelete(false)
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Delete {user.displayName}?</AlertDialogTitle>
            <AlertDialogDescription>
              {user.username} will be signed out everywhere and won&apos;t be able to sign
              in again. Their show content isn&apos;t touched. This can&apos;t be undone —
              disable the account instead if you might want it back.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction onClick={() => void handleDelete()}>Delete</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      <ResetQrSheet user={user} open={resetOpen} onOpenChange={setResetOpen} />
    </>
  )
}
