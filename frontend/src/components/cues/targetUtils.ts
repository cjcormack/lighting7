import type { Cue, CueTarget } from '@/api/cuesApi'
import type { Fixture } from '@/store/fixtures'
import { targetKey } from '@/lib/targetKey'

/**
 * Order: insertion order across the three lists, deduplicated by `type:key`.
 */
export function collectCueTargets(cue: Cue): CueTarget[] {
  const seen = new Set<string>()
  const out: CueTarget[] = []

  const push = (t: CueTarget) => {
    const id = targetKey(t)
    if (seen.has(id)) return
    seen.add(id)
    out.push({ type: t.type, key: t.key })
  }

  for (const a of cue.propertyAssignments) push({ type: a.targetType, key: a.targetKey })
  for (const e of cue.adHocEffects) push({ type: e.targetType, key: e.targetKey })
  // A layer with no targets of its own contributes none here: it uses the Look's targets, which
  // this client cannot expand without fetching every Look's rows.
  for (const layer of cue.layers) {
    for (const t of layer.targets) push(t)
  }

  return out
}

/**
 * Each head's element key → the key of the fixture it belongs to.
 *
 * A cue can name one head of a multi-head fixture: Record writes a head the programmer holds as a
 * **cell-target** row (`{type: 'fixture', key: 'bar.pixel-2'}`), the same spelling a cell has as a
 * press target. That key is no patched fixture's key, so a surface that draws whole fixtures — the
 * cue's Values grid, its MiniStage — has to map it to its parent or it draws nothing. Read from the
 * fixture list's own element descriptors; element keys are opaque here, never parsed.
 */
export function elementParents(
  fixtures: readonly Pick<Fixture, 'key' | 'elements'>[] | undefined,
): Map<string, string> {
  const parents = new Map<string, string>()
  for (const fixture of fixtures ?? []) {
    for (const element of fixture.elements ?? []) parents.set(element.key, fixture.key)
  }
  return parents
}
