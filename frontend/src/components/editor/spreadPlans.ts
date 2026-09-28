import type { SpreadCurve, SpreadOrder } from '@/store/programmerOps'

/**
 * The arithmetic behind the Spread panel's **client-walked** kinds (editor-kit plan D2, D3, D15).
 *
 * The desk resolves every spread over a property in the template vocabulary — a level, a colour, a
 * position, a beam role — through `POST /programmer/spread`, because only it knows a group's
 * member order, each head's range, which cells a fixture has and what a colour means on a head with
 * amber (`lib/spreadIntent.ts`'s reason). This module walks only what the desk has no grammar for:
 *
 * - **addresses** — the patch list's From · Step ([walkAddresses]);
 * - **fade times** — the cue sheet's From · To over a curve ([spreadDurations]);
 * - **raw bytes** on a column outside the vocabulary — Speed today, and only Speed ([rawValues],
 *   D15: `fanValues` renamed and kept for that one column; if Speed ever stops offering Spread this
 *   goes with it).
 *
 * `fanColours` is deleted: a colour spread is desk-resolved, in Lab, per head. What was
 * `sheet/fanMath.ts` is this file.
 *
 * [spreadFractions] mirrors lighting7's `fx/SpreadPlan.kt` — the four curves, the four orders and
 * `parts` — so a raw spread over Speed reads the same shape a desk-resolved spread over Dimmer does
 * on the heads beside it. **Random included**: the desk deals its permutation with
 * `java.util.Random(seed * 31 + count)` and a Fisher–Yates walk (`DistributionStrategy.RANDOM`),
 * and [javaRandom] is that generator's 48-bit congruence ported bit for bit, pinned in
 * `spreadPlans.test.ts` against permutations the JDK itself dealt — so one seed shuffles the same
 * heads the same way on both sides, and a Dimmer spread and a Speed spread at Order: Random line
 * up. It is a mirror for the *client-walked* kinds alone: nothing here is ever applied to an
 * intent, and `spreadIntent.test.ts` pins that `SpreadPanel` and `SpreadPopover` reach this file
 * only for the raw arm.
 */

/** A head's position `x ∈ [0, 1]` along the order, under one of the four orders the panel offers. */
export function spreadPositions(count: number, order: SpreadOrder, seed = 0): number[] {
  if (count === 0) return []
  if (count === 1) return [0]
  const last = count - 1
  switch (order) {
    case 'LINEAR':
      return Array.from({ length: count }, (_, i) => i / last)
    case 'REVERSE':
      return Array.from({ length: count }, (_, i) => (last - i) / last)
    case 'CENTER_OUT': {
      // The desk's rank from the centre: symmetric pairs share a position and the two ends reach 1.
      const centre = last / 2
      const rank = (i: number) => {
        const distance = Math.abs(i - centre)
        return count % 2 === 0 ? Math.floor(distance - 0.5) : Math.floor(distance)
      }
      const maxRank = rank(0)
      return Array.from({ length: count }, (_, i) => (maxRank === 0 ? 0 : rank(i) / maxRank))
    }
    case 'RANDOM': {
      // The desk's own shuffle: a Fisher–Yates walk over `0 … n-1` dealt by `java.util.Random(seed
      // * 31 + n)`, each head's offset its dealt index over n, normalised so the largest reaches 1.
      const random = javaRandom(seed * 31 + count)
      const order = Array.from({ length: count }, (_, i) => i)
      for (let i = count - 1; i >= 1; i--) {
        const j = random.nextInt(i + 1)
        ;[order[i], order[j]] = [order[j], order[i]]
      }
      return order.map((dealt) => dealt / last)
    }
  }
}

/** The fraction `t` of the way from *from* to *to* at position `x`, for the three curves that are a function of position. */
function curveAt(curve: Exclude<SpreadCurve, 'WINGS'>, x: number): number {
  switch (curve) {
    case 'LINE':
      return x
    case 'MIRROR':
      return Math.abs(2 * x - 1)
    case 'ARROW':
      return 1 - Math.abs(2 * x - 1)
  }
}

/**
 * Wings over one run: two mirrored fans meeting at the centre, *to* at each outer end and *from*
 * in the middle, an odd run's centre head in both — the desk's `SpreadPlan.wings`.
 */
function wings(size: number, order: SpreadOrder, seed: number): number[] {
  const out = new Array<number>(size).fill(0)
  if (size === 0) return out
  const leftSize = Math.ceil(size / 2)
  const rightStart = Math.floor(size / 2)
  const rightSize = size - rightStart
  const left = spreadPositions(leftSize, order, seed)
  for (let k = 0; k < leftSize; k++) out[k] = clamp01(1 - left[k])
  const right = spreadPositions(rightSize, order, seed)
  for (let j = 0; j < rightSize; j++) out[rightStart + j] = clamp01(1 - right[rightSize - 1 - j])
  return out
}

/**
 * The fraction `t ∈ [0, 1]` of each of [count] steps: the order cut into [parts] contiguous runs —
 * the first runs one longer when it does not divide — each run's positions under [order] through
 * [curve]. One step in a run sits at 0 (*from*), as it does on the desk.
 */
export function spreadFractions(count: number, curve: SpreadCurve, order: SpreadOrder = 'LINEAR', parts = 1, seed = 0): number[] {
  if (count <= 0) return []
  const runs = Math.max(1, Math.min(Math.round(parts), count))
  const base = Math.floor(count / runs)
  const extra = count % runs
  const out: number[] = []
  for (let i = 0; i < runs; i++) {
    const size = base + (i < extra ? 1 : 0)
    if (curve === 'WINGS') {
      out.push(...wings(size, order, seed))
    } else {
      for (const x of spreadPositions(size, order, seed)) out.push(clamp01(curveAt(curve, x)))
    }
  }
  return out
}

/**
 * Bytes at each fraction from `from` to `to`, rounded — the **raw** kind's walk, for a column the
 * desk has no intent for (D15). Index-parallel to [fractions].
 */
export function rawValues(from: number, to: number, fractions: readonly number[]): number[] {
  return fractions.map((t) => Math.round(from + (to - from) * t))
}

/**
 * Addresses re-spaced from a start channel — the patch list's Spread, and the arithmetic behind its
 * consecutive Set.
 *
 * `step` is a fixed gap between one fixture's start and the next; **null means each fixture's own
 * footprint**, which is what every desk surveyed does by default (Eos `1 Thru 10 @ 1` auto-offsets
 * by type, Hog follows on, MA3's Edit Patch is consecutive) and what a Set over N addresses lands
 * as. `footprints` is in visible-row order, one per head, and the result is index-parallel to it.
 *
 * Channels only. The universe is not part of the walk because the patch PUT cannot move a head to
 * another universe — every head keeps its own, and `patchAddress.ts` checks each landing against
 * the heads on that universe. A walk past 512 is returned as it is, so the caller can name the
 * overflow rather than have it silently wrap or clamp onto the last head.
 */
export function walkAddresses(from: number, step: number | null, footprints: readonly number[]): number[] {
  const out: number[] = []
  let next = from
  for (const footprint of footprints) {
    out.push(next)
    next += step ?? Math.max(1, footprint)
  }
  return out
}

/**
 * Fade times spread across a selection along a curve — the cue sheet's Spread.
 *
 * Milliseconds in, milliseconds out, rounded to whole ms; the ends are exact under `LINE`. The
 * curve row is where a second shape than linear was always going to go
 * (`FU-SPREAD-DURATION-CURVES`): if the cue sheet's fade spread ever moves to the desk it is an
 * `intent` in all but name.
 */
export function spreadDurations(fromMs: number, toMs: number, n: number, curve: SpreadCurve = 'LINE'): number[] {
  return spreadFractions(n, curve).map((t) => Math.round(fromMs + (toMs - fromMs) * t))
}

function clamp01(x: number): number {
  return Math.max(0, Math.min(1, x))
}

/**
 * `java.util.Random`, as far as `nextInt(bound)` — the 48-bit linear congruence the JDK documents
 * (`seed = (seed * 0x5DEECE66D + 0xB) mod 2^48`, `next(bits)` its top bits), so a seed deals the
 * same shuffle here as on the desk. BigInt for the 48-bit product; the bound arithmetic is the
 * JDK's own, including its rejection loop on a non-power-of-two bound.
 */
export function javaRandom(seed: number): { nextInt: (bound: number) => number } {
  const MULTIPLIER = 0x5deece66dn
  const ADDEND = 0xbn
  const MASK = (1n << 48n) - 1n
  let state = (BigInt(Math.trunc(seed)) ^ MULTIPLIER) & MASK
  const next = (bits: number): number => {
    state = (state * MULTIPLIER + ADDEND) & MASK
    return Number(state >> BigInt(48 - bits))
  }
  return {
    nextInt(bound: number): number {
      if (bound <= 0) throw new RangeError('bound must be positive')
      if ((bound & -bound) === bound) return Number((BigInt(bound) * BigInt(next(31))) >> 31n)
      let bits: number
      let value: number
      do {
        bits = next(31)
        value = bits % bound
      } while (((bits - value + (bound - 1)) | 0) < 0)
      return value
    },
  }
}
