import { useEffect, useState } from "react"
import QRCode from "react-qr-code"
import { AlertTriangle, Check, Copy, Loader2, RefreshCw } from "lucide-react"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import {
  Sheet,
  SheetBody,
  SheetContent,
  SheetFooter,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet"
import { formatCountdown } from "@/lib/formatCountdown"
import { formatError } from "@/lib/formatError"
import {
  useCreateResetTokenMutation,
  useResetTokenStatusQuery,
  type DeskUser,
  type ResetTokenResponse,
} from "@/store/users"

/** Matches the sheet's poll cadence to human patience: the admin is watching a phone. */
const POLL_INTERVAL_MS = 2000

/**
 * The QR half of the password reset flow: an admin mints a single-use link, the
 * locked-out user scans it on their phone and sets a new password, and this sheet flips to
 * a success state within a poll interval.
 *
 * **Closing this sheet no longer cancels the link it was showing.** It used to, so that a QR
 * on screen for ten seconds didn't stay redeemable for fifteen minutes — but that made the
 * flow brittle in exactly the situations it exists for: the admin couldn't close the sheet to
 * go and do something else, and an operator slow to reach their phone lost the link for no
 * reason. Visibility replaced cancellation: the link lives its full TTL and
 * `ResetTokenHistory` in `UserDetailSheet` shows that it is live and offers a deliberate
 * Cancel. See `FU-AUTH-RESET-TOKEN-HISTORY` in lighting7's followups.
 *
 * The device-login sheet made the opposite call for the opposite risk profile — it *does*
 * cancel on close — so resist factoring the two into one component.
 *
 * Polling uses RTK Query's own `pollingInterval` rather than the hand-rolled
 * `setInterval` in `cloudSync/DeviceFlowModal` — that flow polls with a *mutation* (the
 * device-code grant is a POST) and had to schedule it itself; this one is a plain GET, so
 * the query can own its own cadence and stop when the sheet unmounts.
 */
export function ResetQrSheet({
  user,
  open,
  onOpenChange,
}: {
  /** Null while no row is selected — the sheet renders nothing until one is. */
  user: DeskUser | null
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const [token, setToken] = useState<ResetTokenResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [copied, setCopied] = useState(false)
  const [now, setNow] = useState(() => Date.now())
  /** Latched when the token reaches a terminal status; stops the poll. */
  const [settled, setSettled] = useState(false)

  const [createResetToken, { isLoading: isMinting }] = useCreateResetTokenMutation()

  const userId = user?.id

  const { data: status } = useResetTokenStatusQuery(
    { userId: userId ?? 0, tokenId: token?.id ?? 0 },
    {
      skip: !open || userId == null || token == null,
      // Stop once the answer can't change again — USED / EXPIRED / CANCELLED are terminal,
      // and a sheet left open on a success message shouldn't keep asking about it.
      pollingInterval: settled ? 0 : POLL_INTERVAL_MS,
    },
  )
  const tokenStatus = status?.status ?? "PENDING"

  useEffect(() => {
    if (tokenStatus === "PENDING") return
    setSettled(true)
  }, [tokenStatus])

  const mint = async () => {
    if (userId == null) return
    setError(null)
    setCopied(false)
    try {
      const minted = await createResetToken({ userId }).unwrap()
      setToken(minted)
      setSettled(false)
      setNow(Date.now())
    } catch (err) {
      setError(formatError(err))
    }
  }

  // One token per opening. Minting on open (rather than behind a button) is the point of
  // the flow: the admin already chose "reset this person's password" to get here.
  //
  // Deliberately keyed on the identity being reset, not on `mint`: `mint` closes over the
  // same `userId` this effect depends on, so the narrow deps are complete — and depending
  // on its (per-render) identity would mint a fresh token, cancelling the one on screen,
  // every time this component re-rendered.
  useEffect(() => {
    if (!open || userId == null) return
    void mint()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, userId])

  // Clear the sheet's own state on close so reopening doesn't flash the previous QR before
  // the new one arrives. The token itself is deliberately left alive — see the docblock.
  useEffect(() => {
    if (open) return
    setToken(null)
    setError(null)
    setCopied(false)
    setSettled(false)
  }, [open])

  // Drives the countdown only — the expiry itself is the backend's call, which the poll
  // reports as EXPIRED. A ticking clock with nothing to tick for is just wasted renders.
  useEffect(() => {
    if (!open || token == null || tokenStatus !== "PENDING") return
    const timer = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(timer)
  }, [open, token, tokenStatus])

  const handleCopy = async () => {
    if (!token) return
    try {
      await navigator.clipboard.writeText(token.url)
      setCopied(true)
    } catch {
      // Clipboard access can be denied; the URL is selectable text either way.
    }
  }

  const remaining = token ? Date.parse(token.expiresAt) - now : 0

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent className="flex flex-col sm:max-w-md">
        <SheetHeader>
          <SheetTitle>Reset password</SheetTitle>
        </SheetHeader>
        <SheetBody>
          {error && (
            <Alert variant="destructive">
              <AlertTriangle className="size-4" />
              <AlertDescription>{error}</AlertDescription>
            </Alert>
          )}

          {isMinting && !token && (
            <div className="flex justify-center p-4">
              <Loader2 className="size-5 animate-spin" />
            </div>
          )}

          {token && tokenStatus === "USED" && (
            <Alert>
              <Check className="size-4" />
              <AlertDescription>
                {token.displayName} set a new password. Every device they were signed in on
                has been signed out.
              </AlertDescription>
            </Alert>
          )}

          {token && (tokenStatus === "EXPIRED" || tokenStatus === "CANCELLED") && (
            <div className="space-y-3">
              <Alert variant="destructive">
                <AlertTriangle className="size-4" />
                <AlertDescription>
                  This link is no longer valid
                  {tokenStatus === "EXPIRED" ? " — it expired before it was used." : "."}
                </AlertDescription>
              </Alert>
              <Button variant="outline" className="w-full" onClick={() => void mint()}>
                <RefreshCw className="size-4" />
                Generate a new link
              </Button>
            </div>
          )}

          {token && tokenStatus === "PENDING" && (
            <>
              <p className="text-sm text-muted-foreground">
                Have {token.displayName} scan this on their phone — both devices need to be
                on the same network. They&apos;ll set the password themselves; you never see
                it.
              </p>
              {/* Says the quiet part out loud, because the old behaviour was the opposite and
                  an admin who learned it here would otherwise assume closing kills the link. */}
              <p className="text-xs text-muted-foreground">
                You can close this — the link stays valid until it expires or you cancel it
                from the user&apos;s Reset links list.
              </p>
              {/* White plate regardless of theme: a dark-on-dark QR doesn't scan. */}
              <div className="flex justify-center rounded-md bg-white p-4">
                <QRCode value={token.url} size={192} />
              </div>
              <p className="text-center text-sm tabular-nums text-muted-foreground">
                Expires in {formatCountdown(remaining)}
              </p>
              <div className="space-y-2">
                <p className="text-xs text-muted-foreground">Or type it in:</p>
                <div className="flex items-start gap-2">
                  <code className="min-w-0 flex-1 rounded-md border px-2 py-1.5 text-xs break-all select-all">
                    {token.url}
                  </code>
                  <Button variant="outline" size="icon" onClick={() => void handleCopy()}>
                    {copied ? <Check className="size-4" /> : <Copy className="size-4" />}
                    <span className="sr-only">Copy reset link</span>
                  </Button>
                </div>
              </div>
              {token.alternateUrls.length > 0 && (
                <details className="text-xs text-muted-foreground">
                  <summary className="cursor-pointer select-none">
                    Phone can&apos;t reach it? Other addresses
                  </summary>
                  <ul className="mt-2 space-y-1">
                    {token.alternateUrls.map((url) => (
                      <li key={url} className="break-all select-all">
                        {url}
                      </li>
                    ))}
                  </ul>
                </details>
              )}
            </>
          )}
        </SheetBody>
        <SheetFooter className="flex-row justify-end gap-2">
          {/* "Close", never "Cancel": closing this sheet leaves the link live on purpose, and
              a button labelled Cancel would promise the opposite. Revoking is a deliberate act
              on the history list in UserDetailSheet. */}
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            {tokenStatus === "USED" ? "Done" : "Close"}
          </Button>
        </SheetFooter>
      </SheetContent>
    </Sheet>
  )
}
