import type { ActiveEffect } from '../../store/fixtureFx'
import type { Fixture } from '../../store/fixtures'

/**
 * The effect half of the marquee's Backspace: which running effects a cell clear takes with it.
 *
 * "Clear these cells" has always meant *take them out of Local*, and an effect busked onto those
 * same cells is Local in every sense that matters — it is the operator's own, Record writes it onto
 * the cue beside the values, and the programmer's whole-desk **Clear** already sweeps the two
 * together (`clearProgrammerCompletely` in lighting7: "programmer values **and** programmer FX").
 * So this is that rule narrowed to a rectangle rather than a new gesture: three cells under an
 * effect template's press are three instances, and stopping them one at a time in the rail was the
 * only way back.
 *
 * Two rules decide what may go, and each keeps the key from reaching further than the selection:
 *
 *  - **Local means unowned.** An effect that came out of a Look, a template layer or a cue is that
 *    thing's, not the operator's: stopping it here would either be undone by the next recook or
 *    quietly edit a library record. [isLocalEffect] is `programmerOwned` *and* no source at all.
 *  - **Every head, or none.** An effect drives whatever its target names — for a group target,
 *    every member — and there is no "stop it on these heads only". So it goes only when the clear
 *    covers all of them; covering some leaves it running and says so, because an effect that half
 *    vanished from the rig would be worse than one that did not move.
 */
export function isLocalEffect(effect: ActiveEffect): boolean {
  return (
    effect.programmerOwned &&
    effect.lookId == null &&
    effect.templateId == null &&
    effect.programmerLayerId == null &&
    effect.cueId == null
  )
}

/**
 * The key a cleared cell and an effect are matched on: one head, one property.
 *
 * `FxSheet` places an effect on a cell by the same pairing and built it inline; both read this now,
 * so the format has one owner. An element-targeted effect keys on the element key, which *is* the
 * row model's name for that target, so no extra mapping is needed on either side.
 */
export function cellEffectKey(fixtureKey: string, propertyName: string): string {
  return `${fixtureKey}|${propertyName}`
}

/**
 * The heads of each group, for expanding a group-targeted effect onto them.
 *
 * Shared with `FxSheet` for the reason [cellEffectKey] is: the two were the same loop, and a
 * change to what group membership means has to land in one place. `rowModel`'s own map is a
 * different thing — `Map<string, Fixture[]>`, built for rendering rows — and is deliberately not
 * folded in here.
 */
export function membersByGroupOf(fixtures: readonly Fixture[] | undefined): Map<string, string[]> {
  const map = new Map<string, string[]>()
  for (const fixture of fixtures ?? []) {
    for (const name of fixture.groups) {
      const list = map.get(name)
      if (list) list.push(fixture.key)
      else map.set(name, [fixture.key])
    }
  }
  return map
}

export interface EffectSweep {
  /** Fully covered by the clear — stop these. */
  stop: ActiveEffect[]
  /** Overlaps the clear but also drives heads outside it — left running. */
  partial: ActiveEffect[]
}

/**
 * Split the running effects against the cells a clear is about to take out of Local.
 *
 * [cleared] holds [cellEffectKey]s, which is the same `fixtureKey|propertyName` pairing `FxSheet`
 * places an effect on a cell by — including element targets, whose key *is* the row model's name
 * for them, so no extra mapping is needed. [membersOf] expands a group target the same way; a
 * group nothing is patched into contributes no heads and is never swept, since "all of nothing"
 * would otherwise be vacuously true.
 */
export function effectsToStop(
  effects: readonly ActiveEffect[],
  membersOf: (groupName: string) => readonly string[],
  cleared: ReadonlySet<string>,
): EffectSweep {
  const stop: ActiveEffect[] = []
  const partial: ActiveEffect[] = []
  if (cleared.size === 0) return { stop, partial }
  for (const effect of effects) {
    if (!isLocalEffect(effect)) continue
    const heads = effect.isGroupTarget ? membersOf(effect.targetKey) : [effect.targetKey]
    if (heads.length === 0) continue
    let covered = 0
    for (const head of heads) {
      if (cleared.has(cellEffectKey(head, effect.propertyName))) covered += 1
    }
    if (covered === 0) continue
    if (covered === heads.length) stop.push(effect)
    else partial.push(effect)
  }
  return { stop, partial }
}

/** What to tell the operator about the effects a clear could not take. */
export function partialSweepMessage(partial: readonly ActiveEffect[]): string {
  const one = partial.length === 1
  return `${partial.length} effect${one ? '' : 's'} left running — ${
    one ? 'it drives' : 'they drive'
  } heads outside the selection. Select every head ${
    one ? 'it covers' : 'they cover'
  }, or stop ${one ? 'it' : 'them'} in the FX list.`
}
