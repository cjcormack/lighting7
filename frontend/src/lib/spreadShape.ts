import { javaRandom } from '@/components/editor/spreadPlans'

/**
 * The spread's shape (effects plan D4, §3.3) — the editor's copy, for the Spread section's **dial
 * preview only** (P2: the desk owns the rule, `fx/SpreadShape.kt`'s `SpreadShaping` and
 * `fx/group/DistributionStrategy.kt`'s ranks). Nothing here is ever sent: the editor writes the four
 * fields and the Order, and the desk shapes the running effect from them. A shared vector,
 * `src/test/resources/fx/spreadShape.fixture.json`, pins this file and the desk's to the same offsets
 * (`spreadShape.test.ts` beside `SpreadShapeTest.kt`), so the dials cannot draw a spread the rig does
 * not run.
 */

/** The four fields as every effect home carries them; each absent at its default. */
export interface SpreadShapeFields {
  /** How much of the cycle the heads cover, 0–2; 1 is the Order's own offsets. */
  spread?: number
  /** Mirrored wings; 1 is none. Not under a mirrored Order. */
  wings?: number
  /** Neighbouring heads sharing one phase; 1 is none. */
  blocks?: number
  /** The spread repeating every this many heads; 0 is off. */
  groups?: number
}

/** The four fields resolved, as the editor's draft holds them. */
export interface SpreadShape {
  spread: number
  wings: number
  blocks: number
  groups: number
}

export const DEFAULT_SPREAD_SHAPE: SpreadShape = { spread: 1, wings: 1, blocks: 1, groups: 0 }

/** Spread's ceiling (200 %) and the counts' — the desk's `SpreadShape.MAX_SPREAD` / `MAX_COUNT`. */
export const MAX_SPREAD = 2
export const MAX_SPREAD_COUNT = 512

/** [fields] with each absent one at its default. */
export function spreadShapeOf(fields: SpreadShapeFields): SpreadShape {
  return {
    spread: fields.spread ?? 1,
    wings: fields.wings ?? 1,
    blocks: fields.blocks ?? 1,
    groups: fields.groups ?? 0,
  }
}

export function isDefaultShape(shape: SpreadShape): boolean {
  return shape.spread === 1 && shape.wings === 1 && shape.blocks === 1 && shape.groups === 0
}

/**
 * The Orders that are two mirrored wings already — Centre out and Centre in — under which Wings
 * has nothing to add: the desk refuses it, and the editor hides the field (§3.3 step 3).
 */
export function isMirroredOrder(order: string): boolean {
  return order === 'CENTER_OUT' || order === 'EDGES_IN'
}

/** The Order's words (§3.1: the strategy, renamed in the UI only) — `lib/orderLabels.ts`'s, one vocabulary. */
export { ORDER_LABELS, ORDER_OPTIONS, orderLabel } from './orderLabels'

/**
 * Step 1 over [n] notional heads, element `i` at position `i / (n − 1)` (0.5 for one): each head's
 * rank, and the rank count — `DistributionStrategy.ranks` and `distinctSlots`. Random is the desk's
 * own shuffle (`java.util.Random(seed × 31 + n)`, Fisher–Yates), at its default seed 0.
 */
export function orderRanks(order: string, n: number): { ranks: number[]; count: number } {
  const linear = () => Array.from({ length: n }, (_, i) => i)
  switch (order) {
    case 'REVERSE':
      return { ranks: Array.from({ length: n }, (_, i) => n - 1 - i), count: n }
    case 'UNIFIED':
      return { ranks: Array.from({ length: n }, () => 0), count: 1 }
    case 'CENTER_OUT':
    case 'EDGES_IN': {
      const slots = Math.floor((n + 1) / 2)
      if (n <= 1) return { ranks: Array.from({ length: n }, () => 0), count: slots }
      const centre = (n - 1) / 2
      const centreOut = (i: number) => {
        const d = Math.abs(i - centre)
        return n % 2 === 0 ? Math.trunc(d - 0.5) : Math.trunc(d)
      }
      return {
        ranks: Array.from({ length: n }, (_, i) => (order === 'CENTER_OUT' ? centreOut(i) : slots - 1 - centreOut(i))),
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
    // LINEAR, PING_PONG, and an Order this copy does not know (drawn as the rig's).
    default:
      return { ranks: linear(), count: n }
  }
}

/**
 * Each of [n] notional heads' phase offset under [order] shaped by [shape], and the slot count after
 * step 4 — `DistributionPlan.forElements` on the desk, step for step:
 *
 * 2. Blocks `B`: `r ← floor(r / B)` over `ceil(R / B)` ranks;
 * 3. Wings `W` (not under a mirrored Order): runs of `m = ceil(R / W)`, an odd run mirrored within
 *    its own length, over `m`;
 * 4. Groups `G`: `r ← r mod G` over `G`, when `G` is below the rank count;
 * 5. Spread `s`: `offset = s × r / R`.
 *
 * At the default shape the offsets are the Order's own (`rank / count`; a position, for Position).
 */
export function spreadOffsets(order: string, n: number, shape: SpreadShape): { offsets: number[]; slots: number } {
  if (n <= 0) return { offsets: [], slots: 0 }
  const { ranks, count: orderCount } = orderRanks(order, n)
  if (isDefaultShape(shape)) {
    if (order === 'POSITIONAL') return { offsets: ranks.map((r) => r / n), slots: n }
    return { offsets: ranks.map((r) => r / orderCount), slots: orderCount }
  }
  let count = Math.max(orderCount, 1)
  const wingsApply = !isMirroredOrder(order)
  // Blocks, Wings and Groups count whole ranks, so Position's continuous rank snaps to its nearest
  // slot first (`DistributionStrategy.wholeRanks`); Spread alone scales it as it is.
  const countsRanks =
    shape.blocks > 1 || (wingsApply && shape.wings > 1) || (shape.groups >= 1 && shape.groups < count)
  let r =
    order === 'POSITIONAL' && countsRanks
      ? Array.from({ length: n }, (_, i) => Math.round((n > 1 ? i / (n - 1) : 0.5) * Math.max(n - 1, 0)))
      : ranks.slice()
  if (shape.blocks > 1) {
    const b = shape.blocks
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
  if (shape.groups >= 1 && shape.groups < count) {
    const g = shape.groups
    r = r.map((x) => x - g * Math.floor(x / g))
    count = g
  }
  return { offsets: r.map((x) => (shape.spread * x) / count), slots: count }
}

/**
 * The Spread section's summary line, each non-default field — *50 % · 2 wings*. The Order sits above
 * the section, so it is not repeated. Wings under a mirrored Order are left unsaid, as the desk
 * leaves them unrun.
 */
export function spreadSummary(order: string, shape: SpreadShape): string[] {
  const parts: string[] = []
  if (shape.spread !== 1) parts.push(`${Math.round(shape.spread * 100)} %`)
  if (shape.wings > 1 && !isMirroredOrder(order)) parts.push(`${shape.wings} wings`)
  if (shape.blocks > 1) parts.push(`segments of ${shape.blocks}`)
  if (shape.groups > 0) parts.push(`parts of ${shape.groups}`)
  return parts
}
