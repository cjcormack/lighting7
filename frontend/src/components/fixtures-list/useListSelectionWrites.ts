import { useCallback } from 'react'
import type { CueTarget } from '@/api/cuesApi'
import type { SubselectMode } from '@/api/selectionApi'
import { effectiveRig } from '@/lib/buskRig'
import { selectionForColour } from '@/lib/colourSelection'
import { subselectTargets } from '@/lib/cellsSubSelection'
import { useBuskRigQuery } from '@/store/busk'
import { useFixtureListQuery } from '@/store/fixtures'
import { useGroupListQuery } from '@/store/groups'
import { setDeskSelection, subselectDeskSelection, useDeskSelectionSnapshot } from '@/store/selection'
import { cellFamilies } from './columns'
import type { CellRef } from '../sheet/cellSelectionModel'
import type { ColumnKey } from './columns'
import { rowIdsForTargets, selectedRowTargets, type Row, type RowId } from './rowModel'

/**
 * The programmer's heads chip's two writes — a subset over rig order and *Select by colour*
 * (programmer-toolbar plan D4) — on whichever selection this window is on.
 *
 * **Following the desk**, each is one desk write: `selection.subselect {mode}` and `selection.set`,
 * which the list's bridge then lands as rows, exactly as a busk band's or a surface's would.
 *
 * **Unlinked**, the programmer's own selection is **the list's rows** (`lib/deskFollow.ts`), not the
 * busk view's copy in `sessionStorage`, so the busk hook's local arm does not fit. The rows are read
 * as targets with [selectedRowTargets] — the bridge's own publish — rewritten by
 * `lib/cellsSubSelection.ts`'s mirror of the desk's rule over the rig document, and read back with
 * [land], which opens the fixtures and groups the answer's heads are folded under before selecting
 * their rows: *Odd* over multi-head fixtures answers with their cells, and a cell of a collapsed
 * fixture has no row, so applying it as the bridge does would empty the selection. Until the rig has answered
 * the local arm does nothing, as the busk hook's does, rather than walk the fallback for a rig that
 * may be built.
 *
 * *Select by colour* follows `selectionForColour`'s rule on both arms — a pick replaces the
 * selection and clears its mask, a ⌥-pick adds to it and keeps its mask — over the desk's own
 * selection while following, and over the list's while not. Unlinked, the list's only mask is the
 * marquee's columns, so a ⌥-pick over a marquee widens the marquee to the added rows in those
 * columns ([addRowsToMarquee]) rather than dropping it to rows.
 */
export function useListSelectionWrites({
  enabled,
  following,
  projectId,
  rows,
  selectedRowIds,
  cells,
  land,
  addRowsToMarquee,
}: {
  /** The programmer scope; off on the plain lists, which never bridge and have no heads chip. */
  enabled: boolean
  following: boolean
  projectId: number | undefined
  rows: readonly Row[]
  selectedRowIds: ReadonlySet<RowId>
  /** The marquee's cells — the unlinked selection's mask. */
  cells: readonly CellRef<ColumnKey>[]
  /** Select these targets as rows, opening whatever they are folded under first. */
  land: (targets: readonly CueTarget[]) => void
  /** Widen the marquee to these rows, in its own columns. */
  addRowsToMarquee: (ids: readonly RowId[]) => void
}) {
  const desk = useDeskSelectionSnapshot()
  const local = enabled && !following
  const { data: groups } = useGroupListQuery(undefined, { skip: !local })
  const { data: fixtures } = useFixtureListQuery(undefined, { skip: !local })
  const { data: rig, isError: rigFailed } = useBuskRigQuery(projectId ?? 0, { skip: !local || projectId == null })
  const rigLoaded = rig != null || rigFailed

  const subselect = useCallback(
    (mode: SubselectMode) => {
      if (following) {
        subselectDeskSelection(mode)
        return
      }
      if (!rigLoaded) return
      const current: CueTarget[] = selectedRowTargets(rows, selectedRowIds)
      const next = subselectTargets(current, mode, {
        rows: effectiveRig(rig, groups, fixtures).rows,
        groups: groups ?? [],
        fixtures: fixtures ?? [],
      })
      if (next === current) return
      land(next)
    },
    [following, rigLoaded, rig, groups, fixtures, rows, selectedRowIds, land],
  )

  const pickColour = useCallback(
    (keys: readonly string[], add: boolean) => {
      if (following) {
        const next = selectionForColour(keys, { targets: desk.targets, families: desk.families }, add)
        setDeskSelection(next.targets, next.families)
        return
      }
      const current = { targets: selectedRowTargets(rows, selectedRowIds), families: cells.length > 0 ? cellFamilies(cells) : null }
      const next = selectionForColour(keys, current, add)
      if (add && cells.length > 0) {
        addRowsToMarquee(rowIdsForTargets(rows, next.targets).filter((id) => !selectedRowIds.has(id)))
      } else land(next.targets)
    },
    [following, desk.targets, desk.families, rows, selectedRowIds, cells, land, addRowsToMarquee],
  )

  return { subselect, pickColour }
}
