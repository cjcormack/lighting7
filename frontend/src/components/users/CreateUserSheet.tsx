import { useState } from "react"
import { AlertTriangle, Loader2 } from "lucide-react"
import { toast } from "sonner"
import { Alert, AlertDescription } from "@/components/ui/alert"
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
import { useCreateUserMutation } from "@/store/users"
import { ROLE_DESCRIPTIONS, ROLE_LABELS } from "./RoleBadge"

const ROLES: UserRole[] = ["OPERATOR", "ADMIN"]

export function CreateUserSheet({
  open,
  onOpenChange,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const [username, setUsername] = useState("")
  const [displayName, setDisplayName] = useState("")
  // Operator is the default deliberately: an extra admin is a decision, not a shortcut.
  const [role, setRole] = useState<UserRole>("OPERATOR")
  const [password, setPassword] = useState("")
  const [confirm, setConfirm] = useState("")
  const [error, setError] = useState<string | null>(null)

  const [createUser, { isLoading }] = useCreateUserMutation()

  const tooShort = password !== "" && password.length < MIN_PASSWORD_LENGTH
  const mismatch = confirm !== "" && password !== confirm
  const canSubmit =
    username.trim() !== "" &&
    displayName.trim() !== "" &&
    password.length >= MIN_PASSWORD_LENGTH &&
    password === confirm &&
    !isLoading

  const reset = () => {
    setUsername("")
    setDisplayName("")
    setRole("OPERATOR")
    setPassword("")
    setConfirm("")
    setError(null)
  }

  const handleOpenChange = (next: boolean) => {
    if (!next) reset()
    onOpenChange(next)
  }

  const handleCreate = async () => {
    if (!canSubmit) return
    setError(null)
    try {
      await createUser({
        username: username.trim(),
        displayName: displayName.trim(),
        role,
        password,
      }).unwrap()
    } catch (err) {
      // A duplicate username (409) is the common one and belongs next to the field
      // that caused it, not in a toast that outlives the sheet.
      setError(formatError(err))
      return
    }
    toast.success(`${displayName.trim()} can now sign in`)
    reset()
    onOpenChange(false)
  }

  return (
    <Sheet open={open} onOpenChange={handleOpenChange}>
      <SheetContent className="flex flex-col sm:max-w-md">
        <SheetHeader>
          <SheetTitle>Add user</SheetTitle>
        </SheetHeader>
        <SheetBody>
          {error && (
            <Alert variant="destructive">
              <AlertTriangle className="size-4" />
              <AlertDescription>{error}</AlertDescription>
            </Alert>
          )}
          <p className="text-sm text-muted-foreground">
            The account lives on this machine only. It is never exported, cloned, or
            cloud-synced with your projects.
          </p>
          <div className="space-y-2">
            <Label htmlFor="new-user-username">Username</Label>
            <Input
              id="new-user-username"
              autoComplete="off"
              autoCapitalize="none"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
            />
            <p className="text-xs text-muted-foreground">
              Stored in lower case and used to sign in. It can&apos;t be changed later.
            </p>
          </div>
          <div className="space-y-2">
            <Label htmlFor="new-user-display-name">Display name</Label>
            <Input
              id="new-user-display-name"
              value={displayName}
              onChange={(e) => setDisplayName(e.target.value)}
            />
          </div>
          <div className="space-y-2">
            <Label htmlFor="new-user-role">Role</Label>
            <Select value={role} onValueChange={(next) => setRole(next as UserRole)}>
              <SelectTrigger id="new-user-role">
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
            <p className="text-xs text-muted-foreground">{ROLE_DESCRIPTIONS[role]}</p>
          </div>
          <div className="space-y-2">
            <Label htmlFor="new-user-password">Password</Label>
            <Input
              id="new-user-password"
              type="password"
              autoComplete="new-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
            <p className="text-xs text-muted-foreground">
              At least {MIN_PASSWORD_LENGTH} characters. They can change it from the user
              menu once they&apos;re in.
            </p>
            {tooShort && (
              <p className="text-sm text-destructive">
                Use at least {MIN_PASSWORD_LENGTH} characters.
              </p>
            )}
          </div>
          <div className="space-y-2">
            <Label htmlFor="new-user-confirm">Confirm password</Label>
            <Input
              id="new-user-confirm"
              type="password"
              autoComplete="new-password"
              value={confirm}
              onChange={(e) => setConfirm(e.target.value)}
            />
            {mismatch && <p className="text-sm text-destructive">Passwords don&apos;t match.</p>}
          </div>
        </SheetBody>
        <SheetFooter className="flex-row justify-end gap-2">
          <Button variant="outline" onClick={() => handleOpenChange(false)}>
            Cancel
          </Button>
          <Button onClick={handleCreate} disabled={!canSubmit}>
            {isLoading && <Loader2 className="size-4 animate-spin" />}
            Add user
          </Button>
        </SheetFooter>
      </SheetContent>
    </Sheet>
  )
}
