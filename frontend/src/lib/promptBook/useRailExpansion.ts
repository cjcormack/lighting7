import { useCallback, useEffect, useState } from 'react'
import { useCueExpansion } from '../../hooks/useCueExpansion'

/**
 * The Prompt Book rail's storage for `useCueExpansion` — the shared "two reasons a card is open"
 * policy, with this surface's two answers to the questions that hook leaves to its caller.
 *
 *  - **The operator's slot is a set**, not the single `?cue=` slot the merged Show view uses. The
 *    rail sits beside the script, and comparing two cues against the page they are anchored to is
 *    the reason to open a second card rather than swap the first.
 *  - **Both playhead cards open**: the live cue and the one on deck. The Prompt Book is read ahead
 *    of the show, so the next cue is as much the subject as the current one.
 *
 * Manual opens are forgotten when the playhead moves, which is what keeps the rail from
 * accumulating a screenful of cards left over from three cues ago. Dismissed playhead cards are
 * `useCueExpansion`'s business and self-clear there.
 */
export function useRailExpansion(activeCueId: number | null, nextCueId: number | null) {
  const [openCueIds, setOpenCueIds] = useState<ReadonlySet<number>>(() => new Set())

  const onOpen = useCallback(
    (cueId: number) => setOpenCueIds((prev) => new Set(prev).add(cueId)),
    [],
  )
  const onClose = useCallback((cueId: number) => {
    setOpenCueIds((prev) => {
      const next = new Set(prev)
      next.delete(cueId)
      return next
    })
  }, [])

  // A GO, a Back or a new standby clears what the operator had opened by hand; the live and next
  // cards re-derive on their own.
  useEffect(() => {
    setOpenCueIds((prev) => (prev.size === 0 ? prev : new Set()))
  }, [activeCueId, nextCueId])

  return useCueExpansion({
    openCueIds,
    onOpen,
    onClose,
    liveCueId: activeCueId,
    nextCueId,
    // A dismissed *next* card would otherwise stay dismissed when the GO that makes it live keeps
    // its id — the one thing self-clearing cannot catch.
    resetKey: activeCueId,
  })
}
