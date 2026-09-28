/**
 * The fixture filter's three forms of one sentence — **short on the field, shorter on the
 * programmer's field, whole on hover**.
 *
 * `Filter fixtures by name, manufacturer, or type…` is ~300px of 14px text behind a 36px search
 * icon, and every field that carries it is allowed to shrink: the field declares `min-w-0` because
 * no list toolbar wraps any more — the programmer's row B never could (`ProgrammerGrid`'s note),
 * and the plain lists' row is a 40px rung of the list shell since CLAUDE.md §List shell — and
 * below a width the programmer's field becomes an icon over a popover. So the placeholder was clipped mid-word at
 * every width — `PD-FILTER-PLACEHOLDER-CLIP`. The fix the finding names is a shorter placeholder,
 * not a wider field: the field gives on purpose.
 *
 * Nothing the long form said is deleted — it rides the input's `title` and `aria-label`, which is
 * where the space plan puts every sentence it moves. That is the same trade `LEGEND_SHORT` makes
 * for the legend's glosses, and `ProgrammerSourceStrip`'s `Busking` label for its sentence.
 *
 * One module rather than a `const` per field because there are three fields — the shared toolbar
 * control in `FixturesListContainer`, `/fixtures`' own search row and the programmer's `FxSheet` —
 * and three hand-typed copies of one placeholder is how two of them end up saying different things.
 */
export const FIXTURE_FILTER_PLACEHOLDER = 'Filter fixtures…'

/**
 * The programmer's row B placeholder, at every width.
 *
 * An input's placeholder cannot switch by container query, and that row keeps its field from
 * 360px of row up (the chrome tidy-up), where `Filter fixtures…` would clip again exactly as
 * `PD-FILTER-PLACEHOLDER-CLIP` found it did at 600. One word fits behind the search icon at the
 * field's 132px floor, so the programmer says the one word and the hint says the rest. The two
 * plain list routes keep the long form: their field is capped at 340 on a row with only Lit and
 * Columns beside it, so it has the room at every width a desk is used at.
 */
export const FIXTURE_FILTER_PLACEHOLDER_SHORT = 'Filter…'

/** The whole sentence, for the `title`, the `aria-label`, and any control that stands in for the field. */
export const FIXTURE_FILTER_HINT = 'Filter fixtures by name, manufacturer, or type'
