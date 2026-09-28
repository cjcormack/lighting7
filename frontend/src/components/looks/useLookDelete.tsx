import { useCallback } from 'react'
import { formatError } from '@/lib/formatError'
import { BatchDeleteDialog } from '@/components/sheet/BatchDeleteDialog'
import { useBatchDelete, type DeleteOutcome } from '@/components/sheet/useBatchDelete'
import { useDeleteLookMutation } from '@/store/looks'
import type { LookInUseError, LookSummary } from '@/api/looksApi'

/**
 * What the batch-delete dialog knows about one Look it held back: the desk's `LOOK_IN_USE` body, or
 * **nothing but its pads** (`fromDesk: false`) — see [useLookDelete].
 */
export interface LookInUse {
  body: LookInUseError | null
  buskPageCount: number
}

/** *2 cue layers — Q4, Q9 · on 3 busk pages*: what uses a Look, in the dialog's one line. */
export function describeLookInUse(summary: LookInUse): string {
  const parts: string[] = []
  const layers = summary.body?.layerCount ?? 0
  if (layers > 0) {
    const cues = summary.body?.cueNames ?? []
    parts.push(`${layers} cue layer${layers === 1 ? '' : 's'}${cues.length > 0 ? ` — ${cues.join(', ')}` : ''}`)
  }
  if (summary.buskPageCount > 0) {
    parts.push(`on ${summary.buskPageCount} busk page${summary.buskPageCount === 1 ? '' : 's'}`)
  }
  return parts.join(' · ')
}

/**
 * The Look delete on `useBatchDelete` (library-sheets plan D13) — one hook for the sheet's batch
 * **and** `LookDetailSheet`'s single delete, so the two ask one question in one dialog. It replaced
 * the route's hand-written confirm + `LOOK_IN_USE` guard pair and the detail sheet's inline one.
 *
 * **A Look with busk pads is held for the question even though the desk would take it.** Pads are a
 * hint, never a guard — `layerCount` alone gates the delete server-side — but they go silently with
 * the Look, and the old confirm's *"It has pads on 2 busk pages; those go with it"* was the only
 * warning an operator ever got. Sending such a Look plain would delete it with no word at all, so
 * `remove` answers `inUse` for it **without sending** on the plain pass, and the dialog lists it
 * beside the desk's refusals (the Kit board draws *Ballyhoo · on 3 busk pages* there).
 *
 * **Its *Delete anyway* is then sent plain, never forced.** The pads were the only use the dialog
 * could name; forcing past them would take any cue layer with it, unsaid, since the desk skips its
 * usage check on `force`. Sent plain, a Look nothing else uses is deleted, and one a cue layers
 * answers `LOOK_IN_USE` — which reopens the dialog with the desk's answer, and only *that*
 * answer's *Delete anyway* forces (`useBatchDelete` hands it back as `previous`).
 *
 * Every other refusal is toasted by the hook; `deleteLook` stays in `SILENT_ENDPOINTS` (D14).
 */
export function useLookDelete({
  projectId,
  onDeleted,
  onKept,
}: {
  projectId: number
  onDeleted?: (looks: LookSummary[]) => void
  onKept?: (looks: LookSummary[]) => void
}) {
  const [deleteLook] = useDeleteLookMutation()
  const remove = useCallback(
    async (look: LookSummary, force: boolean, previous?: LookInUse): Promise<DeleteOutcome<LookInUse>> => {
      // Pads alone are held back unsent on the plain pass — the desk would take the record and the
      // pads would go unannounced. On *Delete anyway* a record held that way is sent **plain**,
      // never forced: pads were the only use the dialog could name, and forcing now would take
      // any cue layer (or effect reference, or running layer) with it unsaid. If the desk answers
      // in use, the dialog asks again with its answer, and only that answer's *Delete anyway*
      // forces.
      if (!force && look.buskPageCount > 0) {
        return { kind: 'inUse', summary: { body: null, buskPageCount: look.buskPageCount } }
      }
      // Forced only when the desk has already said what uses it.
      const sendForced = force && previous?.body != null
      try {
        await deleteLook({ projectId, lookId: look.id, force: sendForced }).unwrap()
        return { kind: 'ok' }
      } catch (err) {
        const body = (err as { data?: LookInUseError } | null)?.data
        if (body?.code === 'LOOK_IN_USE') return { kind: 'inUse', summary: { body, buskPageCount: look.buskPageCount } }
        return { kind: 'refused', reason: formatError(err) }
      }
    },
    [deleteLook, projectId],
  )
  const batch = useBatchDelete<LookSummary, LookInUse>({
    remove,
    name: lookName,
    noun: 'look',
    onDeleted,
    onKept,
    toastKey: 'looks',
  })
  const dialog = (
    <BatchDeleteDialog<LookSummary, LookInUse>
      state={batch.state}
      noun="look"
      name={lookName}
      describe={(_look, summary) => describeLookInUse(summary)}
      consequence="deleting them anyway removes what uses them — a cue layer goes and its cue fires without it, a pad leaves its busk page:"
      busy={batch.busy}
      onForce={batch.force}
      onKeep={batch.keep}
    />
  )
  return { run: batch.run, busy: batch.busy, dialog }
}

function lookName(look: LookSummary): string {
  return look.name
}
