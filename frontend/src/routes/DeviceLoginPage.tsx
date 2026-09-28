import { useState } from "react"
import { useParams } from "react-router"
import { AlertTriangle, Loader2, Smartphone } from "lucide-react"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Card } from "@/components/ui/card"
import { formatError } from "@/lib/formatError"
import { useDeviceLoginInfoQuery, useRedeemDeviceLoginMutation } from "@/store/deviceLogin"

const DEAD_CODE_COPY: Record<string, string> = {
  USED: "This code has already been used. Show a new one on the desk.",
  EXPIRED: "This code expired — they only last a couple of minutes. Show a new one on the desk.",
  CANCELLED: "This code was cancelled, most likely by the sheet on the desk being closed.",
}

const UNKNOWN_CODE_COPY =
  "This sign-in code isn't valid. Check the address, or show a new code on the desk."

/**
 * Why the lookup failed, in the words the person holding the phone needs.
 *
 * Same split as `ResetPasswordPage`: a `FetchBaseQueryError` carries a **numeric** `status`
 * only when the server actually answered, and flaky venue Wi-Fi gives a *string* status with
 * no body. Reading the code unconditionally would tell someone on a bad connection that a
 * perfectly good code is invalid.
 */
function lookupFailureMessage(err: unknown): string {
  const e = err as { status?: unknown; data?: { code?: unknown } }
  if (typeof e?.status !== "number") return formatError(err)
  const code = e.data?.code
  if (typeof code === "string" && code in DEAD_CODE_COPY) return DEAD_CODE_COPY[code]
  return UNKNOWN_CODE_COPY
}

/**
 * The phone-facing half of the device-login QR (`FU-AUTH-LOGIN-QR`).
 *
 * Rendered as a sibling of `Layout` — no sidebar, no ShowBar, no project context — and in
 * front of both gates via `App.tsx`'s `publicPath` flag, because whoever opens this has no
 * session yet and getting one is the entire point.
 *
 * **Signing in needs an explicit tap.** Redeeming on load would be one line shorter and
 * wrong: a link preview, a QR scanner that prefetches, or React's StrictMode double-render
 * would each burn a two-minute single-use code before anyone saw the screen — and the person
 * holding the phone should get to see whose account they are about to be holding.
 */
export function DeviceLoginPage() {
  const { token } = useParams()
  const {
    data: info,
    error: lookupError,
    isLoading,
  } = useDeviceLoginInfoQuery({ token: token ?? "" }, { skip: !token })
  const [redeem, { isLoading: isSigningIn }] = useRedeemDeviceLoginMutation()
  const [error, setError] = useState<string | null>(null)

  const handleSignIn = async () => {
    if (!token) return
    setError(null)
    try {
      await redeem({ token }).unwrap()
    } catch (err) {
      setError(formatError(err))
      return
    }
    // Only now drop the code from the address bar, so Back can't return to a URL that still
    // carries it. Deliberately *not* on mount: this page has no retry button, so reloading is
    // the natural recovery from the flaky-Wi-Fi case `lookupFailureMessage` exists for — and a
    // token stripped before the attempt succeeded would leave `/device`, which matches no route
    // at all (there is no catch-all in App.tsx), stranding the phone on a router error page
    // with nothing to retry. The code is single-use and short-lived, so leaving it in the bar
    // until it is spent costs nothing.
    window.history.replaceState(null, "", "/")
    // A full document load, not a router navigation: `publicPath` in App.tsx is read once at
    // module scope, so navigating in-place would render the whole app with both gates still
    // bypassed. See the comment beside that flag.
    window.location.assign("/")
  }

  return (
    // Mobile-first single column: read on a phone held in one hand, desk across the room.
    <div className="flex min-h-dvh items-center justify-center bg-muted/40 p-4">
      <Card className="w-full max-w-sm space-y-4 p-6">
        <h1 className="text-lg font-semibold">Sign in to lighting7</h1>

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

        {info && (
          <>
            <p className="text-sm text-muted-foreground">
              This will sign this device in as{" "}
              <span className="font-medium text-foreground">{info.displayName}</span> (
              {info.username}) on the lighting desk, and keep it signed in for 30 days.
            </p>
            {error && (
              <Alert variant="destructive">
                <AlertTriangle className="size-4" />
                <AlertDescription>{error}</AlertDescription>
              </Alert>
            )}
            <Button
              className="w-full"
              onClick={() => void handleSignIn()}
              disabled={isSigningIn}
            >
              {isSigningIn ? (
                <Loader2 className="size-4 animate-spin" />
              ) : (
                <Smartphone className="size-4" />
              )}
              Sign in as {info.displayName}
            </Button>
            <p className="text-xs text-muted-foreground">
              Not you? Close this page — the code stays unused and expires on its own.
            </p>
          </>
        )}
      </Card>
    </div>
  )
}
