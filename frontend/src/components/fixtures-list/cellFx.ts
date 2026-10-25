import { useRef, useSyncExternalStore } from 'react'
import type { ActiveFx } from '../../store/fixtureFx'
import type { Fixture } from '../../store/fixtures'
import { fxAimKeys } from '@/lib/fxTargets'
import { carriesFxAttribute, elementFilterIncludes } from '../fx/fxEditorModel'
import type { Row } from './rowModel'
import { createValueStore, type ValueStore } from './valueStore'

/**
 * The effect half of the marquee's Backspace: which running effects a cell clear takes with it.
 *
 * "Clear these cells" has always meant *take them out of Local*, and an effect busked onto those
 * same cells is Local in every sense that matters — it is the operator's own, Record writes it onto
 * the cue beside the values, and the programmer's whole-desk **Clear** already sweeps the two
 * together (`clearProgrammerCompletely` in lighting7: "programmer values **and** programmer FX").
 * So this is that rule narrowed to a rectangle rather than a new gesture: three cells under an
 * effect template's press are three instances, otherwise stopped one at a time on the FX table.
 *
 * Two rules decide what may go, and each keeps the key from reaching further than the selection:
 *
 *  - **Local means unowned.** An effect that came out of a Look, a template layer or a cue is that
 *    thing's, not the operator's: stopping it here would either be undone by the next recook or
 *    quietly edit a library record. [isLocalFx] is `programmerOwned` *and* no source at all,
 *    and not `leaving` — an effect a fade is taking off is nobody's.
 *  - **Every head, or none.** An effect drives whatever its target names — for a group target,
 *    every member — and there is no "stop it on these heads only". So it goes only when the clear
 *    covers all of them; covering some leaves it running and says so, because an effect that half
 *    vanished from the rig would be worse than one that did not move.
 */
export function isLocalFx(effect: ActiveFx): boolean {
  return (
    effect.programmerOwned &&
    effect.lookId == null &&
    effect.paletteId == null &&
    effect.programmerLayerId == null &&
    effect.cueId == null &&
    !effect.leaving
  )
}

/**
 * The key a cell and an effect are matched on: one head, one property — the marquee's effect clear
 * and the grid's FX lens both. An element-targeted effect keys on the element key, which *is* the
 * row model's name for that target, so no extra mapping is needed on either side.
 */
export function cellFxKey(fixtureKey: string, attribute: string): string {
  return `${fixtureKey}|${attribute}`
}

/**
 * The heads of each group, for expanding a group-targeted effect onto them — the effect clear's,
 * the lens's and the split edit's one reading of membership. `rowModel`'s own map is a different
 * thing — `Map<string, Fixture[]>`, built for rendering rows — and is deliberately not folded in.
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

export interface FxSweep {
  /** Fully covered by the clear — stop these. */
  stop: ActiveFx[]
  /** Overlaps the clear but also drives heads outside it — left running. */
  partial: ActiveFx[]
}

/**
 * Split the running effects against the cells a clear is about to take out of Local.
 *
 * [cleared] holds [cellFxKey]s — including element targets, whose key *is* the row model's name
 * for them, so no extra mapping is needed. [membersOf] expands a group target the same way; a
 * group nothing is patched into contributes no heads and is never swept, since "all of nothing"
 * would otherwise be vacuously true.
 */
export function fxToStop(
  effects: readonly ActiveFx[],
  membersOf: (groupName: string) => readonly string[],
  cleared: ReadonlySet<string>,
): FxSweep {
  const stop: ActiveFx[] = []
  const partial: ActiveFx[] = []
  if (cleared.size === 0) return { stop, partial }
  for (const effect of effects) {
    if (!isLocalFx(effect)) continue
    const heads = fxAimKeys(effect, membersOf)
    if (heads.length === 0) continue
    let covered = 0
    for (const head of heads) {
      if (cleared.has(cellFxKey(head, effect.attribute))) covered += 1
    }
    if (covered === 0) continue
    if (covered === heads.length) stop.push(effect)
    else partial.push(effect)
  }
  return { stop, partial }
}

/** What to tell the operator about the effects a clear could not take. */
export function partialSweepMessage(partial: readonly unknown[], outside?: string): string {
  const one = partial.length === 1
  const head = `${partial.length} FX left running — ${one ? 'it drives' : 'they drive'} heads outside`
  // The fixture sheet's Release names its own scope (`this fixture`); a group effect is stopped
  // from the group, so the marquee's "select every head" advice does not fit there.
  if (outside != null) return `${head} ${outside}. Stop ${one ? 'it' : 'them'} from the group, or in the FX list.`
  return `${head} the selection. Select every head ${one ? 'it covers' : 'they cover'}, or stop ${one ? 'it' : 'them'} in the FX list.`
}

/**
 * **The FX lens's index** (tables-colours plan D3): every running effect by the `(head, property)`
 * keys it paints ([fxPaintedHeads], the head filter and element mode applied) with the desk's
 * order kept, so a cell lists its effects as the FX table does.
 *
 * Built once per effect list over the whole rig: which heads a group's effect paints depends on
 * every member, those a filter hides included.
 */
export interface CellFxIndex {
  byKey: ReadonlyMap<string, readonly ActiveFx[]>
  /** Each effect's place in the desk's list. */
  order: ReadonlyMap<number, number>
}

export const EMPTY_CELL_FX_INDEX: CellFxIndex = { byKey: new Map(), order: new Map() }

/** Every fixture the rows hold — a fixture or element row's own, a group row's members — once each; the index's fallback while the rig list loads. */
export function fixturesOfRows(rows: readonly Row[]): Fixture[] {
  const byKey = new Map<string, Fixture>()
  for (const row of rows) {
    if (row.kind === 'group') for (const member of row.members) byKey.set(member.key, member)
    else if (row.kind !== 'divider') byKey.set(row.fixture.key, row.fixture)
  }
  return [...byKey.values()]
}

export function buildCellFxIndex(effects: readonly ActiveFx[] | undefined, fixtures: readonly Fixture[]): CellFxIndex {
  if (effects == null || effects.length === 0) return EMPTY_CELL_FX_INDEX
  const byKey = new Map<string, ActiveFx[]>()
  const order = new Map<number, number>()
  effects.forEach((effect, at) => {
    order.set(effect.id, at)
    for (const head of fxPaintedHeads(effect, fixtures)) {
      const key = cellFxKey(head, effect.attribute)
      const list = byKey.get(key)
      if (list == null) byKey.set(key, [effect])
      else if (!list.includes(effect)) list.push(effect)
    }
  })
  return { byKey, order }
}

/**
 * The heads an effect paints, in its order — the desk's `FxEngine.paintedHeads` walked rather than
 * counted. Each unit the effect is aimed at ([fxAimKeys]) is itself where it carries the effect's
 * property ([carriesFxAttribute], the desk's traits), else its heads that do; a unit with neither
 * paints nothing and is left out. A group takes its first member's branch, as on the desk: members
 * that carry the property themselves, the rest dropped, or every member's heads. The head filter
 * narrows the heads — over the whole strip under `FLAT`, within each fixture otherwise.
 */
export function fxPaintedHeads(
  effect: Pick<
    ActiveFx,
    'targetKey' | 'isGroupTarget' | 'targetType' | 'targets' | 'attribute' | 'elementFilter' | 'elementMode'
  >,
  fixtures: readonly Fixture[],
): string[] {
  const attribute = effect.attribute
  const byKey = new Map(fixtures.map((f) => [f.key, f] as const))
  const members = membersByGroupOf(fixtures)
  const filter = effect.elementFilter ?? 'ALL'
  const narrow = (keys: readonly string[]) => keys.filter((_, i) => elementFilterIncludes(filter, i, keys.length))
  const cellsOf = (fixture: Fixture) =>
    (fixture.elements ?? []).filter((e) => carriesFxAttribute(e.attributes, attribute)).map((e) => e.key)
  const carries = (fixture: Fixture) => carriesFxAttribute(fixture.attributes, attribute)

  // A group: its first member decides, as the desk's expansion does.
  if (effect.isGroupTarget && effect.targetType !== 'selection') {
    const memberFixtures = (members.get(effect.targetKey) ?? []).flatMap((key) => byKey.get(key) ?? [])
    if (memberFixtures.length === 0) return []
    if (carries(memberFixtures[0])) return memberFixtures.filter(carries).map((f) => f.key)
    const runs = memberFixtures.map(cellsOf)
    return effect.elementMode === 'FLAT' ? narrow(runs.flat()) : runs.flatMap(narrow)
  }

  // One fixture or cell, or a selection's units in order, each once.
  const runs: { keys: string[]; cells: boolean }[] = []
  const seen = new Set<string>()
  for (const key of fxAimKeys(effect, (group) => members.get(group) ?? [])) {
    const fixture = byKey.get(key)
    if (fixture == null || carries(fixture)) {
      // A cell's key, or a unit that carries the property itself: one head.
      if (fixture != null || isCellCarrying(fixtures, key, attribute)) runs.push({ keys: [key], cells: false })
      continue
    }
    const cells = cellsOf(fixture).filter((cell) => !seen.has(cell))
    for (const cell of cells) seen.add(cell)
    if (cells.length > 0) runs.push({ keys: cells, cells: true })
  }
  if (effect.elementMode === 'FLAT' && runs.some((r) => r.cells)) return narrow(runs.flatMap((r) => r.keys))
  return runs.flatMap((r) => (r.cells ? narrow(r.keys) : r.keys))
}

/** Whether [key] is a cell that carries [attribute] — an unknown key paints nothing. */
function isCellCarrying(fixtures: readonly Fixture[], key: string, attribute: string): boolean {
  for (const fixture of fixtures) {
    const cell = fixture.elements?.find((e) => e.key === key)
    if (cell != null) return carriesFxAttribute(cell.attributes, attribute)
  }
  return false
}

/** The effects painting any of a cell's keys, once each, in the desk's order. */
export function fxForCell(
  index: CellFxIndex,
  keys: readonly { targetKey: string; attribute: string }[],
): readonly ActiveFx[] {
  let found: ActiveFx[] | null = null
  for (const key of keys) {
    const list = index.byKey.get(cellFxKey(key.targetKey, key.attribute))
    if (list == null) continue
    if (found == null) found = [...list]
    else for (const effect of list) if (!found.includes(effect)) found.push(effect)
  }
  if (found == null) return NO_FX
  if (found.length > 1) found.sort((a, b) => (index.order.get(a.id) ?? 0) - (index.order.get(b.id) ?? 0))
  return found
}

const NO_FX: readonly ActiveFx[] = []

/**
 * The index as a **store**, which the grid's host fills and each cell reads through
 * [useCellFx] — the `marqueeContext.ts` pattern. A context value would re-render every row of
 * the grid per `fxChanged`; a cell's selector re-renders only the cells whose effects moved.
 */
export type CellFxStore = ValueStore<CellFxIndex>

export function createCellFxStore(): CellFxStore {
  return createValueStore(EMPTY_CELL_FX_INDEX)
}

/**
 * One cell's effects from [store], the same array while nothing the lens draws of them has changed
 * — an instance's phase moves on every refetch, so it is compared by [sameLensFields], not by
 * object. A reader matching an effect against the index goes by id. [keys] must be stable — the
 * row's memoised `RowCell.keys`.
 */
export function useCellFx(
  store: CellFxStore,
  keys: readonly { targetKey: string; attribute: string }[],
): readonly ActiveFx[] {
  const cache = useRef<{ index: CellFxIndex; keys: typeof keys; value: readonly ActiveFx[] } | null>(null)
  const read = () => {
    const index = store.get()
    const last = cache.current
    if (last != null && last.index === index && last.keys === keys) return last.value
    const next = fxForCell(index, keys)
    const value = last != null && sameFx(last.value, next) ? last.value : next
    cache.current = { index, keys, value }
    return value
  }
  return useSyncExternalStore(store.subscribe, read, read)
}

function sameFx(a: readonly ActiveFx[], b: readonly ActiveFx[]): boolean {
  return a.length === b.length && a.every((effect, i) => sameLensFields(effect, b[i]))
}

/** The fields the lens's chip reads — its words, its division and why it is held back. */
function sameLensFields(a: ActiveFx, b: ActiveFx): boolean {
  return (
    a === b ||
    (a.id === b.id &&
      a.fxType === b.fxType &&
      a.attribute === b.attribute &&
      a.cycle === b.cycle &&
      a.timingSource === b.timingSource &&
      a.seconds === b.seconds &&
      a.isRunning === b.isRunning &&
      a.programmerOwned === b.programmerOwned &&
      a.spent === b.spent &&
      a.leaving === b.leaving &&
      a.amount === b.amount &&
      a.intensityMultiplier > 0 === b.intensityMultiplier > 0 &&
      a.cueStackId === b.cueStackId)
  )
}
