/**
 * Cue-number model — the front-end half of the shared scheme.
 *
 * A cue number is a number from 0 to 9999.9999 with at most four decimal places, sorted by value:
 * `2.1` and `2.10` are one number, `2.15` sits between `2.1` and `2.2`, and `10` comes after `9`. The
 * desk stores one spelling of each ([canonicalCueNumber]); `sortOrder` is the authoritative playback
 * order.
 *
 * This mirrors `routes/cueNumbering.kt` in the backend — keep the two in step. This copy checks what
 * the operator types and decides whether to *offer* the "Fix Order" banner; the desk refuses a
 * number that is not one, asks before a number lands out of order, and derives auto numbers.
 */

/** Most decimal places a cue number may have. */
export const CUE_NUMBER_DECIMALS = 4

/** What a refused cue number is told — the desk's `CUE_NUMBER_RULE`. */
export const CUE_NUMBER_RULE = 'A cue number is a number from 0 to 9999.9999, with at most 4 decimal places'

/** Digits with an optional fraction; a leading `Q` (as the desk displays a number) is allowed. */
const CUE_NUMBER_RE = /^[Qq]?(\d*)(?:\.(\d*))?$/

/**
 * `raw` in the one spelling the desk stores — no leading zeros, no trailing fraction zeros — or null
 * when it is not a cue number. `2.10` is `2.1`, `007` is `7`, `.5` is `0.5`.
 */
export function canonicalCueNumber(raw: string): string | null {
  const match = CUE_NUMBER_RE.exec(raw.trim())
  if (!match) return null
  const [, whole, fraction = ''] = match
  if (whole === '' && fraction === '') return null
  const integer = whole.replace(/^0+(?=\d)/, '') || '0'
  const decimals = fraction.replace(/0+$/, '')
  if (decimals.length > CUE_NUMBER_DECIMALS || integer.length > 4) return null
  return decimals ? `${integer}.${decimals}` : integer
}

/** `raw`'s value, or null when it is not a cue number. Exact to four places, which is all a number holds. */
export function cueNumberValue(raw: string): number | null {
  const canonical = canonicalCueNumber(raw)
  return canonical == null ? null : Number(canonical)
}

/**
 * The refusal for a typed number, or null when it is blank or a number — what a cue-number field
 * passes as its `validate`.
 */
export function cueNumberProblem(draft: string): string | null {
  return draft.trim() === '' || canonicalCueNumber(draft) != null ? null : CUE_NUMBER_RULE
}

/** True when some numbered cue reads lower than one before it. Blank and non-numbers are ignored. */
export function detectCueNumbersOutOfOrder(cueNumbers: (string | null | undefined)[]): boolean {
  let last: number | null = null
  for (const raw of cueNumbers) {
    if (!raw) continue
    const value = cueNumberValue(raw)
    if (value == null) continue
    if (last != null && value < last) return true
    last = value
  }
  return false
}

/**
 * Styling for an auto-derived cue number. Dimmer and un-bolded so the operator can tell at a
 * glance which numbers are provisional — they'll be rewritten if the cue moves — from the ones
 * they typed. Compose with `cn()` so the surrounding colour/weight still wins where it matters
 * (the live-cue green, for instance).
 */
export const AUTO_CUE_NUMBER_CLASS = 'text-muted-foreground/70 font-normal'

/**
 * Widest rendered cue number in a list, in characters — the `Q` prefix included.
 *
 * Cue lists size their number column from this rather than a fixed pixel budget, so a stack of
 * `Q1`–`Q40` stays narrow while one holding `Q1234.5678` gets the room to show it whole. Every
 * row is its own grid, so the count has to be computed once by the list and handed down; a
 * `max-content` track would size each row independently and stagger the column.
 *
 * Publish it as `--cue-num-chars` on the list, then size the *cell* (not the grid track) with
 * `1ch` — `ch` resolves against the element it is used on, and only the cell is `font-mono`.
 */
export function cueNumberColumnChars(cueNumbers: (string | null | undefined)[]): number {
  let widest = 0
  for (const raw of cueNumbers) {
    // Matches the em-dash placeholder the rows render for a numberless cue.
    const rendered = raw ? `Q${raw}` : '—'
    if (rendered.length > widest) widest = rendered.length
  }
  return widest
}

/**
 * Inline style sizing a cue-number cell from the list's `--cue-num-chars`.
 *
 * `pad` covers whatever else shares the cell — the live row's Play icon, the inline field's
 * border and padding — so the widest number still fits on the busiest row. The floor keeps
 * short-numbered stacks from collapsing to nothing; the ceiling stops one absurd number from
 * eating the name column, and `TruncateStart` clips whatever is left over.
 *
 * Goes on the cell rather than the grid track for two reasons: `ch` resolves against the element
 * it is used on, and the cell is the only `font-mono` box in the row; and `minWidth` has to ride
 * along. Without it an `auto` track collapses to the cell's *content* minimum — which the inline
 * field's own `min-w-0` puts at zero — so each row would compress by however much its cue name
 * demanded and the column would stagger.
 */
export function cueNumberCellWidth(pad: string): { width: string; minWidth: string } {
  const w = `clamp(4rem, calc(var(--cue-num-chars, 7) * 1ch + ${pad}), 12rem)`
  return { width: w, minWidth: w }
}

/** Cue-shaped subset `detectOutOfOrder` needs — satisfied by `CueStackCueEntry`. */
interface NumberedCue {
  cueType: string
  cueNumber: string | null
}

/**
 * Whether a stack's cues warrant offering "Fix Order". MARKERs are skipped — they're inert
 * dividers and the server never moves them.
 */
export function detectOutOfOrder(cues: NumberedCue[]): boolean {
  return detectCueNumbersOutOfOrder(
    cues.filter((c) => c.cueType === 'STANDARD').map((c) => c.cueNumber),
  )
}
