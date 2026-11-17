import { useState } from 'react'
import { Loader2, RotateCcw, TriangleAlert } from 'lucide-react'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { formatError } from '@/lib/formatError'
import {
  cloudApi,
  useConfirmRestoreMutation,
  useDeclineRestoreMutation,
  useRestoreStatusQuery,
  type CloudState,
  type RestoreStatus,
} from '@/store/cloud'
import { formatSize } from './CloudBackups'

/** How often the section re-reads a confirm under way: no frame announces its progress. */
export const RESTORE_POLL_MS = 2000

export const RESTORE_LOCAL_ONLY_REASON =
  'A restore replaces everything on this desk, so it can only be confirmed at the desk itself.'

const PHASE_WORDS: Record<string, string> = {
  DOWNLOADING: 'Downloading the backup…',
  VERIFYING: 'Checking every file in the backup…',
  REPLACING: 'Taking over the old install in the cloud…',
}

/**
 * Whether the Cloud tab (or onboarding) mounts the restore section: on an adopted desk, which may have a
 * restore pending or a last confirm that stopped, and on one waiting for the restart.
 */
export function hasRestore(state: CloudState): boolean {
  return state.adopted != null || state.restartToRestore === true
}

/**
 * What the adoption's approval asked this desk to restore (lighting7 cloud-p3 plan D9): replace an
 * install of the org, restore one of its backups, or both. Nothing moves until an admin at the desk
 * types the old name and confirms; a confirmed backup waits for the desk's restart, which applies it,
 * and can still be declined until then.
 */
export function CloudRestore({ state }: { state: CloudState }) {
  const busy = (status?: RestoreStatus) => status != null && status.phase !== 'IDLE' && status.phase !== 'READY'
  const cached = cloudApi.endpoints.restoreStatus.useQueryState(undefined)
  const pending = state.adopted?.restore
  const { data: status, error: loadError } = useRestoreStatusQuery(undefined, {
    skip: !hasRestore(state),
    pollingInterval: busy(cached.data) ? RESTORE_POLL_MS : 0,
  })
  const [confirm, { isLoading: confirming, error: confirmError, reset: resetConfirm }] = useConfirmRestoreMutation()
  const [decline, { isLoading: declining, error: declineError, reset: resetDecline }] = useDeclineRestoreMutation()
  const [typed, setTyped] = useState('')
  const remote = state.remote === true

  const resetAll = () => {
    resetConfirm()
    resetDecline()
  }
  const go = () => {
    resetAll()
    confirm({ name: typed })
      .unwrap()
      .catch(() => undefined)
  }
  const no = () => {
    resetAll()
    decline()
      .unwrap()
      .catch(() => undefined)
  }
  const errors = (list: unknown[]) =>
    list.map(
      (error, i) =>
        error != null && (
          <Alert variant="destructive" key={i}>
            <TriangleAlert className="h-4 w-4" />
            <AlertDescription>{formatError(error)}</AlertDescription>
          </Alert>
        ),
    )
  const lastError = status?.lastError != null && (
    <Alert variant="destructive" data-testid="cloud-restore-error">
      <TriangleAlert className="h-4 w-4" />
      <AlertDescription>{status.lastError.message}</AlertDescription>
    </Alert>
  )

  if (state.restartToRestore === true) {
    return (
      <div className="space-y-3">
        <Alert data-testid="cloud-restore-restart">
          <RotateCcw className="h-4 w-4" />
          <AlertTitle>Restart the desk to finish the restore</AlertTitle>
          <AlertDescription className="space-y-2">
            <p>
              The backup is downloaded and checked. Quit the desk and start it again: it comes back as the old
              desk, with its shows, its settings and its accounts, and everyone signs in again.
            </p>
            <Button variant="outline" size="sm" onClick={no} disabled={declining}>
              Don’t restore
            </Button>
          </AlertDescription>
        </Alert>
        {errors([declineError])}
      </div>
    )
  }
  if (pending == null) {
    if (status?.lastError == null) return null
    return (
      <section className="space-y-2 rounded-md border p-4" data-testid="cloud-restore">
        <h3 className="text-sm font-medium">The restore stopped</h3>
        {lastError}
      </section>
    )
  }

  const backup = status?.backup
  const confirmName = status?.confirmName ?? pending.replacesName ?? null
  const working = busy(status) || confirming
  const matches = confirmName != null && typed.trim() === confirmName.trim()
  const when = backup != null ? new Date(backup.createdAt).toLocaleString() : null
  let title: string
  let action: string
  if (pending.replacesInstallId != null && pending.backupId != null) {
    title = `Replace “${pending.replacesName}” and restore its backup${when != null ? ` of ${when}` : ''}`
    action = 'Replace and restore'
  } else if (pending.replacesInstallId != null) {
    title = `Replace “${pending.replacesName}”`
    action = 'Replace'
  } else {
    title = `Restore the backup${when != null ? ` of ${when}` : ''}`
    action = 'Restore'
  }

  return (
    <section className="space-y-3 rounded-md border p-4" data-testid="cloud-restore">
      <div className="space-y-1">
        <h3 className="text-sm font-medium">{title}</h3>
        <p className="text-sm text-muted-foreground">
          {pending.replacesInstallId != null
            ? 'This desk takes over the old install in the cloud: its MCP URL, its remote sessions and its links. The old machine, if it is still on, is left unadopted.'
            : 'This desk keeps its own place in the cloud, and its accounts link again afterwards.'}{' '}
          {pending.backupId != null
            ? 'Everything on this desk — its shows, settings and accounts — is replaced by the backup’s, once the desk restarts.'
            : 'Nothing on this desk changes.'}
        </p>
        {backup != null && (
          <p className="text-xs text-muted-foreground">
            Backup of {backup.installName} · {formatSize(backup.sizeBytes)} · made by desk {backup.deskVersion}
          </p>
        )}
      </div>
      {backup?.tooNew === true && (
        <Alert variant="destructive">
          <TriangleAlert className="h-4 w-4" />
          <AlertDescription>That backup was made by a newer desk. Update this desk first, then restore it.</AlertDescription>
        </Alert>
      )}
      {remote ? (
        <p className="text-xs text-muted-foreground">{RESTORE_LOCAL_ONLY_REASON}</p>
      ) : (
        confirmName != null && (
          <label className="block space-y-1 text-sm">
            <span>
              Type <strong>{confirmName}</strong> to confirm
            </span>
            <Input value={typed} disabled={working} onChange={(e) => setTyped(e.target.value)} aria-label="Confirm name" />
          </label>
        )
      )}
      <div className="flex flex-wrap items-center gap-3">
        {!remote && (
          <Button variant="destructive" onClick={go} disabled={!matches || working || declining || backup?.tooNew === true}>
            {working ? <Loader2 className="mr-2 h-4 w-4 animate-spin" /> : null}
            {action}
          </Button>
        )}
        <Button variant="outline" onClick={no} disabled={working || declining}>
          Decline
        </Button>
        {status != null && PHASE_WORDS[status.phase] != null && (
          <span className="text-sm text-muted-foreground" data-testid="cloud-restore-phase">
            {PHASE_WORDS[status.phase]}
          </span>
        )}
      </div>
      {errors([loadError, confirmError, declineError])}
      {lastError}
    </section>
  )
}
