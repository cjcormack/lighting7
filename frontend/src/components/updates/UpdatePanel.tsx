import { useEffect, useState } from 'react'
import { CheckCircle2, Download, Loader2, RefreshCw, TriangleAlert } from 'lucide-react'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { formatError } from '@/lib/formatError'
import {
  useCancelUpdateDownloadMutation,
  useCheckForUpdateMutation,
  useSetUpdateSettingsMutation,
  useStartUpdateDownloadMutation,
  useUpdateStatusQuery,
} from '@/store/updates'
import { useAuthStatusQuery } from '@/store/auth'
import { ApplyUpdateDialog } from './ApplyUpdateDialog'

const MB = 1_000_000

function formatBytes(bytes: number | null | undefined): string {
  if (bytes == null) return '—'
  return `${(bytes / MB).toFixed(1)} MB`
}

function formatWhen(ms: number | null | undefined): string {
  if (ms == null) return 'never'
  return new Date(ms).toLocaleString()
}

/**
 * The Updates tab.
 *
 * Visible to everyone — an operator should be able to see what version the desk is on and why it
 * might be about to restart — with every action disabled for non-admins. The backend enforces the
 * same split per route; this is presentation.
 *
 * There is deliberately **no toast and no modal** when an update appears. A desk operator mid-show
 * must not be interrupted by a nag; discovery is a quiet marker on the settings nav item.
 */
export function UpdatePanel() {
  // Safety net for the machine socket: `emitMachineEvent` uses `tryEmit` on a buffered flow,
  // which *drops* frames when a slow consumer fills it. A lost progress tick is harmless — the
  // next one supersedes it — but a lost phase transition would strand this panel showing a
  // download that finished minutes ago. Poll only while something is actually in flight.
  const [pollingInterval, setPollingInterval] = useState(0)
  const { data: status, isLoading, error: statusError } = useUpdateStatusQuery(undefined, {
    pollingInterval,
  })
  const busy = isBusyPhase(status?.phase)
  useEffect(() => {
    setPollingInterval(busy ? 5000 : 0)
  }, [busy])

  const { data: authStatus } = useAuthStatusQuery()
  const isAdmin = authStatus?.user?.role === 'ADMIN'

  const [checkForUpdate, checkState] = useCheckForUpdateMutation()
  const [startDownload, downloadState] = useStartUpdateDownloadMutation()
  const [cancelDownload, cancelState] = useCancelUpdateDownloadMutation()
  const [setSettings, settingsState] = useSetUpdateSettingsMutation()
  const [applyOpen, setApplyOpen] = useState(false)

  if (isLoading) {
    return (
      <div className="flex items-center gap-2 text-sm text-muted-foreground">
        <Loader2 className="h-4 w-4 animate-spin" /> Loading update status…
      </div>
    )
  }

  // A failed status query leaves `isLoading` false with no data. Without this branch the panel
  // would sit on the spinner forever, which reads as a hang rather than as the reachable problem
  // it usually is (an older backend that has no /update route yet).
  if (status == null) {
    return (
      <Alert variant="destructive">
        <TriangleAlert className="h-4 w-4" />
        <AlertDescription>
          {statusError != null
            ? formatError(statusError)
            : 'Could not read the update status from this desk.'}
        </AlertDescription>
      </Alert>
    )
  }

  // Every mutation the panel owns has to be represented here. All four are in SILENT_ENDPOINTS on
  // the grounds that this panel reports their outcome itself — so one omitted from this chain is
  // a failure with no toast *and* no inline alert, i.e. a Cancel that silently didn't cancel.
  const actionError =
    checkState.error ?? downloadState.error ?? cancelState.error ?? settingsState.error

  return (
    <div className="space-y-6">
      <section className="space-y-1">
        <h3 className="text-sm font-medium">This install</h3>
        <p className="text-sm text-muted-foreground">
          Version <span className="font-mono">{status.currentVersion}</span>
          {status.currentCommit && <> · build <span className="font-mono">{status.currentCommit}</span></>}
        </p>
      </section>

      {status.lastApplyOutcome && !status.lastApplyOutcome.succeeded && (
        <Alert variant="destructive">
          <TriangleAlert className="h-4 w-4" />
          <AlertDescription>{status.lastApplyOutcome.message}</AlertDescription>
        </Alert>
      )}
      {status.lastApplyOutcome?.succeeded && (
        <Alert>
          <CheckCircle2 className="h-4 w-4" />
          <AlertDescription>{status.lastApplyOutcome.message}</AlertDescription>
        </Alert>
      )}

      {status.channel === 'DEV' && (
        <Alert>
          <AlertDescription>
            This is a development build, so it doesn&apos;t update itself. Installed releases check
            GitHub for new versions.
          </AlertDescription>
        </Alert>
      )}

      {status.channel === 'UNSUPPORTED_PLATFORM' && (
        <Alert>
          <AlertDescription>
            In-app updates are Windows-only for now. You can still download the latest release
            from{' '}
            <a className="underline" href={status.latest?.htmlUrl ?? '#'} target="_blank" rel="noreferrer">
              GitHub
            </a>
            .
          </AlertDescription>
        </Alert>
      )}

      {status.channel === 'PACKAGED_WINDOWS' && (
        <section className="space-y-4">
          <div className="flex items-center justify-between gap-4">
            <div className="space-y-1">
              <h3 className="text-sm font-medium">Updates</h3>
              <p className="text-sm text-muted-foreground">
                Last checked {formatWhen(status.lastCheckedAtMs)}
                {status.throttled && ' (just checked — showing the cached result)'}
              </p>
            </div>
            <Button
              variant="outline"
              onClick={() => checkForUpdate()}
              disabled={!isAdmin || checkState.isLoading || status.phase === 'DOWNLOADING'}
            >
              {checkState.isLoading ? (
                <Loader2 className="mr-2 h-4 w-4 animate-spin" />
              ) : (
                <RefreshCw className="mr-2 h-4 w-4" />
              )}
              Check now
            </Button>
          </div>

          {status.lastCheckError && (
            <Alert variant="destructive">
              <AlertDescription>{status.lastCheckError}</AlertDescription>
            </Alert>
          )}

          {status.availability === 'UP_TO_DATE' && status.phase !== 'READY_TO_APPLY' && (
            <p className="text-sm text-muted-foreground">
              lighting7 is up to date.
            </p>
          )}

          {status.availability === 'AHEAD' && (
            <p className="text-sm text-muted-foreground">
              This build is newer than the latest published release.
            </p>
          )}

          {status.availability === 'UNKNOWN' && status.lastCheckedAtMs != null && (
            <p className="text-sm text-muted-foreground">
              Couldn&apos;t work out whether an update applies to this build.
            </p>
          )}

          {status.latest && status.availability === 'UPDATE_AVAILABLE' && (
            <div className="rounded-md border p-4 space-y-3">
              <div className="flex items-baseline justify-between gap-4">
                <div>
                  <p className="font-medium">
                    {status.latest.name ?? status.latest.tag}{' '}
                    <span className="text-muted-foreground font-normal">is available</span>
                  </p>
                  <p className="text-sm text-muted-foreground">
                    Published {formatWhen(status.latest.publishedAtMs)}
                    {status.latest.assetSizeBytes != null &&
                      ` · ${formatBytes(status.latest.assetSizeBytes)} download`}
                  </p>
                </div>
                <a
                  className="text-sm underline shrink-0"
                  href={status.latest.htmlUrl}
                  target="_blank"
                  rel="noreferrer"
                >
                  Release page
                </a>
              </div>

              {/* Plain text, never markdown or HTML: these notes are untrusted text fetched
                  from the internet and rendered into the desk's own UI. */}
              {status.latest.notes && (
                <pre className="max-h-48 overflow-y-auto whitespace-pre-wrap break-words rounded bg-muted p-3 text-sm font-sans">
                  {status.latest.notes}
                </pre>
              )}

              {status.phase !== 'DOWNLOADING' && status.phase !== 'READY_TO_APPLY' && (
                <Button onClick={() => startDownload()} disabled={!isAdmin}>
                  <Download className="mr-2 h-4 w-4" />
                  Download update
                </Button>
              )}
            </div>
          )}

          {status.phase === 'DOWNLOADING' && (
            <div className="space-y-2">
              <div className="h-2 w-full overflow-hidden rounded bg-muted">
                <div
                  className="h-full bg-primary transition-[width]"
                  style={{
                    width:
                      status.totalBytes && status.totalBytes > 0
                        ? `${Math.min(100, (status.downloadedBytes / status.totalBytes) * 100)}%`
                        : '0%',
                  }}
                />
              </div>
              <div className="flex items-center justify-between gap-4">
                <p className="text-sm text-muted-foreground">
                  Downloading {formatBytes(status.downloadedBytes)} of {formatBytes(status.totalBytes)}
                </p>
                <Button variant="outline" onClick={() => cancelDownload()} disabled={!isAdmin}>
                  Cancel
                </Button>
              </div>
            </div>
          )}

          {status.phase === 'READY_TO_APPLY' && (
            <div className="rounded-md border p-4 space-y-3">
              <p className="text-sm">
                {/* Falls back to the advertised version: the WS frame flips the phase to
                    READY_TO_APPLY immediately, but `stagedVersion` only arrives on the refetch
                    that same frame triggers — so without this the sentence renders with the
                    version missing for a frame after every download. */}
                <span className="font-medium">
                  lighting7 {status.stagedVersion ?? status.latest?.version}
                </span>{' '}
                has been
                downloaded and its checksum verified. Installing it restarts the desk.
              </p>
              <Button onClick={() => setApplyOpen(true)} disabled={!isAdmin}>
                Install and restart
              </Button>
            </div>
          )}

          {status.phase === 'APPLY_REQUESTED' && (
            <Alert>
              <Loader2 className="h-4 w-4 animate-spin" />
              <AlertDescription>
                Installing. This window will disconnect in a moment; lighting7 reopens on its own
                when the installer finishes.
              </AlertDescription>
            </Alert>
          )}

          {status.error && status.phase === 'FAILED' && (
            <Alert variant="destructive">
              <TriangleAlert className="h-4 w-4" />
              <AlertDescription>{status.error.message}</AlertDescription>
            </Alert>
          )}

          {actionError != null && (
            <Alert variant="destructive">
              <AlertDescription>{formatError(actionError)}</AlertDescription>
            </Alert>
          )}

          <label className="flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              checked={status.autoCheckEnabled}
              disabled={!isAdmin}
              onChange={(e) => setSettings({ autoCheckEnabled: e.target.checked })}
            />
            Check for updates automatically
          </label>
        </section>
      )}

      {!isAdmin && status.channel === 'PACKAGED_WINDOWS' && (
        <p className="text-sm text-muted-foreground">
          Installing updates requires an administrator account.
        </p>
      )}

      <ApplyUpdateDialog open={applyOpen} onOpenChange={setApplyOpen} status={status} />
    </div>
  )
}

function isBusyPhase(phase: string | undefined): boolean {
  return phase === 'CHECKING' || phase === 'DOWNLOADING' || phase === 'APPLY_REQUESTED'
}
