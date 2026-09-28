import { useState } from "react"
import { useParams } from "react-router"
import { AlertTriangle, Check, Loader2 } from "lucide-react"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Card } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { formatError } from "@/lib/formatError"
import { MIN_PASSWORD_LENGTH } from "@/lib/passwordPolicy"
import { useRedeemResetTokenMutation, useResetTokenInfoQuery } from "@/store/passwordReset"

/** The 410 `code` values the backend uses for a link that is known but no longer usable. */
const DEAD_LINK_COPY: Record<string, string> = {
  USED: "This link has already been used. Ask for a new one if you still need to set a password.",
  EXPIRED: "This link has expired. Reset links last 15 minutes — ask for a new one.",
  CANCELLED: "This link was cancelled. Ask for a new one.",
}

const UNKNOWN_LINK_COPY =
  "This reset link isn't valid. Check it was typed correctly, or ask for a new one."

/**
 * Why the lookup failed, in the words the person holding the phone needs.
 *
 * The split that matters is server-verdict versus never-got-there. A `FetchBaseQueryError`
 * carries a **numeric** `status` only when the server actually answered; flaky venue Wi-Fi
 * gives a *string* status (`FETCH_ERROR`/`TIMEOUT_ERROR`) and no body. Reading only the 410
 * `code` would tell someone on a bad connection that their perfectly good link is invalid,
 * sending them back to the admin to mint a replacement — which cancels the one they hold.
 */
function lookupFailureMessage(err: unknown): string {
  const e = err as { status?: unknown; data?: { code?: unknown } }
  if (typeof e?.status !== "number") return formatError(err)
  const code = e.data?.code
  if (typeof code === "string" && code in DEAD_LINK_COPY) return DEAD_LINK_COPY[code]
  return UNKNOWN_LINK_COPY
}

/**
 * The phone-facing half of the QR password reset (multi-user-auth plan 3.6).
 *
 * Rendered as a sibling of `Layout` — no sidebar, no ShowBar, no project context — and in
 * front of both gates via `App.tsx`'s `publicPath` flag: whoever opens this has no session
 * and cannot get one, which is the entire point.
 */
export function ResetPasswordPage() {
  const { token } = useParams()
  const {
    data: info,
    error: lookupError,
    isLoading,
  } = useResetTokenInfoQuery({ token: token ?? "" }, { skip: !token })
  const [redeem, { isLoading: isSaving }] = useRedeemResetTokenMutation()

  const [password, setPassword] = useState("")
  const [confirm, setConfirm] = useState("")
  const [error, setError] = useState<string | null>(null)
  const [done, setDone] = useState(false)

  const tooShort = password !== "" && password.length < MIN_PASSWORD_LENGTH
  const mismatch = confirm !== "" && password !== confirm
  const canSubmit =
    password.length >= MIN_PASSWORD_LENGTH && password === confirm && !isSaving

  const handleSubmit = async () => {
    if (!canSubmit || !token) return
    setError(null)
    try {
      await redeem({ token, newPassword: password }).unwrap()
    } catch (err) {
      setError(formatError(err))
      return
    }
    setPassword("")
    setConfirm("")
    setDone(true)
  }

  return (
    // Mobile-first single column: this page is read on a phone held in one hand, with the
    // desk on the other side of the room.
    <div className="flex min-h-dvh items-center justify-center bg-muted/40 p-4">
      <Card className="w-full max-w-sm space-y-4 p-6">
        <h1 className="text-lg font-semibold">Set a new password</h1>

        {isLoading && (
          <div className="flex justify-center p-4">
            <Loader2 className="size-5 animate-spin" />
          </div>
        )}

        {lookupError != null && (
          <Alert variant="destructive">
            <AlertTriangle className="size-4" />
            <AlertDescription>{lookupFailureMessage(lookupError)}</AlertDescription>
          </Alert>
        )}

        {done && (
          <>
            <Alert>
              <Check className="size-4" />
              <AlertDescription>
                Your password is set. You can now sign in on the desk.
              </AlertDescription>
            </Alert>
            <p className="text-sm text-muted-foreground">
              You were signed out on every device, so sign in again anywhere you were using
              lighting7.
            </p>
          </>
        )}

        {info && !done && (
          <>
            <p className="text-sm text-muted-foreground">
              For <span className="font-medium text-foreground">{info.displayName}</span> (
              {info.username}) on this lighting desk. Nobody else sees what you type here.
            </p>
            {error && (
              <Alert variant="destructive">
                <AlertTriangle className="size-4" />
                <AlertDescription>{error}</AlertDescription>
              </Alert>
            )}
            <div className="space-y-2">
              <Label htmlFor="reset-password">New password</Label>
              <Input
                id="reset-password"
                type="password"
                autoComplete="new-password"
                autoFocus
                value={password}
                onChange={(e) => setPassword(e.target.value)}
              />
              <p className="text-xs text-muted-foreground">
                At least {MIN_PASSWORD_LENGTH} characters.
              </p>
              {tooShort && (
                <p className="text-sm text-destructive">
                  Use at least {MIN_PASSWORD_LENGTH} characters.
                </p>
              )}
            </div>
            <div className="space-y-2">
              <Label htmlFor="reset-confirm">Confirm password</Label>
              <Input
                id="reset-confirm"
                type="password"
                autoComplete="new-password"
                value={confirm}
                onChange={(e) => setConfirm(e.target.value)}
              />
              {mismatch && <p className="text-sm text-destructive">Passwords don&apos;t match.</p>}
            </div>
            <Button className="w-full" onClick={() => void handleSubmit()} disabled={!canSubmit}>
              {isSaving && <Loader2 className="size-4 animate-spin" />}
              Set password
            </Button>
          </>
        )}
      </Card>
    </div>
  )
}
