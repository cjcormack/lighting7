import { useState } from "react"
import { AlertTriangle, Loader2 } from "lucide-react"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { usePersistentState } from "@/hooks/usePersistentState"
import { formatError } from "@/lib/formatError"
import { useLoginMutation } from "@/store/auth"
import { AuthScreenLayout } from "./AuthScreenLayout"

export function LoginScreen() {
  // A desk is usually driven by the same operator night after night, so pre-filling
  // the last username saves a step. Never the password — that's the password
  // manager's job, not ours.
  const [lastUsername, setLastUsername] = usePersistentState("lastUsername", "")
  const [username, setUsername] = useState(lastUsername)
  const [password, setPassword] = useState("")
  const [error, setError] = useState<string | null>(null)
  const [login, { isLoading }] = useLoginMutation()

  const canSubmit = username.trim() !== "" && password !== "" && !isLoading

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault()
    if (!canSubmit) return
    setError(null)
    try {
      await login({ username: username.trim(), password }).unwrap()
    } catch (err) {
      // The backend answers the same "Incorrect username or password" for an unknown
      // user and a wrong password, on purpose — don't second-guess its wording.
      setError(formatError(err))
      setPassword("")
      return
    }
    setLastUsername(username.trim())
  }

  return (
    <AuthScreenLayout title="Sign in" description="This desk requires an account to control the rig.">
      <form onSubmit={handleSubmit} className="space-y-4">
        {error && (
          <Alert variant="destructive">
            <AlertTriangle className="size-4" />
            <AlertDescription>{error}</AlertDescription>
          </Alert>
        )}
        <div className="space-y-2">
          <Label htmlFor="login-username">Username</Label>
          <Input
            id="login-username"
            autoFocus
            autoComplete="username"
            autoCapitalize="none"
            spellCheck={false}
            value={username}
            onChange={(e) => setUsername(e.target.value)}
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="login-password">Password</Label>
          <Input
            id="login-password"
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </div>
        <Button type="submit" className="w-full" disabled={!canSubmit}>
          {isLoading && <Loader2 className="size-4 animate-spin" />}
          Sign in
        </Button>
      </form>
    </AuthScreenLayout>
  )
}
