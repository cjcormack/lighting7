import { describe, expect, it } from 'vitest'
import { javaRandom, rawValues, spreadDurations, spreadFractions, spreadPositions, walkAddresses } from './spreadPlans'

/**
 * The client-walked kinds' arithmetic (editor-kit plan D2, D15): the fraction rules mirrored from
 * lighting7's `fx/SpreadPlan.kt` as a table, the address walk, the byte lerp for Speed, and the
 * cue sheet's fade times along a curve. Nothing here touches an intent.
 */
describe('spreadFractions', () => {
  it('handles degenerate counts the way the desk does: one step sits at From', () => {
    expect(spreadFractions(0, 'LINE')).toEqual([])
    expect(spreadFractions(1, 'LINE')).toEqual([0])
    expect(spreadFractions(2, 'LINE')).toEqual([0, 1])
  })

  it('draws the four curves over eight heads as SpreadPlan.kt draws them', () => {
    const round = (xs: number[]) => xs.map((x) => Math.round(x * 100) / 100)
    expect(round(spreadFractions(8, 'LINE'))).toEqual([0, 0.14, 0.29, 0.43, 0.57, 0.71, 0.86, 1])
    expect(round(spreadFractions(8, 'MIRROR'))).toEqual([1, 0.71, 0.43, 0.14, 0.14, 0.43, 0.71, 1])
    expect(round(spreadFractions(8, 'ARROW'))).toEqual([0, 0.29, 0.57, 0.86, 0.86, 0.57, 0.29, 0])
    // Two mirrored fans meeting at the centre: `1 · .67 · .33 · 0 | 0 · .33 · .67 · 1`.
    expect(round(spreadFractions(8, 'WINGS'))).toEqual([1, 0.67, 0.33, 0, 0, 0.33, 0.67, 1])
    // An odd run's centre head belongs to both wings: `1 · .5 · 0 · .5 · 1`.
    expect(spreadFractions(5, 'WINGS')).toEqual([1, 0.5, 0, 0.5, 1])
  })

  it('orders as the desk does: Reverse runs the other way, Centre folds the ends together', () => {
    expect(spreadPositions(4, 'REVERSE')).toEqual([1, 2 / 3, 1 / 3, 0])
    expect(spreadPositions(4, 'CENTER_OUT')).toEqual([1, 0, 0, 1])
    expect(spreadPositions(5, 'CENTER_OUT')).toEqual([1, 0.5, 0, 0.5, 1])
  })

  it('deals Random exactly as the desk does — java.util.Random(seed * 31 + n) and a Fisher–Yates walk', () => {
    // The permutations below were dealt by the JDK itself (a `java.util.Random((long) seed * 31
    // + n)` Fisher–Yates walk, `openjdk` on 2026-09-22), not by this port: they are what
    // `DistributionStrategy.RANDOM` on the desk deals for the same seed and head count.
    const dealtByJdk: [seed: number, n: number, order: number[]][] = [
      [0, 6, [3, 4, 0, 2, 5, 1]],
      [3, 6, [2, 4, 0, 5, 3, 1]],
      [4, 6, [1, 2, 3, 4, 5, 0]],
      [7, 8, [1, 7, 2, 3, 4, 0, 6, 5]],
      [1, 5, [1, 0, 3, 2, 4]],
      [0, 4, [0, 3, 1, 2]],
      [42, 3, [0, 2, 1]],
      [5, 2, [0, 1]],
    ]
    for (const [seed, n, order] of dealtByJdk) {
      const random = javaRandom(seed * 31 + n)
      const walked = Array.from({ length: n }, (_, i) => i)
      for (let i = n - 1; i >= 1; i--) {
        const j = random.nextInt(i + 1)
        ;[walked[i], walked[j]] = [walked[j], walked[i]]
      }
      expect(walked).toEqual(order)
      // …and the positions are each head's dealt index over n − 1, the desk's normalisation.
      expect(spreadPositions(n, 'RANDOM', seed)).toEqual(order.map((dealt) => dealt / (n - 1)))
    }
    // A press of Random again bumps the seed, which deals another permutation.
    expect(spreadPositions(6, 'RANDOM', 3)).not.toEqual(spreadPositions(6, 'RANDOM', 4))
  })

  it('cuts the order into parts, the first runs one longer when it does not divide', () => {
    expect(spreadFractions(4, 'LINE', 'LINEAR', 2)).toEqual([0, 1, 0, 1])
    expect(spreadFractions(5, 'LINE', 'LINEAR', 2)).toEqual([0, 0.5, 1, 0, 1])
    // More parts than heads is one head per run.
    expect(spreadFractions(2, 'LINE', 'LINEAR', 5)).toEqual([0, 0])
  })
})

describe('rawValues', () => {
  it('lands bytes at each fraction, rounded, endpoints exact', () => {
    expect(rawValues(0, 255, spreadFractions(3, 'LINE'))).toEqual([0, 128, 255])
    expect(rawValues(255, 0, spreadFractions(3, 'LINE'))).toEqual([255, 128, 0])
    expect(rawValues(10, 250, spreadFractions(7, 'LINE'))[6]).toBe(250)
    expect(rawValues(0, 255, [])).toEqual([])
  })
})

describe('walkAddresses', () => {
  it('lands each head after the previous by its own footprint when the step is blank', () => {
    expect(walkAddresses(1, null, [6, 18, 6])).toEqual([1, 7, 25])
  })

  it('uses a fixed step when one is given, ignoring footprints', () => {
    expect(walkAddresses(300, 20, [6, 18, 6])).toEqual([300, 320, 340])
  })

  it('walks past 512 without clamping, so the caller can name the overflow', () => {
    expect(walkAddresses(500, null, [6, 6, 6])).toEqual([500, 506, 512])
  })
})

describe('spreadDurations', () => {
  it('spreads fade times first→last along a line, rounded to whole ms', () => {
    expect(spreadDurations(1000, 4000, 4)).toEqual([1000, 2000, 3000, 4000])
    expect(spreadDurations(0, 1000, 3)).toEqual([0, 500, 1000])
    expect(spreadDurations(1000, 4000, 1)).toEqual([1000])
    expect(spreadDurations(1000, 4000, 0)).toEqual([])
  })

  it('takes the curve row: Mirror puts From at the centre and To at both ends', () => {
    expect(spreadDurations(1000, 3000, 3, 'MIRROR')).toEqual([3000, 1000, 3000])
    expect(spreadDurations(1000, 3000, 3, 'ARROW')).toEqual([1000, 3000, 1000])
  })
})
