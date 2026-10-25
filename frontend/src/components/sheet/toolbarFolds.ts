/**
 * Where the selection bar's controls fold, as three container-query classes every sheet shares.
 *
 * They were `SelectionToolbar`'s own until the sheet kit (CLAUDE.md §Sheet kit): the patch list,
 * the DMX sheet and the cue sheet draw the same verbs on the same 40px bar, and one control folding
 * at a different width from the one beside it is the bug these exist to prevent. The fixtures
 * list's toolbar re-exports them, so its tests and its callers keep one import.
 */

/**
 * When a verb keeps its word.
 *
 * `hidden sm:inline` is the viewport rule this bar has always had: on a phone the icons and their
 * tooltips carry it. The `@max-[1100px]:hidden` half is the *container* rule the programmer's
 * selection bar adds on top — that bar is one 40px line that must not wrap, and the words are the
 * widest thing on it that a tooltip already says — **and it applies only inside a bar that carries
 * a strip**: `SelectionBar` marks itself `group/bar` + `data-strip` when something rides it, and
 * the fold is written as `group-data-[strip]/bar:@max-[1100px]:hidden`. The strip is what needs
 * the width. On a bar with nothing riding it — the two plain lists, the DMX sheet, the patch list,
 * the cue sheet — a 1100px fold folded every word at once on an ordinary 1160px window, with
 * nothing to give the room to; those keep their words down to `sm`.
 *
 * The programmer's row C is the one bar with a third arm: it carries no strip, and lays its verbs out
 * in clusters (`SelectionBar`'s `clusters`, `data-clusters`), so its words keep their place to 800
 * rather than 1100 and fold there with the rest of that row.
 *
 * Every list has the bar's `@container` above row C since the list shell (CLAUDE.md §List shell);
 * the container is the bar's wrapper on every list, never the page.
 */
export const WORD_CLASS =
  'hidden sm:inline group-data-[strip]/bar:@max-[1100px]:hidden group-data-[clusters]/bar:@max-[800px]:hidden'

/**
 * A selection bar's **phone arm**: what folds below `@[600px]` of the bar — the counts' family badge,
 * a cell selection's Spread — while Set, Clear and Deselect stay, because Enter, Backspace and
 * Escape are keys a phone has not got (`PD-SELECTION-BAR-DENSITY`, `PD-CLEAR-SELECTION-TOUCH`).
 * `@[600px]` is the threshold row B's key button already uses for "this grid column is a phone's".
 * The programmer's row C measures its rungs instead (`programmer/rowCBand.ts`).
 */
export const PHONE_FOLDED_CLASS = '@max-[600px]:hidden'

/**
 * Where a bar's own **row count** folds beside a cell count, whose hover carries it — and, gated on
 * a strip ([STRIP_MID_FOLDED_CLASS]), where a sheet's Locate and Highlight give the strip their
 * width. Both are momentary aids rather than gestures the grid depends on, so they are the right
 * two to lose first. Same container rule as [PHONE_FOLDED_CLASS].
 */
export const MID_FOLDED_CLASS = '@max-[800px]:hidden'

/** [MID_FOLDED_CLASS] gated on the bar carrying a strip — Locate and Highlight's fold. */
export const STRIP_MID_FOLDED_CLASS = 'group-data-[strip]/bar:@max-[800px]:hidden'

/**
 * When a library sheet's **row verbs** — Include, Pick up, Duplicate, Copy to…, Delete — keep their
 * words (library-sheets plan D11). Below 1100px of the bar they are icons, with the verb on each
 * one's `aria-label` and its title.
 *
 * Earlier than [WORD_CLASS], and ungated by a strip, because the row verbs are the widest thing a
 * library bar carries: measured on the template sheet on 2026-09-24, Set · Clear · Spread, four row
 * verbs and Deselect are 769px worded, which with the counts and the key hints is ~1090 — and the
 * 1180×820 iPad frame gives the bar 940 with the sidebar open, so the last two verbs were clipped.
 * Folding the row verbs' words saves ~260 of it. Set, Clear and Spread keep theirs: they are the
 * gestures a phone has no key for (`CellSelectionActions`), and they are what a marquee is for.
 */
export const ROW_VERB_WORD_CLASS = 'hidden sm:inline @max-[1100px]:hidden'

/** The ShowBar's key-cap styling, so every key hint on a selection bar reads as one vocabulary. */
export const KBD_CLASS = 'rounded border bg-muted/50 px-1.5 py-px text-[9.5px]'
