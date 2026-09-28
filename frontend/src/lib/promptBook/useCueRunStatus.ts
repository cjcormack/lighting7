import { useCallback, useMemo } from 'react'
import type { FlatCue } from './desync'
import type { CueRunStatus } from '../../components/promptbook/AnchorOverlay'

/**
 * Where the playhead is, expressed the way the rail and the script overlay both want it: which cue
 * is armed for the next GO, and what status each cue wears.
 *
 * The Prompt Book has to derive "next" itself rather than read it off the transport, because the
 * rail shows the *whole* show and the transport only reports the explicit standby. The rules below
 * are the ones a pre-show rail needs — an unfired stack sits on its own first cue, not on the
 * show's — and getting them wrong makes a stopped desk look armed.
 */
export function useCueRunStatus({
  isShowActive,
  activeCueId,
  standbyCueId,
  activeStackId,
  cueOrder,
  cueOrderIndex,
}: {
  isShowActive: boolean
  activeCueId: number | null
  standbyCueId: number | null
  activeStackId: number | null
  cueOrder: FlatCue[]
  cueOrderIndex: Map<number, number>
}) {
  // The cue armed to fire on the next GO: an explicit standby, else the next cue
  // in reading order. Pre-show (nothing live) the first cue sits on deck.
  const nextCueId = useMemo(() => {
    // Stopped show: nothing is on deck. Without this, a null activeCueId would put
    // the first cue on deck (blue "NEXT"), making a stopped rail look pre-show/armed.
    if (!isShowActive) return null
    // An explicitly-armed standby is the next GO — but never treat the cue that's
    // already live as "next" (activating a standby leaves standbyCueId sitting on it).
    const sb = standbyCueId
    if (sb != null && sb !== activeCueId) return sb
    // Nothing fired yet: the active stack's first cue is on deck; fall back to the
    // very first cue in the show only when no stack is active.
    if (activeCueId == null) {
      const firstOfActive =
        activeStackId != null ? cueOrder.find((c) => c.stackId === activeStackId) : undefined
      return (firstOfActive ?? cueOrder[0])?.cueId ?? null
    }
    const activeIdx = cueOrderIndex.get(activeCueId)
    if (activeIdx == null) return null
    return cueOrder[activeIdx + 1]?.cueId ?? null
  }, [isShowActive, standbyCueId, activeCueId, activeStackId, cueOrder, cueOrderIndex])

  const statusOf = useCallback(
    (cueId: number): CueRunStatus => {
      if (cueId === activeCueId) return 'live'
      if (cueId === nextCueId) return 'next'
      if (activeCueId == null) return 'standby'
      const idx = cueOrderIndex.get(cueId)
      const activeIdx = cueOrderIndex.get(activeCueId)
      if (idx == null || activeIdx == null) return 'standby'
      return idx < activeIdx ? 'done' : 'standby'
    },
    [activeCueId, nextCueId, cueOrderIndex],
  )

  return { nextCueId, statusOf }
}
