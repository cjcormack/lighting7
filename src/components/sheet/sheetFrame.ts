/**
 * The list shell, stated once (CLAUDE.md §List shell).
 *
 * Every list view — Fixtures › List, Groups › List, the programmer, Show › Table, Channels › Table
 * and the patch list — is one anatomy: a 48px header row, any number of 40px chrome rows, the
 * 40px selection bar, the sheet, a 22px footer. The values are the programmer chrome system's
 * (`lighting7/docs/plans/programmer-chrome-design/`) and the sheet kit's
 * (`sheet-views-design/`); nothing here is a new number. What is new is that they are *imported*
 * rather than kept in step by hand: `SheetTable` and `FixturesTable` draw their frame from these,
 * `SheetPage` draws the rows around them, and a surface that wants to differ says so at the
 * import, in one file. The design record is `lighting7/docs/plans/list-shell-design/`.
 *
 * Three rules the strings encode, each of which was a measured inconsistency before this file:
 *
 * - **One 12px gutter** (`px-3`) on every row of every list. The stack header was 16 and the plain
 *   lists 16 + 16 (a `Card`'s margin and its padding).
 * - **One line between neighbours.** A chrome row owns its `border-b`; the sheet owns no top line;
 *   the footer owns its `border-t`. The bar's `border-b` and the sheet's `border-t` used to stack
 *   to 2px on four of the six.
 * - **Three grounds.** The page (`<main>`'s `bg-muted/40`), which every chrome row sits on with no
 *   ground of its own; the sheet (`bg-background` — header row, sticky first column and body, one
 *   colour, so a name column can never read darker than its cells); and a divider row
 *   (`bg-muted/30`). Two named exceptions, both by decision: the programmer's row A keeps its
 *   `bg-card/50` wash, and `ShowHeader`'s line stays `border-transparent` until the unlocked wash
 *   colours it.
 *
 * Whole class strings, never template literals: Tailwind's scanner reads complete strings, and a
 * class assembled at runtime is a class it never emits.
 */

/** The page header — breadcrumbs, the view switcher, the page's actions. 48px, 32px controls. */
export const PAGE_HEADER_CLASS = 'flex h-12 shrink-0 items-center gap-2 border-b px-3'

/**
 * A chrome row — a toolbar, the programmer's row A and row B, the patch list's chips row. 40px
 * holding 32px controls, so the 4px above and below a control is the row's own inset.
 */
export const CHROME_ROW_CLASS = 'flex h-10 shrink-0 items-center gap-2 border-b px-3'

/**
 * The sheet's scroller. `bg-background` explicitly: the sticky first column has to be opaque to
 * cover the cells sliding under it, and with the sheet itself transparent it took its colour from
 * whatever the page happened to be — over `<main>`'s `bg-muted/40` the name column read as a dark
 * block laid over the rows. No `border-t`: the row above owns that line.
 */
export const SHEET_SCROLLER_CLASS = 'min-h-0 flex-1 overflow-auto bg-background'

/** The sticky header row: 30px, one line under it, the sheet ground. */
export const SHEET_HEADER_ROW_CLASS = 'sticky top-0 z-20 grid border-b border-border bg-background'

/** A header cell: 11px uppercase, tracked, muted. */
export const SHEET_HEADER_CELL_CLASS =
  'px-1.5 py-1.5 text-[11px] font-medium uppercase tracking-wider text-muted-foreground'

/** The sticky first column's cell, header and row alike: opaque, on the sheet ground. */
export const SHEET_STICKY_CELL_CLASS = 'sticky left-0 z-10 bg-background'

/** A row: 36px (56 on the DMX sheet), one line under it, the selection wash or the hover. */
export const SHEET_ROW_CLASS = 'group/row grid h-full border-b border-border text-sm'

/** A divider row — Ungrouped, a separator. The one tinted row in a sheet. */
export const SHEET_DIVIDER_CLASS = 'flex h-full items-center border-b border-border bg-muted/30'

/**
 * The footer: 22px, the count on the left, the legend after it, `border-t` its own. It never
 * wraps — an item that does not fit is dropped whole by its own fold, never clipped mid-word.
 */
export const SHEET_FOOTER_CLASS =
  'flex h-[22px] shrink-0 items-center gap-3 overflow-hidden whitespace-nowrap border-t px-3 text-[10.5px] text-muted-foreground'
