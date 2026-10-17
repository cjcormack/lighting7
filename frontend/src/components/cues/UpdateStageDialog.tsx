import { useEffect, useRef, useState } from 'react'
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
import { formatError } from '@/lib/formatError'
import { updateStageLines } from '@/lib/updateStage'
import { useUpdateCueStageMutation } from '@/store/cues'
import type { UpdateStageResponse } from '@/api/cuesApi'

/** The cue an *Update stage…* press asks about, named as its opener shows it (`Q4 Storm`). */
export interface UpdateStageTarget {
  projectId: number
  cueId: number
  name: string
}

/**
 * *Update stage…*'s confirm (show-cue-editing plan D4, D11): one per press. It opens on a dry run of
 * `POST cues/{id}/update-stage`, names the cue and the counts it read, and its *Update stage* sends
 * the one real request — the cue's stored rows to the stage over its own fade, its effects brought in
 * line. Events and scenery are not re-fired, which it says. A dry run the desk refuses (the cue went
 * off: `CUE_NOT_LIVE`) is drawn here, with nothing to send.
 *
 * Mounted by `CueActionDialogsProvider`, above every host, since a GO can collapse the card that
 * opened it.
 */
export function UpdateStageDialog({ target, onClose }: { target: UpdateStageTarget | null; onClose: () => void }) {
  const [dryRun] = useUpdateCueStageMutation()
  const [send, { isLoading: sending }] = useUpdateCueStageMutation()
  const [preview, setPreview] = useState<{ counts: UpdateStageResponse } | { error: string } | null>(null)
  const request = useRef(0)

  useEffect(() => {
    setPreview(null)
    if (target == null) return
    const seq = ++request.current
    dryRun({ projectId: target.projectId, cueId: target.cueId, dryRun: true })
      .unwrap()
      .then((counts) => seq === request.current && setPreview({ counts }))
      .catch((e: unknown) => seq === request.current && setPreview({ error: formatError(e) }))
  }, [target, dryRun])

  const confirm = () => {
    if (target == null) return
    send({ projectId: target.projectId, cueId: target.cueId, dryRun: false })
      .unwrap()
      .then(() => toast.success(`Stage updated to ${target.name}`))
      .catch((e: unknown) => toast.error(formatError(e), { id: 'update-stage' }))
    onClose()
  }

  const counts = preview != null && 'counts' in preview ? preview.counts : null
  return (
    <Dialog open={target != null} onOpenChange={(open) => !open && onClose()}>
      <DialogContent data-update-stage-dialog>
        <DialogHeader>
          <DialogTitle>Update stage to {target?.name ?? ''}?</DialogTitle>
        </DialogHeader>
        <DialogDescription>
          The cue was edited since it went live. This sends what it now holds to the stage. Its events do not
          fire again and its scenery does not move.
        </DialogDescription>
        <div className="space-y-1 text-sm" data-update-stage-counts>
          {preview == null ? (
            <p className="text-xs text-muted-foreground">Reading what it would change…</p>
          ) : 'error' in preview ? (
            <p className="text-xs text-destructive">{preview.error}</p>
          ) : (
            updateStageLines(preview.counts).map((line) => <p key={line}>{line}</p>)
          )}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button onClick={confirm} disabled={counts == null || sending}>
            Update stage
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
