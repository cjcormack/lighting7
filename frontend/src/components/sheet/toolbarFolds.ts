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
 * Every list has the bar's `@container` above row C since the list shell (CLAUDE.md §List shell);
 * the container is the bar's wrapper on every list, never the page.
 */
export const WORD_CLASS = 'hidden sm:inline group-data-[strip]/bar:@max-[1100px]:hidden'

/**
 * Where Locate, Highlight and Spread go on a phone-width programmer bar, and Deselect does not.
 * Set and Clear stay too — see `CellSelectionActions`.
 *
 * `PD-SELECTION-BAR-DENSITY` and `PD-CLEAR-SELECTION-TOUCH`, decided together because they pull
 * against each other: the chips are the only thing on that row an operator presses, so the width
 * goes to them, and the one control the row keeps at every width is the one a phone has no other
 * way to do — Escape is a key, and "click off" needs empty grid space a full list has none of.
 * This is what the `Phone` artboard draws: glyph · count · chips · New · X. The three folded here
 * are not lost — Locate and Highlight are on the busk target band, and Spread comes back with the
 * width.
 *
 * Same container rule as `WORD_CLASS`: the bar's wrapper is the container on every list, the two
 * plain routes included. `@[600px]` is the bar's phone arm — the threshold row B's key button
 * already uses for "this grid column is a phone's".
 *
 * Exported because the programmer's `SelectionBar` folds its own counts and badge at the same
 * width: one constant, so the two halves of one row cannot fold at different thresholds. The
 * import runs this way round — the bar already depends on this toolbar, never the reverse.
 */
export const PHONE_FOLDED_CLASS = '@max-[600px]:hidden'

/**
 * Where **Locate and Highlight** go on a bar with a strip, which is earlier than everything else
 * on the row — the same `data-strip` gate as [WORD_CLASS], and for the same reason.
 *
 * The template row needs the width. `All · n` and `New` are fixed, the chips are what an operator
 * presses, and an iPad portrait's row C is ~800px — enough for two recent chips once these two
 * buttons and the fixture count have gone, and enough for none while they are there.
 *
 * They are the right two to lose first because neither is lost: both are on the busk view's target
 * band, and both are momentary aids rather than gestures the grid depends on — unlike Set, Clear
 * and Deselect, which stay at every width because Enter, Backspace and Escape are keys a phone has
 * not got. Spread keeps the 600 fold: it is a cell verb like the two beside it, and folding it earlier
 * would break up a group of three.
 *
 * Same container rule as [PHONE_FOLDED_CLASS] — the bar's wrapper, on every list. The bare form
 * folds the bar's own counts; Locate and Highlight take [STRIP_MID_FOLDED_CLASS] instead, because
 * on a bar with nothing riding it that toolbar is the only place those two verbs exist.
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
