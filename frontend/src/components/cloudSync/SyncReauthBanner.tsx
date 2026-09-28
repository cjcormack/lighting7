import { useEffect, useState } from "react"
import { useNavigate } from "react-router"
import { AlertTriangle, X } from "lucide-react"
import { Button } from "@/components/ui/button"
import { useOAuthReauthState } from "@/store/oauthGithub"

/**
 * App-wide warning that the desk's GitHub authorisation is dead and cloud sync is going
 * nowhere until someone reconnects it.
 *
 * It exists because the state was previously only visible on the sync pages, which nobody
 * opens unless they already suspect a problem — a desk ran for 25 days with a rejected token
 * while every surface anyone actually looks at said nothing. The sidebar badge is the standing
 * reminder; this is what catches you the first time.
 *
 * Deliberately a banner and not a toast or a modal: per the same rule the update panel follows,
 * the desk must never interrupt whoever is running a show. It is also admin-only by
 * construction — see [useOAuthReauthState], which skips the query for operators.
 */
const DISMISSED_KEY = "lighting7:syncReauthDismissedAt"

/**
 * Dismissal is remembered against the *timestamp of this rejection*, not as a plain boolean, so
 * dismissing survives reloads while a genuinely new outage months later still gets one chance to
 * be seen. Storing only the last dismissed stamp (rather than a set) keeps the key from growing.
 */
function readDismissedAt(): string | null {
  try {
    return window.localStorage.getItem(DISMISSED_KEY)
  } catch {
    // Private-mode or a storage-blocked browser: treat as "not dismissed". Showing the banner
    // again is a far smaller failure than silently hiding a broken connection.
    return null
  }
}

export function SyncReauthBanner() {
  const navigate = useNavigate()
  const { reauthRequired, reauthReason, reauthRequiredAt } = useOAuthReauthState()
  const stamp = reauthRequiredAt ?? "unknown"
  const [dismissedAt, setDismissedAt] = useState<string | null>(() => readDismissedAt())

  // A fresh rejection re-arms the banner even in a tab that was open when the old one was
  // dismissed — the identity cache is kept live by the WS bridge in the store, so `stamp`
  // changes under us without a reload.
  useEffect(() => {
    if (reauthRequired && dismissedAt !== null && dismissedAt !== stamp) {
      setDismissedAt(null)
    }
  }, [reauthRequired, stamp, dismissedAt])

  if (!reauthRequired || dismissedAt === stamp) return null

  const dismiss = () => {
    setDismissedAt(stamp)
    try {
      window.localStorage.setItem(DISMISSED_KEY, stamp)
    } catch {
      // Storage unavailable — the banner is then dismissed for this page only. Acceptable:
      // the badge still carries the warning.
    }
  }

  return (
    <div
      role="alert"
      className="flex flex-wrap items-center gap-x-3 gap-y-2 border-b border-destructive/40 bg-destructive/10 px-4 py-2 text-sm"
    >
      <AlertTriangle className="size-4 shrink-0 text-destructive" />
      <span className="min-w-0 flex-1">
        <strong className="font-semibold">Cloud sync is not running.</strong>{" "}
        GitHub has rejected this desk&rsquo;s authorisation
        {reauthReason ? <> &mdash; {reauthReason}</> : null}
      </span>
      <Button size="sm" onClick={() => navigate("/install/sync")}>
        Reconnect
      </Button>
      <Button
        variant="ghost"
        size="icon"
        aria-label="Dismiss"
        title="Dismiss until this happens again"
        onClick={dismiss}
      >
        <X className="size-4" />
      </Button>
    </div>
  )
}
