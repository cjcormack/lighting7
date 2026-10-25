import { useCallback, type ReactNode } from 'react'
import { ChevronDown, Crosshair, Flashlight, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { useStoredLocateActions } from '@/hooks/useStoredLocateActions'
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
   * The selection's own verbs, drawn before Locate and Highlight: a cell selection's
   * Set · Clear · Spread (`CellSelectionActions`), or a rows-only selection's whole-selection row
   * Spread.
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
import { WORD_CLASS } from '../sheet/toolbarFolds'

export function SelectionToolbar({
  locateTargets,
  targets,
  onClear,
  actions,
}: SelectionToolbarProps) {
  // `shrink-0`: the bar is one line that never wraps, and these are its verbs.
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
      <LocateHighlightButtons
        locateTargets={locateTargets}
        targets={targets}
        wordClass={WORD_CLASS}
      />
      {/* "Deselect", not "Clear": the selection's way out and the programmer's Clear, which releases
          every value on the rig, must not share a label. */}
      <Button variant="ghost" size="sm" onClick={onClear} title="Deselect all">
        <X className="size-3.5" />
        <span className="hidden sm:inline">Deselect</span>
      </Button>
    </div>
  )
}

/**
 * Locate on the selection: all located → release everything, otherwise light up the ones not yet
 * on. The programmer heads chip's phone menu reads it as well as the buttons below.
 */
export function useLocateSelection(locateTargets: readonly LocateTarget[]) {
  const { data: locateState } = useLocateStateQuery()
  const [toggleLocate] = useToggleLocateMutation()
  const isActive = (target: LocateTarget) =>
    locateState?.targets.some((t) => t.type === target.type && t.key === target.key) ?? false
  const allLocated = locateTargets.length > 0 && locateTargets.every(isActive)
  const locateSelection = () => {
    const toToggle = allLocated ? locateTargets : locateTargets.filter((t) => !isActive(t))
    for (const target of toToggle) {
      toggleLocate(target)
        .unwrap()
        .catch((err) => console.error(`Locate toggle failed for ${target.type} '${target.key}'`, err))
    }
  }
  return { allLocated, locateSelection }
}

/**
 * Locate, its ▾ (where Locate points each unit) and Highlight — this toolbar's, and the right end
 * of the programmer's row C. [foldClass] is where the host folds all three; [wordClass] where
 * their words go.
 */
export function LocateHighlightButtons({
  locateTargets,
  targets,
  foldClass,
  wordClass,
}: {
  locateTargets: readonly LocateTarget[]
  targets: readonly WriteTarget[]
  foldClass?: string
  wordClass: string
}) {
  const { allLocated, locateSelection } = useLocateSelection(locateTargets)
  const storedLocate = useStoredLocateActions()
  const getTargets = useCallback(() => [...targets], [targets])
  const highlight = useHighlight(getTargets)
  return (
    <>
      <Tooltip>
        <TooltipTrigger asChild>
          <Button
            variant={allLocated ? 'default' : 'outline'}
            size="sm"
            onClick={locateSelection}
            className={cn(foldClass, allLocated && 'bg-sky-500 text-white hover:bg-sky-600')}
          >
            <Crosshair className="size-3.5" />
            <span className={wordClass}>Locate</span>
          </Button>
        </TooltipTrigger>
        <TooltipContent>
          {allLocated ? 'Release locate on the selection' : 'Locate the selection: white beam at centre'}
        </TooltipContent>
      </Tooltip>
      {/* Where Locate points each unit (venue-visit plan D6): stored on the patch, read from what the
          desk is sending now. Folds with Locate, so the phone's row C — measured to the pixel — keeps
          its budget; there the patch sheet's Locate box is the way in. */}
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button
            variant="outline"
            size="sm"
            className={cn(foldClass, '-ml-1 px-1.5')}
            aria-label="Locate options"
            title="Where Locate points the selection"
            disabled={locateTargets.length === 0 || storedLocate.projectId == null}
          >
            <ChevronDown className="size-3.5" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end">
          <DropdownMenuItem
            disabled={storedLocate.busy}
            onSelect={() => storedLocate.storeFromCurrent(locateTargets)}
          >
            Set locate from current
          </DropdownMenuItem>
          <DropdownMenuItem
            disabled={storedLocate.busy}
            onSelect={() => storedLocate.clearLocate(locateTargets)}
          >
            Clear locate
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
      <Tooltip>
        <TooltipTrigger asChild>
          <Button
            variant={highlight.isActive ? 'default' : 'outline'}
            size="sm"
            className={foldClass}
            onPointerDown={highlight.press}
            onPointerUp={highlight.release}
            onPointerCancel={highlight.release}
            onPointerLeave={highlight.release}
          >
            <Flashlight className="size-3.5" />
            <span className={wordClass}>Highlight</span>
          </Button>
        </TooltipTrigger>
        <TooltipContent>Hold: full intensity on the selection, restored on release</TooltipContent>
      </Tooltip>
    </>
  )
}
