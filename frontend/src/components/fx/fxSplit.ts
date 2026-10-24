import type { CueAdHocFx, CueTarget } from '@/api/cuesApi'
import { fxPaintedHeads, isLocalFx } from '@/components/fixtures-list/cellFx'
import { storedFxTargets } from '@/lib/fxTargets'
import { headName } from '@/lib/headName'
import { describeHeads } from '@/lib/replacementNotes'
import type { ActiveFx } from '@/store/fixtureFx'
import type { Fixture } from '@/store/fixtures'

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
  effect: ActiveFx,
  selection: readonly CueTarget[] | undefined,
  fixtures: readonly Fixture[],
): SplitScope {
  const heads = fxPaintedHeads(effect, fixtures)
  const name = (key: string) => headName(fixtures, key)
  const totalLabel = describeHeads(heads.map(name))
  const inPlace: SplitScope = { heads, covered: null, coveredLabel: totalLabel, totalLabel }
  if (selection == null || selection.length === 0 || !isLocalFx(effect)) return inPlace
  const covered = heads.filter((head) => covers(selection, head, fixtures))
  if (covered.length === 0 || covered.length === heads.length) return inPlace
  return { heads, covered, coveredLabel: describeHeads(covered.map(name)), totalLabel }
}

/**
 * Whether a cue's **stored** effect paints any head [targets] cover — the Effects band's list in the
 * cue editor, read as [fxReaches] reads a running one.
 */
export function storedFxReaches(
  effect: Pick<CueAdHocFx, 'targetType' | 'targetKey' | 'propertyName' | 'elementFilter' | 'elementMode'>,
  targets: readonly CueTarget[],
  fixtures: readonly Fixture[],
): boolean {
  const painted = fxPaintedHeads(
    {
      targetKey: effect.targetKey,
      isGroupTarget: effect.targetType === 'group',
      targetType: effect.targetType,
      targets: effect.targetType === 'selection' ? storedFxTargets(effect.targetType, effect.targetKey) : null,
      propertyName: effect.propertyName ?? '',
      elementFilter: effect.elementFilter,
      elementMode: effect.elementMode,
    },
    fixtures,
  )
  return painted.some((head) => covers(targets, head, fixtures))
}

/** Whether [effect] paints any head [targets] cover — the Effects band's "running on these heads". */
export function fxReaches(
  effect: ActiveFx,
  targets: readonly CueTarget[],
  fixtures: readonly Fixture[],
): boolean {
  return fxPaintedHeads(effect, fixtures).some((head) => covers(targets, head, fixtures))
}
