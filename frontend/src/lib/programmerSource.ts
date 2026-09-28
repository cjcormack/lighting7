import type { IncludedTarget } from '@/api/programmerWsApi'
import { includedTargetParts } from './includedTarget'

/** Where a cue sits in its stack, for the "cue 4 of 14" reassurance. */
export interface CuePosition {
  index: number
  total: number
}

/**
 * What the programmer is currently holding, as the source strip needs to say it.
 *
 * Every arm is reachable from data this client already has. Two states the design drew are NOT
 * here, and both were cut rather than faked:
 *
 *  - **"Q4 changed on another desk since you included it."** `Cue` carries no `updatedAt` or
 *    version and no frame announces it, so the client cannot know ambiently. The one real conflict
 *    code — `INCLUDE_TARGET_GONE` — arrives as a 409 *in response to a write*, not before one. The
 *    `missing` flag below is the one ambient case that IS knowable: the included cue no longer
 *    appears in the stack list, i.e. someone deleted it.
 *  - **Detach.** There is no op to clear the include target. Suppressing it client-side would lie
 *    to `UpdateDialog` and to every other tab.
 */
export type ProgrammerSource =
  | { kind: 'empty' }
  | { kind: 'busking'; valueCount: number }
  | {
      kind: 'cue'
      number?: string
      name?: string
      stackName?: string
      position?: CuePosition
      /** Values moved since Include, or `null` when this tab has no baseline to compare against. */
      dirty: number | null
      /** The cue has gone from the stack list — deleted from another surface. */
      missing: boolean
    }
  | {
      kind: 'look'
      name?: string
      /** e.g. "Colour" or "Colour, Position" — derived server-side from the Look's rows. */
      families?: string
      dirty: number | null
      missing: boolean
    }

export interface ProgrammerSourceInput {
  target: IncludedTarget | null
  /** `ProgrammerSummary.entryCount`: what the programmer holds, not what has changed. */
  entryCount: number
  /** Programmer-owned effects. A busked effect can exist with no value entry behind it. */
  programmerFxCount: number
  dirty: number | null
  /**
   * Resolved from the cue stack list.
   *
   * Three-valued on purpose, and the caller must respect it: an object is "found here", an explicit
   * `null` is "looked it up and it is GONE", and `undefined` is "have not looked it up yet". Only
   * `null` sets `missing` — conflating it with `undefined` would flash "the cue you were editing has
   * been deleted" over every page load, while the stack list is still in flight.
   */
  cueLocation?: { stackName?: string; position?: CuePosition } | null
  lookFamilies?: string
}

/**
 * Resolve the strip's state.
 *
 * Pure, and separated from the component for the usual reason in this codebase: the interesting
 * part is the decision table, and a decision table tests without a DOM.
 */
export function resolveProgrammerSource({
  target,
  entryCount,
  programmerFxCount,
  dirty,
  cueLocation,
  lookFamilies,
}: ProgrammerSourceInput): ProgrammerSource {
  if (target == null) {
    // "Empty" counts effects as well as values: a busking pad can leave an effect running with no
    // value entry behind it, and calling that empty would offer Record on nothing while the rig is
    // visibly doing something. Same reasoning as the Clear button's gate.
    if (entryCount === 0 && programmerFxCount === 0) return { kind: 'empty' }
    return { kind: 'busking', valueCount: entryCount }
  }

  if (target.kind === 'LOOK') {
    return {
      kind: 'look',
      name: includedTargetParts(target).name,
      families: lookFamilies,
      dirty,
      missing: false,
    }
  }

  const { number, name } = includedTargetParts(target)
  return {
    kind: 'cue',
    number,
    name,
    stackName: cueLocation?.stackName,
    position: cueLocation?.position,
    dirty,
    // `=== null`, never `== null`: see `cueLocation`'s doc above. `undefined` means the stack list
    // has not arrived, which is not the same claim as "deleted".
    missing: cueLocation === null,
  }
}

/**
 * Whether the strip may claim the programmer matches its source.
 *
 * A single guarded predicate rather than `dirty === 0` at the call site, because getting it wrong
 * is expensive in one direction only: a false "in sync" tells an operator their work is safe when
 * Update has not run, and they lose the cue. `null` means this tab never saw the Include and
 * genuinely cannot tell — so it must stay silent rather than reassure.
 */
export function canClaimInSync(source: ProgrammerSource): boolean {
  if (source.kind !== 'cue' && source.kind !== 'look') return false
  return source.dirty === 0 && !source.missing
}
