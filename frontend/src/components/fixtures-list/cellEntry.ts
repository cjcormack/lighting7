import type { ProgrammerScope } from '../programmer/ProgrammerScope'
import type { CellActionCopy, CellKeyboardPermission } from '../sheet/cellEntry'

// The generic half — which cell a keystroke opens, the DOM guards, and the two shapes the
// permission and the copy take — lives in the sheet kit and is re-exported here so this list's
// callers and tests keep one import. What stays in this file is the programmer's own answer to
// those two questions, because it reads a `ProgrammerScope`.
export {
  marqueeOwnsKeyTarget,
  openCellEditorTarget,
  orderedSelectedCells,
} from '../sheet/cellEntry'
export type { CellActionCopy, CellKeyboardPermission } from '../sheet/cellEntry'

/**
 * The keyboard half of the marquee: which cell a keystroke opens, and which scopes may take one.
 *
 * The gesture is spreadsheet-shaped — select cells, press Enter (or just start typing) and an
 * editor opens over them — but **which** editor is the thing that changed. It used to be one of
 * its own: a single-line field (`CellEntryPopover`) with a grammar of its own (`parseCellEntry`)
 * for `#ff8800`, `50%`, `full` and `pan,tilt`, which existed because the keyboard had no way into
 * the editor a *click* opens. So every column had two editors, and only one of them could be
 * improved at a time.
 *
 * Now there is one. Enter opens the ordinary cell editor at the first selected cell, with its
 * first field focused (`useEditorKeyboard`), and a character typed at the grid arrives in that
 * field as its first keystroke.
 *
 * **The grammar went with the field, and three of its words went for good.** A hex colour typed as
 * text is replaced in kind — the picker and the R/G/B boxes say the same thing. `pan,tilt` and
 * `r,g,b` survive as a *gesture* rather than a grammar: comma steps to the next field, which is
 * what the position editor's new pair of boxes is for. But **`50%`, `full` and `out` are simply
 * gone**, and nothing says them instead: the level editor's box is `type="number"`, so those
 * characters cannot even be typed into it, let alone parsed. That is a deliberate loss, taken with
 * the same shrug as hex rather than by oversight — do not describe it as relocated, and do not
 * reintroduce a text grammar in the byte field without asking, because the field would stop being
 * a number input (losing its spinner and its arrow-key increment) to get them back.
 *
 * One column's editor for a selection that may span several is deliberate, and it is not a
 * narrowing: a commit from a cell inside the marquee goes through `commitToCells`, which fans it
 * across **every** selected column and drops it from the ones whose shape it does not fit. So a
 * Dimmer + Colour marquee opens the dimmer's slider and moving it sets the dimmers, exactly as
 * `127` did — and the colours are left alone, exactly as they were.
 */

/**
 * The scope gate for the marquee's keyboard — and for the selection bar's Set and Clear, which are
 * the same two gestures with a button on them. The fourth place "read-only" has to be said.
 *
 * The marquee itself arms in every scope (its `pointerdown` sits on the rows wrapper, and a
 * read-only cell's `pointer-events-none` only retargets the press there), so the keyboard cannot
 * rely on there being no cells to type at. And `useCellWriters` has no arm for Output or for a focused
 * *template* layer — `ProgrammerGrid` supplies a `live` context for both — so a commit taken in
 * either would put literals into Local under a grid drawing itself as a read. That is the hole
 * `PropertyCell`'s `disabled` and `SpreadPopover`'s template gate each close for their own path, and
 * this closes it for the keyboard and the bar.
 *
 *  - **Local**, or no scope at all — the two plain list routes, whose cells write straight to the
 *    programmer through `useCellWriters`' `live` arm, which is Local by another name: both. (It
 *    was answered this way while it was unreachable, on the principle that the default should be
 *    the permissive one; the lists gained a marquee of their own this session and now reach it.)
 *  - **Output**: neither. It is a read of the cook.
 *  - **A focused Look layer**: entry only. A value typed there lands in the row draft the way a
 *    cell edit does; Backspace does not, because the draft has no removal (`LookRowStore` exposes
 *    `setValue` alone), and a key that silently does nothing is worse than one withheld.
 *  - **A focused template layer**: neither. A template layer is a read, never an edit.
 */
export function cellKeyboardPermission(
  scope: ProgrammerScope | null,
  focusedTemplate: boolean,
): CellKeyboardPermission {
  if (scope == null || scope.kind === 'local') return { entry: true, clear: true }
  if (scope.kind === 'output') return { entry: false, clear: false }
  return focusedTemplate ? { entry: false, clear: false } : { entry: true, clear: false }
}

/**
 * What the selection bar's Set and Clear say, given where the grid is pointed.
 *
 * Set is one gesture — open the first selected cell's editor over the whole selection, which is
 * what Enter does — and the title names where the value lands, because that is the scope's
 * answer and not the button's: Local, or the focused Look's rows. Where the gesture is refused the
 * title carries the reason, in the words `SpreadPopover` already uses for its own template gate, so
 * the three disabled controls on one bar do not explain themselves three ways. The reasons follow
 * [cellKeyboardPermission] rather than restating it: a title can never promise a gesture the gate
 * refuses.
 */
export function cellActionCopy(
  scope: ProgrammerScope | null,
  focusedTemplate: boolean,
  cellCount: number,
): CellActionCopy {
  const cells = `${cellCount} selected cell${cellCount === 1 ? '' : 's'}`
  if (scope?.kind === 'output') {
    const reason = 'Output is a read of the cook — switch to Local to set these cells'
    return { setTitle: reason, clearTitle: reason }
  }
  if (scope?.kind === 'layer') {
    if (focusedTemplate) {
      const reason = 'This layer applies a template — switch to Local to set these cells'
      return { setTitle: reason, clearTitle: reason }
    }
    return {
      setTitle: `Set the ${cells} in the focused layer's rows (Enter)`,
      clearTitle: "A layer's rows cannot be cleared from here — switch to Local",
    }
  }
  return {
    setTitle: `Set the ${cells} in Local — this is what Record will take (Enter)`,
    clearTitle: `Take the ${cells} out of Local, including any effect busked on them (Backspace)`,
  }
}
