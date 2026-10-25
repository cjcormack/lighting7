import { useState } from 'react'
import { ChevronDown, ChevronLeft, ChevronRight, Grid2x2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { cn } from '@/lib/utils'
import type { SubselectMode } from '@/api/selectionApi'
import { SUBSELECT_FILTER_MODES, SUBSELECT_MODE_LABELS, SUBSELECT_STEP_MODES } from '@/lib/cellsSubSelection'

/**
 * The selection's subsets over rig order (busk-further plan D12), as the busk rig band and the
 * programmer's heads chip both draw them. Every control here only says which `SubselectMode` was
 * pressed; what a press does — `selection.subselect` while the window follows the desk, the
 * `lib/cellsSubSelection.ts` mirror over the window's own copy when it does not — is the host's.
 */

export const SUBSELECT_MODE_TITLES: Record<SubselectMode, string> = {
  ALL: 'Every selected element widened to its whole fixture',
  ODD: 'Every other unit in rig order, from the first — elements where a selected head has them',
  EVEN: 'Every other unit in rig order, from the second',
  FIRST_HALF: 'The first half of the selection in rig order',
  SECOND_HALF: 'The second half of the selection in rig order',
  INVERT: 'Every unit of the selected heads that is not selected',
  MASTERS: 'The masters only: a multi-head fixture’s own channels, no element',
  NEXT: 'The whole selection one step along rig order; one element when only elements are selected',
  PREV: 'The whole selection one step back along rig order',
}

export const SUBSELECT_STEP_LABELS: Record<'PREV' | 'NEXT', string> = {
  PREV: 'Previous along the rig',
  NEXT: 'Next along the rig',
}

/** The menu face's mode word while its prefix is folded. */
const SHORT_LABELS: Record<SubselectMode, string> = {
  ALL: 'All',
  ODD: 'Odd',
  EVEN: 'Even',
  NEXT: 'Next',
  PREV: 'Prev',
  FIRST_HALF: '1st',
  SECOND_HALF: '2nd',
  INVERT: 'Invert',
  MASTERS: 'Masters',
}

/**
 * *Elements: <last>* opening the seven filters. `last` is the filter pressed here most recently and
 * nothing more — the desk keeps no sub-selection, so there is nothing to read one back from. The
 * host's container queries decide when the prefix and the full word fold, so their classes are
 * the host's.
 */
export function SubselectMenu({
  onSubselect,
  className,
  prefixClass,
  shortClass,
}: {
  onSubselect: (mode: SubselectMode) => void
  className?: string
  /** The *Elements:* prefix and the full mode word beside it. */
  prefixClass: string
  /** The short mode word, drawn while the prefix is folded. */
  shortClass: string
}) {
  const [last, setLast] = useState<SubselectMode>('ALL')
  const press = (mode: SubselectMode) => {
    setLast(mode)
    onSubselect(mode)
  }
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button
          variant="outline"
          size="sm"
          data-cells-chip
          aria-label={`Elements: ${SUBSELECT_MODE_LABELS[last]}`}
          title={`Sub-selection over rig order — ${SUBSELECT_FILTER_MODES.map((mode) => SUBSELECT_MODE_LABELS[mode]).join(' · ')}`}
          className={cn(className, 'gap-1')}
        >
          <Grid2x2 className="size-3.5" />
          <span className={prefixClass}>Elements: </span>
          <span className={prefixClass}>{SUBSELECT_MODE_LABELS[last]}</span>
          <span data-cells-mode className={shortClass}>{SHORT_LABELS[last]}</span>
          <ChevronDown className="size-3 text-muted-foreground" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start">
        <DropdownMenuLabel className="text-[11px] font-normal text-muted-foreground">Elements</DropdownMenuLabel>
        <DropdownMenuRadioGroup value={last} onValueChange={(mode) => press(mode as SubselectMode)}>
          {SUBSELECT_FILTER_MODES.map((mode) => (
            <DropdownMenuRadioItem key={mode} value={mode} title={SUBSELECT_MODE_TITLES[mode]}>
              {SUBSELECT_MODE_LABELS[mode]}
            </DropdownMenuRadioItem>
          ))}
        </DropdownMenuRadioGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

/** *Prev* / *Next*: a step along the rig order, which moves the selection rather than filtering it. */
export function SubselectSteps({ onSubselect }: { onSubselect: (mode: SubselectMode) => void }) {
  return (
    <div className="flex shrink-0 items-center gap-0.5" role="group" aria-label="Step the selection along the rig">
      {SUBSELECT_STEP_MODES.map((mode) => (
        <Button
          key={mode}
          variant="outline"
          size="sm"
          data-cells-step={mode}
          aria-label={SUBSELECT_STEP_LABELS[mode as 'PREV' | 'NEXT']}
          title={SUBSELECT_MODE_TITLES[mode]}
          className="h-7 w-7 px-0"
          onClick={() => onSubselect(mode)}
        >
          {mode === 'PREV' ? <ChevronLeft className="size-3.5" /> : <ChevronRight className="size-3.5" />}
        </Button>
      ))}
    </div>
  )
}

/**
 * The filters and the two steps as items of a menu the host already owns — the busk band's
 * compact verbs menu (`list`, one item a row) and the heads chip's (`grid`, the filters three a
 * row and the steps under them, as the boards draw it).
 */
export function SubselectMenuItems({
  onSubselect,
  layout,
}: {
  onSubselect: (mode: SubselectMode) => void
  layout: 'list' | 'grid'
}) {
  if (layout === 'list') {
    return (
      <>
        {SUBSELECT_FILTER_MODES.map((mode) => (
          <DropdownMenuItem key={mode} onSelect={() => onSubselect(mode)}>
            {SUBSELECT_MODE_LABELS[mode]}
          </DropdownMenuItem>
        ))}
      </>
    )
  }
  return (
    <>
      <div className="grid grid-cols-3 gap-1 px-1">
        {SUBSELECT_FILTER_MODES.map((mode) => (
          <DropdownMenuItem
            key={mode}
            data-subselect={mode}
            title={SUBSELECT_MODE_TITLES[mode]}
            onSelect={() => onSubselect(mode)}
            className="h-7 justify-center rounded-[5px] border px-1 text-[11.5px]"
          >
            {SUBSELECT_MODE_LABELS[mode]}
          </DropdownMenuItem>
        ))}
      </div>
      <div className="flex gap-1 px-1 pt-1">
        {SUBSELECT_STEP_MODES.map((mode) => (
          <DropdownMenuItem
            key={mode}
            data-subselect={mode}
            aria-label={SUBSELECT_STEP_LABELS[mode as 'PREV' | 'NEXT']}
            title={SUBSELECT_MODE_TITLES[mode]}
            onSelect={() => onSubselect(mode)}
            className="h-7 flex-1 justify-center rounded-[5px] border px-1 text-[11.5px]"
          >
            {mode === 'PREV' ? (
              <>
                <ChevronLeft className="size-3.5" /> Previous
              </>
            ) : (
              <>
                Next <ChevronRight className="size-3.5" />
              </>
            )}
          </DropdownMenuItem>
        ))}
      </div>
    </>
  )
}

/** The steps as list items, for a host menu that draws [SubselectMenuItems] as a list. */
export function SubselectStepItems({ onSubselect }: { onSubselect: (mode: SubselectMode) => void }) {
  return (
    <>
      {SUBSELECT_STEP_MODES.map((mode) => (
        <DropdownMenuItem key={mode} onSelect={() => onSubselect(mode)} title={SUBSELECT_MODE_TITLES[mode]}>
          {SUBSELECT_STEP_LABELS[mode as 'PREV' | 'NEXT']}
        </DropdownMenuItem>
      ))}
    </>
  )
}
