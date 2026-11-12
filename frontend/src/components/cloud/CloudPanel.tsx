import { useState, type ReactNode } from 'react'
import QRCode from 'react-qr-code'
import { Check, Copy, Loader2, TriangleAlert, X } from 'lucide-react'
import { toast } from 'sonner'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { useNow } from '@/components/rigSafety/useNow'
import { usePersistentState } from '@/hooks/usePersistentState'
import { formatCountdown } from '@/lib/formatCountdown'
import { formatError } from '@/lib/formatError'
import { cn } from '@/lib/utils'
import { errorCode } from '@/lib/venueSyncRefusal'
import { useAuthStatusQuery } from '@/store/auth'
import { CloudMoveSection } from './CloudMoveSection'
import { CloudRemoteAccess } from './CloudRemoteAccess'
import {
  useCancelCloudAdoptionMutation,
  useCloudStateQuery,
  useStartCloudAdoptionMutation,
  useUnadoptCloudMutation,
  type CloudAdopted,
  type CloudAdoption,
  type CloudState,
} from '@/store/cloud'

/**
 * The Cloud tab: this desk's adoption by Lighting7 Cloud, and what may reach it remotely. Admin only
 * and machine-local. Everything it shows is the desk's own state, so a pending adoption survives a
 * reload and is the same on every window. Backend contract in lighting7's `docs/cloud-engineering.md`.
 */
export function CloudPanel() {
  const { data: authStatus } = useAuthStatusQuery()
  const isAdmin = authStatus?.user?.role === 'ADMIN'
  const { data: state, isLoading, error } = useCloudStateQuery(undefined, { skip: !isAdmin })

  if (!isAdmin) {
    return <p className="text-sm text-muted-foreground">The cloud requires an administrator account.</p>
  }
  if (isLoading) {
    return (
      <div className="flex items-center gap-2 text-sm text-muted-foreground">
        <Loader2 className="h-4 w-4 animate-spin" /> Loading the cloud…
      </div>
    )
  }
  if (state == null) {
    return (
      <Alert variant="destructive">
        <TriangleAlert className="h-4 w-4" />
        <AlertDescription>
          {error != null ? formatError(error) : 'Could not read the cloud settings from this desk.'}
        </AlertDescription>
      </Alert>
    )
  }
  return <CloudBody state={state} />
}

function CloudBody({ state }: { state: CloudState }) {
  const available = state.configured && state.enabled
  return (
    <div className="max-w-2xl space-y-6">
      <Notice notice={state.notice} noticeAt={state.noticeAt} />
      {!state.configured && (
        <Unavailable title="No cloud configured">
          {state.lastError != null ? (
            state.lastError.message
          ) : (
            <>
              <code>cloud.baseUrl</code> in this desk’s <code>local.conf</code> names no cloud.
            </>
          )}
        </Unavailable>
      )}
      {state.configured && !state.enabled && (
        <Unavailable title="Turned off on this desk">
          <code>cloud.enabled = false</code> in this desk’s <code>local.conf</code> keeps it from the cloud.
        </Unavailable>
      )}
      {state.adopted != null ? (
        <Adopted adopted={state.adopted} state={state} />
      ) : state.adoption != null ? (
        <Adopting adoption={state.adoption} state={state} />
      ) : (
        available && <NotAdopted remote={state.remote === true} />
      )}
      {state.adopted != null && <CloudMoveSection remote={state.remote === true} />}
      <CloudRemoteAccess state={state} />
    </div>
  )
}

/** Adoption alone — the button, or the code while it waits — without the rest of the tab: onboarding's. */
export function CloudAdoptionFlow({ state }: { state: CloudState }) {
  if (state.adoption != null) return <Adopting adoption={state.adoption} state={state} />
  return <NotAdopted remote={state.remote === true} />
}

function Unavailable({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="space-y-1">
      <h3 className="text-sm font-medium">{title}</h3>
      <p className="text-sm text-muted-foreground">{children}</p>
    </section>
  )
}

/**
 * Why an adoption ended: removed in the cloud, refused, expired. Dismissed on this browser only, by
 * the instant the desk set it, since the same words come again for the next one.
 */
function Notice({ notice, noticeAt }: { notice?: string | null; noticeAt?: string | null }) {
  const [dismissed, setDismissed] = usePersistentState<string | null>('lighting7.cloud.dismissedNotice', null)
  const key = noticeAt ?? notice
  if (notice == null || key === dismissed) return null
  return (
    <Alert>
      <TriangleAlert className="h-4 w-4" />
      <AlertDescription className="flex items-start justify-between gap-2">
        <span>{notice}</span>
        <Button variant="ghost" size="sm" className="h-6 w-6 p-0" aria-label="Dismiss" onClick={() => setDismissed(key ?? null)}>
          <X className="h-4 w-4" />
        </Button>
      </AlertDescription>
    </Alert>
  )
}

function NotAdopted({ remote }: { remote: boolean }) {
  const [start, { isLoading, error, reset }] = useStartCloudAdoptionMutation()
  const adopt = async () => {
    reset()
    try {
      await start().unwrap()
    } catch {
      // Shown below.
    }
  }
  return (
    <section className="space-y-3">
      <h3 className="text-sm font-medium">Not adopted</h3>
      <p className="text-sm text-muted-foreground">
        Adopting puts this desk in your organisation in Lighting7 Cloud, so the cloud can see that it is
        online. The desk keeps working exactly as it does now if the cloud is ever unreachable.
      </p>
      <Button onClick={() => void adopt()} disabled={remote || isLoading}>
        {isLoading ? 'Adopting…' : 'Adopt this desk'}
      </Button>
      {remote && (
        <p className="text-xs text-muted-foreground">
          A desk can only be adopted from the desk itself, never over remote access.
        </p>
      )}
      {error != null && <ErrorAlert error={error} />}
    </section>
  )
}

function Adopting({ adoption, state }: { adoption: CloudAdoption; state: CloudState }) {
  const [cancel, { isLoading, error, reset }] = useCancelCloudAdoptionMutation()
  const expiresMs = Date.parse(adoption.expiresAt)
  const now = useNow(true, expiresMs, 1000)
  const remainingMs = expiresMs - now
  const onCancel = async () => {
    reset()
    try {
      await cancel().unwrap()
    } catch {
      // Shown below.
    }
  }
  const code = adoption.userCode
  return (
    <section className="space-y-4">
      <h3 className="text-sm font-medium">Adopting</h3>
      {code != null ? (
        <>
          <p className="text-sm text-muted-foreground">
            Open the link, or scan the code, and enter this code in Lighting7 Cloud to approve the desk.
          </p>
          <div data-sentry-mask className="font-mono text-4xl font-semibold tracking-widest" data-testid="cloud-user-code">
            {code}
          </div>
          {adoption.verificationUriComplete != null && (
            <div data-sentry-block className="inline-flex rounded-md bg-white p-3">
              <QRCode value={adoption.verificationUriComplete} size={160} />
            </div>
          )}
          {adoption.verificationUri != null && (
            <p data-sentry-block className="text-sm">
              <a
                href={adoption.verificationUriComplete ?? adoption.verificationUri}
                target="_blank"
                rel="noreferrer"
                className="underline"
              >
                {adoption.verificationUri}
              </a>
            </p>
          )}
        </>
      ) : (
        <p className="text-sm text-muted-foreground">
          An adoption is waiting for approval. It can only be approved from the desk itself: the code is
          never shown over remote access.
        </p>
      )}
      <p className="text-sm text-muted-foreground">
        {remainingMs > 0 ? `${formatCountdown(remainingMs)} left` : 'Expired'} · key {adoption.fingerprint}
      </p>
      {state.lastError != null && <p className="text-xs text-destructive">{state.lastError.message}</p>}
      <Button variant="outline" onClick={() => void onCancel()} disabled={isLoading}>
        Cancel
      </Button>
      {error != null && <ErrorAlert error={error} />}
    </section>
  )
}

function Adopted({ adopted, state }: { adopted: CloudAdopted; state: CloudState }) {
  const [confirm, setConfirm] = useState(false)
  return (
    <section className="space-y-4">
      <div className="space-y-1">
        <h3 className="text-sm font-medium">Adopted by {adopted.orgName}</h3>
        <ConnectionLine state={state} />
      </div>
      <dl className="grid grid-cols-[max-content_1fr] gap-x-4 gap-y-2 text-sm">
        <dt className="text-muted-foreground">Install</dt>
        <dd>
          <CopyableId value={adopted.installId} />
        </dd>
        <dt className="text-muted-foreground">Adopted</dt>
        <dd>
          {new Date(adopted.adoptedAt).toLocaleString()}
          {adopted.adoptedBy != null ? ` by ${adopted.adoptedBy}` : ''}
        </dd>
        {adopted.linkedEmail != null && (
          <>
            <dt className="text-muted-foreground">Cloud account</dt>
            <dd>{adopted.linkedEmail}</dd>
          </>
        )}
        <dt className="text-muted-foreground">Install key</dt>
        <dd className="font-mono text-xs">{adopted.fingerprint}</dd>
      </dl>
      <Button variant="destructive" onClick={() => setConfirm(true)}>
        Remove from cloud
      </Button>
      <RemoveDialog open={confirm} onOpenChange={setConfirm} orgName={adopted.orgName} />
    </section>
  )
}

/**
 * The connection as `cloud.state` reports it: Online, Connecting, Retrying in *n* s, or Off, with why
 * the last connection ended.
 */
function ConnectionLine({ state }: { state: CloudState }) {
  const connection = state.connection ?? 'OFF'
  const retryMs = state.retryAt != null ? Date.parse(state.retryAt) : undefined
  const now = useNow(connection === 'RETRYING' && retryMs != null, retryMs, 500)
  const tone =
    connection === 'ONLINE' ? 'bg-green-500' : connection === 'OFF' ? 'bg-muted-foreground/40' : 'bg-amber-500'
  let label: string
  switch (connection) {
    case 'ONLINE':
      label = 'Online'
      break
    case 'CONNECTING':
      label = 'Connecting…'
      break
    case 'RETRYING': {
      const s = retryMs != null ? Math.max(0, Math.ceil((retryMs - now) / 1000)) : 0
      label = s > 0 ? `Retrying in ${s} s` : 'Retrying…'
      break
    }
    case 'OFF':
      label = 'Not connected'
      break
  }
  return (
    <div className="space-y-1" data-testid="cloud-connection">
      <div className="flex items-start gap-2 text-sm">
        <span className={cn('mt-1.5 size-2 shrink-0 rounded-full', tone)} aria-hidden />
        <span>{label}</span>
      </div>
      {connection !== 'ONLINE' && state.lastError != null && (
        <p className="text-xs text-muted-foreground">{state.lastError.message}</p>
      )}
    </div>
  )
}

/**
 * *Remove from cloud*: asks the cloud first. When it cannot be reached the desk refuses
 * (`CLOUD_UNREACHABLE`), and the dialog offers to forget the adoption here alone.
 */
function RemoveDialog({
  open,
  onOpenChange,
  orgName,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  orgName: string
}) {
  const [unadopt, { isLoading, error, reset }] = useUnadoptCloudMutation()
  const unreachable = errorCode(error) === 'CLOUD_UNREACHABLE'

  const close = (next: boolean) => {
    if (!next) reset()
    onOpenChange(next)
  }

  const run = async (force: boolean) => {
    try {
      await unadopt({ force }).unwrap()
      close(false)
      toast.success(force ? 'Forgotten on this desk' : `Removed from ${orgName}`)
    } catch {
      // The dialog shows the refusal, and offers the forced arm when the cloud is unreachable.
    }
  }

  return (
    <Dialog open={open} onOpenChange={close}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{unreachable ? 'The cloud couldn’t be told' : `Remove this desk from ${orgName}?`}</DialogTitle>
          <DialogDescription>
            {unreachable
              ? `${formatError(error)} The desk can forget the adoption here alone. ${orgName} keeps a record of it that can never connect again, which you can remove in the portal.`
              : 'The desk forgets its install key and stops connecting. Adopting it again starts afresh.'}
          </DialogDescription>
        </DialogHeader>
        {error != null && !unreachable && <ErrorAlert error={error} />}
        <DialogFooter>
          <Button variant="outline" onClick={() => close(false)}>
            Cancel
          </Button>
          {unreachable ? (
            <Button variant="destructive" disabled={isLoading} onClick={() => void run(true)}>
              Forget on this desk only
            </Button>
          ) : (
            <Button variant="destructive" disabled={isLoading} onClick={() => void run(false)}>
              Remove from cloud
            </Button>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function CopyableId({ value }: { value: string }) {
  const [copied, setCopied] = useState(false)
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(value)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 1500)
    } catch {
      // The clipboard is refused on a plain-http LAN origin; the id is selectable instead.
      toast.error('Could not copy — select the id instead')
    }
  }
  return (
    <span className="flex items-center gap-2">
      <code className="min-w-0 truncate font-mono text-xs select-all">{value}</code>
      <Button variant="outline" size="sm" onClick={() => void copy()} aria-label="Copy install id">
        {copied ? <Check className="h-4 w-4" /> : <Copy className="h-4 w-4" />}
      </Button>
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
