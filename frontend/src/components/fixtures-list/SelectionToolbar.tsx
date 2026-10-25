import type { ReactNode } from 'react'
import { X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { HighlightButton, LocateControl, type RigVerbs } from './RigVerbs'

export interface SelectionToolbarProps {
  /**
   * Locate and Highlight over the selection — the container's `useRigVerbs`, whose owner outlives
   * this toolbar and lets go of a held Highlight when the selection empties.
   */
  verbs: RigVerbs
  /** Drops the selection — cells if there are any, the rows otherwise. */
  onClear: () => void
  /**
   * The selection's own verbs, drawn before Locate and Highlight: a cell selection's Set · Clear ·
   * Fan (`CellSelectionActions`), and for a rows-only selection the whole-selection row Fan. The
   * two plain lists' toolbar; the programmer composes its own row C (`programmer/SelectionBar.tsx`).
   */
  actions?: ReactNode
}

// The three fold classes moved to the sheet kit; re-exported so this list's callers keep one import.
export {
  MID_FOLDED_CLASS,
  PHONE_FOLDED_CLASS,
  STRIP_MID_FOLDED_CLASS,
  WORD_CLASS,
} from '../sheet/toolbarFolds'
import { STRIP_MID_FOLDED_CLASS, WORD_CLASS } from '../sheet/toolbarFolds'

export function SelectionToolbar({ verbs, onClear, actions }: SelectionToolbarProps) {
  // `shrink-0`: the bar is one 40px line that never wraps, and a flex item that gives would be
  // squeezed rather than leave the slack to the counts.
  return (
    <div className="flex shrink-0 items-center gap-1.5 sm:gap-2">
      {/* No count here. There was one — "12 selected", folding to the number on a phone — and it
          went when the two plain lists gained the selection bar (CLAUDE.md §List shell): every
          list this sits on now counts the selection at the bar's left end, as "4 fixtures" — the
          heads a press lands on — and the same number twice at opposite ends of one 40px line
          reads as two different facts that happen to agree. */}
      {actions}
      {/* No "Apply palette" or "Record palette" here any more. Both authored value-level
          references, which layers replace: applying a look to a cue is a layer, and recording the
          programmer into a look is the record rewrite. Leaving Record in place would have been
          worse than removing it — its route still answers 200 while writing rows no consumer
          reads. */}
      {/* Folds at 800 only on a bar with a strip (`STRIP_MID_FOLDED_CLASS`): on the plain lists this
          toolbar is the only place Locate and Highlight exist. The chevron is where Locate points each
          unit (venue-visit plan D6), stored on the patch, read from what the desk is sending now. */}
      <LocateControl verbs={verbs} wordClass={WORD_CLASS} className={STRIP_MID_FOLDED_CLASS} />
      <HighlightButton verbs={verbs} wordClass={WORD_CLASS} className={STRIP_MID_FOLDED_CLASS} />
      {/* "Deselect", not "Clear": the programmer's whole-desk Clear is the other "Clear", and
          "drop the selection" and "release every value on the rig" must not share a label. */}
      <Button variant="ghost" size="sm" onClick={onClear} title="Deselect all">
        <X className="size-3.5" />
        <span className="hidden sm:inline">Deselect</span>
      </Button>
    </div>
  )
}
