import type { CueTarget } from '@/api/cuesApi'
import { isLocalEffect, membersByGroupOf } from '@/components/fixtures-list/cellEffects'
import { effectAimKeys } from '@/lib/effectTargets'
import { headName } from '@/lib/headName'
import { describeHeads } from '@/lib/replacementNotes'
import type { ActiveEffect } from '@/store/fixtureFx'
import type { Fixture } from '@/store/fixtures'
import { carriesEffectProperty, elementFilterIncludes } from './fxEditorModel'

/**
 * Which of a running effect's heads an edit is for (apply-model plan §3.4, D5 — MagicQ's rule): with
 * every head it paints selected an edit changes it in place; with some of them, those split off.
 *
 * **A reading for the editor's words, never the rule.** The desk decides the split from the targets
 * the edit names (`PUT /fx/{id}`'s `planSplit`); this only says whether the editor should offer one
 * — *Hex 1–2 of 1–4* — and which heads to name when it is asked to. A head the client reads
 * differently is the desk's to answer, in its own words.
 */
export interface SplitScope {
  /** Every head the effect paints, in its order. */
  heads: string[]
  /** The heads the selection covers — a strict subset, or the edit is in place and this is null. */
  covered: string[] | null
  /** *Hex 1–2* and *Hex 1–4*, for the editor's line. */
  coveredLabel: string
  totalLabel: string
}

/**
 * The heads an effect paints, in its order — the desk's `FxEngine.paintedHeads` walked rather than
 * counted. Each unit the effect is aimed at ([effectAimKeys]) is itself where it carries the effect's
 * property ([carriesEffectProperty], the desk's traits), else its heads that do; a unit with neither
 * paints nothing and is left out. A group takes its first member's branch, as on the desk: members
 * that carry the property themselves, the rest dropped, or every member's heads. The head filter
 * narrows the heads — over the whole strip under `FLAT`, within each fixture otherwise.
 */
export function effectPaintedHeads(
  effect: Pick<
    ActiveEffect,
    'targetKey' | 'isGroupTarget' | 'targetType' | 'targets' | 'propertyName' | 'elementFilter' | 'elementMode'
  >,
  fixtures: readonly Fixture[],
): string[] {
  const property = effect.propertyName
  const byKey = new Map(fixtures.map((f) => [f.key, f] as const))
  const members = membersByGroupOf(fixtures)
  const filter = effect.elementFilter ?? 'ALL'
  const narrow = (keys: readonly string[]) => keys.filter((_, i) => elementFilterIncludes(filter, i, keys.length))
  const cellsOf = (fixture: Fixture) =>
    (fixture.elements ?? []).filter((e) => carriesEffectProperty(e.properties, property)).map((e) => e.key)
  const carries = (fixture: Fixture) => carriesEffectProperty(fixture.properties, property)

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
  for (const key of effectAimKeys(effect, (group) => members.get(group) ?? [])) {
    const fixture = byKey.get(key)
    if (fixture == null || carries(fixture)) {
      // A cell's key, or a unit that carries the property itself: one head.
      if (fixture != null || isCellCarrying(fixtures, key, property)) runs.push({ keys: [key], cells: false })
      continue
    }
    const cells = cellsOf(fixture).filter((cell) => !seen.has(cell))
    for (const cell of cells) seen.add(cell)
    if (cells.length > 0) runs.push({ keys: cells, cells: true })
  }
  if (effect.elementMode === 'FLAT' && runs.some((r) => r.cells)) return narrow(runs.flatMap((r) => r.keys))
  return runs.flatMap((r) => (r.cells ? narrow(r.keys) : r.keys))
}

/** Whether [key] is a cell that carries [property] — an unknown key paints nothing. */
function isCellCarrying(fixtures: readonly Fixture[], key: string, property: string): boolean {
  for (const fixture of fixtures) {
    const cell = fixture.elements?.find((e) => e.key === key)
    if (cell != null) return carriesEffectProperty(cell.properties, property)
  }
  return false
}

/**
 * Whether [selection] covers [head] as a press would: the head itself, its fixture, or a group its
 * fixture is in.
 */
function covers(selection: readonly CueTarget[], head: string, fixtures: readonly Fixture[]): boolean {
  const fixture = fixtures.find((f) => f.key === head) ?? fixtures.find((f) => (f.elements ?? []).some((e) => e.key === head))
  return selection.some((target) =>
    target.type === 'group'
      ? (fixture?.groups.includes(target.key) ?? false)
      : target.key === head || target.key === fixture?.key,
  )
}

/**
 * The split an edit of [effect] would make with [selection] the heads it is for. In place — no
 * `covered` — when the effect cannot split (only a Local effect does: a palette layer's is edited
 * as its layer's, apply-model plan P5, and a cue's or a manual one's is refused by the desk), when
 * the selection covers all of its heads, or none of them.
 */
export function splitScopeOf(
  effect: ActiveEffect,
  selection: readonly CueTarget[] | undefined,
  fixtures: readonly Fixture[],
): SplitScope {
  const heads = effectPaintedHeads(effect, fixtures)
  const name = (key: string) => headName(fixtures, key)
  const totalLabel = describeHeads(heads.map(name))
  const inPlace: SplitScope = { heads, covered: null, coveredLabel: totalLabel, totalLabel }
  if (selection == null || selection.length === 0 || !isLocalEffect(effect)) return inPlace
  const covered = heads.filter((head) => covers(selection, head, fixtures))
  if (covered.length === 0 || covered.length === heads.length) return inPlace
  return { heads, covered, coveredLabel: describeHeads(covered.map(name)), totalLabel }
}

/** Whether [effect] paints any head [targets] cover — the Effects band's "running on these heads". */
export function effectReaches(
  effect: ActiveEffect,
  targets: readonly CueTarget[],
  fixtures: readonly Fixture[],
): boolean {
  return effectPaintedHeads(effect, fixtures).some((head) => covers(targets, head, fixtures))
}
