/**
 * Is an effect **held back** on one head — running, but not painting a property there because the
 * programmer holds it (fixture-fx-sheets plan D4, D19)?
 *
 * The client's copy of the desk's `EffectSuppression.heldBackByProgrammer`
 * (`fx/EffectSuppression.kt`), and it has to stay that rule exactly, because a mark that disagrees
 * with the engine is worse than no mark:
 *
 * - **Blind holds nothing back.** The desk's suppression snapshot is empty while blind — the
 *   programmer is off stage, so everything under it plays.
 * - **A programmer-band effect is never held back.** Those modulate *on top of* programmer values
 *   (`programmerOwned` on the wire is the band).
 * - **Otherwise the programmer holding an *entry* on the effect's own key holds it back** — the key
 *   the effect paints, `(head, effect.propertyName)`. A `pan` entry does not hold back a Circle
 *   keyed `position`, and a raw-channel sideband slot (which is not an entry) holds back nothing.
 *
 * What the desk also suppresses and this cannot see is a **within-cue stomp**; the property's stack
 * (`programmer.keyStack`, the sheet's `LayerStack`) is the desk's own answer and names it. The dot
 * is the cheap half — it needs nothing the client does not already hold.
 *
 * `FxSheet`'s struck-through chips and the fixture sheet's amber dot both read this; the grid's
 * corner dot (session 5) does, through [heldBackReach].
 */
export interface HeldBackEffect {
  programmerOwned: boolean
  propertyName: string
}

/** Whether the programmer holds an entry on `(headKey, propertyName)` — `getKeyState(…).entry`. */
export type ProgrammerHolds = (headKey: string, propertyName: string) => boolean

export function isHeldBack(
  effect: HeldBackEffect,
  headKey: string,
  holds: ProgrammerHolds,
  blind: boolean,
): boolean {
  if (blind) return false
  if (effect.programmerOwned) return false
  return holds(headKey, effect.propertyName)
}

/** What [heldBackReach] needs of an effect — the wire's own fields. */
export interface ReachingEffect extends HeldBackEffect {
  targetKey: string
  isGroupTarget: boolean
}

/** What [heldBackReach] needs of a fixture. */
export interface ReachFixture {
  key: string
  groups: readonly string[]
  elements?: readonly { key: string }[] | null
}

/** One `(head, property)` key of [heldBackReach]'s set. */
export function heldBackKey(headKey: string, propertyName: string): string {
  return `${headKey}\u0000${propertyName}`
}

/**
 * Every `(head, property)` an effect that **can** be held back paints (the programmer grid's corner
 * dot, D19): a non-band effect's own property on each head it reaches — its fixture and that
 * fixture's heads (the desk paints a fixture's effect onto them), a group's members and theirs, a
 * head alone. Whether one *is* held back is then [isHeldBack]'s other clause — the programmer holds
 * an entry on that key, and the programmer is not blind — which the cell asks of its own keys, so
 * this set moves only with the effect list.
 */
export function heldBackReach(effects: readonly ReachingEffect[] | undefined, fixtures: readonly ReachFixture[]): Set<string> {
  const out = new Set<string>()
  if (effects == null) return out
  const byKey = new Map(fixtures.map((f) => [f.key, f]))
  const heads = (fixture: ReachFixture) => [fixture.key, ...(fixture.elements ?? []).map((e) => e.key)]
  for (const effect of effects) {
    if (effect.programmerOwned) continue
    const reached = effect.isGroupTarget
      ? fixtures.filter((f) => f.groups.includes(effect.targetKey)).flatMap(heads)
      : byKey.has(effect.targetKey)
        ? heads(byKey.get(effect.targetKey)!)
        : [effect.targetKey]
    for (const head of reached) out.add(heldBackKey(head, effect.propertyName))
  }
  return out
}

/**
 * Does a grid cell hold an effect back (D19)? Any of its `(head, property)` keys that an effect
 * paints ([reach]) and the programmer holds an entry on — never while blind, when the desk holds
 * nothing back.
 */
export function cellHoldsBack(
  keys: readonly { targetKey: string; propertyName: string }[],
  reach: ReadonlySet<string>,
  holds: ProgrammerHolds,
  blind: boolean,
): boolean {
  if (blind || reach.size === 0) return false
  return keys.some((k) => reach.has(heldBackKey(k.targetKey, k.propertyName)) && holds(k.targetKey, k.propertyName))
}
