import type { BuskPadKind } from '@/api/buskApi'

/**
 * **Where the desk's hand can let go** — one table, so no surface answers it privately.
 *
 * The hand holds a template, a Look or a cue (`api/handApi.ts`). Four kinds of place can take one,
 * and each already has a mutation of its own; what this module states is only *which pairs are
 * legal*, which is the half two surfaces would otherwise each answer for themselves. Two copies of
 * a coverage rule drift, and the copy in the browser is the one no test against the rig can reach —
 * so `handTargets.test.ts` pins the slot row against `slotAssignmentFor` **itself** rather than
 * against a restatement of it.
 *
 * ### The four rows, and why each is what it is
 *
 * - **A bank takes all three.** A busk pad *is* a reference to exactly one template, Look or cue
 *   (`BuskPadKind` is the same three), so there is nothing a bank can refuse.
 * - **A cue slot takes a cue, or a Look with no deferred effect.** A slot has no selection, so it
 *   can hold only what needs none (busk-layout plan D7): a template always lands on the selection,
 *   and a Look with a deferred effect has no own fixtures. This is `dnd/slotDrop.ts`'s rule and
 *   must stay identical to it.
 * - **The programmer's layer stack takes a Look or a template.** A layer applies one of those two;
 *   a cue is not a thing a layer can be.
 * - **A cue's own stack takes a Look or a template**, for the same reason — a cue layer is a cue
 *   layer whichever stack it is in.
 *
 * So a **cue in the hand can land only on a bank or a slot**, which falls out of the two rows
 * rather than being stated a fifth time.
 *
 * ### This is eligibility, never permission
 *
 * Every place still runs its own mutation and every mutation keeps its own validation. A wrong
 * answer here shows or hides a ring; it cannot put a record somewhere the desk would refuse.
 */

/**
 * The five kinds of place a held record can be dropped on.
 *
 * **`rig-row` takes nothing the hand can hold today.** A rig row is made of groups, fixtures and
 * cells (busk-further plan session 3), and the hand's wire — `hand.pickUp {kind, id}` with
 * `HeldRecord.kind` a `BuskPadKind`, `HandState.kt` requiring one of the three summaries — holds
 * only a template, a Look or a cue. So the row's `HandPlaceStrip` is mounted, wired to the rig PUT
 * and `hand.drop`, and never lights; the day the desk can hold a group, this table and
 * `RigBand`'s `rigRecordOf` are the two places to teach, and nothing else moves. Recorded as a
 * session 3 amendment in the plan rather than worked around here, because a client-side pick-up
 * of a group would be a second hand.
 */
export type HandTargetKind = 'bank' | 'slot' | 'layer-stack' | 'cue-stack' | 'rig-row'

/**
 * What a target needs to know about the held record — deliberately **not** the whole
 * {@link import('@/api/handApi').HeldRecord}, so this module is callable from a test, from the
 * palette's row model, and from a surface that has only a summary in hand.
 */
export interface HandCandidate {
  kind: BuskPadKind
  /**
   * For a `LOOK`: does it have an effect with no targets of its own? A slot cannot supply one.
   * Ignored for the other two kinds, and `undefined` is read as "no" — the honest default, since
   * every other kind has no deferred effects by construction.
   */
  hasDeferredEffects?: boolean
}

/** Can this record be placed here? */
export function canHandLand(candidate: HandCandidate, target: HandTargetKind): boolean {
  switch (target) {
    case 'bank':
      return true
    case 'slot':
      if (candidate.kind === 'CUE') return true
      return candidate.kind === 'LOOK' && candidate.hasDeferredEffects !== true
    case 'layer-stack':
    case 'cue-stack':
      return candidate.kind === 'LOOK' || candidate.kind === 'TEMPLATE'
    case 'rig-row':
      // No held kind is a rig target; see the type's note.
      return false
  }
}

/**
 * Why a target is refusing, for the one place that says so out loud — the chip's own line while a
 * target is hovered. Null when it would land.
 *
 * Kept beside the table rather than at the surfaces, because a reason that disagreed with the rule
 * would be worse than no reason at all.
 */
export function handRefusalReason(
  candidate: HandCandidate,
  target: HandTargetKind,
): string | null {
  if (canHandLand(candidate, target)) return null
  if (target === 'slot') {
    if (candidate.kind === 'TEMPLATE') return 'A slot has no selection, and a template needs one'
    return 'This Look has a deferred effect, so it needs a selection — a slot has none to give'
  }
  if (target === 'rig-row') return 'A rig row takes a group, a fixture or a cell, and the hand cannot hold one yet'
  return 'A layer applies a Look or a template, never a cue'
}

/**
 * Every target a held record could land on, in the order the four surfaces read top to bottom.
 *
 * **Derived through a `Record`, not written as a bare array**, and for `BindingTargetPicker`'s
 * reason: an array literal type-checks while *missing* a union member, so a fifth target kind could
 * ship with `canHandLand` correctly updated — that switch has no `default`, so it is a compile error
 * — and this list silently one short, leaving every consumer that iterates it never offering the new
 * kind, with nothing to catch it. A missing key in the record below is a compile error.
 */
const ALL_HAND_TARGETS: Record<HandTargetKind, true> = {
  bank: true,
  slot: true,
  'layer-stack': true,
  'cue-stack': true,
  'rig-row': true,
}

export const HAND_TARGET_KINDS: readonly HandTargetKind[] = Object.keys(
  ALL_HAND_TARGETS,
) as HandTargetKind[]
