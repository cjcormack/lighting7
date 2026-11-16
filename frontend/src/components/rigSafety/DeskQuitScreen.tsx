import { useState } from 'react'
import { Loader2, Power } from 'lucide-react'
import { RunningLine } from '@/components/cloud/BackupRunLine'
import { Button } from '@/components/ui/button'
import { clockLabel } from '@/lib/rigSafety'
import { useBackupStatusQuery, type BackupStatus } from '@/store/cloud'
import { useQuitDeskMutation } from '@/store/rigSafety'
import { useDeskQuit } from './deskQuit'
import { useNow } from './useNow'

/** How often the screen re-reads the backup a quitting desk is making. */
export const QUIT_BACKUP_POLL_MS = 1000

/** Failed reads in a row, each unable to reach the desk, that mean it has gone. */
export const QUIT_GONE_AFTER = 2

function unreachable(error: unknown): boolean {
  const status = (error as { status?: unknown } | undefined)?.status
  return status === 'FETCH_ERROR' || status === 'TIMEOUT_ERROR'
}

/**
 * What the window that quit the desk shows from then on (venue-visit plan S6): mounted beside the
 * gates in `App`, so the reconnect screens they draw once the socket closes never cover it. A desk
 * quit while its lamps were cooling counts the cool-down out here, since no window hears the desk now.
 * A desk that backs up on quit is followed until it stops answering, with *Quit without waiting*.
 */
export function DeskQuitScreen() {
  const quit = useDeskQuit()
  const coolsAt = quit?.coolsAt ?? null
  const now = useNow(coolsAt != null, coolsAt ?? undefined)
  if (quit == null) return null
  const left = coolsAt == null ? 0 : Math.max(0, coolsAt - now)
  const cooling = coolsAt != null && (
    <p data-testid="quit-screen-cooling" className="max-w-sm text-sm font-medium">
      {left > 0 ? (
        <>
          The lamps are still cooling: leave the rig&apos;s power on for another{' '}
          <span className="tabular-nums">{clockLabel(left)}</span>.
        </>
      ) : (
        'The lamps have cooled: the rig can be switched off.'
      )}
    </p>
  )
  return (
    <div role="alert" className="fixed inset-0 z-[200] flex flex-col items-center justify-center gap-3 bg-background p-6 text-center">
      {quit.backup != null ? (
        <QuitBackup start={quit.backup} since={quit.at} />
      ) : (
        <Quit />
      )}
      {cooling}
    </div>
  )
}

function Quit({ children }: { children?: React.ReactNode }) {
  return (
    <>
      <Power className="size-10 text-muted-foreground" />
      <h1 className="text-xl font-semibold">The desk has quit</h1>
      <p className="max-w-sm text-sm text-muted-foreground">
        The lighting desk has stopped on this computer. You can close this window; start the desk again to carry on.
      </p>
      {children}
    </>
  )
}

/**
 * The quit's own backup, read until the desk stops answering, which is the only sure sign it has gone:
 * [QUIT_GONE_AFTER] reads in a row, each begun after the quit, that could not reach it. A desk answering
 * with an error is still there. A status fetched before [since] predates the quit, so the quit's answer
 * stands in for it.
 */
function QuitBackup({ start, since }: { start: BackupStatus; since: number }) {
  const { data, error, fulfilledTimeStamp, startedTimeStamp, isFetching } = useBackupStatusQuery(undefined, {
    pollingInterval: QUIT_BACKUP_POLL_MS,
  })
  const [quitNow, { isLoading: skipping }] = useQuitDeskMutation()
  const [reads, setReads] = useState<{ counted?: number; failures: number }>({ failures: 0 })
  if (!isFetching && startedTimeStamp != null && startedTimeStamp >= since && startedTimeStamp !== reads.counted) {
    setReads({ counted: startedTimeStamp, failures: unreachable(error) ? reads.failures + 1 : 0 })
  }
  const status = data != null && fulfilledTimeStamp != null && fulfilledTimeStamp >= since ? data : start
  const ok = status.lastKind === 'QUIT' && status.lastOkAt != null && status.lastOkAt !== start.lastOkAt
  const failed = !ok && status.lastFailedAt != null && status.lastFailedAt !== start.lastFailedAt

  if (reads.failures >= QUIT_GONE_AFTER) {
    return (
      <Quit>
        <p data-testid="quit-screen-backup" className={failed ? 'max-w-sm text-sm text-destructive' : 'max-w-sm text-sm'}>
          {ok
            ? 'It backed up to Lighting7 Cloud first.'
            : failed
              ? `Its backup failed: ${status.lastMessage ?? status.lastCode}`
              : 'It quit before its backup finished; an upload under way is finished when the desk next starts.'}
        </p>
      </Quit>
    )
  }
  return (
    <>
      <Loader2 className="size-10 animate-spin text-muted-foreground" />
      <h1 className="text-xl font-semibold">Backing up before the desk quits</h1>
      <p className="max-w-sm text-sm text-muted-foreground">
        A copy of this desk is going to Lighting7 Cloud. The desk stops as soon as it is there, or after 15 minutes at most.
      </p>
      {status.running != null && <RunningLine run={status.running} />}
      <Button
        variant="outline"
        disabled={skipping}
        onClick={() => {
          quitNow({ force: true, skipBackup: true })
            .unwrap()
            .catch(() => {
              // The desk may already have gone: the next read says so.
            })
        }}
      >
        Quit without waiting
      </Button>
    </>
  )
}
