import { useEffect, useState } from 'react'
import { Check, Copy, Loader2, TriangleAlert } from 'lucide-react'
import { toast } from 'sonner'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { formatError } from '@/lib/formatError'
import { cn } from '@/lib/utils'
import { useAuthStatusQuery } from '@/store/auth'
import {
  useSaveTunnelSettingsMutation,
  useTunnelSettingsQuery,
  type TunnelSettings,
  type TunnelState,
  type UpdateTunnelRequest,
} from '@/store/remoteAccess'

/**
 * The Remote access tab: the desk's own ngrok tunnel, which puts the **whole desk** — the app, its
 * API and the MCP connector — on the internet at the operator's ngrok domain. Admin only, and
 * machine-local (it is about this computer's network, not the show). Backend contract in
 * lighting7's `docs/mcp-engineering.md` §"Remote access".
 *
 * Three things here are decisions rather than detail:
 *
 * - **Turning it on asks first**, in a dialog that says what becomes reachable and by whom. Turning
 *   it off does not: going dark is never the risky direction.
 * - **Saving a changed domain asks first**, because the domain is the OAuth issuer: every connector
 *   added under the old address stops working and has to be added again.
 * - **The authtoken is write-only.** The desk never sends it back, so the field is always empty and
 *   says only whether one is stored; pasting replaces it and Clear removes it (which turns remote
 *   access off, since there is nothing left to run).
 */
export function RemoteAccessPanel() {
  const { data: authStatus } = useAuthStatusQuery()
  const isAdmin = authStatus?.user?.role === 'ADMIN'
  const { data: settings, isLoading, error } = useTunnelSettingsQuery(undefined, { skip: !isAdmin })

  if (!isAdmin) {
    return <p className="text-sm text-muted-foreground">Remote access requires an administrator account.</p>
  }
  if (isLoading) {
    return (
      <div className="flex items-center gap-2 text-sm text-muted-foreground">
        <Loader2 className="h-4 w-4 animate-spin" /> Loading remote access…
      </div>
    )
  }
  if (settings == null) {
    return (
      <Alert variant="destructive">
        <TriangleAlert className="h-4 w-4" />
        <AlertDescription>
          {error != null ? formatError(error) : 'Could not read the remote access settings from this desk.'}
        </AlertDescription>
      </Alert>
    )
  }
  return <RemoteAccessForm settings={settings} />
}

function RemoteAccessForm({ settings }: { settings: TunnelSettings }) {
  const [save, { isLoading: saving, error: saveError, reset }] = useSaveTunnelSettingsMutation()
  const [token, setToken] = useState('')
  const [domain, setDomain] = useState(settings.domain ?? '')
  const [confirmEnable, setConfirmEnable] = useState(false)
  const [confirmDomain, setConfirmDomain] = useState(false)

  // Re-seed the field when the saved domain moves (a save here, or another admin's elsewhere),
  // never on every settings refresh — the state frame patches the cache several times a second
  // during a download, and each would wipe what the operator is typing.
  const savedDomain = settings.domain ?? ''
  useEffect(() => {
    setDomain(savedDomain)
  }, [savedDomain])

  const run = async (body: UpdateTunnelRequest): Promise<boolean> => {
    reset()
    try {
      await save(body).unwrap()
      return true
    } catch {
      return false
    }
  }

  const trimmedDomain = domain.trim()
  const trimmedToken = token.trim()
  const domainChanged = trimmedDomain !== savedDomain
  const dirty = domainChanged || trimmedToken.length > 0

  const saveAccount = async () => {
    const body: UpdateTunnelRequest = {}
    if (domainChanged) body.domain = trimmedDomain
    if (trimmedToken.length > 0) body.authtoken = trimmedToken
    if (await run(body)) {
      setToken('')
      toast.success('Remote access saved')
    }
  }

  const onSaveAccount = () => {
    // Replacing a domain that is already the issuer breaks every connector added under it.
    if (domainChanged && savedDomain !== '' && !settings.publicUrlOverridden) setConfirmDomain(true)
    else void saveAccount()
  }

  const clearToken = async () => {
    if (await run({ authtoken: '' })) toast.success('Authtoken removed')
  }

  const enableReason = !settings.hasAuthtoken
    ? 'Paste your ngrok authtoken and save first.'
    : savedDomain === ''
      ? 'Enter your ngrok domain and save first.'
      : null

  const onToggle = () => {
    if (settings.enabled) void run({ enabled: false })
    else setConfirmEnable(true)
  }

  return (
    <div className="max-w-2xl space-y-6">
      <p className="text-sm text-muted-foreground">
        Put this desk on the internet through your own ngrok account, so Claude on your phone — or a
        browser anywhere — can reach it. The first time it is turned on, the desk downloads the ngrok
        agent
        {settings.pinnedVersion != null ? ` (version ${settings.pinnedVersion})` : ''} from ngrok.
      </p>

      <section className="space-y-3">
        <div className="flex items-center justify-between gap-4">
          <div className="space-y-1">
            <h3 className="text-sm font-medium">Remote access</h3>
            <TunnelStatusLine state={settings.state} />
          </div>
          <button
            type="button"
            role="switch"
            aria-checked={settings.enabled}
            aria-label="Remote access"
            disabled={saving || (!settings.enabled && enableReason != null)}
            title={!settings.enabled && enableReason != null ? enableReason : undefined}
            onClick={onToggle}
            className={cn(
              'relative inline-flex h-6 w-11 shrink-0 items-center rounded-full border-2 border-transparent transition-colors',
              'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-50',
              settings.enabled ? 'bg-primary' : 'bg-input',
            )}
          >
            <span
              className={cn(
                'pointer-events-none block h-5 w-5 rounded-full bg-background shadow transition-transform',
                settings.enabled ? 'translate-x-5' : 'translate-x-0',
              )}
            />
          </button>
        </div>
        {!settings.enabled && enableReason != null && (
          <p className="text-xs text-muted-foreground">{enableReason}</p>
        )}
        <ConnectorAddress settings={settings} />
      </section>

      <section className="space-y-3">
        <h3 className="text-sm font-medium">ngrok account</h3>
        <div className="space-y-2">
          <Label htmlFor="ngrok-authtoken">Authtoken</Label>
          <div className="flex gap-2">
            <Input
              id="ngrok-authtoken"
              type="password"
              autoComplete="off"
              value={token}
              onChange={(e) => setToken(e.target.value)}
              placeholder={settings.hasAuthtoken ? 'Stored — paste a new one to replace it' : 'Paste from your ngrok dashboard'}
            />
            {settings.hasAuthtoken && (
              <Button variant="outline" onClick={() => void clearToken()} disabled={saving}>
                Clear
              </Button>
            )}
          </div>
          <p className="text-xs text-muted-foreground">
            Kept in this computer’s keychain. The desk never shows it again.
          </p>
        </div>
        <div className="space-y-2">
          <Label htmlFor="ngrok-domain">Domain</Label>
          <Input
            id="ngrok-domain"
            value={domain}
            onChange={(e) => setDomain(e.target.value)}
            placeholder="your-name.ngrok-free.app"
            autoComplete="off"
            spellCheck={false}
          />
          <p className="text-xs text-muted-foreground">
            Your static domain from the ngrok dashboard. It becomes the desk’s address, so changing it
            later means adding Claude’s connector again.
          </p>
        </div>
        <div className="flex justify-end">
          <Button onClick={onSaveAccount} disabled={!dirty || saving}>
            {saving ? 'Saving…' : 'Save'}
          </Button>
        </div>
      </section>

      <section className="space-y-2">
        <h3 className="text-sm font-medium">Scripts</h3>
        <label className="flex items-start gap-2 text-sm">
          <input
            type="checkbox"
            className="mt-0.5"
            checked={settings.allowScripts}
            disabled={saving}
            onChange={(e) => void run({ allowScripts: e.target.checked })}
          />
          <span>
            Allow scripts over remote access
            <span className="block text-xs text-muted-foreground">
              A script runs as the desk, with this computer’s files and network. Off, anyone signed in
              from outside can edit and busk but cannot save, compile or run a script.
            </span>
          </span>
        </label>
      </section>

      <section className="space-y-2">
        <h3 className="text-sm font-medium">Cannons</h3>
        <label className="flex items-start gap-2 text-sm">
          <input
            type="checkbox"
            className="mt-0.5"
            checked={settings.allowEffects === true}
            disabled={saving}
            onChange={(e) => void run({ allowEffects: e.target.checked })}
          />
          <span>
            Allow arming and firing over remote access
            <span className="block text-xs text-muted-foreground">
              A confetti cannon spends something physical in a room a remote caller cannot see. Off,
              anyone signed in from outside can author a cue&apos;s events but cannot arm the desk, fire
              a tube or reload one; at the desk itself every signed-in role can. The arm is the
              operator&apos;s consent: while the desk is armed, a GO from anywhere — a remote session
              or Claude included — fires its cue&apos;s events.
            </span>
          </span>
        </label>
      </section>

      <section className="space-y-2">
        <h3 className="text-sm font-medium">Fixture commands</h3>
        <label className="flex items-start gap-2 text-sm">
          <input
            type="checkbox"
            className="mt-0.5"
            checked={settings.allowCommands === true}
            disabled={saving}
            onChange={(e) => void run({ allowCommands: e.target.checked })}
          />
          <span>
            Allow fixture commands over remote access
            <span className="block text-xs text-muted-foreground">
              A reset swings a head through its travel and a lamp off leaves a discharge head dark for
              minutes, in a room a remote caller cannot see. Off, anyone signed in from outside — and
              Claude over MCP, which is always remote — cannot run a reset or a lamp command; at the desk
              itself every signed-in role can, behind the fixture panel&apos;s confirm.
            </span>
          </span>
        </label>
      </section>

      {saveError != null && (
        <Alert variant="destructive">
          <TriangleAlert className="h-4 w-4" />
          <AlertDescription>{formatError(saveError)}</AlertDescription>
        </Alert>
      )}

      <EnableDialog
        open={confirmEnable}
        onOpenChange={setConfirmEnable}
        domain={savedDomain}
        allowScripts={settings.allowScripts}
        onConfirm={async () => {
          setConfirmEnable(false)
          await run({ enabled: true })
        }}
      />
      <Dialog open={confirmDomain} onOpenChange={setConfirmDomain}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>
              {trimmedDomain === '' ? 'Remove the desk’s address?' : 'Change the desk’s address?'}
            </DialogTitle>
            <DialogDescription>
              {trimmedDomain === ''
                ? `The domain is the address Claude signs in to. Without one, remote access turns off and connectors added for https://${savedDomain}/mcp stop working.`
                : `The domain is the address Claude signs in to. Connectors added for https://${savedDomain}/mcp stop working, and each one has to be removed and added again as https://${trimmedDomain}/mcp.`}
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setConfirmDomain(false)}>
              Cancel
            </Button>
            <Button
              onClick={() => {
                setConfirmDomain(false)
                void saveAccount()
              }}
            >
              {trimmedDomain === '' ? 'Remove domain' : 'Change domain'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}

function EnableDialog({
  open,
  onOpenChange,
  domain,
  allowScripts,
  onConfirm,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  domain: string
  allowScripts: boolean
  onConfirm: () => void
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Put this desk on the internet?</DialogTitle>
          <DialogDescription>
            The whole desk becomes reachable at https://{domain} — every control, the admin settings
            {allowScripts ? ', and scripts, which run as this computer' : ''} — from anywhere, to anyone
            with a desk password.
          </DialogDescription>
        </DialogHeader>
        <ul className="list-disc space-y-1 pl-5 text-sm text-muted-foreground">
          <li>Use strong passwords on every desk account before you turn this on.</li>
          <li>Sign-ins from outside lock for fifteen minutes after ten failures.</li>
          <li>
            {allowScripts
              ? 'Scripts are allowed from outside. Turn that off below unless you need it.'
              : 'Scripts stay blocked from outside unless you allow them below.'}
          </li>
        </ul>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button variant="destructive" onClick={onConfirm}>
            Turn on
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

/** One line saying what the tunnel is doing now, from the live `tunnel.state` frame. */
export function TunnelStatusLine({ state }: { state: TunnelState }) {
  const tone =
    state.status === 'online'
      ? 'bg-green-500'
      : state.status === 'error' || state.status === 'no-binary'
        ? 'bg-destructive'
        : state.status === 'off'
          ? 'bg-muted-foreground/40'
          : 'bg-amber-500'
  return (
    <div className="flex items-start gap-2 text-sm" data-testid="tunnel-status">
      <span className={cn('mt-1.5 size-2 shrink-0 rounded-full', tone)} aria-hidden />
      <span>{describeTunnelState(state)}</span>
    </div>
  )
}

export function describeTunnelState(state: TunnelState): string {
  switch (state.status) {
    case 'off':
      return 'Off'
    case 'installing': {
      const total = state.totalBytes
      const read = state.downloadedBytes ?? 0
      return total != null && total > 0
        ? `Downloading ngrok… ${Math.floor((read / total) * 100)}%`
        : 'Downloading ngrok…'
    }
    case 'starting':
      return 'Starting…'
    case 'online':
      return state.url != null ? `Online · ${state.url}` : 'Online'
    case 'no-binary':
      return state.message ?? 'The ngrok agent is not available on this computer.'
    case 'error': {
      const code = state.code != null ? ` (${state.code})` : ''
      const retry = state.retrying ? ' Retrying…' : ''
      return `${state.message ?? 'The tunnel stopped.'}${code}${retry}`
    }
  }
}

/** What to paste into Claude's "Add custom connector", with Copy. */
function ConnectorAddress({ settings }: { settings: TunnelSettings }) {
  const [copied, setCopied] = useState(false)
  const reachable = settings.publicUrlOverridden || (settings.domain ?? '') !== ''
  if (!reachable) return null

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(settings.connectorUrl)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 1500)
    } catch {
      // The clipboard is refused on a plain-http LAN origin; the address is selectable instead.
      toast.error('Could not copy — select the address instead')
    }
  }

  return (
    <div className="space-y-1">
      <Label className="text-muted-foreground">Claude connector address</Label>
      <div className="flex items-center gap-2">
        <code className="min-w-0 flex-1 truncate rounded border bg-muted/40 px-2 py-1.5 text-xs select-all">
          {settings.connectorUrl}
        </code>
        <Button variant="outline" size="sm" onClick={() => void copy()} aria-label="Copy connector address">
          {copied ? <Check className="h-4 w-4" /> : <Copy className="h-4 w-4" />}
        </Button>
      </div>
      <p className="text-xs text-muted-foreground">
        {settings.publicUrlOverridden
          ? 'mcp.publicUrl in local.conf names a tunnel of your own, and it wins over the ngrok domain.'
          : 'In claude.ai, open Settings → Connectors → Add custom connector and paste this.'}
      </p>
    </div>
  )
}
