import { useCallback } from 'react'
import type { CueTarget } from '../../api/cuesApi'
import type { SubselectMode } from '../../api/selectionApi'
import type { Fixture } from '../../store/fixtures'
import type { GroupSummary } from '../../api/groupsApi'
import type { LocateTarget } from '../../store/locate'
import { useBuskRigQuery } from '../../store/busk'
import { subselectDeskSelection } from '../../store/selection'
import { effectiveRig } from '../../lib/buskRig'
import { subselectTargets } from '../../lib/cellsSubSelection'

/**
 * The programmer's subsets over rig order — the heads chip's *All · Odd · … · Next* — over the
 * list's own selection.
 *
 * Following the desk, a press is `selection.subselect`, as the busk band's is, and the desk's
 * answer comes back through `useDeskSelectionBridge` like any other frame. Unlinked, the list's
 * selection is the window's own, so the press runs `lib/cellsSubSelection.ts`'s mirror over its
 * targets and [land]s the answer as rows.
 *
 * **Either way the answer is given rows first** ([reveal]): *Odd* over six multi-head fixtures
 * answers with their elements, and an element of a collapsed fixture, or a member of a collapsed
 * group, has no row. Following, the mirror runs only to learn what to open — the desk's targets are
 * the ones this list published, so it answers what the desk will.
 */
export function useListSubselect({
  enabled,
  projectId,
  following,
  fixtures,
  groups,
  targets,
  reveal,
  land,
}: {
  enabled: boolean
  projectId: number | undefined
  following: boolean
  fixtures: readonly Fixture[]
  groups: readonly GroupSummary[]
  /** The selection as it stands, as `selectedRowTargets` gives it. */
  targets: readonly LocateTarget[]
  /** Open whatever the targets need to have rows. */
  reveal: (targets: readonly CueTarget[]) => void
  /** Select the targets as rows, opening what they need first. */
  land: (targets: readonly CueTarget[]) => void
}): ((mode: SubselectMode) => void) | null {
  const { data: rig, isError: rigFailed } = useBuskRigQuery(projectId ?? 0, { skip: !enabled || projectId == null })
  // `useBuskingSelection`'s guard: walking the show-all fallback for a rig that may be built is a
  // wrong answer with nothing to correct it.
  const rigLoaded = rig != null || rigFailed

  const subselect = useCallback(
    (mode: SubselectMode) => {
      const next = rigLoaded
        ? subselectTargets(targets, mode, { rows: effectiveRig(rig, groups, fixtures).rows, groups, fixtures })
        : null
      if (following) {
        if (next != null) reveal(next)
        subselectDeskSelection(mode)
        return
      }
      if (next == null || next === targets) return
      land(next)
    },
    [rigLoaded, rig, groups, fixtures, targets, following, reveal, land],
  )

  return enabled ? subselect : null
}
