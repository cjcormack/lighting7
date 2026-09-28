/**
 * Cue-number model — the front-end half of the shared scheme.
 *
 * A cue number is a free-form display label; `sortOrder` is the authoritative playback order.
 * What this adds is *structure*: a number reads as a group prefix, a dotted decimal run, and an
 * optional letter suffix. Numbers are only ever compared against others in the same group, so a
 * stack holding `Pre-show 1`, `Pre-show 2`, `T2-1`, `S-1`, `S-2` is in order even though the
 * groups themselves aren't alphabetical.
 *
 * This mirrors `routes/cueNumbering.kt` in the lighting7 backend — keep the two in step. This
 * copy decides whether to *offer* the "Fix Order" banner; the Kotlin copy performs the fix and
 * derives auto numbers.
 */

/** A cue number split into its ordering components. */
export interface ParsedCueNumber {
  /** Everything before the trailing decimal run — the group key ("S1-", "Pre-show "). */
  prefix: string
  /** The dotted decimal run, most significant first ("3.1" → [3, 1]). */
  segments: number[]
  /** Trailing letters, if any ("14A" → "A"). */
  suffix: string
}

/**
 * The prefix is lazy but still has to match the whole string, so it settles on the *last* decimal
 * run: "S1-3.1" → ("S1-", [3,1], ""), "T2-1" → ("T2-", [1], ""), "14A" → ("", [14], "A").
 */
const CUE_NUMBER_RE = /^(.*?)(\d+(?:\.\d+)*)([A-Za-z]*)$/

/** Parse `cueNumber`, or null when it holds no decimal run to order by (e.g. "A", "Preshow"). */
export function parseCueNumber(cueNumber: string): ParsedCueNumber | null {
  const match = CUE_NUMBER_RE.exec(cueNumber)
  if (!match) return null
  const [, prefix, digits, suffix] = match
  const segments = digits.split('.').map(Number)
  if (segments.some((n) => !Number.isSafeInteger(n))) return null
  return { prefix, segments, suffix }
}

/**
 * The group a cue number sorts within. Unparseable numbers get a private key so they form
 * singleton groups and therefore never move.
 */
export function cueNumberGroupKey(cueNumber: string): string {
  const parsed = parseCueNumber(cueNumber)
  // NUL can't occur in a parsed prefix, so the sentinel can never collide with a real group
  // — a leading space could, since a prefix may end in one ("Pre-show "). Written as
  // an escape: a raw NUL in source makes git treat the whole file as binary.
  return parsed ? parsed.prefix.toLowerCase() : `\u0000unparsed:${cueNumber.toLowerCase()}`
}

/**
 * Order two numbers from the same group: decimal run element-wise (so `3` < `3.1` < `3.2` < `4`),
 * shorter run first on a shared stem, then the letter suffix ("14" < "14A" < "14B").
 */
export function compareWithinGroup(a: ParsedCueNumber, b: ParsedCueNumber): number {
  const shared = Math.min(a.segments.length, b.segments.length)
  for (let i = 0; i < shared; i++) {
    if (a.segments[i] !== b.segments[i]) return a.segments[i] - b.segments[i]
  }
  if (a.segments.length !== b.segments.length) return a.segments.length - b.segments.length
  return a.suffix.toLowerCase().localeCompare(b.suffix.toLowerCase())
}

/**
 * True when some group's members do not ascend in the order given. Interleaved groups are fine —
 * only a group descending against itself counts, so `["Pre-show 1", "T2-1", "S-1", "S-2"]` passes
 * while `["S-2", "S-1"]` does not. Blank and unparseable numbers are ignored.
 */
export function detectCueNumbersOutOfOrder(cueNumbers: (string | null | undefined)[]): boolean {
  const lastByGroup = new Map<string, ParsedCueNumber>()
  for (const raw of cueNumbers) {
    if (!raw) continue
    const parsed = parseCueNumber(raw)
    if (!parsed) continue
    const key = parsed.prefix.toLowerCase()
    const previous = lastByGroup.get(key)
    if (previous && compareWithinGroup(parsed, previous) < 0) return true
    lastByGroup.set(key, parsed)
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
 * `Q1`–`Q40` stays narrow while one holding `QS1-3.2.10` gets the room to show it whole. Every
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
