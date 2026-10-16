import { useCallback, useMemo } from 'react'
import type { FlatCue } from './desync'
import type { CueRunStatus } from '../../components/promptbook/AnchorOverlay'

/**
 * Where the playhead is, expressed the way the rail and the script overlay both want it: which
 * entry is armed for the next GO, and what status each entry wears. Keyed by entry, since a cue
 * shared by two stacks is live in one place and not the other.
 *
 * The Prompt Book has to derive "next" itself rather than read it off the transport, because the
 * rail shows the *whole* show and the transport only reports the explicit standby. The rules below
 * are the ones a pre-show rail needs — an unfired stack sits on its own first cue, not on the
 * show's — and getting them wrong makes a stopped desk look armed.
 */
export function useCueRunStatus({
  isShowActive,
  activeEntryId,
  standbyEntryId,
  activeStackId,
  cueOrder,
  cueOrderIndex,
}: {
  isShowActive: boolean
  activeEntryId: number | null
  standbyEntryId: number | null
  activeStackId: number | null
  cueOrder: FlatCue[]
  cueOrderIndex: Map<number, number>
}) {
  // The cue armed to fire on the next GO: an explicit standby, else the next cue
  // in reading order. Pre-show (nothing live) the first cue sits on deck.
  const nextEntryId = useMemo(() => {
    // Stopped show: nothing is on deck. Without this, a null activeEntryId would put
    // the first cue on deck (blue "NEXT"), making a stopped rail look pre-show/armed.
    if (!isShowActive) return null
    // An explicitly-armed standby is the next GO — but never treat the entry that's
    // already live as "next" (activating a standby leaves the standby sitting on it).
    const sb = standbyEntryId
    if (sb != null && sb !== activeEntryId) return sb
    // Nothing fired yet: the active stack's first cue is on deck; fall back to the
    // very first cue in the show only when no stack is active.
    if (activeEntryId == null) {
      const firstOfActive =
        activeStackId != null ? cueOrder.find((c) => c.stackId === activeStackId) : undefined
      return (firstOfActive ?? cueOrder[0])?.entryId ?? null
    }
    const activeIdx = cueOrderIndex.get(activeEntryId)
    if (activeIdx == null) return null
    return cueOrder[activeIdx + 1]?.entryId ?? null
  }, [isShowActive, standbyEntryId, activeEntryId, activeStackId, cueOrder, cueOrderIndex])

  const statusOf = useCallback(
    (entryId: number): CueRunStatus => {
      if (entryId === activeEntryId) return 'live'
      if (entryId === nextEntryId) return 'next'
      if (activeEntryId == null) return 'standby'
      const idx = cueOrderIndex.get(entryId)
      const activeIdx = cueOrderIndex.get(activeEntryId)
      if (idx == null || activeIdx == null) return 'standby'
      return idx < activeIdx ? 'done' : 'standby'
    },
    [activeEntryId, nextEntryId, cueOrderIndex],
  )

  return { nextEntryId, statusOf }
}
