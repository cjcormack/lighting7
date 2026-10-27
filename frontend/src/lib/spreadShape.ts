import { javaRandom } from '@/components/editor/spreadPlans'

/**
 * The spread's shape (effects plan D4, §3.3) — the editor's copy, for the Spread section's **dial
 * preview only** (P2: the desk owns the rule, `fx/SpreadShape.kt`'s `SpreadShaping` and
 * `fx/group/SpreadOrder.kt`'s ranks). Nothing here is ever sent: the editor writes the four
 * fields and the Order, and the desk shapes the running effect from them. A shared vector,
 * `src/test/resources/fx/spreadShape.fixture.json`, pins this file and the desk's to the same offsets
 * (`spreadShape.test.ts` beside `SpreadShapeTest.kt`), so the dials cannot draw a spread the rig does
 * not run.
 */

/**
 * What `spread` counts (fx-settings plan D6): a share of the cycle, or the FX's own steps — each head
 * starting that many of them after the one before, so a chase stays on its steps whatever the head
 * count. Only an FX whose library entry names `stepsFrom` takes `STEP`.
 */
export type SpreadUnit = 'CYCLE' | 'STEP'

/** The fields as every effect home carries them; each absent at its default. */
export interface SpreadShapeFields {
  /** How much of the cycle the heads cover, 0–2; 1 is the Order's own offsets. */
  spread?: number
  /** Mirrored wings; 1 is none. Not under a mirrored Order. */
  wings?: number
  /** Neighbouring heads sharing one phase; 1 is none. */
  segments?: number
  /** The spread repeating every this many heads; 0 is off. */
  parts?: number
  /** What `spread` counts; absent is `CYCLE`, as every row written before the unit reads. */
  spreadUnit?: SpreadUnit
}

/** The fields resolved, as the editor's draft holds them. */
export interface SpreadShape {
  spread: number
  wings: number
  segments: number
  parts: number
  spreadUnit: SpreadUnit
}

export const DEFAULT_SPREAD_SHAPE: SpreadShape = { spread: 1, wings: 1, segments: 1, parts: 0, spreadUnit: 'CYCLE' }

/**
 * Spread's ceiling (200 %), its ceiling in steps and the counts' — the desk's `SpreadShape.MAX_SPREAD`,
 * `MAX_STEP_SPREAD` and `MAX_COUNT`.
 */
export const MAX_SPREAD = 2
export const MAX_STEP_SPREAD = 16
export const MAX_SPREAD_COUNT = 512

/** [fields] with each absent one at its default. */
export function spreadShapeOf(fields: SpreadShapeFields): SpreadShape {
  return {
    spread: fields.spread ?? 1,
    wings: fields.wings ?? 1,
    segments: fields.segments ?? 1,
    parts: fields.parts ?? 0,
    spreadUnit: fields.spreadUnit ?? 'CYCLE',
  }
}

export function isDefaultShape(shape: SpreadShape): boolean {
  return (
    shape.spread === 1 &&
    shape.wings === 1 &&
    shape.segments === 1 &&
    shape.parts === 0 &&
    shape.spreadUnit === 'CYCLE'
  )
}

/**
 * The fields a door that starts or creates an FX writes for its spread (fx-settings plan D7): one step
 * behind for an FX that steps (its library entry names `stepsFrom`), nothing otherwise.
 */
export function newFxSpread(stepsFrom: string | undefined | null): Pick<SpreadShapeFields, 'spread' | 'spreadUnit'> {
  return stepsFrom ? { spread: 1, spreadUnit: 'STEP' } : {}
}

/**
 * How many steps an FX runs through, for the dials only: the entries of the list parameter its
 * library entry names (`stepsFrom`) — a blank list its declared default, as the desk reads one, a
 * blank entry no step and, in a colour list, a `pal:` reference none. The desk also drops a colour it
 * cannot parse and a palette that does not resolve, which this cannot see. Null for an FX with no steps.
 */
export function stepCountOf(
  entry: { stepsFrom?: string; parameters: readonly { name: string; type: string; defaultValue: string }[] } | undefined,
  parameters: Readonly<Record<string, string>>,
): number | null {
  const from = entry?.stepsFrom
  if (!from) return null
  const declared = entry.parameters.find((p) => p.name === from)
  const given = parameters[from]
  const raw = given != null && given.trim() !== '' ? given : (declared?.defaultValue ?? '')
  const colours = declared?.type === 'colourList'
  return raw.split(',').filter((item) => {
    const trimmed = item.trim()
    return trimmed !== '' && !(colours && trimmed.startsWith('pal:'))
  }).length
}

/**
 * The Orders that are two mirrored wings already — Centre out and Centre in — under which Wings
 * has nothing to add: the desk refuses it, and the editor hides the field (§3.3 step 3).
 */
export function isMirroredOrder(order: string): boolean {
  return order === 'CENTRE_OUT' || order === 'CENTRE_IN'
}

/** The Order's words — `lib/orderLabels.ts`'s, one vocabulary. */
export { ORDER_LABELS, ORDER_OPTIONS, orderLabel } from './orderLabels'

/**
 * Step 1 over [n] notional heads, element `i` at position `i / (n − 1)` (0.5 for one): each head's
 * rank, and the rank count — `SpreadOrder.ranks` and `distinctSlots`. Random is the desk's
 * own shuffle (`java.util.Random(seed × 31 + n)`, Fisher–Yates), at its default seed 0.
 */
export function orderRanks(order: string, n: number): { ranks: number[]; count: number } {
  const linear = () => Array.from({ length: n }, (_, i) => i)
  switch (order) {
    case 'REVERSE':
      return { ranks: Array.from({ length: n }, (_, i) => n - 1 - i), count: n }
    case 'UNIFIED':
      return { ranks: Array.from({ length: n }, () => 0), count: 1 }
    case 'CENTRE_OUT':
    case 'CENTRE_IN': {
      const slots = Math.floor((n + 1) / 2)
      if (n <= 1) return { ranks: Array.from({ length: n }, () => 0), count: slots }
      const centre = (n - 1) / 2
      const centreOut = (i: number) => {
        const d = Math.abs(i - centre)
        return n % 2 === 0 ? Math.trunc(d - 0.5) : Math.trunc(d)
      }
      return {
        ranks: Array.from({ length: n }, (_, i) => (order === 'CENTRE_OUT' ? centreOut(i) : slots - 1 - centreOut(i))),
        count: slots,
      }
    }
    case 'RANDOM': {
      if (n <= 1) return { ranks: Array.from({ length: n }, () => 0), count: n }
      const random = javaRandom(0 * 31 + n)
      const dealt = linear()
      for (let i = n - 1; i >= 1; i--) {
        const j = random.nextInt(i + 1)
        ;[dealt[i], dealt[j]] = [dealt[j], dealt[i]]
      }
      return { ranks: dealt, count: n }
    }
    case 'POSITIONAL':
      return { ranks: Array.from({ length: n }, (_, i) => (n > 1 ? i / (n - 1) : 0.5) * n), count: n }
    // NORMAL, PING_PONG, and an Order this copy does not know (drawn as the rig's).
    default:
      return { ranks: linear(), count: n }
  }
}

/**
 * Each of [n] notional heads' phase offset under [order] shaped by [shape], and the slot count after
 * step 4 — `DistributionPlan.forElements` on the desk, step for step:
 *
 * 2. Segments `S`: `r ← floor(r / S)` over `ceil(R / S)` ranks;
 * 3. Wings `W` (not under a mirrored Order): runs of `m = ceil(R / W)`, an odd run mirrored within
 *    its own length, over `m`;
 * 4. Parts `P`: `r ← r mod P` over `P`, when `P` is below the rank count;
 * 5. Spread `s`: `offset = s × r / R`; under `STEP`, `s × r / steps`, the FX's [steps] (at least
 *    1). With no [steps] a `STEP` spread runs as `CYCLE`, as the desk's does.
 *
 * At the default shape the offsets are the Order's own (`rank / count`; a position, for Position).
 */
export function spreadOffsets(
  order: string,
  n: number,
  shape: SpreadShape,
  steps: number | null = null,
): { offsets: number[]; slots: number } {
  if (n <= 0) return { offsets: [], slots: 0 }
  const { ranks, count: orderCount } = orderRanks(order, n)
  if (isDefaultShape(shape)) {
    if (order === 'POSITIONAL') return { offsets: ranks.map((r) => r / n), slots: n }
    return { offsets: ranks.map((r) => r / orderCount), slots: orderCount }
  }
  let count = Math.max(orderCount, 1)
  const wingsApply = !isMirroredOrder(order)
  // Segments, Wings and Parts count whole ranks, so Position's continuous rank snaps to its nearest
  // slot first (`SpreadOrder.wholeRanks`); Spread alone scales it as it is.
  const countsRanks =
    shape.segments > 1 || (wingsApply && shape.wings > 1) || (shape.parts >= 1 && shape.parts < count)
  let r =
    order === 'POSITIONAL' && countsRanks
      ? Array.from({ length: n }, (_, i) => Math.round((n > 1 ? i / (n - 1) : 0.5) * Math.max(n - 1, 0)))
      : ranks.slice()
  if (shape.segments > 1) {
    const b = shape.segments
    count = Math.ceil(count / b)
    r = r.map((x) => Math.min(Math.floor(x / b), count - 1))
  }
  if (shape.wings > 1 && wingsApply) {
    const w = shape.wings
    const m = Math.ceil(count / w)
    r = r.map((x) => {
      const run = Math.max(0, Math.min(w - 1, Math.floor(x / m)))
      const local = x - run * m
      if (run % 2 === 1) {
        const len = Math.max(1, Math.min(m, count - run * m))
        return Math.max(0, len - 1 - local)
      }
      return local
    })
    count = m
  }
  if (shape.parts >= 1 && shape.parts < count) {
    const g = shape.parts
    r = r.map((x) => x - g * Math.floor(x / g))
    count = g
  }
  const over = shape.spreadUnit === 'STEP' && steps != null ? Math.max(steps, 1) : count
  return { offsets: r.map((x) => (shape.spread * x) / over), slots: count }
}

/**
 * The Spread section's summary line, each non-default field — *50 % · 2 wings*. The Order sits above
 * the section, so it is not repeated. Wings under a mirrored Order are left unsaid, as the desk
 * leaves them unrun.
 */
export function spreadSummary(order: string, shape: SpreadShape): string[] {
  const parts: string[] = []
  if (shape.spreadUnit === 'STEP') parts.push(shape.spread === 1 ? '1 step' : `${shape.spread} steps`)
  else if (shape.spread !== 1) parts.push(`${Math.round(shape.spread * 100)} %`)
  if (shape.wings > 1 && !isMirroredOrder(order)) parts.push(`${shape.wings} wings`)
  if (shape.segments > 1) parts.push(`segments of ${shape.segments}`)
  if (shape.parts > 0) parts.push(`parts of ${shape.parts}`)
  return parts
}
