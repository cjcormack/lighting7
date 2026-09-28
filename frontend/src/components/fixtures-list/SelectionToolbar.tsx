import { useCallback, type ReactNode } from 'react'
import { Crosshair, Flashlight, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { cn } from '@/lib/utils'
import { useLocateStateQuery, useToggleLocateMutation } from '../../store/locate'
import { useHighlight } from './useHighlight'
import type { LocateTarget } from '../../store/locate'
import type { WriteTarget } from './rowModel'

export interface SelectionToolbarProps {
  /** Selected rows as locate targets (groups stay groups — the backend
   *  handles their members natively). */
  locateTargets: readonly LocateTarget[]
  /** Distinct write targets (fixtures or elements) the selection expands to,
   *  in visible row order. */
  targets: readonly WriteTarget[]
  /** Drops the selection — cells if there are any, the rows otherwise. */
  onClear: () => void
  /**
   * The selection's own verbs, drawn before Locate and Highlight. A cell selection's
   * Set · Clear · Spread (`CellSelectionActions`) on every list; for a rows-only selection, nothing on
   * the programmer — where the marquee is what a selection is for — and on the two plain list
   * routes the whole-selection row Spread they have always had.
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

export function SelectionToolbar({
  locateTargets,
  targets,
  onClear,
  actions,
}: SelectionToolbarProps) {
  const { data: locateState } = useLocateStateQuery()
  const [toggleLocate] = useToggleLocateMutation()
  const getTargets = useCallback(() => [...targets], [targets])
  const highlight = useHighlight(getTargets)

  const isActive = (target: LocateTarget) =>
    locateState?.targets.some((t) => t.type === target.type && t.key === target.key) ?? false
  const allLocated = locateTargets.length > 0 && locateTargets.every(isActive)

  // All located → release everything; otherwise light up the ones not yet on.
  const locateSelection = () => {
    const toToggle = allLocated ? locateTargets : locateTargets.filter((t) => !isActive(t))
    for (const target of toToggle) {
      toggleLocate(target)
        .unwrap()
        .catch((err) => console.error(`Locate toggle failed for ${target.type} '${target.key}'`, err))
    }
  }

  // `shrink-0`: on the programmer's one-line selection bar this sits at the right end of a row
  // whose middle is a scroller, and a flex item that gives would be squeezed by the chips it is
  // supposed to sit beside.
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
      <Tooltip>
        <TooltipTrigger asChild>
          <Button
            variant={allLocated ? 'default' : 'outline'}
            size="sm"
            onClick={locateSelection}
            // Folds at 800 only on a bar with a strip (`STRIP_MID_FOLDED_CLASS`): on the plain lists
            // this toolbar is the only place Locate and Highlight exist.
            className={cn(STRIP_MID_FOLDED_CLASS, allLocated && 'bg-sky-500 text-white hover:bg-sky-600')}
          >
            <Crosshair className="size-3.5" />
            <span className={WORD_CLASS}>Locate</span>
          </Button>
        </TooltipTrigger>
        <TooltipContent>
          {allLocated ? 'Release locate on the selection' : 'Locate the selection: white beam at centre'}
        </TooltipContent>
      </Tooltip>
      <Tooltip>
        <TooltipTrigger asChild>
          <Button
            variant={highlight.isActive ? 'default' : 'outline'}
            size="sm"
            className={STRIP_MID_FOLDED_CLASS}
            onPointerDown={highlight.press}
            onPointerUp={highlight.release}
            onPointerCancel={highlight.release}
            onPointerLeave={highlight.release}
          >
            <Flashlight className="size-3.5" />
            <span className={WORD_CLASS}>Highlight</span>
          </Button>
        </TooltipTrigger>
        <TooltipContent>Hold: full intensity on the selection, restored on release</TooltipContent>
      </Tooltip>
      {/* "Deselect", not "Clear": on the programmer sheet this sits beside the programmer's
          own Clear, and two buttons a few pixels apart that mean "drop the selection" and
          "release every value on the rig" must not share a label. */}
      <Button variant="ghost" size="sm" onClick={onClear} title="Deselect all">
        <X className="size-3.5" />
        <span className="hidden sm:inline">Deselect</span>
      </Button>
    </div>
  )
}
