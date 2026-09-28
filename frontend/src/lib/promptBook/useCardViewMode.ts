import { useCallback, useState } from 'react'
import type { ExpansionMode } from '../../components/runner/mobile/CueCardBody'
import type { CueRunStatus } from '../../components/promptbook/AnchorOverlay'

/**
 * What an expanded rail card is showing — Stage or Details — which, unlike whether it is open,
 * persists *across* cue changes.
 *
 * `viewMode` is the live card's view and is carried forward on GO, so choosing Details once keeps
 * showing Details. A non-live cue the operator toggled remembers its own choice in `overrides`
 * until it becomes live, when it rejoins `viewMode`. The live cue therefore never writes an
 * override — that is what stops a mode getting pinned stale on the card the playhead is sitting on.
 *
 * Per-session and never persisted.
 */
export function useCardViewMode() {
  const [viewMode, setViewMode] = useState<ExpansionMode | null>('stage')
  const [overrides, setOverrides] = useState<Map<number, ExpansionMode | null>>(new Map())

  const onCueModeChange = useCallback(
    (cueId: number, status: CueRunStatus, next: ExpansionMode | null) => {
      if (status === 'live') {
        // Toggling the live card updates the shared view (which the next GO carries).
        // Never write a per-cue override for the live cue, so it can't get pinned stale.
        setViewMode(next)
      } else {
        setOverrides((prev) => {
          const m = new Map(prev)
          m.set(cueId, next)
          return m
        })
      }
    },
    [],
  )

  // The live cue ALWAYS follows the persistent viewMode (so GO carries the view forward and a cue
  // never opens live with a stale pinned mode); a non-live cue uses the operator's own choice if it
  // has one, else opens with neither selected.
  const modeOf = useCallback(
    (cueId: number, status: CueRunStatus): ExpansionMode | null => {
      if (status === 'live') return viewMode
      return overrides.has(cueId) ? overrides.get(cueId) ?? null : null
    },
    [overrides, viewMode],
  )

  return { modeOf, onCueModeChange }
}
