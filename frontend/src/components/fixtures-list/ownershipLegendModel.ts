import type { CellOwnershipSource } from './useRowOwnership'

/**
 * Operator-facing gloss for each ownership colour.
 *
 * `OWNERSHIP_LABELS` in `ownership.ts` names the source; this says what it *means for you*, which
 * is a different job — "You — Record takes this" is the sentence that makes the accent ring worth
 * learning, and it is not what belongs in a hover tooltip. `OwnershipLegend.test.ts` asserts the
 * two maps cover the same keys, so adding a source cannot leave the legend silently short of one.
 *
 * A plain module rather than exports from `OwnershipLegend.tsx` so the component file exports only
 * a component — see the React Refresh note in CLAUDE.md.
 */
export const LEGEND_GLOSS: Record<CellOwnershipSource, string> = {
  programmer: 'You — Record takes this',
  cue: 'Cue',
  effect: 'Effect',
  parked: 'Parked',
  baseline: 'Nothing asserts it',
}

/**
 * The same five, as the words the artboard actually draws.
 *
 * The legend is a 22px footer since the space plan's session 1, so it has one line and no wrap.
 * `LEGEND_GLOSS` is what an operator needs the *first* time; this is what fits once they know it,
 * and the two differ in exactly one place — "You" against "You — Record takes this". The rest are
 * the same string in both maps on purpose: a short form invented for its own sake would be a
 * second vocabulary for one idea. The long form is still one hover away at every width.
 */
export const LEGEND_SHORT: Record<CellOwnershipSource, string> = {
  programmer: 'You',
  cue: 'Cue',
  effect: 'Effect',
  parked: 'Parked',
  baseline: 'Nothing asserts it',
}

/** Draw order — loudest claim first, "nothing" last. */
export const LEGEND_ORDER: readonly CellOwnershipSource[] = [
  'programmer',
  'cue',
  'effect',
  'parked',
  'baseline',
]

/**
 * The key for **layer scope**, where the ownership colours are switched off entirely.
 *
 * A separate list rather than extra entries in the one above, because it describes a different
 * subject: the ownership vocabulary answers "which part of the engine is painting the rig", and in
 * layer scope the grid is showing a Look's stored rows, which the rig has no opinion about. Leaving
 * the six-colour key under a grid drawing none of them would be a legend for something not on
 * screen.
 */
export type LayerLegendKey = 'set' | 'unset' | 'inert' | 'untargeted'

export const LAYER_LEGEND_GLOSS: Record<LayerLegendKey, string> = {
  set: 'This look sets it',
  unset: 'Not in this look',
  inert: 'Outside the mask',
  untargeted: 'Outside the targets',
}

export const LAYER_LEGEND_ORDER: readonly LayerLegendKey[] = [
  'set',
  'unset',
  'inert',
  'untargeted',
]
