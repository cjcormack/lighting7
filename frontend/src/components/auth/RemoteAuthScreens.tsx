import { useEffect, useState } from "react"
import { AlertTriangle, Loader2 } from "lucide-react"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { formatError } from "@/lib/formatError"
import { reportUnlinked } from "@/lib/remoteMode"
import { useProveCloudLinkMutation } from "@/store/cloudLinks"
import { AuthScreenLayout } from "./AuthScreenLayout"

/**
 * Remote mode's stand-ins for the sign-in screens (cloud-p1 plan D9). A remote session is the
 * cloud's to start and end, so there is no sign-in form here. An account not linked to a user on
 * this desk is told so, and the shell with `auth.unlinked`, and may link itself by signing in once
 * with a desk username and password (D12): the session then carries on as that user.
 */
export function RemoteUnlinkedScreen({ email }: { email?: string | null }) {
  const [username, setUsername] = useState("")
  const [password, setPassword] = useState("")
  const [error, setError] = useState<string | null>(null)
  const [prove, { isLoading }] = useProveCloudLinkMutation()

  useEffect(() => {
    reportUnlinked()
  }, [])

  const canSubmit = username.trim() !== "" && password !== "" && !isLoading

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault()
    if (!canSubmit) return
    setError(null)
    try {
      await prove({ username: username.trim(), password }).unwrap()
    } catch (err) {
      setError(formatError(err))
      setPassword("")
    }
  }

  return (
    <AuthScreenLayout title="Not linked to this desk">
      <p className="text-sm text-muted-foreground">
        {email ? <>Your Lighting7 Cloud account, {email}, is</> : <>Your Lighting7 Cloud account is</>} not
        linked to a user on this desk. An admin can invite it from Users on the desk, or you can link it
        now by signing in once with your desk username and password.
      </p>
      <form onSubmit={(event) => void handleSubmit(event)} className="space-y-4">
        {error && (
          <Alert variant="destructive">
            <AlertTriangle className="size-4" />
            <AlertDescription>{error}</AlertDescription>
          </Alert>
        )}
        <div className="space-y-2">
          <Label htmlFor="prove-username">Desk username</Label>
          <Input
            id="prove-username"
            autoComplete="username"
            autoCapitalize="none"
            spellCheck={false}
            value={username}
            onChange={(e) => setUsername(e.target.value)}
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="prove-password">Password</Label>
          <Input
            id="prove-password"
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </div>
        <Button type="submit" className="w-full" disabled={!canSubmit}>
          {isLoading && <Loader2 className="size-4 animate-spin" />}
          Link and sign in
        </Button>
      </form>
    </AuthScreenLayout>
  )
}

/** A remote session the desk no longer accepts: ended, revoked, or its token lapsed. */
export function RemoteSessionEndedScreen() {
  return (
    <AuthScreenLayout title="Remote session ended">
      <p className="text-sm text-muted-foreground">
        This remote session has ended. Open the desk again from Lighting7 Cloud.
      </p>
    </AuthScreenLayout>
  )
}
