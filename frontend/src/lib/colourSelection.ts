import type { CueTarget } from '@/api/cuesApi'
import type { AttributeFamily } from './attributeFamily'

/**
 * The fixtures of one colour as the desk selection: replacing it, or added to it with ⌥ (tables-
 * colours plan, `Colours.dc.html` §C). Added keeps the desk's heads, its order and its mask, and
 * appends the ones it lacks.
 */
export function selectionForColour(
  keys: readonly string[],
  desk: { targets: readonly CueTarget[]; families: readonly AttributeFamily[] | null },
  add: boolean,
): { targets: CueTarget[]; families: readonly AttributeFamily[] | null } {
  const picked: CueTarget[] = keys.map((key) => ({ type: 'fixture', key }))
  if (!add) return { targets: picked, families: null }
  const held = new Set(desk.targets.filter((t) => t.type === 'fixture').map((t) => t.key))
  return { targets: [...desk.targets, ...picked.filter((t) => !held.has(t.key))], families: desk.families }
}
