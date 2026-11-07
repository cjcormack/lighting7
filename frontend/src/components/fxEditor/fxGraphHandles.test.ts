import { describe, expect, it } from 'vitest'
import { previewFx } from '@/test/fxPreviewAnswers'
import { cycleFromDrag, phaseFromDrag, snapCycle, spreadFromDrag, spreadShareOf } from './fxGraphHandles'

/**
 * What each of the graph's handles writes (FX editor plan D12): the Speed snapped to a power of two, Spread
 * moved so the grabbed row's start follows the finger, Phase wrapped within its cycle.
 */
describe('the graph’s handles', () => {
  it('snaps the Speed to the nearest power of two, by ratio', () => {
    expect(snapCycle(3.1)).toBe(4)
    expect(snapCycle(2.7)).toBe(2)
    expect(snapCycle(0.01)).toBe(0.125)
    expect(snapCycle(100)).toBe(128)
    for (const cycle of [0.125, 0.5, 1, 2, 4, 8, 16, 64]) expect(snapCycle(cycle)).toBe(cycle)
  })

  it('scales the cycle by how far the first cycle’s edge moved from the plot’s left, onto a snap', () => {
    // The edge 100px into the plot: dragged to 200px is twice the cycle, to 50px half of it.
    expect(cycleFromDrag(4, 72, 172, 272)).toBe(8)
    expect(cycleFromDrag(4, 72, 172, 122)).toBe(2)
    expect(cycleFromDrag(4, 72, 172, 180)).toBe(4)
    // Past the plot's left edge it is the fastest snap the drag reaches, never zero or negative.
    expect(cycleFromDrag(4, 72, 172, 0)).toBe(0.125)
  })

  it('keeps a Speed off the snaps until the drag leaves it for one', () => {
    // A triplet's 3 beats (×⅔): a nudge keeps it, a real drag moves to a snap.
    expect(cycleFromDrag(3, 72, 172, 175)).toBe(3)
    expect(cycleFromDrag(3, 72, 172, 202)).toBe(4)
    expect(cycleFromDrag(32, 72, 172, 122)).toBe(16)
  })

  it('snaps a wall-clock cycle to a power of two of its Speed too', () => {
    // Two seconds is ×1 on the wall clock.
    expect(cycleFromDrag(2, 72, 172, 272, true)).toBe(4)
    expect(cycleFromDrag(3, 72, 172, 175, true)).toBe(3)
    expect(cycleFromDrag(4, 72, 172, 122, true)).toBe(2)
  })

  it('moves Spread by the drag over the grabbed row’s share of a cycle, within the desk’s range', () => {
    // A row half way through the ranks: 12 samples is a quarter-cycle, so Spread moves by a half.
    expect(spreadFromDrag(1, 12, 0.5)).toBe(1.5)
    expect(spreadFromDrag(1, -12, 0.5)).toBe(0.5)
    expect(spreadFromDrag(1, 480, 0.5)).toBe(2)
    expect(spreadFromDrag(1, -480, 0.5)).toBe(0)
    // A row with no share (the first, rank 0) cannot move Spread.
    expect(spreadFromDrag(1, 12, 0)).toBe(1)
  })

  it('counts a STEP spread in the FX’s own steps, up to 16 (fx-settings plan D6)', () => {
    const fx = previewFx(4)
    // Three colours: each unit of Spread moves a row by its rank over three, not over the four ranks.
    expect([0, 1, 2, 3].map((r) => spreadShareOf(fx, r, 3))).toEqual([0, 1 / 3, 2 / 3, 1])
    expect(spreadFromDrag(1, 480, 0.5, true)).toBe(16)
    expect(spreadFromDrag(1, 480, 0.5)).toBe(2)
  })

  it('reads a row’s share of Spread off the desk’s Parts rung', () => {
    const fx = previewFx(4)
    expect([0, 1, 2, 3].map((r) => spreadShareOf(fx, r))).toEqual([0, 0.25, 0.5, 0.75])
    expect(spreadShareOf({ ladder: { ...fx.ladder, parts: { ranks: [null], counts: [null] } } }, 0)).toBeNull()
  })

  it('moves Phase against the drag, wrapping within the cycle and keeping a whole cycle’s parity', () => {
    // A quarter-cycle right is a quarter less Phase.
    expect(phaseFromDrag(0.5, 12)).toBeCloseTo(0.25)
    expect(phaseFromDrag(0.25, 24)).toBeCloseTo(0.75)
    expect(phaseFromDrag(1.25, 24)).toBeCloseTo(1.75)
    // Whole degrees, and never a full turn that would flip the parity.
    expect(phaseFromDrag(0, 0.001)).toBe(0)
  })
})
