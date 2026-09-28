import { isEditableTarget } from '@/lib/domUtils'
import type { CellRef, RowId } from './cellSelectionModel'

/**
 * The keyboard half of a sheet's marquee: which cell a keystroke opens, and the two shapes every
 * surface's permission and copy take.
 *
 * Written once for every sheet (CLAUDE.md §Sheet kit). The programmer's scope gate —
 * `cellKeyboardPermission` and `cellActionCopy`, which read a `ProgrammerScope` — stays in
 * `fixtures-list/cellEntry.ts`; the patch list, the DMX sheet and the cue sheet each answer the
 * same two questions from their own facts (a locked show, an offline desk, a column that cannot be
 * cleared) in the shape declared here, so `CellSelectionActions` and the keyboard read one type.
 *
 * The gesture is spreadsheet-shaped — select cells, press Enter (or just start typing) and an
 * editor opens over them. It used to be a second editor of its own (`CellEntryPopover`, with a
 * grammar for `#ff8800`, `50%`, `full` and `pan,tilt`); there is one editor per column now, and
 * the character typed at the grid arrives in its first field. `fixtures-list/cellEntry.ts` keeps
 * the record of what that deletion cost.
 */

/**
 * The selected cells in the order the operator sees them: by displayed row, and within a row by
 * visible column.
 *
 * The caller takes the **first one that has an editor**, which is why this hands back the whole
 * order rather than just the winner. A marquee is geometric — `hitsFor` sweeps a rectangle over
 * rows and column bands — so it happily covers a Colour cell on a dimmer-only par, and opening
 * "the first selected cell" flatly would leave Enter doing nothing at all on a perfectly ordinary
 * selection. Which columns a row resolves is `buildRowCells`' answer and needs the rows, so it is
 * the container's half rather than this one's.
 *
 * The ordering is the first cell in display order, on both axes. (It was shared with
 * `singleColumnAnchor`, which anchored the editor a released drag used to open; that open and
 * that function are gone, and this is the one copy of the rule.) [rowOrder] and [columnOrder] are
 * as displayed;
 * a selected cell that is filtered out or in a hidden column ranks last rather than being dropped,
 * so a selection made entirely of such cells is still offered rather than silently empty.
 */
export function orderedSelectedCells<C extends string>(
  cells: readonly CellRef<C>[],
  rowOrder: readonly RowId[],
  columnOrder: readonly C[],
): CellRef<C>[] {
  const rowRank = new Map(rowOrder.map((id, index) => [id, index]))
  const colRank = new Map(columnOrder.map((col, index) => [col, index]))
  const rank = (cell: CellRef<C>): [number, number] => [
    rowRank.get(cell.rowId) ?? Infinity,
    colRank.get(cell.col) ?? Infinity,
  ]
  return [...cells].sort((a, b) => {
    const [aRow, aCol] = rank(a)
    const [bRow, bCol] = rank(b)
    return aRow - bRow || aCol - bCol
  })
}

/** Which of the marquee's two keyboard gestures the current scope may take. */
export interface CellKeyboardPermission {
  /** Enter / a character: the cell's own editor is opened over the selection and its commit taken. */
  entry: boolean
  /** Backspace / Delete: the selected cells — values and the local effects on them — are taken out of Local. */
  clear: boolean
}

/** The hovers on the selection bar's two cell verbs, per scope. */
export interface CellActionCopy {
  /** Set's hover, or the reason it is disabled. */
  setTitle: string
  /** Clear's hover, or the reason it is disabled. */
  clearTitle: string
}

/**
 * Is a keystroke's target a cell the live marquee already covers?
 *
 * The DOM half of the grid's "not from a focused control" guard, and the reason it needs a half at
 * all. A cell trigger is a `<button>`, so a bare `closest('button')` test calls it someone else's
 * control — and after a marquee drag it can be *exactly* where the focus is: the press focuses the
 * button under it (Chromium does, on mousedown; the release blurs it, but an editor closed by
 * Escape hands focus back to its trigger). Every arm of the marquee keyboard then fell through
 * from there, Enter to the button's own default activation — which is now "select this one cell",
 * and was then "open *that* cell's editor with nothing focused": either way, not the first
 * selected cell's editor with its first field focused and waiting.
 *
 * The exemption is exactly as wide as the marquee and no wider, which is what keeps the rest of
 * the guard intact: a template chip, the bar's own Set and a menu item are not inside a cell at
 * all; a cell *outside* the selection, tabbed to while one is live, is still its own editor's
 * trigger; and with no cells selected the caller never asks, so plain Tab-then-Enter is untouched
 * — it does exactly what a click on that cell does, which on every list this guard serves is
 * to select it.
 *
 * **It claims any control inside a covered cell, not the editor trigger specifically**, and that
 * is a deliberate width rather than an oversight: all four cell editors hang off a Popover
 * today, but naming the trigger — by `data-slot`, or by "the only button here" — would make this
 * exemption lapse silently the day one of them became a Select or a Dialog, which is the very
 * defect it exists to fix. The cost is the other direction: the grid's *second* in-cell control,
 * `OwnerJumpOverlay` (`FixturesTable.tsx`), would have its Enter and Backspace taken by the
 * marquee too. It does not today, because it renders only in Output scope, where
 * `cellKeyboardPermission` refuses both keys — so **a third in-cell control added in an editable
 * scope needs its own answer here**, and that is the check to make rather than a narrower
 * predicate now.
 *
 * Reads `data-cell` and `data-row-id`, the grid's addressing contract — the same two attributes
 * `FixturesTable` hangs on its rows and cells, and the same pair `orderedSelectedCells` above names
 * a cell by. Rename either and this has to move with it.
 */
/**
 * Which cell's editor is open, by the grid's own addressing contract.
 *
 * `data-state` is Radix's word, restored by hand on the cell's anchor (`EditorSurface`) because
 * an anchor does not carry one — and `[data-cell]` is what scopes the question to a *cell* editor,
 * so `SpreadPanel`'s panel, mounted from the toolbar and a cell editor in every other way, does not
 * answer it. Read with `data-row-id`, the same pair `marqueeOwnsKeyTarget` below walks.
 *
 * The selection bar's **Set** asks, because Set has to be able to close what it opened: a press on
 * that button is not the outside click that dismisses a popover — the button is that popover's own
 * anchor — so without an answer here the second press had nothing to do and the panel stayed open
 * with focus stranded on the button. The DOM rather than lifted state, for the reason
 * `editorIsOpen` gives: this is one bit, and the state lives per cell, hundreds of instances
 * down.
 */
export function openCellEditorTarget<C extends string = string>(): CellRef<C> | null {
  if (typeof document === 'undefined') return null
  const anchor = document.querySelector('[data-cell] [data-state="open"]')
  const rowId = anchor?.closest('[data-row-id]')?.getAttribute('data-row-id')
  const col = anchor?.closest('[data-cell]')?.getAttribute('data-cell')
  if (!rowId || !col) return null
  return { rowId, col: col as C }
}

export function marqueeOwnsKeyTarget<C extends string = string>(
  target: EventTarget | null,
  isCellSelected: (rowId: RowId, col: C) => boolean,
): boolean {
  if (!(target instanceof HTMLElement)) return false
  const cell = target.closest<HTMLElement>('[data-cell]')
  const col = cell?.dataset.cell
  const rowId = cell?.closest<HTMLElement>('[data-row-id]')?.dataset.rowId
  if (col == null || rowId == null) return false
  return isCellSelected(rowId, col as C)
}

/**
 * A key the grid must not hear at all: typed into a field, or pressed inside a dialog.
 *
 * **Both dialog roles.** Radix's `AlertDialog` is `role="alertdialog"`, not `dialog` — the batch
 * delete's *Keep them / Delete anyway*, the cue sheet's unlock question, the sheet primitive's
 * *Discard changes?* — and a guard reading `[role="dialog"]` alone let ↑ / ↓ move the rows behind
 * one and Escape clear the selection *Keep them* promises to leave. Every sheet listener and the
 * programmer's ask this one question, so a third role is added once.
 */
export function keyTargetIsGuarded(target: EventTarget | null): boolean {
  if (isEditableTarget(target instanceof Element ? target : null)) return true
  return target instanceof Element && target.closest('[role="dialog"], [role="alertdialog"]') != null
}

/**
 * A focused control **outside the sheet's rows** — a partition chip, a row verb, a menu item, a
 * Select's list — whose arrows and ⌘A are not the row selection's to take.
 *
 * The row keys stand aside from these (CLAUDE.md §Sheet kit). Most controls on a sheet's chrome do
 * not answer an arrow themselves — the partition chips and the bar's verbs are plain buttons — so
 * nothing claims the key before the window hears it, and without this ↓ on *Delete* would move the
 * row it was about to delete. **A control inside a row is exempt**, because that is where focus
 * sits in the ordinary case: a click on a name focuses its rename button, a click on a value its
 * cell trigger, and focus stays there while ↓ moves the selection on.
 */
export function isForeignControl(target: EventTarget | null): boolean {
  if (!(target instanceof Element)) return false
  const control = target.closest('button, a, [role="menuitem"], [role="menu"], [role="listbox"], [role="slider"]')
  return control != null && control.closest('[data-row-id]') == null
}
