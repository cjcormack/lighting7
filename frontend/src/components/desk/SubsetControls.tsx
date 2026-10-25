import { useState } from 'react'
import { ChevronDown, ChevronLeft, ChevronRight, Grid2x2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { ToolbarMenuItem, ToolbarMenuLabel } from '@/components/sheet/ToolbarMenu'
import { cn } from '@/lib/utils'
import type { SubselectMode } from '@/api/selectionApi'
import { SUBSELECT_FILTER_MODES, SUBSELECT_MODE_LABELS, SUBSELECT_STEP_MODES } from '@/lib/cellsSubSelection'

/**
 * The selection's **subsets over rig order** — All · Odd · Even · 1st half · 2nd half · Invert ·
 * Masters only, and Previous / Next (busk-further plan D12) — as the busk rig band draws them and as
 * the programmer's heads chip does (programmer-toolbar plan D4). One module, so both send the same
 * `SubselectMode` for the same word; what a mode does is the caller's `onSubselect`:
 * `selection.subselect` while the window follows the desk, `lib/cellsSubSelection.ts`'s mirror over
 * the window's own selection when it does not.
 */

/** Each mode's hover, shared by every control that offers it. */
export const SUBSET_MODE_TITLES: Partial<Record<SubselectMode, string>> = {
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

/** The two steps' accessible names. */
export const SUBSET_STEP_LABELS: Partial<Record<SubselectMode, string>> = {
  PREV: 'Previous along the rig',
  NEXT: 'Next along the rig',
}

/** The Cells control's face while its prefix is folded: the mode in one word. */
const SUBSET_SHORT_LABELS: Record<SubselectMode, string> = {
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
 * The busk band's Cells menu (D12, revised 2026-09-21): *Elements: <last>* opening the seven
 * filters. `last` is the filter pressed here most recently and nothing more — the desk keeps no
 * sub-selection, so there is nothing to read one back from. The band's fold classes come in as props.
 */
export function CellsMenu({
  onSubselect,
  triggerClassName,
  prefixClassName,
  shortClassName,
}: {
  onSubselect: (mode: SubselectMode) => void
  triggerClassName?: string
  /** The *Elements:* prefix and the full mode word beside it. */
  prefixClassName?: string
  /** The short mode word, drawn while the prefix is folded. */
  shortClassName?: string
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
          className={cn(triggerClassName, 'gap-1')}
        >
          <Grid2x2 className="size-3.5" />
          {/* Never a bare glyph: the prefix folds, the mode word stays — in its short form once
              the prefix has gone (`1st`, `Masters`), its full form beside it (`1st half`). */}
          <span className={prefixClassName}>Elements: </span>
          <span className={prefixClassName}>{SUBSELECT_MODE_LABELS[last]}</span>
          <span data-cells-mode className={shortClassName}>{SUBSET_SHORT_LABELS[last]}</span>
          <ChevronDown className="size-3 text-muted-foreground" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start">
        <DropdownMenuLabel className="text-[11px] font-normal text-muted-foreground">Elements</DropdownMenuLabel>
        <DropdownMenuRadioGroup value={last} onValueChange={(mode) => press(mode as SubselectMode)}>
          {SUBSELECT_FILTER_MODES.map((mode) => (
            <DropdownMenuRadioItem key={mode} value={mode} title={SUBSET_MODE_TITLES[mode]}>
              {SUBSELECT_MODE_LABELS[mode]}
            </DropdownMenuRadioItem>
          ))}
        </DropdownMenuRadioGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

/** *Prev* / *Next*: a step along the rig order, which moves the selection rather than filtering it. */
export function StepButtons({ onSubselect }: { onSubselect: (mode: SubselectMode) => void }) {
  return (
    <div className="flex shrink-0 items-center gap-0.5" role="group" aria-label="Step the selection along the rig">
      {SUBSELECT_STEP_MODES.map((mode) => (
        <Button
          key={mode}
          variant="outline"
          size="sm"
          data-cells-step={mode}
          aria-label={SUBSET_STEP_LABELS[mode]}
          title={SUBSET_MODE_TITLES[mode]}
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
 * The subsets as a section of a toolbar menu — the heads chip's: *In rig order*, the seven filters
 * as a grid of chips, then *Previous* and *Next*. Written with the `ToolbarMenu` pieces, so it is a
 * dropdown's items on a desk and a bottom sheet's rows on a phone.
 */
export function SubsetMenuSection({
  onSubselect,
  disabled,
}: {
  onSubselect: (mode: SubselectMode) => void
  /** Nothing to narrow — no selection. */
  disabled?: boolean
}) {
  const chip = 'justify-center rounded-md border text-xs'
  return (
    <>
      <ToolbarMenuLabel>In rig order</ToolbarMenuLabel>
      <div className="grid grid-cols-3 gap-1 px-1 pb-1" data-subset-grid>
        {SUBSELECT_FILTER_MODES.map((mode) => (
          <ToolbarMenuItem
            key={mode}
            disabled={disabled}
            title={SUBSET_MODE_TITLES[mode]}
            className={chip}
            onSelect={() => onSubselect(mode)}
          >
            {SUBSELECT_MODE_LABELS[mode]}
          </ToolbarMenuItem>
        ))}
      </div>
      <div className="grid grid-cols-2 gap-1 px-1 pb-1">
        {SUBSELECT_STEP_MODES.map((mode) => (
          <ToolbarMenuItem
            key={mode}
            disabled={disabled}
            title={SUBSET_MODE_TITLES[mode]}
            className={chip}
            onSelect={() => onSubselect(mode)}
          >
            {mode === 'PREV' ? '‹ Previous' : 'Next ›'}
          </ToolbarMenuItem>
        ))}
      </div>
    </>
  )
}
