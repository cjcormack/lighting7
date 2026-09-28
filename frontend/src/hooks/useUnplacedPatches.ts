import { useMemo } from 'react'
import { useVisiblePatchListQuery } from '../store/patches'
import type { FixturePatch } from '../api/patchApi'

export interface UnplacedPatches {
  /**
   * Patches with no resolvable stage position. These are drawn on **no** stage
   * surface at all, so before the tray existed the only way to place one was to
   * type coordinates into a form — which is most of what made rigging setup
   * cumbersome.
   */
  unplaced: FixturePatch[]
  /**
   * Excluded from the stage views by `stageHidden`. Real DMX that isn't a stage
   * object (a dimmer on hard power), so it belongs in a list rather than on the
   * plot — whether or not it happens to carry coordinates.
   */
  hidden: FixturePatch[]
  /**
   * Placed with X and Y but no Z. These *do* render — `worldPositionLighting`
   * defaults a null Z to 0 — so they sit silently on the deck rather than at the
   * height they're actually rigged at. Worth flagging, but not tray membership:
   * they're on the plot and draggable.
   */
  needsHeight: FixturePatch[]
}

/**
 * Splits the patch list by how completely it's been placed.
 *
 * The `unplaced` predicate mirrors `worldPositionLighting`'s null return — X
 * **or** Y missing, Z irrelevant — so a fixture can't be absent from the plot and
 * from the tray at the same time, which would leave it unreachable by anything but
 * the patch list.
 *
 * `stageHidden` is tested **first**, and that ordering is the whole point: a
 * hidden patch is one someone has explicitly declared not to be a stage object,
 * and those are routinely un-positioned too. Testing coordinates first put every
 * dimmer on hard power into the tray as work to be done — on a real show file that
 * buries the handful of fixtures that genuinely need placing, and inviting the
 * operator to place them undoes the point of hiding them.
 */
export function useUnplacedPatches(projectId: number | undefined): UnplacedPatches {
  // Infrastructure is in none of the three lists: it is not placement work, and not a stage
  // object to un-hide either — the Patches view is where it is managed.
  const { data: patches } = useVisiblePatchListQuery(projectId ?? 0, { skip: projectId == null })

  return useMemo(() => {
    const unplaced: FixturePatch[] = []
    const hidden: FixturePatch[] = []
    const needsHeight: FixturePatch[] = []
    for (const patch of patches ?? []) {
      if (patch.stageHidden) {
        hidden.push(patch)
        continue
      }
      if (patch.stageX == null || patch.stageY == null) {
        unplaced.push(patch)
        continue
      }
      if (patch.stageZ == null) needsHeight.push(patch)
    }
    return { unplaced, hidden, needsHeight }
  }, [patches])
}
