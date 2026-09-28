import { useState } from "react"
import { AlertTriangle, Loader2 } from "lucide-react"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { formatError } from "@/lib/formatError"
import { useSetupMutation } from "@/store/auth"
import { MIN_PASSWORD_LENGTH } from "@/lib/passwordPolicy"
import { AuthScreenLayout } from "./AuthScreenLayout"

export function SetupScreen() {
  const [username, setUsername] = useState("")
  const [displayName, setDisplayName] = useState("")
  const [password, setPassword] = useState("")
  const [confirm, setConfirm] = useState("")
  const [error, setError] = useState<string | null>(null)
  const [setup, { isLoading }] = useSetupMutation()

  const tooShort = password !== "" && password.length < MIN_PASSWORD_LENGTH
  const mismatch = confirm !== "" && password !== confirm
  const canSubmit =
    username.trim() !== "" &&
    displayName.trim() !== "" &&
    password.length >= MIN_PASSWORD_LENGTH &&
    password === confirm &&
    !isLoading

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault()
    if (!canSubmit) return
    setError(null)
    try {
      await setup({
        username: username.trim(),
        displayName: displayName.trim(),
        password,
      }).unwrap()
    } catch (err) {
      setError(formatError(err))
    }
  }

  return (
    <AuthScreenLayout
      title="Set up this desk"
      description="Create the first administrator account. It is stored on this machine only — accounts are never exported, cloned or cloud-synced, so every desk has its own."
    >
      <form onSubmit={handleSubmit} className="space-y-4">
        {error && (
          <Alert variant="destructive">
            <AlertTriangle className="size-4" />
            <AlertDescription>{error}</AlertDescription>
          </Alert>
        )}
        <div className="space-y-2">
          <Label htmlFor="setup-username">Username</Label>
          <Input
            id="setup-username"
            autoFocus
            autoComplete="username"
            autoCapitalize="none"
            spellCheck={false}
            value={username}
            onChange={(e) => setUsername(e.target.value)}
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="setup-display-name">Display name</Label>
          <Input
            id="setup-display-name"
            autoComplete="name"
            value={displayName}
            onChange={(e) => setDisplayName(e.target.value)}
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="setup-password">Password</Label>
          <Input
            id="setup-password"
            type="password"
            autoComplete="new-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
          {tooShort && (
            <p className="text-sm text-destructive">
              Use at least {MIN_PASSWORD_LENGTH} characters.
            </p>
          )}
        </div>
        <div className="space-y-2">
          <Label htmlFor="setup-confirm">Confirm password</Label>
          <Input
            id="setup-confirm"
            type="password"
            autoComplete="new-password"
            value={confirm}
            onChange={(e) => setConfirm(e.target.value)}
          />
          {mismatch && <p className="text-sm text-destructive">Passwords don&apos;t match.</p>}
        </div>
        <Button type="submit" className="w-full" disabled={!canSubmit}>
          {isLoading && <Loader2 className="size-4 animate-spin" />}
          Create account
        </Button>
      </form>
    </AuthScreenLayout>
  )
}
