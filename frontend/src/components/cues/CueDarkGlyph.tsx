import { useCueStackMoveInDarkQuery } from '@/store/cueStacks'
import { darkCellOf, darkCellSentence, darkCellShows } from '@/lib/moveInDark'
import { DarkCellFace } from './moveInDarkMarks'

/**
 * A cue card's *Dark* glyph (lighting7 move-in-dark design §5.2): the cue table's cell, folded to its
 * glyphs and counts, from the stack's static plan — one cache entry every card of the stack shares.
 */
export function CueDarkGlyph({ projectId, stackId, entryId }: { projectId: number; stackId: number; entryId: number }) {
  // The entry itself is selected, never a cell built here: a fresh object per selection would
  // re-render every card on every store update.
  const { entry } = useCueStackMoveInDarkQuery(
    { projectId, stackId },
    { selectFromResult: ({ data }) => ({ entry: data?.entries.find((e) => e.entryId === entryId) }) },
  )
  const cell = darkCellOf(entry)
  if (!darkCellShows(cell)) return null
  return (
    <span title={darkCellSentence(cell)} data-dark-glyph={entryId} className="inline-flex shrink-0">
      <DarkCellFace cell={cell} wordClass="hidden" />
    </span>
  )
}
