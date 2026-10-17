import { lightingApi } from '@/api/lightingApi'
import { resolveCell } from '@/components/fixtures-list/columns'
import type { PickWrite } from '@/components/fixtureSheet/sheetPick'
import type { PositionResolution } from '@/components/fixtureSheet/sheetRows'
import { serializeLevel } from '@/lib/programmerValue'
import { templatePropertiesForFamily } from '@/lib/templateIntent'
import type { Fixture, PropertyDescriptor, SliderPropertyDescriptor } from '@/store/fixtures'
import type { BuskingTarget } from './buskingTypes'

/**
 * The busk selection as the Level, Position and Beam tabs write it: one **unit** per selected
 * target, each a `PickWrite` (the fixture sheet's write shape over a pick of heads) and the heads it
 * lands on with what each resolves to.
 *
 * - A **group** is one group entry where every member carries the property itself, which the desk
 *   fans to the members and Record turns back into a group row; otherwise each member's own entry —
 *   or its cells', for a bar whose property lives on its cells — carrying the group.
 * - A **fixture** is its own head where it carries the property, else its cells that do.
 * - A **cell** is itself.
 *
 * A head reached twice — a fixture selected beside its group — is written by the first unit that
 * reaches it. A target that resolves nothing is left out, so a selection with no dimmer anywhere
 * has no units.
 */
export interface FamilyUnit<R> {
  write: PickWrite
  heads: { key: string; resolution: R }[]
}

export function familyUnits<R>(
  selected: readonly BuskingTarget[],
  fixtures: readonly Fixture[],
  resolve: (properties: PropertyDescriptor[]) => R | null,
): FamilyUnit<R>[] {
  const own = (fixture: Fixture) => {
    const resolution = resolve(fixture.properties)
    return resolution == null ? null : { key: fixture.key, resolution }
  }
  const cells = (fixture: Fixture) =>
    (fixture.elements ?? []).flatMap((element) => {
      const resolution = resolve(element.properties)
      return resolution == null ? [] : [{ key: element.key, resolution }]
    })
  const headsOf = (fixture: Fixture) => {
    const head = own(fixture)
    return head != null ? [head] : cells(fixture)
  }

  const units: FamilyUnit<R>[] = []
  const seen = new Set<string>()
  const push = (write: (keys: string[]) => PickWrite, heads: { key: string; resolution: R }[]) => {
    const fresh = heads.filter((h) => !seen.has(h.key))
    if (fresh.length === 0) return
    for (const h of fresh) seen.add(h.key)
    units.push({ write: write(fresh.map((h) => h.key)), heads: fresh })
  }
  for (const target of selected) {
    if (target.type === 'group') {
      const members = fixtures.filter((fixture) => fixture.groups.includes(target.name))
      const owned = members.map(own)
      // One group entry only while no member has been reached already: over a member an earlier
      // unit wrote, the group entry would be a second write to it.
      if (members.length > 0 && owned.every((head) => head != null && !seen.has(head.key))) {
        const heads = owned as { key: string; resolution: R }[]
        for (const h of heads) seen.add(h.key)
        units.push({ write: { kind: 'group', group: target.name, keys: heads.map((h) => h.key) }, heads })
        continue
      }
      push((keys) => ({ kind: 'heads', keys, sourceGroup: target.name }), members.flatMap(headsOf))
      continue
    }
    const heads =
      target.element != null
        ? (() => {
            const resolution = resolve(target.element.properties)
            return resolution == null ? [] : [{ key: target.element.key, resolution }]
          })()
        : headsOf(target.fixture)
    push((keys) => ({ kind: 'heads', keys }), heads)
  }
  return units
}

/**
 * How many of the selected heads — a group's members, a fixture, a cell — resolve nothing, so the
 * tab can say they are left out. A fixture counts once, whether its property is its own or its cells'.
 */
export function headsWithout(
  selected: readonly BuskingTarget[],
  fixtures: readonly Fixture[],
  resolve: (properties: PropertyDescriptor[]) => unknown,
): number {
  const has = (fixture: Fixture) =>
    resolve(fixture.properties) != null || (fixture.elements ?? []).some((e) => resolve(e.properties) != null)
  const without = new Set<string>()
  for (const target of selected) {
    if (target.type === 'group') {
      for (const fixture of fixtures) if (fixture.groups.includes(target.name) && !has(fixture)) without.add(fixture.key)
    } else if (target.element != null) {
      if (resolve(target.element.properties) == null) without.add(target.element.key)
    } else if (!has(target.fixture)) {
      without.add(target.fixture.key)
    }
  }
  return without.size
}

/** A level slider of [category] — `dimmer`, `zoom`, `iris` — the property a Level or Beam tab drives. */
export function sliderOf(category: string): (properties: PropertyDescriptor[]) => SliderPropertyDescriptor | null {
  return (properties) =>
    // A fine channel shares its coarse slider's category, and is never the level.
    (properties.find((p): p is SliderPropertyDescriptor => p.type === 'slider' && p.category === category && p.fineOf == null) ??
      null)
}

/** Pan and tilt as the grid's position cell resolves them. */
export function positionOf(properties: PropertyDescriptor[]): PositionResolution | null {
  return resolveCell(properties, 'position') as PositionResolution | null
}

/**
 * The Beam properties the Beam tab offers: the percent-valued Beam properties a value palette can
 * carry (zoom, focus, iris, frost), in the palette vocabulary's order, where some selected head has
 * one. A Beam setting (gobo, prism) is a setting cell's, not a level's.
 */
export function beamPropertiesOf(selected: readonly BuskingTarget[], fixtures: readonly Fixture[]): string[] {
  return templatePropertiesForFamily('BEAM')
    .filter((p) => p.intent === 'percent')
    .map((p) => p.propertyName)
    .filter((name) => familyUnits(selected, fixtures, sliderOf(name)).length > 0)
}

/**
 * A level over the units, into Local: a group unit as one group entry, every other head as its own
 * entry under its own property's name (a cell's may differ from its fixture's), a group member's
 * carrying the group.
 */
export function writeUnitLevels(units: readonly FamilyUnit<SliderPropertyDescriptor>[], level: number, fadeMs?: number): void {
  for (const { write, heads } of units) {
    if (write.kind === 'group') {
      const head = heads[0]
      const clamped = Math.max(head.resolution.min, Math.min(head.resolution.max, Math.round(level)))
      lightingApi.programmer.set('group', write.group, head.resolution.name, serializeLevel(clamped), fadeMs)
      continue
    }
    for (const { key, resolution } of heads) {
      const clamped = Math.max(resolution.min, Math.min(resolution.max, Math.round(level)))
      lightingApi.programmer.set('fixture', key, resolution.name, serializeLevel(clamped), fadeMs, write.sourceGroup)
    }
  }
}
