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
 *  - `slotAssignmentFor`'s own **kind re-check**, which is a second line of defence for a template
 *    and — deliberately, see its docblock — is *not* one for a deferred-effect Look. The test below
 *    asserts both halves of that asymmetry directly, by handing it a drag whose `slotEligible` is
 *    wrongly true.
 *  - The table's other three rows, which nothing else states.
 *
 * The earlier version built its fixture's `slotEligible` from a hand-written copy of the formula
 * and then asserted `canHandLand` agreed with `slotAssignmentFor` — which reads that flag. For the
 * deferred-Look row that compared two hardcoded copies of one rule, so real drift in
 * `LibraryPalette` would have kept it green. That is the failure this comment exists to prevent a
 * second time.
 */

/**
 * A palette drag of the shape `slotAssignmentFor` reads.
 *
 * `slotEligible` is taken from the **real rule** rather than recomputed here, which is what
 * `LibraryPalette` now does too — so this fixture is the palette's row, not an imitation of it.
 * `eligible` overrides it, for the two tests that need a *wrongly* eligible drag to probe
 * `slotAssignmentFor`'s own re-check.
 */
function paletteDrag(kind: BuskPadKind, hasDeferredEffects = false, eligible?: boolean) {
  const record =
    kind === 'CUE'
      ? { kind: 'CUE' as const, cue: { id: 7, name: 'Cue 1', cueNumber: '1', cueStackId: 1, cueStackName: 'Act I' } }
      : kind === 'LOOK'
        ? { kind: 'LOOK' as const, look: { id: 7, name: 'Warm', hasDeferredEffects } }
        : { kind: 'TEMPLATE' as const, template: { id: 7, name: 'Amber' } }
  return {
    type: 'busk-palette',
    record,
    face: {},
    slotEligible: eligible ?? canHandLand({ kind, hasDeferredEffects }, 'slot'),
  }
}

const KINDS: BuskPadKind[] = ['TEMPLATE', 'LOOK', 'CUE']

describe('canHandLand', () => {
  it('lets a bank take all three kinds — a pad is a reference to exactly one of them', () => {
    for (const kind of KINDS) {
      expect(canHandLand({ kind }, 'bank')).toBe(true)
    }
    expect(canHandLand({ kind: 'LOOK', hasDeferredEffects: true }, 'bank')).toBe(true)
  })

  it('routes every kind to the same answer slotAssignmentFor gives for a palette row', () => {
    const cases: { kind: BuskPadKind; deferred?: boolean }[] = [
      { kind: 'TEMPLATE' },
      { kind: 'LOOK' },
      { kind: 'LOOK', deferred: true },
      { kind: 'CUE' },
    ]
    for (const { kind, deferred } of cases) {
      const slotWouldTakeIt = slotAssignmentFor(paletteDrag(kind, deferred === true)) != null
      expect(
        canHandLand({ kind, hasDeferredEffects: deferred }, 'slot'),
        `${kind}${deferred === true ? ' (deferred)' : ''}`,
      ).toBe(slotWouldTakeIt)
    }
  })

  it('lets both layer stacks take a Look or a template, and neither take a cue', () => {
    for (const target of ['layer-stack', 'cue-stack'] as const) {
      expect(canHandLand({ kind: 'LOOK' }, target)).toBe(true)
      expect(canHandLand({ kind: 'LOOK', hasDeferredEffects: true }, target)).toBe(true)
      expect(canHandLand({ kind: 'TEMPLATE' }, target)).toBe(true)
      expect(canHandLand({ kind: 'CUE' }, target)).toBe(false)
    }
  })

  it('leaves a cue only a bank or a slot — the rule the plan states, derived not restated', () => {
    const takers = HAND_TARGET_KINDS.filter((target) => canHandLand({ kind: 'CUE' }, target))
    expect(takers).toEqual(['bank', 'slot'])
  })

  it('reads an absent hasDeferredEffects as "no", so a bare kind is enough for a slot', () => {
    expect(canHandLand({ kind: 'LOOK' }, 'slot')).toBe(true)
  })
})

/**
 * `slotAssignmentFor`'s second line of defence, and the exact shape of the hole in it.
 *
 * These are the assertions that can still fail independently of `canHandLand`, because they feed it
 * a drag whose `slotEligible` is wrong on purpose — which no amount of sharing the rule can produce.
 */
describe('slotAssignmentFor against a wrongly-eligible drag', () => {
  it('still refuses a template, because it re-checks the kind itself', () => {
    expect(slotAssignmentFor(paletteDrag('TEMPLATE', false, true))).toBeNull()
  })

  it('does NOT catch a deferred-effect Look — that half rests on canHandLand alone', () => {
    // Not a defect to fix here: it is the documented asymmetry in `slotDrop.ts`, pinned so that a
    // future reader who assumes the re-check covers both cases is contradicted by a test rather
    // than by a rig. If this ever starts returning null, the docblock there needs updating too.
    expect(slotAssignmentFor(paletteDrag('LOOK', true, true))).toEqual({ lookId: 7 })
  })
})

describe('handRefusalReason', () => {
  it('is null wherever the record would land', () => {
    expect(handRefusalReason({ kind: 'CUE' }, 'bank')).toBeNull()
    expect(handRefusalReason({ kind: 'LOOK' }, 'slot')).toBeNull()
  })

  it('names the actual reason for each refusal, not one generic line', () => {
    expect(handRefusalReason({ kind: 'TEMPLATE' }, 'slot')).toMatch(/selection/)
    expect(handRefusalReason({ kind: 'LOOK', hasDeferredEffects: true }, 'slot')).toMatch(
      /deferred effect/,
    )
    expect(handRefusalReason({ kind: 'CUE' }, 'layer-stack')).toMatch(/never a cue/)
  })
})

describe('the rig row', () => {
  it('is a target kind that takes nothing the hand can hold today', () => {
    expect(HAND_TARGET_KINDS).toContain('rig-row')
    for (const kind of KINDS) expect(canHandLand({ kind }, 'rig-row')).toBe(false)
    expect(canHandLand({ kind: 'LOOK', hasDeferredEffects: true }, 'rig-row')).toBe(false)
  })

  it('says why', () => {
    expect(handRefusalReason({ kind: 'CUE' }, 'rig-row')).toMatch(/cannot hold one yet/)
  })
})
