import type { CueTarget } from '@/api/cuesApi'
import type { ElementDescriptor, Fixture, AttributeDescriptor } from '@/store/fixtures'

/**
 * What a running effect targets, read off the desk's report (effects plan D3): one fixture, one
 * group, or an ordered **selection** of them, which the desk spreads over as it does a group's
 * members. A selection's `targetKey` is its canonical key, never a fixture's or a group's, so every
 * question about which heads an effect reaches goes through here rather than comparing that key.
 */
export interface FxTargetFields {
  targetKey: string
  isGroupTarget: boolean
  /** `fixture`, `group` or `selection`; absent from a desk before effects plan session 3. */
  targetType?: string
  /** A selection's fixtures and groups, in order. */
  targets?: CueTarget[] | null
}

/** A selection's items, or null for an effect on one fixture or one group. */
export function selectionItems(effect: FxTargetFields): CueTarget[] | null {
  return effect.targetType === 'selection' ? (effect.targets ?? []) : null
}

/** Whether the effect spreads over members — a group's, or a selection's. */
export function spreadsOverMembers(effect: FxTargetFields): boolean {
  return effect.isGroupTarget || effect.targetType === 'selection'
}

/**
 * Whether the effect names one of [keys] (a fixture or its heads) or a group in [groups] — itself,
 * or as one of a selection's items. The desk paints a fixture's effect onto its heads, so a caller
 * passes a head's fixture too.
 */
export function fxNames(effect: FxTargetFields, keys: ReadonlySet<string>, groups: ReadonlySet<string>): boolean {
  const items = selectionItems(effect)
  if (items != null) return items.some((t) => (t.type === 'group' ? groups.has(t.key) : keys.has(t.key)))
  return effect.isGroupTarget ? groups.has(effect.targetKey) : keys.has(effect.targetKey)
}

/**
 * The fixture and head keys the effect is aimed at, in its order, each once: a fixture's own key,
 * a group's members (from [membersOf]), a selection's items flattened. A fixture whose heads carry
 * the property is its own key here, as the desk's report names it.
 */
export function fxAimKeys(effect: FxTargetFields, membersOf: (group: string) => readonly string[]): string[] {
  const items = selectionItems(effect)
  const flat =
    items != null
      ? items.flatMap((t) => (t.type === 'group' ? [...membersOf(t.key)] : [t.key]))
      : effect.isGroupTarget
        ? [...membersOf(effect.targetKey)]
        : [effect.targetKey]
  return [...new Set(flat)]
}

/**
 * What the effect is on, in words: a fixture's or group's name, or a selection's — its first two
 * items and how many more (`Front wash, Par 3 +2`).
 */
export function fxTargetLabel(effect: FxTargetFields, nameOf: (target: CueTarget) => string = (t) => t.key): string {
  const items = selectionItems(effect)
  if (items == null) return nameOf({ type: effect.isGroupTarget ? 'group' : 'fixture', key: effect.targetKey })
  const named = items.slice(0, 2).map(nameOf).join(', ')
  return items.length > 2 ? `${named} +${items.length - 2}` : named
}

/**
 * A stored selection's items from its canonical key — the `[{"key":…,"type":…}]` a Look or cue
 * effect row carries. Anything that is not such a list reads as empty, as the desk refuses it.
 */
export function parseSelectionKey(key: string): CueTarget[] {
  let parsed: unknown
  try {
    parsed = JSON.parse(key)
  } catch {
    return []
  }
  if (!Array.isArray(parsed)) return []
  const out: CueTarget[] = []
  for (const item of parsed) {
    if (item == null || typeof item !== 'object') return []
    const { type, key: itemKey } = item as { type?: unknown; key?: unknown }
    if ((type !== 'fixture' && type !== 'group') || typeof itemKey !== 'string') return []
    out.push({ type, key: itemKey })
  }
  return out
}

/** A stored effect row's targets: the one it names, or a selection's items. */
export function storedFxTargets(targetType: string, targetKey: string): CueTarget[] {
  if (targetType === 'selection') return parseSelectionKey(targetKey)
  if (targetType === 'fixture' || targetType === 'group') return [{ type: targetType, key: targetKey }]
  return []
}

/** A unit of a selection: a fixture with its heads, or a cell with none. */
export interface SelectionUnitOf {
  key: string
  attributes: readonly AttributeDescriptor[]
  elements?: readonly ElementDescriptor[]
}

/**
 * A selection's units as the desk walks them (`FxEngine.selectionUnitKeys`): each fixture or cell it
 * names, each member of each group it names, in order and each once. A cell is a unit with its own
 * properties and no heads; a key the fixture list does not know is skipped, as the desk skips it.
 * A head reached twice is the counter's to drop ([selectionHeadCount]), as on the desk.
 */
export function selectionUnits(
  items: readonly CueTarget[],
  fixtures: readonly Fixture[] | undefined,
): SelectionUnitOf[] {
  const list = fixtures ?? []
  const seen = new Set<string>()
  const out: SelectionUnitOf[] = []
  const push = (unit: (typeof out)[number]) => {
    if (seen.has(unit.key)) return
    seen.add(unit.key)
    out.push(unit)
  }
  for (const item of items) {
    if (item.type === 'group') {
      for (const f of list) if (f.groups.includes(item.key)) push(f)
      continue
    }
    const fixture = list.find((f) => f.key === item.key)
    if (fixture) {
      push(fixture)
      continue
    }
    const cell = list.flatMap((f) => f.elements ?? []).find((e) => e.key === item.key)
    if (cell) push({ key: cell.key, attributes: cell.attributes })
  }
  return out
}

/** The fixtures a selection reaches — a cell by its fixture — for their property descriptors. */
export function selectionFixtures(items: readonly CueTarget[], fixtures: readonly Fixture[] | undefined): Fixture[] {
  const list = fixtures ?? []
  const out = new Map<string, Fixture>()
  for (const item of items) {
    const reached =
      item.type === 'group'
        ? list.filter((f) => f.groups.includes(item.key))
        : list.filter((f) => f.key === item.key || (f.elements ?? []).some((e) => e.key === item.key))
    for (const f of reached) if (!out.has(f.key)) out.set(f.key, f)
  }
  return [...out.values()]
}
