import type { BackupRun } from '@/store/cloud'

/** A running backup's phase in words, for the Cloud tab and the quit screen. */
export function RunningLine({ run }: { run: BackupRun }) {
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
