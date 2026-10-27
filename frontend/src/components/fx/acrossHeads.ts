import { isMirroredOrder, orderLabel, spreadOffsets, type SpreadShape } from '@/lib/spreadShape'

/**
 * **Across the heads in words** (fx-settings plan D10): the head ladder's numbers, the line under
 * Segments · Parts · Wings, and *Each head later by*'s readout. Pure, and built on
 * `lib/spreadShape.ts`, the client's one copy of the desk's spread rule (P2): nothing here works out
 * what an FX outputs, only how far apart the heads start.
 */

const NUMBER_WORDS = ['zero', 'one', 'two', 'three', 'four', 'five', 'six', 'seven', 'eight', 'nine', 'ten', 'eleven', 'twelve']

function numberWord(n: number): string {
  return NUMBER_WORDS[n] ?? String(n)
}

/** Neighbours moving as one, by how many: *pairs*, *threes*, then *groups of 13*. */
function groupWord(n: number): string {
  if (n === 2) return 'pairs'
  if (n === 6) return 'sixes'
  return n >= 3 && n <= 12 ? `${numberWord(n)}s` : `groups of ${n}`
}

/**
 * Each head's number on the ladder: its place among the distinct starts, 1 the first, heads that
 * start together sharing one. Null where the head has no start (a row the FX paints no rung on).
 */
export function startNumbers(starts: readonly (number | null)[]): (number | null)[] {
  const distinct: number[] = []
  for (const s of starts) {
    if (s != null && !distinct.some((d) => Math.abs(d - s) < 1e-6)) distinct.push(s)
  }
  distinct.sort((a, b) => a - b)
  return starts.map((s) => (s == null ? null : distinct.findIndex((d) => Math.abs(d - s) < 1e-6) + 1))
}

/**
 * What Segments, Parts and Wings do to these heads, one clause each — *Segments 2: neighbours move in
 * pairs · Parts off: one pattern across all four · Wings off: no mirror*. [headCount] is the
 * selection's; null on an FX palette, which has no heads of its own.
 */
export function groupingSentence(order: string, shape: SpreadShape, headCount: number | null): string {
  const all = headCount == null ? 'every head' : headCount === 1 ? 'the one head' : `all ${numberWord(headCount)}`
  const { segments, parts, wings } = shape

  const segmentsClause =
    segments <= 1
      ? 'Segments 1: each head on its own'
      : headCount != null && segments >= headCount
        ? `Segments ${segments}: ${all} move as one`
        : `Segments ${segments}: neighbours move in ${groupWord(segments)}`

  // Parts acts only below the starts Segments and Wings leave; at or above them it changes nothing.
  const before = headCount == null ? null : spreadOffsets(order, headCount, { ...shape, parts: 0, spread: 1, spreadUnit: 'CYCLE' }).slots
  const partsClause =
    parts <= 0
      ? `Parts off: one pattern across ${all}`
      : before != null && parts >= before
        ? `Parts ${parts}: only ${numberWord(before)} ${before === 1 ? 'start' : 'starts'}, so no repeat`
        : `Parts ${parts}: the pattern repeats every ${parts} ${segments <= 1 ? 'heads' : groupWord(segments)}`

  const wingsClause = isMirroredOrder(order)
    ? `${orderLabel(order)} is two wings already`
    : wings <= 1
      ? 'Wings off: no mirror'
      : wings === 2
        ? 'Wings 2: the second half mirrors the first'
        : `Wings ${wings}: each wing mirrors the one before`

  return [segmentsClause, partsClause, wingsClause].join(' · ')
}

/**
 * The offset between neighbouring starts, as a share of the cycle: the smallest gap between two
 * distinct [starts]. Read off the offsets themselves rather than worked out from the spread, so every
 * Order, Segments, Wings and Parts reads as the ladder draws it — Position's ends a whole cycle
 * apart, say, over one fewer gap than heads. Zero when every head starts together.
 */
export function neighbourGap(starts: readonly (number | null)[]): number {
  const sorted = [...new Set(starts.filter((s): s is number => s != null))].sort((a, b) => a - b)
  let gap = Infinity
  for (let i = 1; i < sorted.length; i++) {
    const d = sorted[i] - sorted[i - 1]
    if (d > 1e-9) gap = Math.min(gap, d)
  }
  return Number.isFinite(gap) ? gap : 0
}

/** How far apart neighbouring starts are (fx-settings plan D6). */
export interface LaterBy {
  /** Between neighbours, as a share of the cycle. */
  share: number
  /** The same in the FX's own steps; null for an FX with none. */
  steps: number | null
  /** The spread the desk runs, as a share of the cycle over every slot (`spread × slots / steps` under `STEP`). */
  deskSpread: number
}

/**
 * [gap] ([neighbourGap]) in the FX's [steps] where it has them, and the spread the desk runs for
 * [shape] over [slots] starts. A `STEP` spread on an FX with no steps runs as `CYCLE`, as the desk's does.
 */
export function laterBy(shape: SpreadShape, gap: number, slots: number, steps: number | null): LaterBy {
  const inSteps = shape.spreadUnit === 'STEP' && steps != null
  return {
    share: gap,
    steps: steps != null ? gap * steps : null,
    deskSpread: inSteps ? (shape.spread * slots) / Math.max(steps, 1) : shape.spread,
  }
}

const FRACTIONS: readonly [number, string][] = [
  [1 / 2, '½'],
  [1 / 3, '⅓'],
  [2 / 3, '⅔'],
  [1 / 4, '¼'],
  [3 / 4, '¾'],
  [1 / 5, '⅕'],
  [2 / 5, '⅖'],
  [3 / 5, '⅗'],
  [4 / 5, '⅘'],
  [1 / 6, '⅙'],
  [5 / 6, '⅚'],
  [1 / 8, '⅛'],
  [3 / 8, '⅜'],
  [5 / 8, '⅝'],
  [7 / 8, '⅞'],
]

/** A number as a reader says it: *⅓*, *1⅓*, *2*, *<0.01*, else to two places. */
export function mixedNumber(x: number): string {
  if (x > 0 && x < 0.005) return '<0.01'
  const whole = Math.floor(x + 1e-6)
  const rest = x - whole
  if (Math.abs(rest) < 1e-6) return String(whole)
  const glyph = FRACTIONS.find(([v]) => Math.abs(v - rest) < 1e-6)?.[1]
  if (glyph != null) return whole === 0 ? glyph : `${whole}${glyph}`
  return String(Math.round(x * 100) / 100)
}

/** [gap] in a cycle and, where the FX has them, its [steps]: *⅓ cycle · 1 step*. */
export function laterByWords(gap: number, steps: number | null): string {
  const parts = [`${mixedNumber(gap)} cycle`]
  if (steps != null) {
    const inSteps = gap * steps
    parts.push(`${mixedNumber(inSteps)} ${inSteps > 1 + 1e-6 ? 'steps' : 'step'}`)
  }
  return parts.join(' · ')
}

/** The graph knob's words (§4): *Later by ⅓ cycle · 1 step*, or *Every head together*. */
export function laterByPhrase(gap: number, steps: number | null): string {
  return gap > 0 ? `Later by ${laterByWords(gap, steps)}` : 'Every head together'
}

/**
 * *Each head later by*'s readout: [gap] as a share of the cycle, in steps, in beats (or seconds) and
 * in degrees, then — under `STEP`, where the selection is known — the spread the desk runs: *⅓ cycle
 * · 1 step · 1⅓ beats · 120° · spread 133 %*. [cycle] is one head's, in beats or in [seconds] — the
 * FX's cycle times its slots under *One cycle for: Each head*. Under `CYCLE` with no heads of its own
 * (an FX palette) the gap is the notional eight's, and says so.
 */
export function laterByReadout(
  shape: SpreadShape,
  gap: number,
  slots: number,
  steps: number | null,
  cycle: { value: number; seconds: boolean },
  selectionKnown: boolean,
): string {
  if (gap <= 0) return 'Every head starts together'
  const by = laterBy(shape, gap, slots, steps)
  const time = gap * cycle.value
  const parts = [laterByWords(gap, steps)]
  parts.push(cycle.seconds ? `${time > 0 && time < 0.005 ? '<0.01' : Math.round(time * 100) / 100} s` : `${mixedNumber(time)} ${time > 1 + 1e-6 ? 'beats' : 'beat'}`)
  parts.push(`${Math.round(gap * 360)}°`)
  const inSteps = shape.spreadUnit === 'STEP' && steps != null
  if (inSteps && selectionKnown) parts.push(`spread ${Math.round(by.deskSpread * 100)} %`)
  if (!inSteps && !selectionKnown) parts.push('on eight heads')
  return parts.join(' · ')
}
