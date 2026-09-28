import { useCallback, useEffect, useState } from 'react'

/**
 * Which cue cards are open, for every surface that draws a list of them.
 *
 * There are exactly two reasons a card can be open — **the operator opened it**, and **the playhead
 * is on it** — and one rule that follows: closing has to silence both, or a card open for both
 * reasons needs two presses to shut. That is the whole hook, and it is what neither predecessor
 * managed:
 *
 *  - **Run kept a `Set` and added every live cue to it**, never removing any, so after five GOs the
 *    operator was scrolling five open cards. It also needed two effects whose *declaration order*
 *    was load-bearing — a reset followed by a re-add — to reconstruct something computable.
 *  - **Show kept a single scalar in `?cue=`**, so a GO that auto-expanded the new live cue would
 *    replace the card being read.
 *
 * Deriving the playhead's cards instead means a GO cannot take away the card the operator opened,
 * because nothing here writes to the operator's slot at all.
 *
 * **The operator's slot belongs to the caller**, because its storage and its multiplicity genuinely
 * differ: the merged Show view keeps one cue in `?cue=`, which is an external contract (the Prompt
 * Book mints those links), while the Prompt Book's rail keeps a set in local state so several cards
 * can be compared side by side. Hence `openCueIds` in and `onOpen`/`onClose` out, rather than a
 * setter this hook drives. Pass a **stable** `onOpen`/`onClose` and a memoised `openCueIds`: an
 * inline arrow would give `toggleExpanded` a fresh identity every render and break `ShowView`'s
 * memo mid-fade.
 *
 * `collapsedAuto` is the one piece of state that is neither derived nor addressable, and it exists
 * for a single reason: closing a playhead card can no longer be a deletion from a set. It
 * self-clears — when the show moves on, `liveCueId`/`nextCueId` change and the stale ids stop
 * matching — so a later GO re-opens the new cards without anything having to reset it. `resetKey`
 * covers the one case that self-clearing cannot: an id that survives the move, which is exactly
 * what a dismissed *next* card does the moment a GO makes it live.
 */
export function useCueExpansion({
  openCueIds,
  onOpen,
  onClose,
  liveCueId,
  nextCueId = null,
  resetKey,
}: {
  /** The cues the operator opened, however the caller stores them. Memoise it. */
  openCueIds: ReadonlySet<number>
  /** Add a cue to the operator's slot. Must be stable — see the docblock. */
  onOpen: (cueId: number) => void
  /** Remove a cue from the operator's slot. Must be stable — see the docblock. */
  onClose: (cueId: number) => void
  /** The cue on stage in the list being shown, or null when there is none. */
  liveCueId: number | null
  /** The cue on deck, for surfaces that open it too. Omit where only the live card auto-opens. */
  nextCueId?: number | null
  /** Changing this forgets dismissed playhead cards — the live cue, or the stack being read. */
  resetKey: number | null
}) {
  const [collapsedAuto, setCollapsedAuto] = useState<ReadonlySet<number>>(() => new Set())

  useEffect(() => {
    setCollapsedAuto((prev) => (prev.size === 0 ? prev : new Set()))
  }, [resetKey])

  const isAuto = useCallback(
    (cueId: number) => cueId === liveCueId || cueId === nextCueId,
    [liveCueId, nextCueId],
  )

  const isExpanded = useCallback(
    (cueId: number) => openCueIds.has(cueId) || (isAuto(cueId) && !collapsedAuto.has(cueId)),
    [openCueIds, isAuto, collapsedAuto],
  )

  const toggleExpanded = useCallback(
    (cueId: number) => {
      // Closing has to silence both reasons a card can be open, or a card expanded for both would
      // need two presses to shut.
      if (isExpanded(cueId)) {
        if (openCueIds.has(cueId)) onClose(cueId)
        if (isAuto(cueId)) setCollapsedAuto((prev) => new Set(prev).add(cueId))
        return
      }
      onOpen(cueId)
      setCollapsedAuto((prev) => {
        if (!prev.has(cueId)) return prev
        const next = new Set(prev)
        next.delete(cueId)
        return next
      })
    },
    [isExpanded, openCueIds, isAuto, onOpen, onClose],
  )

  return { isExpanded, toggleExpanded }
}
