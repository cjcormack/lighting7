import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { useCueDeletePreviewQuery, useDeleteCuesMutation } from '@/store/cues'
import { ignoreReportedError } from '@/store/errorToastMiddleware'
import type { CueUses } from '@/api/cuesApi'

/** A cue the confirm is asked about: what it is called, for a cue nothing names. */
export interface CueToDelete {
  id: number
  name: string
}

const plural = (n: number, word: string) => `${n} ${word}${n === 1 ? '' : 's'}`

/**
 * *Delete…* (lighting7 cue model plan D8): one confirm for one cue or several, listing everything
 * that names each from a dry run of `POST cues/delete` — its entries, busk pads, cue slots,
 * prompt-book anchors and surface bindings — then deleting them all with `force`. A `FireCue`
 * binding is not removed: it stays and reports its cue missing (`FU-CUE-BINDING-SWEEP`), which the
 * confirm says.
 *
 * The Show view's cue actions mount it; the Cues table (cue model plan session 5) is meant to mount
 * the same one.
 */
export function CueDeleteDialog({
  projectId,
  cues,
  onClose,
  onDeleted,
}: {
  projectId: number
  /** The cues asked about, in the order they are listed; null closes the dialog. */
  cues: readonly CueToDelete[] | null
  onClose: () => void
  onDeleted?: (cueIds: number[]) => void
}) {
  const cueIds = cues?.map((c) => c.id) ?? []
  const { data: preview, isFetching: previewing, isError } = useCueDeletePreviewQuery(
    { projectId, cueIds },
    { skip: cues == null || cueIds.length === 0 },
  )
  const [deleteCues, { isLoading: deleting }] = useDeleteCuesMutation()

  const confirm = () => {
    if (cues == null || cues.length === 0) return
    const ids = [...cueIds]
    deleteCues({ projectId, cueIds: ids })
      .unwrap()
      .then(() => {
        toast.success(cues.length === 1 ? `Deleted “${cues[0].name}”` : `Deleted ${plural(cues.length, 'cue')}`)
        onDeleted?.(ids)
      })
      .catch(ignoreReportedError)
    onClose()
  }

  const usesById = new Map((preview?.uses ?? []).map((u) => [u.cueId, u]))
  const bindingCount = (preview?.uses ?? []).reduce((n, u) => n + u.bindings.length, 0)
  const title =
    cues == null ? '' : cues.length === 1 ? `Delete “${cues[0].name}”?` : `Delete ${plural(cues.length, 'cue')}?`

  return (
    <Dialog open={cues != null} onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
        </DialogHeader>
        <DialogDescription>
          {cues != null && cues.length === 1
            ? 'This deletes the cue, and takes everything below that names it with it.'
            : 'This deletes the cues, and takes everything below that names them with them.'}
        </DialogDescription>
        <div className="max-h-[50vh] space-y-2 overflow-y-auto text-sm" data-cue-delete-uses>
          {previewing && preview == null ? (
            <p className="text-xs text-muted-foreground">Checking what uses {cues?.length === 1 ? 'it' : 'them'}…</p>
          ) : isError ? (
            <p className="text-xs text-destructive">Could not check what uses {cues?.length === 1 ? 'it' : 'them'}.</p>
          ) : (
            cues?.map((cue) => <CueUseBlock key={cue.id} name={cue.name} uses={usesById.get(cue.id)} />)
          )}
        </div>
        {bindingCount > 0 && (
          <p className="text-xs text-amber-500" data-binding-note>
            {bindingCount === 1 ? 'The surface binding stays' : `The ${bindingCount} surface bindings stay`}, and will
            report a missing cue until it is rebound.
          </p>
        )}
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button
            variant="destructive"
            onClick={confirm}
            disabled={deleting || previewing || isError || preview == null}
          >
            Delete
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

/** One cue's uses, a line per kind — or that nothing names it. */
function CueUseBlock({ name, uses }: { name: string; uses: CueUses | undefined }) {
  const lines = uses == null ? [] : cueUseLines(uses)
  return (
    <div data-cue-use-block>
      <div className="font-medium">{name}</div>
      {lines.length === 0 ? (
        <div className="text-xs text-muted-foreground">Nothing else names it.</div>
      ) : (
        lines.map((line) => (
          <div key={line} className="text-xs text-muted-foreground" data-cue-use>
            {line}
          </div>
        ))
      )}
    </div>
  )
}

/** What names one cue, a line per kind, in the order the desk lists them. */
export function cueUseLines(uses: CueUses): string[] {
  const entries = uses.entries.map((e) => (e.cueNumber ? `${e.stackName} as Q${e.cueNumber}` : e.stackName))
  const pages = [...new Set(uses.pads.map((p) => p.pageName))]
  return [
    entries.length > 0 ? `In ${entries.join(' · ')}` : null,
    pages.length > 0 ? `${uses.pads.length === 1 ? 'A busk pad' : `${uses.pads.length} busk pads`} on ${pages.join(', ')}` : null,
    ...uses.slots.map((s) => `Cue slot ${s.label}`),
    uses.anchors.length > 0
      ? `${plural(uses.anchors.length, 'prompt-book anchor')}${uses.anchors.some((a) => a.label) ? ` (${uses.anchors.map((a) => a.label).filter(Boolean).join(', ')})` : ''}`
      : null,
    ...uses.bindings.map((b) => `Surface binding ${b.label} — stays, naming no cue`),
  ].filter((line): line is string => line != null)
}
