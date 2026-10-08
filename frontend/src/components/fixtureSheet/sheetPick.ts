import type { ElementDescriptor, Fixture, PropertyDescriptor, SliderPropertyDescriptor } from '@/store/fixtures'
import { buildSheetRows, type SheetFamily, type SheetRow, type SheetRowGroup } from './sheetRows'

/**
 * One pip on the head strip (fixture-fx-sheets plan D13): a head of a multi-head fixture, or a
 * member of a group. [properties] are its own descriptors — what its rows read and write.
 */
export interface PickHead {
  key: string
  name: string
  properties: readonly PropertyDescriptor[]
}

/**
 * What the strip has picked: **null is *All*** — the fixture's every head, or the group itself —
 * and a set is a subset of the heads. A set never covers every head and is never empty: both
 * normalise back to *All* ([normalisePick]), so *All* is one state, not three spellings of it.
 */
export type HeadPick = ReadonlySet<string> | null

/** A pick as it stands after an edit: unknown keys dropped, empty or every head back to *All*. */
export function normalisePick(keys: Iterable<string>, all: readonly string[]): HeadPick {
  const known = new Set(all)
  const next = new Set([...keys].filter((k) => known.has(k)))
  if (next.size === 0 || next.size === known.size) return null
  return next
}

/**
 * A pip's tap — or one pip of a run — toggles it (the busk pip's rule). From *All* nothing is
 * picked yet, so the first tap picks that one head; toggling the last one off is *All* again.
 */
export function togglePick(pick: HeadPick, key: string, all: readonly string[]): HeadPick {
  const next = new Set(pick ?? [])
  if (next.has(key)) next.delete(key)
  else next.add(key)
  return normalisePick(next, all)
}

/** The heads a pick covers, in strip order. */
export function pickedHeads<H extends PickHead>(heads: readonly H[], pick: HeadPick): H[] {
  return pick == null ? [...heads] : heads.filter((h) => pick.has(h.key))
}

/** A multi-head fixture's heads, in the desk's element order. */
export function headsOfFixture(fixture: Fixture): PickHead[] {
  return (fixture.elements ?? []).map((e: ElementDescriptor) => ({ key: e.key, name: e.displayName, properties: e.properties }))
}

/**
 * One row of the sheet over several heads: [row] is the first head's (its label, kind and family),
 * and [heads] is every picked head's own row of the same id — its own channels, its own key.
 */
export interface PickRow {
  row: SheetRow
  heads: { key: string; name: string; row: SheetRow }[]
}

export interface PickRowGroup {
  family: SheetFamily
  rows: PickRow[]
}

/**
 * The rows a set of heads share, grouped by family in D3's order: a row is drawn only where **every**
 * picked head has a row of that id and kind — the rule the desk's group aggregation uses
 * (`generateGroupPropertyDescriptors`: only properties common to all members, matched by name and
 * type), so a pick over a mixed group offers what a write could land on everywhere. A **setting**
 * also needs the same options on every head, level for level (the desk's `aggregateSettingProperty`
 * refuses a group setting otherwise): the row writes one option's level to every head, and on a
 * model whose list differs that level is another option.
 *
 * [optionsFor] is `buildSheetRows`' options per head — a head's fixture-level dimmer for its swatch.
 */
export function rowsOverHeads(
  heads: readonly PickHead[],
  optionsFor: (head: PickHead) => Parameters<typeof buildSheetRows>[1] = () => ({}),
): PickRowGroup[] {
  if (heads.length === 0) return []
  const perHead = heads.map((h) => buildSheetRows(h.properties, optionsFor(h)))
  const byId = perHead.map((groups) => new Map(groups.flatMap((g: SheetRowGroup) => g.rows).map((r) => [r.id, r])))
  return perHead[0]
    .map((group) => ({
      family: group.family,
      rows: group.rows.flatMap((row): PickRow[] => {
        const matching = byId.map((m) => m.get(row.id))
        if (matching.some((r) => r == null || r.kind !== row.kind || !sameOptions(row, r))) return []
        return [{ row, heads: heads.map((h, i) => ({ key: h.key, name: h.name, row: matching[i]! })) }]
      }),
    }))
    .filter((g) => g.rows.length > 0)
}

/** A setting row's options, name and level in order, as every other head's must be; true for any other kind. */
function sameOptions(a: SheetRow, b: SheetRow | undefined): boolean {
  if (a.kind !== 'setting' || b?.kind !== 'setting') return true
  const x = a.property.options
  const y = b.property.options
  return x.length === y.length && x.every((o, i) => o.name === y[i].name && o.level === y[i].level)
}

/**
 * Where a row over a pick writes (§3.3):
 *
 * - **`heads`** — each head's own key: a fixture's heads (its *All* included — the desk has no
 *   all-heads property to write), or a subset of a group's members, each carrying the group as
 *   `sourceGroup` so Record can still tell it came through the group;
 * - **`group`** — a group's *All*: **one** group entry, which the desk fans to the members and
 *   Record turns back into a group row.
 *
 * Never a raw channel (the group visualisers' sliders sent one per member; D13, note 3).
 */
export type PickWrite =
  | { kind: 'heads'; keys: string[]; sourceGroup?: string }
  | { kind: 'group'; group: string; keys: string[] }

/** What a pick writes, on a fixture's heads or a group's members. */
export function pickWrite(heads: readonly PickHead[], pick: HeadPick, group?: string): PickWrite {
  const keys = pickedHeads(heads, pick).map((h) => h.key)
  if (group != null && pick == null) return { kind: 'group', group, keys }
  return { kind: 'heads', keys, sourceGroup: group }
}

/** The strip's count — `4 of 12`, `All 6`. */
export function pickCountLabel(heads: readonly PickHead[], pick: HeadPick): string {
  return pick == null ? `All ${heads.length}` : `${pick.size} of ${heads.length}`
}

/** A head's fixture-level dimmer, for its swatch when it has none of its own — `buildSheetRows`' option. */
export function headRowOptions(fixtureDimmer: SliderPropertyDescriptor | undefined) {
  return () => ({ fallbackDimmer: fixtureDimmer })
}

/**
 * The element filter that names exactly a pick of a fixture's heads, if one does — the desk's
 * `ElementFilter.includes`, clause for clause (odd and even are 1-based, the first half takes the
 * middle head of an odd count). Null when the pick is *All* or no filter is exactly it.
 */
export function elementFilterFor(heads: readonly PickHead[], pick: HeadPick): 'ODD' | 'EVEN' | 'FIRST_HALF' | 'SECOND_HALF' | null {
  if (pick == null) return null
  const total = heads.length
  const half = Math.floor((total + 1) / 2)
  const filters = {
    ODD: (i: number) => i % 2 === 0,
    EVEN: (i: number) => i % 2 === 1,
    FIRST_HALF: (i: number) => i < half,
    SECOND_HALF: (i: number) => i >= half,
  } as const
  for (const [name, includes] of Object.entries(filters) as [keyof typeof filters, (i: number) => boolean][]) {
    if (heads.every((h, i) => includes(i) === pick.has(h.key))) return name
  }
  return null
}

/**
 * A pip on a group sheet: a member fixture, or — a group may hold single heads — one head of a
 * multi-head fixture, found by its key among the fixtures' own elements (never parsed out of it).
 */
export interface GroupSheetMember extends PickHead {
  /** The fixture it is, or the fixture it is a head of. */
  fixture: Fixture
  /** Its place among that fixture's heads, when it is one — its pip's colour segment. */
  elementIndex: number | null
}

/** A group's members, in the group's order, as the sheet's pips; a key the rig no longer has is left out. */
export function groupSheetMembers(
  members: readonly { fixtureKey: string; fixtureName: string }[],
  fixtures: readonly Fixture[],
): GroupSheetMember[] {
  const byKey = new Map(fixtures.map((f) => [f.key, f]))
  const elements = new Map<string, { fixture: Fixture; index: number; element: ElementDescriptor }>()
  for (const f of fixtures) (f.elements ?? []).forEach((element, index) => elements.set(element.key, { fixture: f, index, element }))
  return members.flatMap((m): GroupSheetMember[] => {
    const fixture = byKey.get(m.fixtureKey)
    if (fixture != null) return [{ key: fixture.key, name: fixture.name, properties: fixture.properties, fixture, elementIndex: null }]
    const head = elements.get(m.fixtureKey)
    if (head == null) return []
    return [
      {
        key: head.element.key,
        name: m.fixtureName || `${head.fixture.name} · ${head.element.displayName}`,
        properties: head.element.properties,
        fixture: head.fixture,
        elementIndex: head.index,
      },
    ]
  })
}

/**
 * One head as a fixture, for the FX picker to start an effect on it alone: its key and its own
 * properties under its fixture's name. The desk resolves an effect's key through
 * `untypedGroupableFixture`, which takes a head's key as readily as a fixture's.
 */
export function headAsFixture(fixture: Fixture, head: PickHead): Fixture {
  return {
    ...fixture,
    key: head.key,
    name: `${fixture.name} · ${head.name}`,
    properties: [...head.properties],
    elements: undefined,
    elementGroupProperties: undefined,
  }
}
