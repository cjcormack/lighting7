import { useCallback, useMemo, useState } from 'react'
import { toast } from 'sonner'
import { useAddBuskPadMutation, useBuskPagesQuery } from '@/store/busk'
import {
  buskAddBody,
  buskAddTargets,
  defaultBuskAddTarget,
  rememberBuskAddTarget,
  type BuskAddPage,
  type BuskAddRecord,
  type BuskAddTarget,
} from '@/lib/buskAdd'

/**
 * Everything *Add to busk page* needs from the store, in one place.
 *
 * **The `buskPages` subscription lives here rather than in each caller.** That is the strongest
 * argument for one shared control: the query is the busk view's whole nested document — every page,
 * every pad, each pad carrying its record's full summary — and a surface that forgot to subscribe
 * would render an empty menu and blame the operator's data.
 *
 * It is `skip`ped until the menu is first opened, because a cue properties pane that is never asked
 * to place anything should not pull that document. The latch is deliberately one-way: unsubscribing
 * when the menu closes would refetch it on every reopen.
 */
export function useAddToBuskPage(
  projectId: number,
  /** What is being placed, when it exists. Null in a create sheet — nothing is highlighted yet. */
  record: BuskAddRecord | null,
  /** Fetch straight away rather than on first open, for a surface that must show a default. */
  eager = false,
) {
  const [wanted, setWanted] = useState(eager)
  const { data: pages, isLoading } = useBuskPagesQuery(projectId, {
    skip: !wanted || !Number.isFinite(projectId),
  })
  const [addPad] = useAddBuskPadMutation()

  // Computed whether or not there is a record yet: a create sheet has none, and the pages and banks
  // are still exactly what it needs to offer. Gating this on `record` made the create sheets' whole
  // control render nothing and their placement branch unreachable.
  const targets: BuskAddPage[] = useMemo(
    () => buskAddTargets(pages ?? [], record),
    [pages, record],
  )

  /**
   * Place [placed] on [target]. The record is a parameter rather than the hook's own, because a
   * create sheet only learns the id it is placing *after* its POST has answered.
   */
  const place = useCallback(
    async (target: BuskAddTarget, placed: BuskAddRecord | null = record) => {
      if (placed == null) return
      await addPad({
        projectId,
        pageId: target.pageId,
        bankId: target.bankId,
        ...buskAddBody(placed),
      }).unwrap()
      rememberBuskAddTarget(projectId, target.bankId)
      toast.success(`“${placed.name}” added to ${target.bankLabel} on ${target.pageName}`)
    },
    [addPad, projectId, record],
  )

  /**
   * The *create* sheets' whole after-the-fact dance: place the pad if one was chosen, and fall back
   * to [fallbackMessage] only when no pad was placed.
   *
   * Here rather than in each sheet because the rule has three parts that must agree — the pad needs
   * the id the create call just minted, a failed pad must leave the created record standing, and the
   * success line must not claim a pad it did not get. Two sheets already want it and a third would
   * copy it again.
   */
  const placeAfterCreate = useCallback(
    async (target: BuskAddTarget | null, placed: BuskAddRecord, fallbackMessage: string) => {
      if (target != null) {
        try {
          await place(target, placed)
          return
        } catch {
          // Reported by the error-toast middleware. The record stands; say so below.
        }
      }
      toast.success(fallbackMessage)
    },
    [place],
  )

  const defaultTarget = useCallback(
    () => defaultBuskAddTarget(projectId, targets),
    [projectId, targets],
  )

  return {
    targets,
    isLoading: wanted && isLoading,
    /** Call on first open, so the document is fetched only for a menu somebody actually used. */
    arm: useCallback(() => setWanted(true), []),
    defaultTarget,
    place,
    placeAfterCreate,
  }
}
