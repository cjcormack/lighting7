import { useEffect, useRef, useState } from "react"
import QRCode from "react-qr-code"
import { AlertTriangle, Check, Copy, Loader2, RefreshCw } from "lucide-react"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { useMountedRef } from "@/hooks/useMountedRef"
import { formatCountdown } from "@/lib/formatCountdown"
import { formatError } from "@/lib/formatError"
import {
  useCancelDeviceLoginMutation,
  useCreateDeviceLoginMutation,
  useDeviceLoginStatusQuery,
  useRevokeOtherSessionsMutation,
  type DeviceLoginResponse,
} from "@/store/auth"

/** Matches the reset sheet's cadence: someone is watching a phone across the room. */
const POLL_INTERVAL_MS = 2000

/**
 * Sign your own phone or tablet in by scanning a QR off the desk. This is the whole body of
 * `ProfileSheet`'s **Sign-in tab** — not a sheet of its own, and not something that appears
 * merely because Profile was opened, which lands on the Profile tab instead.
 *
 * Mounting mints. Radix mounts a tab's content only while it is the active tab, so navigating
 * here is the request for a code and navigating away is the cancellation; there is deliberately
 * no button for either.
 *
 * The QR never carries a session token — a photographed QR would be a photographed 30-day
 * cookie. It carries a single-use code the phone exchanges for a real session, and the
 * exchange burns it.
 *
 * **The code is cancelled the moment it leaves the screen, and that is the opposite of what
 * `ResetQrSheet` does** — deliberately, because the risk profiles are opposite. A reset link
 * can only ever set a password, so letting it outlive its sheet costs little and buys a lot of
 * practicality; this one is a way *into* the account, so the two-minute TTL is the backstop and
 * cancel-on-leave is the actual control. Resist any temptation to factor the two together — and
 * don't turn this back into a sheet, either: the parent's close is one of the three paths below.
 *
 * Those paths are one mechanism, the teardown effect: `active` going false (the Profile sheet
 * closing), and unmount (leaving the tab, or the whole authenticated tree going away when
 * `AuthGate` swaps in the login screen). Both are needed. Unmount alone would defer the cancel
 * by Radix's ~300 ms exit animation; `active` alone would miss the hide and the unmount. Note
 * the sign-out case is belt-and-braces on the client's part — that `DELETE` carries a dead
 * cookie and is answered 401 — because logout and revoke-all already retire outstanding codes
 * server-side.
 *
 * There is no confirmation step (same-LAN plus a physically present crew was judged enough),
 * so the success state names the device that took it and offers to sign it straight back
 * out — detect-and-undo where a confirmation code would have been prevent.
 */
export function DeviceLoginSection({
  active,
  onDone,
}: {
  /** False while the parent sheet is closed: cancels the outstanding code without waiting for unmount. */
  active: boolean
  /** Leave the tab. Offered only once a phone has taken the code — otherwise the tab bar is the way out. */
  onDone: () => void
}) {
  const [code, setCode] = useState<DeviceLoginResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [copied, setCopied] = useState(false)
  const [now, setNow] = useState(() => Date.now())
  /** Latched when the code reaches a terminal status; stops the poll. */
  const [settled, setSettled] = useState(false)
  const [revoked, setRevoked] = useState(false)

  const [createDeviceLogin, { isLoading: isMinting }] = useCreateDeviceLoginMutation()
  const [cancelDeviceLogin] = useCancelDeviceLoginMutation()
  const [revokeOtherSessions, { isLoading: isRevoking }] = useRevokeOtherSessionsMutation()

  // Held in a ref as well as state so the close cleanup can cancel the live code without
  // re-running — and thus cancelling — every time the code changes.
  const liveCode = useRef<string | null>(null)

  // Together these answer "is this section still on screen?" after an await. Both are needed,
  // and they are deliberately *separate* refs rather than one flag with two writers.
  //
  // `activeRef` tracks the prop, assigned during render so it is already correct for a mint
  // that resolves before effects flush. It covers the parent sheet closing.
  //
  // `mountedRef` covers unmount — the whole tree going away — which the render assignment
  // cannot see, because a ref keeps its last value through unmount and would read `true`
  // forever afterwards. Its StrictMode trap (a teardown-only flag reads `false` for the life
  // of a section that is genuinely on screen, so every code cancels itself the moment it
  // arrives) is why it is a shared hook; the reasoning lives there now.
  const activeRef = useRef(active)
  activeRef.current = active
  /** One mint per mount. Declared here because `mint` below closes over it. */
  const mintedRef = useRef(false)
  const mountedRef = useMountedRef()
  const onScreen = () => activeRef.current && mountedRef.current

  const { data: status } = useDeviceLoginStatusQuery(
    { id: code?.id ?? "" },
    {
      skip: !active || code == null,
      pollingInterval: settled ? 0 : POLL_INTERVAL_MS,
    },
  )
  const codeStatus = status?.status ?? "PENDING"

  useEffect(() => {
    if (codeStatus === "PENDING") return
    liveCode.current = null
    setSettled(true)
  }, [codeStatus])

  const mint = async () => {
    setError(null)
    setCopied(false)
    setRevoked(false)
    try {
      const minted = await createDeviceLogin().unwrap()
      // Gone while the mint was in flight: the cleanup below only cancelled what
      // `liveCode` pointed at *then*, which was nothing. Cancel it here instead — nobody has
      // seen this QR, so leaving it exchangeable would leave a way in that nobody knows about.
      if (!onScreen()) {
        void cancelDeviceLogin({ id: minted.id })
        return
      }
      setCode(minted)
      setSettled(false)
      setNow(Date.now())
      liveCode.current = minted.id
    } catch (err) {
      setError(formatError(err))
      // Keeps the flag meaning "a mint is in flight or a code is on screen" rather than "this
      // component once tried". Nothing was minted on this path, so nothing can be doubled up
      // on. The user-visible recovery is the Try again button, which calls `mint()` directly
      // and so doesn't depend on this — but leaving the flag set would make a re-run of the
      // effect silently do nothing, which is a trap for whoever next changes its deps.
      mintedRef.current = false
    }
  }

  // One code per mount: the parent only mounts this once somebody has asked for a code, so
  // getting here *is* the intent and there's no second button to press. Keyed on `active`
  // alone — `mint` closes over nothing that changes, and depending on its per-render identity
  // would mint a fresh code on every re-render, cancelling the one on screen.
  //
  // `mintedRef` makes that "one" literal, and it is load-bearing rather than tidy. Minting is a
  // server-side *create*, so it is not idempotent, and React StrictMode invokes this effect,
  // tears it down and invokes it again in development — two POSTs. That is worse than
  // wasteful, because the backend enforces "newest mint wins" (`AuthService.createDeviceLogin`
  // cancels the caller's outstanding codes), while the two promises can resolve in either
  // order: resolve them backwards and the section displays a QR the server has already
  // retired. The client cannot repair that afterwards — it has no way to know which mint the
  // server saw last — so the only correct fix is not to make the second call. A real
  // hide-then-show unmounts this component, so it gets a fresh ref and does mint again.
  useEffect(() => {
    if (!active || mintedRef.current) return
    mintedRef.current = true
    void mint()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [active])

  // The control that matters: a code must not outlive the screen showing it.
  //
  // Written as a teardown rather than as `if (active) return` in the body, so it fires on
  // **unmount** as well as on `active` going false — which is what makes hiding the section
  // and the whole tree disappearing behind the login screen the same mechanism as the parent
  // sheet closing. See the doc comment above for why both triggers are needed.
  useEffect(() => {
    if (!active) return
    return () => {
      const outstanding = liveCode.current
      liveCode.current = null
      if (outstanding) void cancelDeviceLogin({ id: outstanding })
    }
  }, [active, cancelDeviceLogin])

  // Display state only. Nearly dead now that the parent unmounts this on hide — but it is what
  // stops a cancelled code staying on screen through Radix's ~300 ms sheet exit animation.
  useEffect(() => {
    if (active) return
    setCode(null)
    setError(null)
    setCopied(false)
    setSettled(false)
    setRevoked(false)
  }, [active])

  // Drives the countdown only; expiry itself is the backend's call, reported by the poll.
  useEffect(() => {
    if (!active || code == null || codeStatus !== "PENDING") return
    const timer = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(timer)
  }, [active, code, codeStatus])

  const handleCopy = async () => {
    if (!code) return
    try {
      await navigator.clipboard.writeText(code.url)
      setCopied(true)
    } catch {
      // Clipboard access can be denied; the URL is selectable text either way.
    }
  }

  const handleRevoke = async () => {
    try {
      await revokeOtherSessions().unwrap()
      setRevoked(true)
    } catch (err) {
      setError(formatError(err))
    }
  }

  const remaining = code ? Date.parse(code.expiresAt) - now : 0

  return (
    <div className="space-y-3">
      {error && (
        <Alert variant="destructive">
          <AlertTriangle className="size-4" />
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}

      {/* A mint that failed leaves no code, so the EXPIRED/CANCELLED retry below can't render
          and this state would otherwise be a dead end — an error with nothing to press. */}
      {error && code == null && (
        <Button
          variant="outline"
          className="w-full"
          disabled={isMinting}
          onClick={() => void mint()}
        >
          {isMinting ? <Loader2 className="size-4 animate-spin" /> : <RefreshCw className="size-4" />}
          Try again
        </Button>
      )}

      {isMinting && !code && (
        <div className="flex justify-center p-4">
          <Loader2 className="size-5 animate-spin" />
        </div>
      )}

      {code && codeStatus === "USED" && (
        <div className="space-y-3">
          <Alert>
            <Check className="size-4" />
            <AlertDescription>
              Signed in as {code.displayName}
              {status?.redeemedByUserAgent != null && " on that device"}. It stays signed
              in for 30 days.
            </AlertDescription>
          </Alert>
          {revoked ? (
            <Alert>
              <Check className="size-4" />
              <AlertDescription>
                Every other device has been signed out. This desk is still signed in.
              </AlertDescription>
            </Alert>
          ) : (
            <>
              <p className="text-xs text-muted-foreground">
                Wasn&apos;t you? Nobody confirms a scan, so if a device you don&apos;t
                recognise took this code, sign it out now.
              </p>
              <Button
                variant="destructive"
                className="w-full"
                disabled={isRevoking}
                onClick={() => void handleRevoke()}
              >
                {isRevoking && <Loader2 className="size-4 animate-spin" />}
                That wasn&apos;t me — sign out every other device
              </Button>
            </>
          )}
        </div>
      )}

      {code && (codeStatus === "EXPIRED" || codeStatus === "CANCELLED") && (
        <div className="space-y-3">
          <Alert variant="destructive">
            <AlertTriangle className="size-4" />
            <AlertDescription>
              This code is no longer valid
              {codeStatus === "EXPIRED" ? " — it expired before it was used." : "."}
            </AlertDescription>
          </Alert>
          <Button variant="outline" className="w-full" onClick={() => void mint()}>
            <RefreshCw className="size-4" />
            Show a new code
          </Button>
        </div>
      )}

      {code && codeStatus === "PENDING" && (
        <>
          <p className="text-sm text-muted-foreground">
            Scan this on the phone or tablet you want to sign in as{" "}
            {code.displayName} — both devices need to be on the same network. Tap the
            confirm button that appears on the phone.
          </p>
          {/* White plate regardless of theme: a dark-on-dark QR doesn't scan. */}
          <div className="flex justify-center rounded-md bg-white p-4">
            <QRCode value={code.url} size={192} />
          </div>
          <p className="text-center text-sm tabular-nums text-muted-foreground">
            Expires in {formatCountdown(remaining)}
          </p>
          <p className="text-xs text-muted-foreground">
            Leaving this tab, or closing Profile, cancels the code — it only works while
            it&apos;s on screen.
          </p>
          <div className="space-y-2">
            <p className="text-xs text-muted-foreground">Or type it in:</p>
            <div className="flex items-start gap-2">
              <code className="min-w-0 flex-1 rounded-md border px-2 py-1.5 text-xs break-all select-all">
                {code.url}
              </code>
              <Button variant="outline" size="icon" onClick={() => void handleCopy()}>
                {copied ? <Check className="size-4" /> : <Copy className="size-4" />}
                <span className="sr-only">Copy sign-in link</span>
              </Button>
            </div>
          </div>
          {code.alternateUrls.length > 0 && (
            <details className="text-xs text-muted-foreground">
              <summary className="cursor-pointer select-none">
                Phone can&apos;t reach it? Other addresses
              </summary>
              <ul className="mt-2 space-y-1">
                {code.alternateUrls.map((url) => (
                  <li key={url} className="break-all select-all">
                    {url}
                  </li>
                ))}
              </ul>
            </details>
          )}
        </>
      )}

      {/* Only once the code is spent. While it is live the tab bar directly above is the way
          out — a Hide button beside it would be a second control for the same thing, and the
          cancelling happens on the way out either way. "Done" lands on Devices, where the phone
          that just signed in is now a row. */}
      {codeStatus === "USED" && (
        <Button variant="outline" className="w-full" onClick={onDone}>
          See your devices
        </Button>
      )}
    </div>
  )
}
