import { useEffect } from 'react'
import { AlertTriangle, XCircle } from 'lucide-react'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { formatError } from '@/lib/formatError'
import { useDetachVenueMutation } from '@/store/venues'
import type { DetachResult } from '@/api/venueApi'

/**
 * *Detach from venue…* (venue plan D9; `Projects.dc.html`'s callout 5): flatten venue and show into
 * one standalone project — the archive, and the rare move elsewhere. The confirm reads the detach
 * **rolled back** on the desk (`dryRun`), so what it lists is what the press will do: the venue's
 * records that become the show's own, the overrides written in, the hides that go. A show behind its
 * venue detaches at its pin — what it runs — and the confirm says the hall's later changes stay
 * behind. Both roles: a detach changes only the show. The version it leaves is remembered, which is
 * what *Attach to venue…* matches against three ways. A refusal is drawn here (`detachVenue` is in
 * `SILENT_ENDPOINTS`).
 */
export function DetachVenueDialog({
  open,
  onOpenChange,
  projectId,
  showName,
  venueName,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  projectId: number
  showName: string
  venueName: string
}) {
  const [preview, { data: dry, isLoading: reading, error: previewError, reset: resetPreview }] = useDetachVenueMutation()
  const [detach, { isLoading, error, reset }] = useDetachVenueMutation()

  useEffect(() => {
    if (open) void preview({ projectId, dryRun: true })
  }, [open, projectId, preview])

  const close = (next: boolean) => {
    if (!next) {
      reset()
      resetPreview()
    }
    onOpenChange(next)
  }
  const failure = error ?? previewError
  return (
    <AlertDialog open={open} onOpenChange={close}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Detach {showName} from {venueName}?</AlertDialogTitle>
          <AlertDialogDescription>
            The venue&apos;s rig at the show&apos;s version — overrides applied, hidden records left out — becomes the
            show&apos;s own. The show stops following {venueName}: no update reaches it again, and the venue and its
            versions are not changed.
          </AlertDialogDescription>
        </AlertDialogHeader>
        {dry ? <DetachSummary result={dry} /> : <p className="text-sm text-muted-foreground">{reading ? 'Reading what moves…' : ' '}</p>}
        {failure && (
          <Alert variant="destructive">
            <XCircle className="size-4" />
            <AlertDescription>{formatError(failure)}</AlertDescription>
          </Alert>
        )}
        <AlertDialogFooter>
          <AlertDialogCancel disabled={isLoading}>Cancel</AlertDialogCancel>
          <AlertDialogAction
            disabled={isLoading || dry == null}
            onClick={async (e) => {
              e.preventDefault()
              try {
                await detach({ projectId }).unwrap()
              } catch {
                return
              }
              close(false)
            }}
          >
            {isLoading ? 'Detaching…' : 'Detach'}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}

/** What the dry run says the detach will do, in counts and the lines worth reading. */
function DetachSummary({ result }: { result: DetachResult }) {
  const c = result.copied
  const counts = [
    [c.patches.length, 'fixture'],
    [c.universes.length, 'universe'],
    [c.riggings.length, 'rigging'],
    [c.elements.length, 'scene element'],
    [c.groups.length, 'group'],
    [c.regions.length, 'region'],
    [c.viewpoints.length, 'viewpoint'],
    [c.parks?.length ?? 0, 'parked channel'],
  ] as const
  const listed = counts.filter(([n]) => n > 0).map(([n, noun]) => `${n} ${noun}${n === 1 ? '' : 's'}`)
  const behind = result.behind != null && result.behind !== 'NONE' && result.installed != null
  return (
    <div className="space-y-2 text-sm" aria-label="What the detach does">
      {behind && (
        <Alert>
          <AlertTriangle className="size-4" />
          <AlertDescription>
            This show is on v{result.version}; {result.venueName} is at v{result.installed}
            {result.behind === 'OUTPUT' ? ', which changes output' : ''}. It detaches at v{result.version}, as it runs — the
            venue&apos;s later changes do not come with it.
          </AlertDescription>
        </Alert>
      )}
      <p>
        <span className="font-medium">Becomes the show&apos;s own:</span>{' '}
        {listed.length > 0 ? listed.join(' · ') : 'nothing'} from v{result.version}.
      </p>
      {result.overridesApplied.length > 0 && (
        <p>
          <span className="font-medium">Overrides written in:</span> {result.overridesApplied.join('; ')}.
        </p>
      )}
      {result.hidesDropped > 0 && (
        <p className="text-muted-foreground">
          {result.hidesDropped} hidden record{result.hidesDropped === 1 ? '' : 's'} stay{result.hidesDropped === 1 ? 's' : ''} the venue&apos;s.
        </p>
      )}
      {(result.parkReleasesDropped ?? 0) > 0 && (
        <p className="text-muted-foreground">
          {result.parkReleasesDropped} released venue park{result.parkReleasesDropped === 1 ? '' : 's'} stay{result.parkReleasesDropped === 1 ? 's' : ''} unparked.
        </p>
      )}
      {(result.dropped ?? []).map((item, i) => (
        <p key={i} className="text-muted-foreground">
          {item.name}: {item.detail}
        </p>
      ))}
      <p data-detach-remembered="">
        {result.detachedFrom?.venueName ?? result.venueName} v{result.detachedFrom?.version ?? result.version} will be remembered, so
        the show can be attached again.
      </p>
    </div>
  )
}
