import { useState } from 'react'
import { Loader2, TriangleAlert } from 'lucide-react'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { formatError } from '@/lib/formatError'
import {
  cloudApi,
  useBackupStatusQuery,
  useRunBackupMutation,
  useSetBackupSettingsMutation,
  type BackupRun,
  type BackupStatus,
  type CloudState,
} from '@/store/cloud'
import { LOCAL_ONLY_REASON, SwitchRow } from './CloudRemoteAccess'

/** How often the section re-reads the status while a backup runs: no frame announces its progress. */
export const BACKUP_POLL_MS = 2000
/** How often it re-reads while none runs, to see one start from the schedule, the portal or another window. */
export const BACKUP_IDLE_POLL_MS = 30_000

const KIND_LABEL: Record<string, string> = { SCHEDULED: 'Nightly', REQUESTED: 'From the portal', MANUAL: 'From this desk' }

export const REMOTE_OFF_REASON =
  'Over remote access a backup can be started only while Back up every night is on: what leaves through the cloud is the desk’s decision.'

export function formatSize(bytes: number): string {
  const mb = bytes / (1024 * 1024)
  if (mb >= 1024) return `${(mb / 1024).toFixed(1)} GB`
  if (mb >= 1) return `${mb.toFixed(1)} MB`
  return `${Math.max(1, Math.round(bytes / 1024))} KB`
}

/**
 * The Cloud tab's *Off-site backups* section (lighting7 cloud-p3 plan D6): the switch, the time each
 * night's backup runs, *Back up now*, a backup running and the last result. The settings change only
 * on the desk's own listener; over remote access it reads, and *Back up now* works only with the switch on.
 */
export function CloudBackups({ state }: { state: CloudState }) {
  const cached = cloudApi.endpoints.backupStatus.useQueryState(undefined)
  const { data: status, error: loadError } = useBackupStatusQuery(undefined, {
    pollingInterval: cached.data?.running != null ? BACKUP_POLL_MS : BACKUP_IDLE_POLL_MS,
  })
  const isRunning = status?.running != null
  const [save, { isLoading: saving, error: saveError, reset: resetSave }] = useSetBackupSettingsMutation()
  const [run, { isLoading: starting, error: runError, reset: resetRun }] = useRunBackupMutation()
  const remote = state.remote === true
  const remoteOff = remote && status?.enabled !== true

  if (status == null) {
    return (
      <section className="space-y-2">
        <h3 className="text-sm font-medium">Off-site backups</h3>
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

  const set = (body: { enabled?: boolean; dailyAt?: string }) => {
    resetSave()
    save(body).unwrap().catch(() => {
      // Shown below.
    })
  }
  const backUpNow = () => {
    resetRun()
    run()
      .unwrap()
      .catch(() => {
        // Shown below.
      })
  }

  return (
    <section className="space-y-4" data-testid="cloud-backups">
      <div className="space-y-1">
        <h3 className="text-sm font-medium">Off-site backups</h3>
        {remote && <p className="text-xs text-muted-foreground">{LOCAL_ONLY_REASON}</p>}
      </div>
      <SwitchRow
        label="Back up every night"
        checked={status.enabled}
        disabled={remote || saving}
        onChange={(on) => set({ enabled: on })}
      >
        A copy of this whole desk — every show, its accounts and its settings — encrypted here under{' '}
        {state.adopted?.orgName ?? 'the cloud'}’s key and kept by Lighting7 Cloud, so a new machine can be
        brought back from it. It waits for the show to be quiet, for up to six hours.
      </SwitchRow>
      {status.enabled && (
        <DailyAt
          key={status.dailyAt}
          value={status.dailyAt}
          disabled={remote || saving}
          nextAt={status.nextAt}
          onSave={(dailyAt) => set({ dailyAt })}
        />
      )}
      {saveError != null && <ErrorAlert error={saveError} />}

      <div className="flex flex-wrap items-center gap-3">
        <Button variant="outline" onClick={backUpNow} disabled={isRunning || starting || remoteOff}>
          {isRunning || starting ? <Loader2 className="mr-2 h-4 w-4 animate-spin" /> : null}
          Back up now
        </Button>
        {status.running != null && <RunningLine run={status.running} />}
      </div>
      {remoteOff && <p className="text-xs text-muted-foreground">{REMOTE_OFF_REASON}</p>}
      {runError != null && <ErrorAlert error={runError} />}
      <LastResult status={status} />
    </section>
  )
}

function DailyAt({
  value,
  disabled,
  nextAt,
  onSave,
}: {
  value: string
  disabled: boolean
  nextAt?: string | null
  onSave: (dailyAt: string) => void
}) {
  const [draft, setDraft] = useState(value)
  return (
    <div className="space-y-1">
      <label className="flex items-center gap-3 text-sm">
        <span>Each night at</span>
        <Input
          type="time"
          className="w-32"
          value={draft}
          disabled={disabled}
          aria-label="Each night at"
          onChange={(e) => setDraft(e.target.value)}
          onBlur={() => {
            if (draft !== value && /^\d{2}:\d{2}$/.test(draft)) onSave(draft)
          }}
        />
      </label>
      {nextAt != null && (
        <p className="text-xs text-muted-foreground">Next: {new Date(nextAt).toLocaleString()}</p>
      )}
    </div>
  )
}

function RunningLine({ run }: { run: BackupRun }) {
  let words: string
  switch (run.phase) {
    case 'WAITING':
      words = 'Waiting for the show to go quiet…'
      break
    case 'MAKING':
      words = 'Making the backup…'
      break
    case 'UPLOADING':
      words =
        run.parts != null && run.parts > 1
          ? `Uploading, part ${Math.min((run.partsSent ?? 0) + 1, run.parts)} of ${run.parts}…`
          : 'Uploading…'
      break
    default:
      words = 'Backing up…'
  }
  return (
    <span className="text-sm text-muted-foreground" data-testid="cloud-backup-running">
      {words}
    </span>
  )
}

function LastResult({ status }: { status: BackupStatus }) {
  const failed = status.lastCode != null
  return (
    <div className="space-y-1 text-sm" data-testid="cloud-backup-last">
      {status.lastOkAt != null ? (
        <p>
          Last backup {new Date(status.lastOkAt).toLocaleString()}
          {status.lastSizeBytes != null ? ` · ${formatSize(status.lastSizeBytes)}` : ''}
          {status.lastKind != null ? ` · ${KIND_LABEL[status.lastKind] ?? status.lastKind}` : ''}
        </p>
      ) : (
        !failed && <p className="text-muted-foreground">No backup made yet.</p>
      )}
      {failed && (
        <p className="text-destructive">
          The last backup failed{status.lastFailedAt != null ? ` ${new Date(status.lastFailedAt).toLocaleString()}` : ''}:{' '}
          {status.lastMessage ?? status.lastCode}
        </p>
      )}
    </div>
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
