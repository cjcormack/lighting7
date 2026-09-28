import { useCallback, useRef, useState } from 'react'
import { toast } from 'sonner'
import { formatError } from '@/lib/formatError'

/** What one plain (or forced) delete answered. */
export type DeleteOutcome<S> =
  | { kind: 'ok' }
  /** The desk refused because the record is in use — the summary says by what. */
  | { kind: 'inUse'; summary: S }
  /** Any other refusal, named. */
  | { kind: 'refused'; reason: string }

/** One record the dialog asks about, with what uses it. */
export interface InUseEntry<Item, S> {
  item: Item
  summary: S
}

/** What the open dialog shows — the batch's outcome so far. */
export interface BatchDeleteState<Item, S> {
  /** How many records the batch asked to delete, skipped ones excluded. */
  total: number
  /** The names of those already deleted by the plain pass. */
  deleted: string[]
  inUse: InUseEntry<Item, S>[]
}

export interface UseBatchDeleteOptions<Item, S> {
  /**
   * One delete — plain, or forced — answered as an outcome, never thrown. On the forced pass
   * [previous] is what that record answered last time, so an entity whose plain pass held a record
   * back **without sending** (a Look's busk pads, which the desk does not refuse on) can send it
   * plain now and learn what else uses it, rather than forcing past uses nobody was told about.
   */
  remove: (item: Item, force: boolean, previous?: S) => Promise<DeleteOutcome<S>>
  name: (item: Item) => string
  /**
   * A record this batch must not send at all, with the reason — master 1, a built-in effect
   * (library-sheets plan D13). Named in one toast, before anything is sent.
   */
  skip?: (item: Item) => string | null
  /** The sheet's noun for a record — `master`, `look`. */
  noun: string
  /**
   * The records the batch finished with: every one deleted (plain and forced). The sheet drops them
   * from its selection; the detail sheet closes.
   */
  onDeleted?: (items: Item[]) => void
  /** *Keep them* — the in-use records the operator chose not to force. The sheet re-selects them. */
  onKept?: (items: Item[]) => void
  /** The toast id refusals are reported under — per sheet, so a batch's refusals replace. */
  toastKey: string
}

/**
 * **One delete for a batch** (library-sheets plan D13), for any library whose delete answers an
 * in-use refusal (`LOOK_IN_USE`, `TEMPLATE_IN_USE`, `SPEED_MASTER_IN_USE`) and takes `?force=true`.
 *
 * Sends each record **plain**, gathers the in-use refusals and asks **once**, listing what each is
 * used by (`BatchDeleteDialog`); *Delete anyway* forces only those, *Keep them* leaves them to the
 * sheet to re-select. Records [skip] names (master 1, built-ins) are left out before anything is
 * sent, and named in a toast. It replaces the three hand-written delete + in-use dialog pairs, and
 * it **reports its own failures** (D14): every other refusal is toasted, under one id per sheet, so
 * the delete endpoints stay in `SILENT_ENDPOINTS` where they were for the dialogs' inline alerts.
 *
 * The plain pass is sequential rather than parallel: every delete invalidates the list, and a
 * refetch landing between two deletes of one batch would otherwise race the next one's answer.
 */
export function useBatchDelete<Item, S>({
  remove,
  name,
  skip,
  noun,
  onDeleted,
  onKept,
  toastKey,
}: UseBatchDeleteOptions<Item, S>) {
  const [state, setState] = useState<BatchDeleteState<Item, S> | null>(null)
  const [busy, setBusy] = useState(false)
  const busyRef = useRef(false)

  const report = useCallback(
    (refusals: { item: Item; reason: string }[]) => {
      if (refusals.length === 0) return
      const message =
        refusals.length === 1
          ? `${name(refusals[0].item)} was not deleted: ${refusals[0].reason}`
          : `${refusals.length} ${noun}s were not deleted — ${refusals.map((r) => `${name(r.item)}: ${r.reason}`).join('; ')}`
      toast.error(message, { id: `batch-delete:${toastKey}` })
    },
    [name, noun, toastKey],
  )

  const run = useCallback(
    async (items: readonly Item[]) => {
      if (busyRef.current || items.length === 0) return
      // `skip` is asked once per item: the reason that gates an item out is the reason it is named by.
      const skipped = skip
        ? items.flatMap((item) => {
            const reason = skip(item)
            return reason ? [{ item, reason }] : []
          })
        : []
      if (skipped.length > 0) {
        toast.info(
          skipped.length === 1
            ? `${name(skipped[0].item)} skipped — ${skipped[0].reason}`
            : `${skipped.length} ${noun}s skipped — ${skipped.map((s) => `${name(s.item)}: ${s.reason}`).join('; ')}`,
          { id: `batch-delete-skip:${toastKey}` },
        )
      }
      const skippedSet = new Set(skipped.map((s) => s.item))
      const sending = items.filter((item) => !skippedSet.has(item))
      if (sending.length === 0) return

      busyRef.current = true
      setBusy(true)
      const deleted: Item[] = []
      const inUse: InUseEntry<Item, S>[] = []
      const refused: { item: Item; reason: string }[] = []
      try {
        for (const item of sending) {
          const outcome = await settle(remove(item, false))
          if (outcome.kind === 'ok') deleted.push(item)
          else if (outcome.kind === 'inUse') inUse.push({ item, summary: outcome.summary })
          else refused.push({ item, reason: outcome.reason })
        }
      } finally {
        busyRef.current = false
        setBusy(false)
      }
      report(refused)
      if (deleted.length > 0) onDeleted?.(deleted)
      if (inUse.length > 0) setState({ total: sending.length, deleted: deleted.map(name), inUse })
    },
    [name, noun, onDeleted, remove, report, skip, toastKey],
  )

  /**
   * *Delete anyway*: force the in-use ones, and only those.
   *
   * A record may answer **in use again** — one [remove] held back unsent and has now sent plain,
   * learning uses the first question could not name. Those reopen the dialog, listing what the desk
   * said, so nothing is forced past a use the operator was never shown; the next *Delete anyway*
   * forces them with that answer as [remove]'s `previous`.
   */
  const force = useCallback(async () => {
    if (state == null || busyRef.current) return
    busyRef.current = true
    setBusy(true)
    const deleted: Item[] = []
    const again: InUseEntry<Item, S>[] = []
    const refused: { item: Item; reason: string }[] = []
    try {
      for (const { item, summary } of state.inUse) {
        const outcome = await settle(remove(item, true, summary))
        if (outcome.kind === 'ok') deleted.push(item)
        else if (outcome.kind === 'inUse') again.push({ item, summary: outcome.summary })
        else refused.push({ item, reason: outcome.reason })
      }
    } finally {
      busyRef.current = false
      setBusy(false)
    }
    setState(again.length > 0 ? { total: state.total, deleted: [...state.deleted, ...deleted.map(name)], inUse: again } : null)
    report(refused)
    if (deleted.length > 0) onDeleted?.(deleted)
  }, [name, onDeleted, remove, report, state])

  /** *Keep them*: close the dialog, and hand the in-use records back to the sheet. */
  const keep = useCallback(() => {
    if (state == null) return
    const kept = state.inUse.map((entry) => entry.item)
    setState(null)
    onKept?.(kept)
  }, [onKept, state])

  return { run, force, keep, state, busy }
}

/** A delete that threw rather than answering is a refusal like any other, never an uncaught error. */
async function settle<S>(pending: Promise<DeleteOutcome<S>>): Promise<DeleteOutcome<S>> {
  try {
    return await pending
  } catch (err) {
    return { kind: 'refused', reason: formatError(err) }
  }
}
