import { describe, expect, it } from 'vitest'
import { canHandLand, handRefusalReason, HAND_TARGET_KINDS } from './handTargets'
import { slotAssignmentFor } from '@/components/dnd/slotDrop'
import type { BuskPadKind } from '@/api/buskApi'

/**
 * The hand's eligibility table (multi-screen plan §3.5).
 *
 * **What this file can and cannot pin, stated honestly**, because a first cut got it wrong. The
 * slot rule now has exactly one statement — `canHandLand(…, 'slot')` — which `LibraryPalette` calls
 * to set each row's `slotEligible` and the hand's targets call directly. So "do the two agree" is
 * no longer a question a test *can* fail: they are the same function, which is the point. What is
 * worth pinning instead is the part that is still two independent pieces of code:
 *
 *  - `slotAssignmentFor`'s own **kind re-check**, a second line of defence for a palette. The test
 *    below asserts it directly, by handing it a drag whose `slotEligible` is wrongly true.
 *  - The table's other three rows, which nothing else states.
 */

/**
 * A palette drag of the shape `slotAssignmentFor` reads.
 *
 * `slotEligible` is taken from the **real rule** rather than recomputed here, which is what
 * `LibraryPalette` now does too — so this fixture is the palette's row, not an imitation of it.
 * `eligible` overrides it, for the two tests that need a *wrongly* eligible drag to probe
 * `slotAssignmentFor`'s own re-check.
 */
function paletteDrag(kind: BuskPadKind, eligible?: boolean) {
  const record =
    kind === 'CUE'
      ? { kind: 'CUE' as const, cue: { id: 7, name: 'Cue 1', entries: [{ stackId: 1, stackName: 'Act I', entryId: 107, cueNumber: '1' }] } }
      : kind === 'LOOK'
        ? { kind: 'LOOK' as const, look: { id: 7, name: 'Warm' } }
        : { kind: 'PALETTE' as const, palette: { id: 7, name: 'Amber' } }
  return {
    type: 'busk-palette',
    record,
    face: {},
    slotEligible: eligible ?? canHandLand({ kind }, 'slot'),
  }
}

const KINDS: BuskPadKind[] = ['PALETTE', 'LOOK', 'CUE']

describe('canHandLand', () => {
  it('lets a bank take all three kinds — a pad is a reference to exactly one of them', () => {
    for (const kind of KINDS) {
      expect(canHandLand({ kind }, 'bank')).toBe(true)
    }
  })

  it('routes every kind to the same answer slotAssignmentFor gives for a palette row', () => {
    for (const kind of KINDS) {
      expect(canHandLand({ kind }, 'slot'), kind).toBe(slotAssignmentFor(paletteDrag(kind)) != null)
    }
  })

  it('lets both layer stacks take a Look or a palette, and neither take a cue', () => {
    for (const target of ['layer-stack', 'cue-stack'] as const) {
      expect(canHandLand({ kind: 'LOOK' }, target)).toBe(true)
      expect(canHandLand({ kind: 'PALETTE' }, target)).toBe(true)
      expect(canHandLand({ kind: 'CUE' }, target)).toBe(false)
    }
  })

  it('leaves a cue only a bank or a slot — the rule the plan states, derived not restated', () => {
    const takers = HAND_TARGET_KINDS.filter((target) => canHandLand({ kind: 'CUE' }, target))
    expect(takers).toEqual(['bank', 'slot'])
  })

  it('lets a slot take any Look, which lands whole, and no palette', () => {
    expect(canHandLand({ kind: 'LOOK' }, 'slot')).toBe(true)
    expect(canHandLand({ kind: 'PALETTE' }, 'slot')).toBe(false)
  })
})

/**
 * `slotAssignmentFor`'s second line of defence: it can still fail independently of `canHandLand`,
 * because it is fed a drag whose `slotEligible` is wrong on purpose.
 */
describe('slotAssignmentFor against a wrongly-eligible drag', () => {
  it('still refuses a palette, because it re-checks the kind itself', () => {
    expect(slotAssignmentFor(paletteDrag('PALETTE', true))).toBeNull()
  })
})

describe('handRefusalReason', () => {
  it('is null wherever the record would land', () => {
    expect(handRefusalReason({ kind: 'CUE' }, 'bank')).toBeNull()
    expect(handRefusalReason({ kind: 'LOOK' }, 'slot')).toBeNull()
  })

  it('names the actual reason for each refusal, not one generic line', () => {
    expect(handRefusalReason({ kind: 'PALETTE' }, 'slot')).toMatch(/selection/)
    expect(handRefusalReason({ kind: 'CUE' }, 'layer-stack')).toMatch(/never a cue/)
  })
})

describe('the rig row', () => {
  it('is a target kind that takes nothing the hand can hold today', () => {
    expect(HAND_TARGET_KINDS).toContain('rig-row')
    for (const kind of KINDS) expect(canHandLand({ kind }, 'rig-row')).toBe(false)
  })

  it('says why', () => {
    expect(handRefusalReason({ kind: 'CUE' }, 'rig-row')).toMatch(/cannot hold one yet/)
  })
})
