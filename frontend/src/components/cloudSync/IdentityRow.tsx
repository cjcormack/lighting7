import { useState } from "react"
import { toast } from "sonner"
import { Button } from "@/components/ui/button"
import { Badge } from "@/components/ui/badge"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { AlertTriangle, LogOut, Loader2 } from "lucide-react"
import { GithubIcon } from "@/components/GithubIcon"
import {
  useDisconnectOAuthGithubMutation,
  useOauthGithubIdentityQuery,
} from "@/store/oauthGithub"
import { formatError } from "@/lib/formatError"
import { DeviceFlowModal } from "./DeviceFlowModal"

/**
 * Header row for the cloud-sync configuration card. Shows the connected GitHub user
 * (or a "Connect GitHub" call-to-action) and exposes the device-flow fallback link
 * underneath.
 *
 * The web flow is initiated by a normal `<a>` to the backend's `/oauth/github/start`
 * route — no SPA navigation trickery, the backend sets the CSRF cookie and redirects.
 */
// Backend refreshes at 60s remaining; this badge stays on a few minutes earlier
// so a sluggish refresh doesn't surprise the user.
const REFRESH_SOON_THRESHOLD_MS = 5 * 60 * 1000

export function IdentityRow({ projectId }: { projectId: number | null }) {
  const { data: identity, isLoading } = useOauthGithubIdentityQuery()
  const [disconnect, { isLoading: isDisconnecting }] = useDisconnectOAuthGithubMutation()
  const [deviceFlowOpen, setDeviceFlowOpen] = useState(false)

  if (isLoading || !identity) {
    return (
      <div className="flex items-center gap-2 text-xs text-muted-foreground">
        <Loader2 className="size-3.5 animate-spin" />
        Checking GitHub connection…
      </div>
    )
  }

  if (!identity.oauthConfigured) {
    return (
      <div className="text-xs text-muted-foreground">
        OAuth is not configured on this install. Use the Advanced section below to
        store a Personal Access Token, or set <code className="text-xs">sync.oauth.github</code>{" "}
        in <code className="text-xs">local.conf</code>.
      </div>
    )
  }

  const handleDisconnect = async () => {
    try {
      await disconnect().unwrap()
      toast.success("GitHub disconnected")
    } catch (err) {
      toast.error(`Disconnect failed: ${formatError(err)}`)
    }
  }

  // Web flow: hand the browser straight to the backend, which sets the CSRF cookie
  // and redirects to GitHub. The `projectId` query param tells the callback where
  // to bounce the user back to; when called from the install-level Sync hub it's
  // omitted and the backend falls back to its default landing page.
  const startUrl = projectId != null
    ? `/api/rest/oauth/github/start?projectId=${encodeURIComponent(String(projectId))}`
    : `/api/rest/oauth/github/start`

  const disconnectButton = (
    <Button
      variant="outline"
      size="sm"
      onClick={handleDisconnect}
      disabled={isDisconnecting}
    >
      <LogOut className="size-3.5 mr-1.5" />
      {isDisconnecting ? "Disconnecting…" : "Disconnect"}
    </Button>
  )

  // Rejected identity: still connected in the sense that we know whose it is, but nothing
  // will sync until the user reconnects. This case must be checked *before* the healthy
  // one — it used to fall through to it and render "refreshing soon" indefinitely, because
  // an access token expired in the past reads as "about to be refreshed".
  if (identity.connected && identity.reauthRequired) {
    return (
      <div className="space-y-3">
        <Alert variant="destructive">
          <AlertTriangle className="size-4" />
          <AlertTitle>Reconnect GitHub</AlertTitle>
          <AlertDescription>
            GitHub has rejected this desk&rsquo;s authorisation for{" "}
            <strong>@{identity.login}</strong>
            {identity.reauthRequiredAt != null && (
              <> since {new Date(identity.reauthRequiredAt).toLocaleString()}</>
            )}
            {/* Deliberately not "sync has stopped": this row is install-wide and can't see
                whether a given project has a PAT to fall back on. */}
            . Cloud sync can&rsquo;t use this connection until you reconnect.
            {identity.reauthReason && (
              <span className="block mt-1 text-xs opacity-90">{identity.reauthReason}</span>
            )}
          </AlertDescription>
        </Alert>
        <div className="flex flex-wrap items-center justify-between gap-3">
          <a href={startUrl}>
            <Button size="sm">
              <GithubIcon className="size-3.5 mr-1.5" />
              Reconnect GitHub
            </Button>
          </a>
          {disconnectButton}
        </div>
        <button
          type="button"
          onClick={() => setDeviceFlowOpen(true)}
          className="text-xs text-muted-foreground hover:text-foreground underline-offset-2 hover:underline"
        >
          Can&rsquo;t open a popup? Use a device code
        </button>
        <DeviceFlowModal open={deviceFlowOpen} onOpenChange={setDeviceFlowOpen} />
      </div>
    )
  }

  if (identity.connected) {
    const expiresIn = identity.accessExpiresAt
      ? Math.max(0, Date.parse(identity.accessExpiresAt) - Date.now())
      : null
    return (
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-2 min-w-0">
          <GithubIcon className="size-4 shrink-0" />
          <span className="text-sm truncate">
            Connected as <strong>@{identity.login}</strong>
          </span>
          {expiresIn != null && expiresIn < REFRESH_SOON_THRESHOLD_MS && (
            <Badge variant="outline" className="text-[10px]">
              refreshing soon
            </Badge>
          )}
        </div>
        {disconnectButton}
      </div>
    )
  }

  return (
    <div className="space-y-1">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-2">
          <GithubIcon className="size-4 shrink-0" />
          <span className="text-sm">Not connected to GitHub.</span>
        </div>
        <a href={startUrl}>
          <Button size="sm">
            <GithubIcon className="size-3.5 mr-1.5" />
            Connect GitHub
          </Button>
        </a>
      </div>
      <button
        type="button"
        onClick={() => setDeviceFlowOpen(true)}
        className="text-xs text-muted-foreground hover:text-foreground underline-offset-2 hover:underline"
      >
        Can&rsquo;t open a popup? Use a device code
      </button>
      <DeviceFlowModal open={deviceFlowOpen} onOpenChange={setDeviceFlowOpen} />
    </div>
  )
}
