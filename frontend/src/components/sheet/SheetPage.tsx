import type { ReactNode } from 'react'
import { Loader2 } from 'lucide-react'
import { cn } from '@/lib/utils'
import {
  CHROME_ROW_CLASS,
  PAGE_HEADER_CLASS,
  SHEET_FOOTER_CLASS,
} from './sheetFrame'

/**
 * The list shell — the column every list view is (CLAUDE.md §List shell, design record
 * `lighting7/docs/plans/list-shell-design/`). Thin components over `sheetFrame.ts`, so a surface
 * writes the slots and never the classes:
 *
 * ```
 * <SheetPage>
 *   <SheetPage.Header>breadcrumbs · the view switcher</SheetPage.Header>
 *   <SheetPage.Row>filter · spacer · Lit · Columns</SheetPage.Row>
 *   <SelectionBar … />                      row C, the kit's
 *   <SheetTable … /> or <FixturesTable … />  the sheet, the only scroller
 *   <SheetPage.Footer>the count · the legend</SheetPage.Footer>
 * </SheetPage>
 * ```
 *
 * The page fills `<main>` and never scrolls: the sheet is the one scroller, which is what keeps a
 * 393px-tall landscape phone from getting 169px of grid under two scroll bars. No `Card` — the
 * page ground is `<main>`'s, and the sheet is recessed on it.
 */
export function SheetPage({ className, children }: { className?: string; children: ReactNode }) {
  return <div className={cn('flex h-full min-h-0 flex-col', className)}>{children}</div>
}

/**
 * The 48px header row. The `@container` is an **unpadded wrapper** around the padded row, not the
 * row itself: everything that reads a width here — the view switchers' labels, `Breadcrumbs`'
 * collapsed form, the stack header's own folds — asks its nearest container, and a size query
 * measures the container's *content box*, so a container on the `px-3` row would answer 24px less
 * than the row's width and every threshold calibrated against it would fire early. `className`
 * goes on the row, where the line and the ground are (the unlocked wash, a gap).
 */
function Header({ className, children }: { className?: string; children?: ReactNode }) {
  return (
    <div className="@container shrink-0">
      <div className={cn(PAGE_HEADER_CLASS, className)}>{children}</div>
    </div>
  )
}

/**
 * A 40px chrome row. `minHeight` swaps `h-10` for `min-h-10` for the one row that is allowed to
 * wrap onto a second line — the programmer's short-height fold, where row A's two halves join
 * row B. A wrapped row is taller than 40 by construction; the minimum keeps the 4px inset when it
 * is not wrapped.
 */
function Row({
  className,
  minHeight = false,
  children,
}: {
  className?: string
  minHeight?: boolean
  children?: ReactNode
}) {
  return (
    <div className={cn(CHROME_ROW_CLASS, minHeight && 'h-auto min-h-10', className)}>{children}</div>
  )
}

/**
 * The 22px footer. The same shape as the header — an unpadded `@container` wrapper around the
 * padded row — so a legend can fold its own glosses by the footer's *own* column at its full width
 * (the programmer's footer sits beside the rail, and measuring the page would show the long
 * glosses at a width the column cannot hold; `OwnershipLegend`'s note). `className` goes on the
 * **wrapper** here, unlike the header's: what a caller passes is how the footer folds away
 * (`@max-[600px]:hidden`), and that has to hide the whole thing, line included.
 */
function Footer({ className, children }: { className?: string; children?: ReactNode }) {
  return (
    <div className={cn('@container shrink-0', className)}>
      <div className={SHEET_FOOTER_CLASS}>{children}</div>
    </div>
  )
}

/**
 * The body while loading, or when there is nothing to draw — a centred spinner or a sentence, on
 * the page ground under the same header row, so a list keeps its shape from loading to loaded.
 * One component for the two states rather than a `Card m-4 p-4` per route.
 */
function Empty({
  loading = false,
  className,
  children,
}: {
  /** Draw the spinner. With children, the sentence instead. */
  loading?: boolean
  className?: string
  children?: ReactNode
}) {
  return (
    <div
      className={cn(
        'flex min-h-0 flex-1 items-center justify-center p-4 text-sm text-muted-foreground',
        className,
      )}
    >
      {loading ? <Loader2 className="size-6 animate-spin" /> : children}
    </div>
  )
}

SheetPage.Header = Header
SheetPage.Row = Row
SheetPage.Footer = Footer
SheetPage.Empty = Empty

/**
 * The one swatch a footer's key draws: 12px, `rounded-sm`, wearing the **real** class of the thing
 * it stands for — `ownershipCellClass` for an ownership ring, `layerCellClass` for a layer state,
 * the cue row's own tint for Live and Next — so retuning the cell moves the legend with it. The
 * cue sheet, the DMX sheet and the programmer each drew a shape of their own before this
 * (`rounded-full border` · `rounded-sm ring-1 ring-inset` · the real class); one vocabulary now.
 */
export function LegendSwatch({ className, children }: { className?: string; children?: ReactNode }) {
  return (
    <span
      aria-hidden="true"
      className={cn('inline-flex size-3 shrink-0 items-center justify-center rounded-sm', className)}
    >
      {children}
    </span>
  )
}
