import { useState, type ReactNode } from 'react'
import { Loader2, TriangleAlert } from 'lucide-react'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { formatError } from '@/lib/formatError'
import { cn } from '@/lib/utils'
import {
  useEndRemoteSessionMutation,
  useRemoteAccessSettingsQuery,
  useRemoteSessionsQuery,
  useSetRemoteAccessMutation,
  type CloudState,
  type RemoteAccessSettings,
  type RemoteSession,
} from '@/store/cloud'

/** How often the session list is re-read while the section is open: no frame announces a session. */
export const SESSIONS_POLL_MS = 5000

export const LOCAL_ONLY_REASON =
  'These settings limit what can reach the desk remotely, so they can only be changed on the desk itself.'

/**
 * The Cloud tab's *Remote access* section (lighting7 cloud-p1 plan D6, D13): what may come through
 * the cloud — remote sessions, Claude, and whether each new session waits for an admin here — the
 * sidecar that carries the sessions, the open and recent sessions with *End*, and the scripts,
 * effects and commands switches every remote caller answers to, a remote session and Claude alike.
 * Editable only on the desk's own listener; over remote access it reads, and says why.
 */
export function CloudRemoteAccess({ state }: { state: CloudState }) {
  const { data: settings, error: loadError } = useRemoteAccessSettingsQuery()
  const [save, { isLoading: saving, error: saveError, reset }] = useSetRemoteAccessMutation()
  const remote = state.remote === true
  const adopted = state.adopted != null
  const locked = remote || saving

  if (settings == null) {
    return (
      <section className="space-y-2">
        <h3 className="text-sm font-medium">Remote access</h3>
        {loadError != null ? (
          <ErrorAlert error={loadError} />
        ) : (
          <div className="flex items-center gap-2 text-sm text-muted-foreground">
            <Loader2 className="h-4 w-4 animate-spin" /> Loading…
          </div>
        )}
      </section>
    )
  }

  const set = (body: Partial<RemoteAccessSettings>) => {
    reset()
    save(body).unwrap().catch(() => {
      // Shown below.
    })
  }

  return (
    <section className="space-y-4" data-testid="cloud-remote-access">
      <div className="space-y-1">
        <h3 className="text-sm font-medium">Remote access</h3>
        {remote && <p className="text-xs text-muted-foreground">{LOCAL_ONLY_REASON}</p>}
      </div>

      {adopted && (
        <div className="space-y-4">
          <SwitchRow
            label="Remote sessions"
            checked={settings.remoteAccess}
            disabled={locked}
            onChange={(on) => set({ remoteAccess: on })}
          >
            Members of {state.adopted?.orgName} can open this desk’s own app from anywhere, through
            Lighting7 Cloud, as the desk account their cloud account is linked to. Off, no session opens
            and the open ones end.
          </SwitchRow>
          {settings.remoteAccess && <SidecarLine link={state.link} />}
          <SwitchRow
            label="Approve each new session on this desk"
            checked={settings.approveSessions}
            disabled={locked}
            onChange={(on) => set({ approveSessions: on })}
          >
            A new remote session waits up to a minute for an admin at this desk to allow it, on every
            signed-in admin window here. It does not cover Claude: keep that off to watch every way in.
          </SwitchRow>
          <SwitchRow
            label="Claude (MCP)"
            checked={settings.mcpAccess}
            disabled={locked}
            onChange={(on) => set({ mcpAccess: on })}
          >
            Claude, through Lighting7 Cloud’s MCP address, can run the desk’s tools as the desk account its
            cloud account is linked to. Off, every call through the cloud is refused.
          </SwitchRow>
        </div>
      )}

      <div className="space-y-3">
        <p className="text-xs text-muted-foreground">
          Whoever reaches the desk from outside — a remote session or Claude — may also:
        </p>
        <CheckRow
          label="Allow scripts over remote access"
          checked={settings.allowScripts}
          disabled={locked}
          onChange={(on) => set({ allowScripts: on })}
        >
          A script runs as the desk, with this computer’s files and network. Off, anyone signed in from
          outside can edit and busk but cannot save, compile or run a script.
        </CheckRow>
        <CheckRow
          label="Allow arming and firing over remote access"
          checked={settings.allowEffects}
          disabled={locked}
          onChange={(on) => set({ allowEffects: on })}
        >
          A confetti cannon spends something physical in a room a remote caller cannot see. Off, anyone
          signed in from outside can author a cue’s events but cannot arm the desk, fire a tube or reload
          one; at the desk itself every signed-in role can. The arm is the operator’s consent: while the
          desk is armed, a GO from anywhere — a remote session or Claude included — fires its cue’s events.
        </CheckRow>
        <CheckRow
          label="Allow fixture commands over remote access"
          checked={settings.allowCommands}
          disabled={locked}
          onChange={(on) => set({ allowCommands: on })}
        >
          A reset swings a head through its travel and a lamp off leaves a discharge head dark for minutes,
          in a room a remote caller cannot see. Off, anyone signed in from outside — and Claude, which is
          always remote — cannot run a reset or a lamp command; at the desk itself every signed-in role
          can, behind the fixture panel’s confirm.
        </CheckRow>
      </div>

      {saveError != null && <ErrorAlert error={saveError} />}

      {adopted && <SessionList />}
    </section>
  )
}

function SwitchRow({
  label,
  checked,
  disabled,
  onChange,
  children,
}: {
  label: string
  checked: boolean
  disabled: boolean
  onChange: (on: boolean) => void
  children: ReactNode
}) {
  return (
    <div className="flex items-start justify-between gap-4">
      <div className="space-y-1">
        <p className="text-sm">{label}</p>
        <p className="text-xs text-muted-foreground">{children}</p>
      </div>
      <button
        type="button"
        role="switch"
        aria-checked={checked}
        aria-label={label}
        disabled={disabled}
        onClick={() => onChange(!checked)}
        className={cn(
          'relative inline-flex h-6 w-11 shrink-0 items-center rounded-full border-2 border-transparent transition-colors',
          'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-50',
          checked ? 'bg-primary' : 'bg-input',
        )}
      >
        <span
          className={cn(
            'pointer-events-none block h-5 w-5 rounded-full bg-background shadow transition-transform',
            checked ? 'translate-x-5' : 'translate-x-0',
          )}
        />
      </button>
    </div>
  )
}

function CheckRow({
  label,
  checked,
  disabled,
  onChange,
  children,
}: {
  label: string
  checked: boolean
  disabled: boolean
  onChange: (on: boolean) => void
  children: ReactNode
}) {
  return (
    <label className="flex items-start gap-2 text-sm">
      <input
        type="checkbox"
        className="mt-0.5"
        checked={checked}
        disabled={disabled}
        onChange={(e) => onChange(e.target.checked)}
      />
      <span>
        {label}
        <span className="block text-xs text-muted-foreground">{children}</span>
      </span>
    </label>
  )
}

/** The sidecar that carries remote sessions, as `cloud.state`'s `link` reports it. */
export function SidecarLine({ link }: { link?: CloudState['link'] }) {
  const status = link ?? 'OFF'
  const tone = status === 'RUNNING' ? 'bg-green-500' : status === 'OFF' ? 'bg-muted-foreground/40' : 'bg-amber-500'
  const label = {
    RUNNING: 'Sidecar running',
    STARTING: 'Sidecar starting…',
    RESTARTING: 'Sidecar restarting…',
    OFF: 'Sidecar not running: it starts once the desk is online to the cloud, if this desk has one and its public listener is on.',
  }[status]
  return (
    <div className="flex items-start gap-2 text-xs text-muted-foreground" data-testid="cloud-sidecar">
      <span className={cn('mt-1 size-2 shrink-0 rounded-full', tone)} aria-hidden />
      <span>{label}</span>
    </div>
  )
}

const END_REASONS: Record<string, string> = {
  DESK_RESTARTED: 'desk restarted',
  DESK_STOPPED: 'desk stopped',
  EXPIRED: 'token ran out',
  REVOKED: 'revoked in the cloud',
  MISMATCH: 'token for someone else',
  CREDENTIALS_REVOKED: 'desk account signed out',
  LINK_REMOVED: 'link removed',
  REMOTE_ACCESS_OFF: 'remote sessions turned off',
  UNADOPTED: 'desk unadopted',
  CLOSED_BY_CLOUD: 'closed in the cloud',
  LINK_CLOSED: 'connection closed',
  SIDECAR_STOPPED: 'sidecar stopped',
  ENDED_BY_ADMIN: 'ended here',
  ENDED: 'ended',
}

export function endReasonLabel(reason?: string | null): string {
  return reason == null ? 'ended' : (END_REASONS[reason] ?? reason.toLowerCase().replaceAll('_', ' '))
}

function SessionList() {
  const { data, error } = useRemoteSessionsQuery(undefined, { pollingInterval: SESSIONS_POLL_MS })
  const [end, { isLoading: ending }] = useEndRemoteSessionMutation()
  const [confirming, setConfirming] = useState<string | null>(null)

  if (data == null) return error != null ? <ErrorAlert error={error} /> : null
  return (
    <div className="space-y-3" data-testid="cloud-sessions">
      <div className="space-y-2">
        <h4 className="text-sm font-medium">Open sessions</h4>
        {data.open.length === 0 ? (
          <p className="text-xs text-muted-foreground">None.</p>
        ) : (
          <ul className="space-y-2">
            {data.open.map((s) => (
              <li key={s.sid} className="flex items-start justify-between gap-3 text-sm">
                <SessionText session={s} />
                {confirming === s.sid ? (
                  <span className="flex shrink-0 gap-1">
                    <Button size="sm" variant="outline" onClick={() => setConfirming(null)}>
                      Keep
                    </Button>
                    <Button
                      size="sm"
                      variant="destructive"
                      disabled={ending}
                      onClick={() => {
                        setConfirming(null)
                        void end(s.sid)
                      }}
                    >
                      End now
                    </Button>
                  </span>
                ) : (
                  <Button size="sm" variant="outline" className="shrink-0" onClick={() => setConfirming(s.sid)}>
                    End
                  </Button>
                )}
              </li>
            ))}
          </ul>
        )}
      </div>
      {data.recent.length > 0 && (
        <div className="space-y-2">
          <h4 className="text-sm font-medium">Recent</h4>
          <ul className="space-y-1">
            {data.recent.map((s) => (
              <li key={s.sid} className="text-sm">
                <SessionText session={s} />
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  )
}

function SessionText({ session }: { session: RemoteSession }) {
  const started = new Date(session.startedAt).toLocaleString()
  const as = session.user != null ? `as ${session.user}` : 'not linked'
  const path = session.path === 'RELAY' ? 'relayed' : session.path === 'DIRECT' ? 'direct' : null
  const when =
    session.endedAt != null
      ? `${started} – ${new Date(session.endedAt).toLocaleTimeString()} · ${endReasonLabel(session.endReason)}`
      : `since ${started}`
  return (
    <span className="min-w-0">
      <span className="block truncate">
        {session.email} <span className="text-muted-foreground">· {as}</span>
      </span>
      <span className="block text-xs text-muted-foreground">
        {when}
        {path != null ? ` · ${path}` : ''}
      </span>
    </span>
  )
}

function ErrorAlert({ error }: { error: unknown }) {
  return (
    <Alert variant="destructive">
      <TriangleAlert className="h-4 w-4" />
      <AlertDescription>{formatError(error)}</AlertDescription>
    </Alert>
  )
}
