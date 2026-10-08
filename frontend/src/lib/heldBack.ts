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
 * corner dot (session 5) will too.
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
