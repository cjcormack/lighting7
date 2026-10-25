import { useRef, useState, type ReactNode, type Ref } from 'react'
import { Pencil } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from '@/components/ui/sheet'
import { cn } from '@/lib/utils'
import { FAMILY_LABELS, type AttributeFamily } from '@/lib/attributeFamily'
import { KBD_CLASS, WORD_CLASS } from '@/components/sheet/toolbarFolds'
import { ReleaseTargetsButton } from '@/components/fixtureSheet/ReleaseTargetsButton'
import type { ReleaseTarget } from '@/components/fixtureSheet/useRelease'
import { LocateHighlightButtons } from '@/components/fixtures-list/SelectionToolbar'
import type { WriteTarget } from '@/components/fixtures-list/rowModel'
import type { LocateTarget } from '@/store/locate'
import { useRowCBand } from './rowCBand'
import { plural } from '@/lib/plural'

export interface ProgrammerSetProps {
  /** Set's hover, or the reason this scope refuses it. */
  title: string
  enabled: boolean
  /** The button, where the cells' editor anchors (`editorAnchorRef`). */
  setRef: Ref<HTMLButtonElement>
  /** Cells selected: open their editor, or close the one Set opened. */
  onSet: () => void
  /** Rows only: the families the rows have a column in, in the order to offer them. */
  families: readonly AttributeFamily[]
  /** Rows only: select the rows' cells in that family's columns and open their editor. */
  onSetFamily: (family: AttributeFamily) => void
}

export interface ProgrammerReleaseProps {
  targets: readonly ReleaseTarget[]
  /** The heads the whole-heads arm releases — a group's every member, shown or not. */
  heads: number
  disabledReason?: string
  onReleaseCells: () => void
  cellsRefusal?: string
  cellsTitle: string
}

/**
 * The programmer's row C after the heads chip and the mask (plan D3): *what to do to them* — Set,
 * Fan, Release ▾ — then *where they are on the rig* — Locate ▾ and Highlight, pushed to the right
 * edge, and in the heads chip's menu on a phone.
 *
 * **Set works on whole rows too** (D6). With cells it is the marquee's Enter, as on every sheet.
 * With rows alone it asks which attribute first; the answer becomes a marquee over the rows' cells
 * in that family's columns, so the mask becomes that family and the same editor opens.
 */
export function ProgrammerSelectionVerbs({
  heads,
  cells,
  set,
  fan,
  release,
  locateTargets,
  writeTargets,
}: {
  heads: number
  cells: number
  set: ProgrammerSetProps
  /** The Fan panel (`SpreadPopover`), over the cells' columns or the rows'. */
  fan: ReactNode
  release: ProgrammerReleaseProps
  locateTargets: readonly LocateTarget[]
  writeTargets: readonly WriteTarget[]
}) {
  const band = useRowCBand()
  const showKeys = band === 'desk'
  return (
    <>
      <span aria-hidden className="mx-0.5 h-[22px] w-px shrink-0 bg-border" />
      <SetButton heads={heads} cells={cells} set={set} showKeys={showKeys} sheet={band === 'phone'} />
      {fan}
      <ReleaseTargetsButton
        targets={release.targets}
        wordClass={WORD_CLASS}
        disabledReason={release.disabledReason}
        split={{
          heads: release.heads,
          cells,
          onReleaseCells: release.onReleaseCells,
          cellsRefusal: release.cellsRefusal,
          cellsTitle: release.cellsTitle,
          showKeys,
          sheet: band === 'phone',
        }}
      />
      {band !== 'phone' && (
        <>
          <span className="min-w-2 flex-1" />
          <LocateHighlightButtons
            locateTargets={locateTargets}
            targets={writeTargets}
            wordClass={band === 'desk' ? 'inline' : 'hidden'}
          />
        </>
      )}
    </>
  )
}

function SetButton({
  heads,
  cells,
  set,
  showKeys,
  sheet,
}: {
  heads: number
  cells: number
  set: ProgrammerSetProps
  showKeys: boolean
  sheet: boolean
}) {
  const [asking, setAsking] = useState(false)
  // A pick hands focus to the editor it opens; the question's close must not take it back to Set.
  const pickedRef = useRef(false)
  const onCells = cells > 0
  const canAsk = set.enabled && set.families.length > 0
  const title = !onCells && set.enabled && set.families.length === 0 ? 'These heads have no column to set' : set.title
  const button = (
    <Button
      ref={set.setRef}
      variant="outline"
      size="sm"
      disabled={onCells ? !set.enabled : !canAsk}
      aria-label={onCells ? 'Set' : `Set an attribute on the ${plural(heads, 'head')}`}
      title={title}
      onClick={(e) => {
        if (onCells) {
          // Cells: the marquee's editor, never the question — and, under the popover's trigger,
          // not the trigger's own toggle either.
          e.preventDefault()
          set.onSet()
        } else if (sheet) {
          setAsking((open) => !open)
        }
      }}
    >
      <Pencil className="size-3.5" />
      <span className={WORD_CLASS}>Set</span>
      {showKeys && onCells && <kbd className={KBD_CLASS}>⏎</kbd>}
    </Button>
  )
  const pick = (family: AttributeFamily) => {
    pickedRef.current = true
    setAsking(false)
    set.onSetFamily(family)
  }
  const question = `${plural(heads, 'head')}, no cells picked — which attribute?`
  if (sheet) {
    return (
      <>
        {button}
        <Sheet open={asking && !onCells} onOpenChange={setAsking}>
          <SheetContent
            side="bottom"
            className="gap-0 pb-4"
            onCloseAutoFocus={(e) => {
              if (pickedRef.current) e.preventDefault()
              pickedRef.current = false
            }}
          >
            <SheetHeader className="pb-2">
              <SheetTitle>Set</SheetTitle>
              <SheetDescription>{question}</SheetDescription>
            </SheetHeader>
            <FamilyChoice families={set.families} onPick={pick} touch />
          </SheetContent>
        </Sheet>
      </>
    )
  }
  return (
    <Popover open={asking && !onCells} onOpenChange={setAsking}>
      <PopoverTrigger asChild>{button}</PopoverTrigger>
      <PopoverContent
        align="start"
        className="w-auto min-w-64 space-y-2 p-2.5"
        onCloseAutoFocus={(e) => {
          if (pickedRef.current) e.preventDefault()
          pickedRef.current = false
        }}
      >
        <p className="px-0.5 text-[11px] text-muted-foreground">{question}</p>
        <FamilyChoice families={set.families} onPick={pick} />
      </PopoverContent>
    </Popover>
  )
}

function FamilyChoice({
  families,
  onPick,
  touch = false,
}: {
  families: readonly AttributeFamily[]
  onPick: (family: AttributeFamily) => void
  touch?: boolean
}) {
  return (
    <div
      role="group"
      aria-label="Attribute"
      className={cn('flex overflow-hidden rounded-md border', touch ? 'mx-4 h-10' : 'h-[30px]')}
    >
      {families.map((family, i) => (
        <button
          key={family}
          type="button"
          data-set-family={family}
          onClick={() => onPick(family)}
          className={cn('flex-1 px-3 text-xs hover:bg-accent', i > 0 && 'border-l')}
        >
          {FAMILY_LABELS[family].singular}
        </button>
      ))}
    </div>
  )
}
