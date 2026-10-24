import { useCallback, useEffect, useRef, useState } from 'react'
import { Loader2, XCircle } from 'lucide-react'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { formatError } from '@/lib/formatError'
import { AUTO_CUE_NUMBER_CLASS } from '@/lib/cueNumber'
import { useUpdateProgrammerMutation } from '@/store/programmerOps'
import type {
  ChecklistCue,
  ProgrammerConflict,
  UpdateChecklist,
} from '@/store/programmerOps'
import { MaskPicker, maskIsEmpty, maskToWire, maskValueKey, type MaskValue } from './maskPicker'
import { useWindowMaskReader } from '@/hooks/useWindowMaskReader'
import type { IncludedTarget } from '@/api/programmerWsApi'
import { describeIncludedTarget, includedTargetKey } from '@/lib/includedTarget'
import { announceWrite, updateNote } from './writeToasts'

export interface UpdateDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  projectId: number
  /** What Include last loaded. Present ⇒ Mode A; absent ⇒ the Mode B checklist. */
  includeTarget: IncludedTarget | null
}

/**
 * Write the programmer back into a cue.
 *
 * Two shapes behind one button, matching the console idiom:
 *
 * - **Mode A** — a cue was Included, so this is a confirmation naming it. Only the values the
 *   operator actually changed since Include are written, which is what leaves palette
 *   references the operator didn't touch as references; a value cleared since Include has its row
 *   deleted.
 * - **Mode B** — nothing was Included, so the server answers with the provenance-derived list
 *   of cues the programmer is currently sitting on top of, grouped by stack, and the operator
 *   picks which to write.
 *
 * A Dialog rather than a Sheet: both shapes are "confirm what this will overwrite".
 *
 * **It takes a mask, like Record and Include** (venue-visit plan S10): the families the write may
 * touch, sent as `mask`, absent for every attribute. In Mode B the mask also decides which cues the
 * programmer is over, so changing it fetches the checklist again under the new one.
 */
export function UpdateDialog({ open, onOpenChange, projectId, includeTarget }: UpdateDialogProps) {
  const [update, { isLoading, error, reset }] = useUpdateProgrammerMutation()
  const [checklist, setChecklist] = useState<UpdateChecklist | null>(null)
  const [selected, setSelected] = useState<ReadonlySet<number>>(new Set())
  const [conflict, setConflict] = useState<ProgrammerConflict | null>(null)
  // Set by a write that landed, until the next open: the dialog animates out still mounted, and its
  // Update must not take a second press meanwhile.
  const [done, setDone] = useState(false)
  const [mask, setMask] = useState<MaskValue>(null)
  const maskKey = maskValueKey(mask)
  // The selection's mask, read once on open (apply-model plan D3). A change here is this Update's
  // own and never moves the selection.
  const readSelectionMask = useWindowMaskReader()
  // Cues the operator unticked, kept across a checklist re-fetched under a new mask so narrowing
  // the mask does not tick them again behind their back.
  const untickedRef = useRef<Set<number>>(new Set())
  // Each request's turn: a checklist answered after a newer request was sent is stale (the mask
  // moved while it was in flight) and is dropped rather than drawn over the newer one.
  const requestSeqRef = useRef(0)

  const run = useCallback(
    async (body: { targets?: number[]; preview?: boolean; mask?: ReturnType<typeof maskToWire> }) => {
      setConflict(null)
      const seq = ++requestSeqRef.current
      try {
        const response = await update({ projectId, ...body }).unwrap()
        if (response.mode === 'CHECKLIST') {
          if (seq !== requestSeqRef.current) return response
          setChecklist(response.checklist ?? null)
          // Preselect everything: the operator opened Update meaning to write, and unticking
          // is a smaller gesture than ticking each cue.
          setSelected(
            new Set(
              (response.checklist?.stacks ?? [])
                .flatMap((s) => s.cues.map((c) => c.cueId))
                .filter((cueId) => !untickedRef.current.has(cueId)),
            ),
          )
        } else if (response.applied || response.mode === 'A') {
          // Mode A closes even when nothing changed, and says so: left open, it reads as stuck.
          setDone(true)
          announceWrite(updateNote(response))
          onOpenChange(false)
        }
        return response
      } catch (err) {
        const data = (err as { data?: ProgrammerConflict })?.data
        if (data?.code === 'INCLUDE_TARGET_GONE') setConflict(data)
        return null
      }
    },
    [projectId, update, onOpenChange],
  )

  // Runs on open, and again if the dialog's *mode* flips underneath it — not on every render
  // with changed deps. RTK Query's `reset` gets a new identity whenever the mutation's state
  // changes, so an effect that both depends on it and calls it re-fires forever, which for the
  // checklist fetch is an actual request loop.
  //
  // The mode is part of the key rather than just `open` because the programmer is shared: a
  // Clear from another tab (or a MIDI surface) drops the include target while this dialog is
  // open, flipping it from Mode A to Mode B. Keying on `open` alone left it showing a Mode B
  // frame with no checklist behind it and a permanently disabled button.
  const modeB = !includeTarget
  const runKey = open ? includedTargetKey(includeTarget) : null
  const appliedRunKeyRef = useRef<string | null>(null)
  // The mask the checklist on screen was fetched under — null when none is, so the effect below
  // fetches it. One request per (open, mode, mask), never one per render.
  const checklistMaskKeyRef = useRef<string | null>(null)
  useEffect(() => {
    if (runKey === null) {
      appliedRunKeyRef.current = null
      // The mask is this opening's own, like Record's: a closed dialog forgets it. A mode flip
      // while open keeps it — the operator is still narrowing the same write.
      setMask(null)
      untickedRef.current = new Set()
      return
    }
    if (appliedRunKeyRef.current === runKey) return
    // Opening, as against a mode flip while open: the dialog starts on the selection's mask. The
    // checklist fetch below runs in this same commit, still under the closed dialog's mask, so when
    // the seed moves it the fetch is told the closed mask's checklist is already asked — it then
    // waits one render and asks under the seed, rather than asking twice.
    if (appliedRunKeyRef.current === null) {
      setDone(false)
      const seed = readSelectionMask()
      setMask(seed)
      checklistMaskKeyRef.current = maskValueKey(seed) === maskKey ? null : maskKey
    } else {
      checklistMaskKeyRef.current = null
    }
    appliedRunKeyRef.current = runKey
    setChecklist(null)
    setSelected(new Set())
    setConflict(null)
    untickedRef.current = new Set()
    reset()
    // `maskKey` is read as the mask the closed dialog holds, never a reason to re-run.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [runKey, reset, readSelectionMask])

  // With no include target there is nothing to confirm yet — fetch the checklist so the dialog
  // opens onto the actual decision rather than a spinner behind a button press. Fetched again when
  // the mask changes, since the mask decides which cues the programmer is over; keyed on the mask's
  // one spelling, so re-picking the same families is no request.
  useEffect(() => {
    // Nothing ticked asks nothing: the checklist on screen stays, and Update is disabled until a
    // family is ticked again.
    if (runKey === null || !modeB || maskIsEmpty(mask)) return
    if (checklistMaskKeyRef.current === maskKey) return
    checklistMaskKeyRef.current = maskKey
    // `preview`, so the fetch can never write: the desk takes a request with no targets as Mode A's
    // write whenever it holds an include target, and this tab may not have heard of one yet.
    void run({ preview: true, mask: maskToWire(mask) })
    // `mask` is read for the request; `maskKey` is its identity.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [maskKey, runKey, modeB, run])

  const toggle = (cueId: number) => {
    setSelected((prev) => {
      const next = new Set(prev)
      if (next.has(cueId)) {
        next.delete(cueId)
        untickedRef.current.add(cueId)
      } else {
        next.add(cueId)
        untickedRef.current.delete(cueId)
      }
      return next
    })
  }

  const canCommit = (modeB ? selected.size > 0 : true) && !maskIsEmpty(mask)

  // Nothing is offered as a retry: `INCLUDE_TARGET_GONE` is terminal — the cue or Look Include
  // staged has been deleted, so there is nowhere to write it back to. The recoverable arm was
  // `CUE_EDIT_SESSION_OPEN`, retired with the sessions in backend sweep item D1.
  const sentMask = maskToWire(mask)
  const commit = () => {
    if (done) return
    void run(modeB ? { targets: [...selected], mask: sentMask } : { mask: sentMask })
  }

  return (
    <Dialog open={open} onOpenChange={(next) => !isLoading && onOpenChange(next)}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>Update</DialogTitle>
          <DialogDescription>
            {includeTarget
              ? `Write your changes back into ${describeIncludedTarget(includeTarget)}.`
              : 'Nothing is included, so these are the cues the programmer is currently overriding.'}
          </DialogDescription>
        </DialogHeader>

        <div className="max-h-[50vh] space-y-4 overflow-y-auto">
          <MaskPicker
            value={mask}
            onChange={setMask}
            hint="It narrows the values written back; held scenery goes back whole."
          />

          {includeTarget && (
            <p className="text-sm text-muted-foreground">
              Only the values you changed since including are written — anything you left alone
              keeps whatever the cue already stored, palette references included. A value you
              cleared since including is deleted from it. Scenery the programmer holds goes back
              too, a cue’s own clock kept.
            </p>
          )}

          {modeB && checklist && <ChecklistView checklist={checklist} selected={selected} onToggle={toggle} />}

          {/* Only when there is genuinely nothing to say. With unattributed keys the
              checklist's own "Not from any cue" block already explains the situation, and
              repeating it underneath reads as two different findings. */}
          {modeB && checklist && checklist.stacks.length === 0 && checklist.unattributed.length === 0 && (
            <p className="text-sm text-muted-foreground">
              The programmer isn’t overriding any cue. Use Record to make a new cue instead.
            </p>
          )}

          {conflict && (
            <Alert variant="destructive">
              <XCircle className="size-4" />
              <AlertDescription>{conflict.error}</AlertDescription>
            </Alert>
          )}

          {error != null && !conflict && (
            <Alert variant="destructive">
              <XCircle className="size-4" />
              <AlertDescription>{formatError(error)}</AlertDescription>
            </Alert>
          )}
        </div>

        <DialogFooter className="flex-row justify-end gap-2">
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={isLoading}>
            Cancel
          </Button>
          <Button onClick={() => commit()} disabled={!canCommit || isLoading || done}>
            {isLoading && <Loader2 className="size-4 animate-spin" />}
            {isLoading ? 'Updating…' : modeB ? `Update ${selected.size} cue(s)` : 'Update'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function ChecklistView({
  checklist,
  selected,
  onToggle,
}: {
  checklist: UpdateChecklist
  selected: ReadonlySet<number>
  onToggle: (cueId: number) => void
}) {
  return (
    <div className="space-y-4">
      {checklist.stacks.map((stack) => (
        <div key={stack.cueStackId ?? 'unknown'} className="space-y-1">
          <p className="text-xs font-medium text-muted-foreground">
            {stack.cueStackName ?? 'Unknown stack'}
            {stack.isActive && ' · running'}
          </p>
          {stack.cues.map((cue) => (
            <ChecklistRow
              key={cue.cueId}
              cue={cue}
              checked={selected.has(cue.cueId)}
              onToggle={() => onToggle(cue.cueId)}
            />
          ))}
        </div>
      ))}

      {checklist.unattributed.length > 0 && (
        <div className="space-y-1">
          <p className="text-xs font-medium text-muted-foreground">Not from any cue</p>
          <p className="text-xs text-muted-foreground">
            {checklist.unattributed.length} value
            {checklist.unattributed.length === 1 ? '' : 's'} sitting over the baseline (
            {checklist.unattributed
              .slice(0, 4)
              .map((key) => `${key.targetKey}.${key.propertyName}`)
              .join(', ')}
            {checklist.unattributed.length > 4 ? '…' : ''}). Record these into a new cue instead.
          </p>
        </div>
      )}
    </div>
  )
}

function ChecklistRow({
  cue,
  checked,
  onToggle,
}: {
  cue: ChecklistCue
  checked: boolean
  onToggle: () => void
}) {
  return (
    <label className="flex items-start gap-2 rounded px-2 py-1.5 text-sm hover:bg-accent">
      <input type="checkbox" checked={checked} onChange={onToggle} className="mt-1 size-4" />
      <span className="min-w-0 flex-1">
        <span className="flex items-baseline gap-2">
          {cue.cueNumber && (
            <span
              className={cn('tabular-nums text-xs', cue.cueNumberAuto && AUTO_CUE_NUMBER_CLASS)}
            >
              {cue.cueNumber}
            </span>
          )}
          <span className="truncate">{cue.cueName}</span>
          {cue.isActive && <span className="text-xs text-muted-foreground">running</span>}
        </span>
        <span className="block text-xs text-muted-foreground">
          {cue.keyCount} value{cue.keyCount === 1 ? '' : 's'} overridden ·{' '}
          {cue.sample
            .slice(0, 3)
            .map((key) => `${key.targetKey}.${key.propertyName}`)
            .join(', ')}
          {cue.keyCount > 3 ? '…' : ''}
        </span>
        {cue.viaFxKeyCount > 0 && (
          <span className="block text-xs text-amber-600 dark:text-amber-500">
            {cue.viaFxKeyCount} of these are driven by an FX in this cue — a stored value
            will be masked by that FX on the next GO.
          </span>
        )}
      </span>
    </label>
  )
}
