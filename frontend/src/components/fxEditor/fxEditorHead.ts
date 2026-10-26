import type { CueTarget } from '@/api/cuesApi'
import type { ActiveFx } from '@/store/fixtureFx'
import type { Fixture } from '@/store/fixtures'
import { FAMILY_LABELS, PALETTE_FAMILY_ORDER, type AttributeFamily } from '@/lib/attributeFamily'
import { fxTargetLabel, selectionFixtures } from '@/lib/fxTargets'
import { headName } from '@/lib/headName'
import { paletteAttributeFor } from '@/lib/paletteIntent'
import { LANE_ATTRIBUTE, familyOfFx, fxTargetsOf } from './fxEditorList'

/**
 * The list's head (FX editor follow-ups plan D1, D2): what the subject says the selection is, and
 * the running FX offered as cards while nothing is selected. Pure, so the words are tested apart from
 * the column that draws them.
 */

/** A fixture the selection reaches, with the elements of it selected and the targets that name it. */
export interface SubjectFixture {
  fixture: Fixture
  /** Its selected elements: every one when the fixture is selected whole, 0 for one with none. */
  elements: number
  /** What *Only* selects: the fixture whole, or the elements of it the selection names. */
  targets: CueTarget[]
}

/**
 * The fixtures [targets] reach, in the order the selection first reaches each: a group by its
 * members, a fixture whole, an element by its fixture. A fixture reached whole and by its elements
 * counts whole.
 */
export function subjectFixtures(targets: readonly CueTarget[], fixtures: readonly Fixture[]): SubjectFixture[] {
  const byKey = new Map<string, { fixture: Fixture; whole: boolean; elements: Set<string> }>()
  const reach = (fixture: Fixture, element: string | null) => {
    let entry = byKey.get(fixture.key)
    if (entry == null) {
      entry = { fixture, whole: false, elements: new Set() }
      byKey.set(fixture.key, entry)
    }
    if (element == null) entry.whole = true
    else entry.elements.add(element)
  }
  for (const target of targets) {
    if (target.type === 'group') {
      for (const fixture of fixtures) if (fixture.groups.includes(target.key)) reach(fixture, null)
      continue
    }
    const fixture = fixtures.find((f) => f.key === target.key)
    if (fixture != null) {
      reach(fixture, null)
      continue
    }
    const parent = fixtures.find((f) => (f.elements ?? []).some((e) => e.key === target.key))
    if (parent != null) reach(parent, target.key)
  }
  return [...byKey.values()].map(({ fixture, whole, elements }) =>
    whole
      ? { fixture, elements: fixture.elements?.length ?? 0, targets: [{ type: 'fixture', key: fixture.key }] }
      : { fixture, elements: elements.size, targets: [...elements].map((key) => ({ type: 'fixture' as const, key })) },
  )
}

const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`

/** *12 elements*, *1 element*. */
function elementsWord(n: number): string {
  return plural(n, 'element', 'elements')
}

/** *4 fixtures · 12 elements*, or *4 fixtures* where none of them has an element selected. */
function fixturesWord(list: readonly SubjectFixture[]): string {
  const elements = list.reduce((n, f) => n + f.elements, 0)
  const fixtures = plural(list.length, 'fixture', 'fixtures')
  return elements > 0 ? `${fixtures} · ${elementsWord(elements)}` : fixtures
}

/**
 * What the subject reads (D2): a group its name and how many fixtures; one fixture its name and
 * its elements (or *1 head*); several fixtures a count that opens their list.
 */
type SubjectForm =
  | { kind: 'named'; name: string; count: string }
  | { kind: 'several'; label: string; fixtures: SubjectFixture[] }

export function subjectForm(
  targets: readonly CueTarget[],
  fixtures: readonly Fixture[],
  name: string,
): SubjectForm | null {
  if (targets.length === 0) return null
  const list = subjectFixtures(targets, fixtures)
  if (targets.length === 1 && targets[0].type === 'group') {
    return { kind: 'named', name, count: plural(list.length, 'fixture', 'fixtures') }
  }
  if (list.length > 1) return { kind: 'several', label: fixturesWord(list), fixtures: list }
  const one = list[0]
  if (one == null) return { kind: 'named', name, count: plural(targets.length, 'head', 'heads') }
  // Several elements of one fixture read as that fixture, the elements counted.
  const named = targets.length > 1 ? one.fixture.name : name
  return { kind: 'named', name: named, count: one.elements > 0 ? elementsWord(one.elements) : '1 head' }
}

/** A running FX as a card while nothing is selected (D1). */
interface RunningCard {
  effect: ActiveFx
  family: AttributeFamily
  /** Its family, or its attribute where that is not the family's own — *UV*, *Zoom* — as a lane card names it. */
  drives: string
  /** *LX1–LX2 Shehds · 4 fixtures*: what it runs on, and how many fixtures that is. */
  on: string
}

/**
 * The running FX as cards (D1), by family in the lanes' order then by name: every FX but one the
 * desk is letting go of on a fade.
 */
export function runningCards(
  effects: readonly ActiveFx[],
  fixtures: readonly Fixture[],
  name: (effect: ActiveFx) => string,
): RunningCard[] {
  const nameOf = (target: CueTarget) => (target.type === 'group' ? target.key : headName(fixtures, target.key))
  return effects
    .filter((effect) => effect.leaving !== true)
    .map((effect) => {
      const count = selectionFixtures(fxTargetsOf(effect), fixtures).length
      const family = familyOfFx(effect, fixtures)
      return {
        effect,
        family,
        drives:
          LANE_ATTRIBUTE[family] === effect.attribute
            ? FAMILY_LABELS[family].singular
            : (paletteAttributeFor(effect.attribute)?.label ?? effect.attribute),
        on: `${fxTargetLabel(effect, nameOf)} · ${plural(count, 'fixture', 'fixtures')}`,
      }
    })
    .sort(
      (a, b) =>
        PALETTE_FAMILY_ORDER.indexOf(a.family) - PALETTE_FAMILY_ORDER.indexOf(b.family) ||
        name(a.effect).localeCompare(name(b.effect)) ||
        a.effect.id - b.effect.id,
    )
}
