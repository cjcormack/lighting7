import { useMemo, useState } from 'react'
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
import { useDeleteProjectCueStackMutation } from '@/store/cueStacks'
import { useCueDeletePreviewQuery } from '@/store/cues'
import { ignoreReportedError } from '@/store/errorToastMiddleware'
import { isCueRow, type CueStack } from '@/api/cueStacksApi'
import type { CueUses } from '@/api/cuesApi'
import { cuePlacesByCue } from '@/lib/cuePlaces'

const plural = (n: number, word: string) => `${n} ${word}${n === 1 ? '' : 's'}`

/** The cues of [stack] that no other stack uses — what deleting it would leave in no stack. */
export function cuesOnlyIn(stack: CueStack, stacks: readonly CueStack[]): number[] {
  const places = cuePlacesByCue(stacks)
  const ids = new Set<number>()
  for (const row of stack.cues) {
    if (!isCueRow(row)) continue
    if ((places.get(row.id) ?? []).every((p) => p.stackId === stack.id)) ids.add(row.id)
  }
  return [...ids]
}

/**
 * Deleting a stack deletes its list and keeps its cues (lighting7 cue model plan §3.6, D17). The
 * confirm offers, unticked, to delete the cues no other stack uses as well, and lists what names
 * them from a dry run before it does.
 */
export function DeleteStackDialog({
  projectId,
  stack,
  stacks,
  onClose,
}: {
  projectId: number
  /** The stack asked about; null closes the dialog. */
  stack: CueStack | null
  stacks: readonly CueStack[]
  onClose: () => void
}) {
  const [deleteStack, { isLoading: deleting }] = useDeleteProjectCueStackMutation()
  const [alsoCues, setAlsoCues] = useState(false)
  const close = () => {
    setAlsoCues(false)
    onClose()
  }

  const cueCount = useMemo(
    () => (stack == null ? 0 : new Set(stack.cues.filter(isCueRow).map((c) => c.id)).size),
    [stack],
  )
  const looseIds = useMemo(() => (stack == null ? [] : cuesOnlyIn(stack, stacks)), [stack, stacks])
  const { data: preview, isFetching: previewing } = useCueDeletePreviewQuery(
    { projectId, cueIds: looseIds },
    { skip: !alsoCues || looseIds.length === 0 },
  )

  const confirm = () => {
    if (stack == null) return
    const deleteLooseCues = alsoCues && looseIds.length > 0
    deleteStack({ projectId, stackId: stack.id, deleteLooseCues })
      .unwrap()
      .then((result) => {
        const loose = result?.looseCueIds?.length ?? 0
        const deleted = result?.deletedCueIds?.length ?? 0
        if (deleted > 0) toast.success(`Deleted “${stack.name}” and ${plural(deleted, 'cue')}`)
        else if (loose > 0) toast.success(`Deleted “${stack.name}” — ${plural(loose, 'cue')} kept in no stack`)
      })
      .catch(ignoreReportedError)
    close()
  }

  return (
    <Dialog open={stack != null} onOpenChange={(open) => !open && close()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Delete stack?</DialogTitle>
        </DialogHeader>
        <DialogDescription>
          {stack == null ? null : deleteSentence(stack.name, cueCount, looseIds.length, alsoCues)}
        </DialogDescription>
        {looseIds.length > 0 && (
          <label className="flex items-start gap-2 text-sm">
            <input
              type="checkbox"
              className="mt-0.5"
              checked={alsoCues}
              onChange={(e) => setAlsoCues(e.target.checked)}
            />
            <span>
              Also delete the {plural(looseIds.length, 'cue')} no other stack uses
              {alsoCues && <UsesList uses={preview?.uses} loading={previewing} />}
            </span>
          </label>
        )}
        <DialogFooter>
          <Button variant="outline" onClick={close}>
            Cancel
          </Button>
          <Button variant="destructive" onClick={confirm} disabled={deleting || (alsoCues && previewing)}>
            Delete
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

/** What the confirm says happens to the stack's cues. */
export function deleteSentence(name: string, cueCount: number, looseCount: number, alsoLoose: boolean): string {
  if (cueCount === 0) return `This deletes the empty stack “${name}”.`
  const stays = (n: number) => (n === 1 ? 'stays' : 'stay')
  const head = `This deletes “${name}” and its list.`
  if (alsoLoose && looseCount > 0) {
    const kept = cueCount - looseCount
    return kept > 0
      ? `${head} The ${plural(kept, 'cue')} other stacks use ${stays(kept)}.`
      : `${head} Its cues go with it.`
  }
  const loose =
    looseCount === 0 ? '' : looseCount === cueCount ? ', in no stack' : ` — ${looseCount} of them in no stack`
  return `${head} ${cueCount === 1 ? 'Its cue' : `Its ${cueCount} cues`} ${stays(cueCount)}${loose}.`
}

/** What goes with the cues: their pads, slots, anchors and bindings, from the dry run. */
function UsesList({ uses, loading }: { uses: readonly CueUses[] | undefined; loading: boolean }) {
  if (loading && uses == null) {
    return <span className="block text-xs text-muted-foreground">Checking what uses them…</span>
  }
  const lines = (uses ?? []).flatMap((u) => {
    const parts = [
      u.pads.length > 0 ? `a pad on ${[...new Set(u.pads.map((p) => p.pageName))].join(', ')}` : null,
      ...u.slots.map((s) => `slot ${s.label}`),
      ...u.bindings.map((b) => `binding ${b.label}, left naming no cue`),
      u.anchors.length > 0 ? plural(u.anchors.length, 'prompt book anchor') : null,
    ].filter((p): p is string => p != null)
    return parts.length > 0 ? [`${u.name}: ${parts.join(' · ')}`] : []
  })
  if (lines.length === 0) {
    return <span className="block text-xs text-muted-foreground">Nothing else names them.</span>
  }
  return (
    <span className="mt-1 block text-xs text-muted-foreground">
      These go with them:
      {lines.map((line) => (
        <span key={line} className="block" data-cue-use>
          {line}
        </span>
      ))}
    </span>
  )
}
