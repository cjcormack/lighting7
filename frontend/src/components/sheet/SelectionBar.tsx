import { useRef, type ReactNode } from 'react'
import { MousePointerSquareDashed } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { cn } from '@/lib/utils'
import { useMediaQuery } from '@/hooks/useMediaQuery'
import { MID_FOLDED_CLASS, PHONE_FOLDED_CLASS } from './toolbarFolds'
import { CHROME_ROW_CLASS } from './sheetFrame'
import { selectionBandState } from './selectionBand'

/** The ShowBar's key-cap styling, so the two hints read as one vocabulary. */
const KBD_CLASS = 'rounded border bg-muted/50 px-1.5 py-px text-[9.5px]'

/**
 * The short-viewport fold, spelled for `matchMedia` — one of the copies `shortViewport.test.ts`
 * pins to one number. It cannot be imported from `ProgrammerPage`: `import/no-cycle` is an error
 * here, and the kit must not reach into a page anyway.
 *
 * Row C asks it for a different reason than the rest of D8 does. The others fold *chrome* to buy
 * height; this one decides whether the selection bar is permanently in the flow or held back until
 * a drag ends — see `selectionBandState`.
 */
const SHORT_VIEWPORT = '(max-height: 500px)'

export interface SelectionBarProps {
  /**
   * The selection counted as rows — `4 fixtures`, `4 cues` — or null where the surface has no row
   * axis (the DMX grid is all cells). Folds at 800 beside a cell count, since the cell count's
   * hover carries it (see [cellTitle]).
   */
  rowLabel: string | null
  /** The selection counted as cells — `4 cells`, `8 channels` — or null with no cells selected. */
  cellLabel: string | null
  /**
   * The cell count's hover: the row count and the scope, `4 fixtures · 4 × Colour — edit once,
   * applies to all`. The one place the row count is said on a phone.
   */
  cellTitle?: string
  /**
   * The family pill: the attribute family the marquee named on the programmer, the column's own
   * name elsewhere (`Address`, `Value`, `Fade`). Null draws none. Folded on the phone arm.
   */
  family: string | null
  /** The two keyboard hints, each drawn only where the surface's permission allows the key. */
  hints: { entry: boolean; clear: boolean }
  /**
   * A chip after the family pill and the hints, before the strip — the programmer's desk chip
   * (`components/desk/DeskChip.tsx`), which says whose selection this is. Drawn in the reserved
   * arm too, since a window can unlink with nothing selected — with one limit it shares with the
   * whole bar: on a short viewport with nothing selected the bar is out of the flow altogether
   * (`selectionBandState`'s `absent`, the space plan's call that 40px is worth more there), so
   * the chip is not on screen until something is selected. The busk band's chip has no such
   * arm. Absent on every other sheet.
   */
  chip?: ReactNode
  /**
   * What rides the bar between the counts and the verbs — the programmer's template strip, the
   * cue sheet's lock note. Absent leaves the slack to `ml-auto` below.
   */
  strip?: ReactNode
  /**
   * The verbs, right-aligned: the surface's `CellSelectionActions` (Set · Clear · Spread), its own
   * verbs after them, and Deselect last and ghost. Null when nothing is selected.
   */
  verbs: ReactNode | null
  /** A marquee drag is in flight — the band holds its place for the duration on a short viewport. */
  marqueeDragging: boolean
  /**
   * Fold the verbs' words at `@max-[1100px]` and Locate/Highlight at `@max-[800px]` to give the
   * strip room (`toolbarFolds.ts`). Defaults to "there is a strip"; a surface whose strip is a
   * short label rather than a scroller — the cue sheet's lock note — says false.
   */
  foldForStrip?: boolean
}

/**
 * Row C — the selection bar, one 40px line that never wraps, shared by every sheet.
 *
 * The left half is the kit's — the glyph, the counts, the family pill, the ⏎/⌫ hints — and the
 * verbs are the surface's (CLAUDE.md §Sheet kit): the programmer adds Locate, Highlight and the
 * template strip, the patch list Locate · Highlight · Unpatch, the DMX sheet Park · Unpark, the
 * cue sheet Arm as next. Deselect is always last and always ghost, and it is the toolbar's
 * (`SelectionToolbar` or the surface's own) rather than this bar's: since rows and cells became
 * one selection there is exactly one way out, whichever shape the selection is in.
 *
 * **Whether it is in the flow with nothing selected is `selectionBandState`'s answer, not a
 * constant** (`PD-SELECTION-BAR-SHIFT`). It used to be simply absent, which meant the first cell
 * of a marquee mounted it and pushed every row down by its height under a pointer that was
 * mid-drag. On a desk the band is now always in the flow — reserved and quiet when there is no
 * selection, since the height is only saved at a moment the grid is not being used anyway. On a
 * landscape phone the 40px is worth more than that, so there it stays out of the flow and its
 * presence is *held* for the duration of a drag instead: it arrives on pointer-up, not on the
 * first cell.
 *
 * It is full-bleed with a `border-b` rather than a rounded card inset in a padded block: it is a
 * *rung of the grid's chrome* like row B above it, not an object floating over the page — the
 * shell's `CHROME_ROW_CLASS`, so it cannot drift a pixel from the rows above it, and the line under
 * it is the one line between the bar and the sheet (the sheet draws no top line of its own).
 *
 * **Below `@[800px]` the row starts shedding.** The row count and its separator fold into the cell
 * count's hover (`MID_FOLDED_CLASS`); below `@[600px]` the family pill goes too
 * (`PHONE_FOLDED_CLASS`). The hints go first of all, at `@[1100px]` — a hint, not a control.
 *
 * **The wash is `foreground/5`, not a primary tint** (D4). Selection is neutral on every sheet, so
 * that `--primary` can mean one thing — you own this value — from the ownership rings down to the
 * row wash.
 *
 * It is a component rather than JSX inside a render prop, and that is load-bearing on the
 * programmer: `renderToolbar` is invoked during the container's render, so a hook in its body is a
 * hook of *that* component. It returns `null` itself rather than being mounted conditionally, so
 * its own hooks run in a stable order.
 */
export function SelectionBar({
  rowLabel,
  cellLabel,
  cellTitle,
  family,
  hints,
  chip,
  strip,
  verbs,
  marqueeDragging,
  foldForStrip = strip != null,
}: SelectionBarProps) {
  const shortViewport = useMediaQuery(SHORT_VIEWPORT)
  const hasSelection = !!verbs || cellLabel != null
  // The presence from *before* the drag, which is the whole trick: the drag flag and the marquee's
  // first cells arrive in one commit, so a latch taken when `marqueeDragging` flips would already
  // read `true` and hold exactly the arrival it is meant to hold back. Writing a ref during render
  // is safe here — it is idempotent and touches nothing outside this component.
  //
  // It is latched on **every** arm and not only the short one, even though `selectionBandState`
  // reads `heldPresence` past its tall-viewport early return alone. A phone can be rotated between
  // one gesture and the next, and a latch that only ran while short would answer the first drag
  // after the fold with whatever was true whenever it last happened to be short.
  const idlePresenceRef = useRef(hasSelection)
  if (!marqueeDragging) idlePresenceRef.current = hasSelection
  const band = selectionBandState({
    shortViewport,
    heldPresence: marqueeDragging ? idlePresenceRef.current : null,
    hasSelection,
  })

  if (band === 'absent') return null

  // Holding the height with nothing to say. No `bg-foreground/5` wash: that wash *is* the selection
  // (D4), so wearing it over an empty bar would say there is one. The sentence is there because
  // 40px of otherwise blank strip above a grid reads as a rendering fault rather than as a rung.
  if (band === 'reserved') {
    return (
      <div className={cn(CHROME_ROW_CLASS, 'min-w-0 text-muted-foreground')}>
        <MousePointerSquareDashed className="size-3.5 shrink-0 opacity-60" />
        <span className="text-xs">Nothing selected</span>
        {chip}
      </div>
    )
  }

  return (
    // `group/bar` + `data-strip`: the gate the word folds in `toolbarFolds.ts` read, so a verb's
    // word folds for the strip only on a bar that has one.
    <div
      className={cn(CHROME_ROW_CLASS, 'group/bar min-w-0 bg-foreground/5')}
      data-strip={foldForStrip ? '' : undefined}
    >
      <MousePointerSquareDashed className="size-3.5 shrink-0" />
      {/* One selection, counted two ways under a marquee: the rows it reaches, and the cells it
          names on them. With cells in play the row count and its separator fold into the cell
          count's hover, and the width goes to the strip. With rows only it is the one count there
          is, so it stays. */}
      {rowLabel != null && (
        <span
          className={cn(
            'whitespace-nowrap text-xs font-semibold tabular-nums',
            cellLabel != null && MID_FOLDED_CLASS,
          )}
        >
          {rowLabel}
        </span>
      )}
      {cellLabel != null && (
        <>
          {rowLabel != null && (
            <span className={cn('text-muted-foreground/50', MID_FOLDED_CLASS)}>·</span>
          )}
          <span className="whitespace-nowrap text-xs font-semibold tabular-nums" title={cellTitle}>
            {cellLabel}
          </span>
        </>
      )}
      {/* The family pill sits outside the cell block: on the programmer it is the mask the next
          press carries, which while following the desk can stand over a row-only selection (the
          bridge lands another window's mask as rows). Every other sheet passes null with no
          cells, so nothing changes there. */}
      {family != null && (
        <Badge
          variant="outline"
          className={cn('shrink-0 whitespace-nowrap px-1.5 py-0 text-[10px]', PHONE_FOLDED_CLASS)}
        >
          {family}
        </Badge>
      )}
      {cellLabel != null && (
        <>
          {/* The keyboard half: the two keys that reach the marquee's editor from the grid. Both
              hints follow the surface's own answer — each flag is false where its key is refused —
              so this cannot advertise a key that does nothing. A hint, not a control, so on a bar
              with a strip it is the first thing to go. */}
          {(hints.entry || hints.clear) && (
            <span className="inline-flex shrink-0 items-center gap-1 whitespace-nowrap text-[10px] text-muted-foreground group-data-[strip]/bar:@max-[1100px]:hidden">
              {hints.entry && (
                <>
                  <kbd className={KBD_CLASS}>⏎</kbd> edit
                </>
              )}
              {hints.clear && (
                <>
                  <kbd className={`${KBD_CLASS} ml-1`}>⌫</kbd> clear
                </>
              )}
            </span>
          )}
        </>
      )}
      {chip}
      {strip}
      {/* `ml-auto`, not a `flex-1` spacer. A strip's chip scroller is itself `flex-1`, and two
          `flex: 1 1 0%` siblings *split* the row's free space rather than one of them taking it
          all — so a spacer here silently stole roughly half the scroller's width and opened a
          blank gap before these buttons. An auto margin is resolved after flex growth, so it
          takes the whole slack when the strip is absent and exactly nothing when it is there. */}
      {verbs && <div className="ml-auto flex shrink-0 items-center">{verbs}</div>}
    </div>
  )
}
