import { useMemo } from 'react'
import type { CueTarget } from '@/api/cuesApi'
import { useProgrammerAppliedQuery } from '@/store/programmer'
import { lookLayerTarget } from './buskingTypes'
import { useBuskingSelection } from './useBuskingSelection'

/**
 * The busk view's state: what is selected, and what the desk currently has applied to it.
 *
 * It used to carry the pad *actions* too — a mutation per kind, and a presence predicate each.
 * Every press now goes through one route, `POST /busk/pads/{id}/press`, because the pad is what
 * knows its bank and the bank is what decides the siblings; so what is left here is the selection
 * and the one query the rings read.
 *
 * **Everything here addresses a target by `{type, key}`** — `CueTarget`, `ToggleLookTarget` and the
 * layer stack's own target shape are the same three fields, and `lookLayerTarget` is the single
 * place the group-name convention is applied. The ring and the tap have to agree about which layer a
 * pad is talking about; a group answering to its name in one and its key in the other would light a
 * pad that a tap then failed to clear.
 */
export function useBuskingState(projectId: number) {
  const { selectedTargets, families, toggleTarget, clearSelection, subselect } = useBuskingSelection(projectId)
  // The **resolved** stack, not the layer list: the pads ask about coverage and the desk has
  // already answered, so this view never subscribes to the layers themselves.
  const { data: programmerApplied } = useProgrammerAppliedQuery()

  const selectedArray = useMemo(() => [...selectedTargets.values()], [selectedTargets])

  /** The selection in the one target shape the layer stack, the toggle routes and cues all take. */
  const selectedLayerTargets = useMemo<CueTarget[]>(
    () => selectedArray.map(lookLayerTarget),
    [selectedArray],
  )

  return {
    selectedTargets,
    selectedArray,
    selectedLayerTargets,
    /** The selection's attribute mask — the other half of the pair a press sends (D4). */
    families,
    toggleTarget,
    clearSelection,
    /** The Cells chip's write: one desk op while following, the mirror over the tab's copy when not. */
    subselect,
    programmerApplied,
  }
}
