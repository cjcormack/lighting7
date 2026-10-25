import type { CueTarget } from '@/api/cuesApi'
import type { ProgrammerAppliedSource } from '@/store/programmer'
import type { FxPresence } from './buskingTypes'

/**
 * How much of the selection one palette covers, from the desk's **resolved** applied state.
 *
 * The desk answers the hard half: `programmer.layerState` carries, beside the layer list, one entry
 * per Look or palette, a palette's naming every target it covers — each covered fixture, and each
 * group marked `all` or `some` by how many of its heads the palette holds
 * (`ProgrammerLayerStack.appliedState`).
 * Group expansion, coverage and precedence are its rules, and it owns the layers, the groups and
 * the fixtures they are about.
 *
 * All that is left here is folding a multi-selection into one ring, which is a question about the
 * *selection* rather than about the show: every selected target `all` → full, none of them applied
 * → dark, anything else → partial. A single selected target is a lookup.
 *
 * This replaced a client-side rule that walked the layer list, matched target lists and expanded
 * groups from `GroupSummary.memberKeys`. It gave the same answers on paper and was the wrong shape:
 * two copies of one coverage rule, and the copy in the browser is the one no test against the rig
 * can reach. It also drifted immediately — the client compared `(type, key)` pairs literally where
 * the server expanded groups, so a pad on the wash read dark the moment one of its heads was
 * selected while a press on that head really did take the layer off.
 */
function appliedPresence(
  applied: readonly ProgrammerAppliedSource[],
  targets: readonly CueTarget[],
  paletteId: number,
): FxPresence {
  if (targets.length === 0) return 'none'

  const record = applied.find(
    (entry) => entry.source.kind === 'PALETTE' && entry.source.id === paletteId,
  )
  if (!record) return 'none'

  // Keyed by type as well as key: a fixture and a group may share a name, and
  // `fixture:front-wash` must never answer for `group:front-wash`.
  const extents = new Map(record.targets.map((t) => [`${t.type}:${t.key}`, t.state]))

  let full = 0
  let partial = 0
  for (const target of targets) {
    const extent = extents.get(`${target.type}:${target.key}`)
    if (extent === 'all') full++
    else if (extent === 'some') partial++
  }

  if (full === targets.length) return 'all'
  if (full === 0 && partial === 0) return 'none'
  return 'some'
}

/**
 * A **palette** pad's ring: is this palette applied to the selection? Matched within the PALETTE arm,
 * since a Look can carry the same int PK from the other table.
 *
 * This is the *only* way a palette's pad can light, and since fx-templates that rule is **more**
 * load-bearing rather than less. It used to hold because a template contained no effects at all, so
 * the running-effect presence a Look's ring is read from had nothing to say about one. A template
 * can hold one effect now — and matching on the running instance would therefore light for an
 * effect template while leaving every value template's pad dark, which is the worst of both
 * answers. The layer is what a press adds and what a second press removes, whatever the template
 * holds.
 */
export function paletteLayerPresence(
  applied: readonly ProgrammerAppliedSource[],
  targets: readonly CueTarget[],
  paletteId: number,
): FxPresence {
  return appliedPresence(applied, targets, paletteId)
}

/**
 * A **Look** pad's ring (absolute-looks plan P4): lit while a layer of it is on the programmer's
 * stack, whatever is selected. A Look lands whole, so coverage is not its question, and the desk's
 * entry for it names no targets.
 *
 * Matched on the source being a **Look** with this id, not on the id alone: a palette layer can carry
 * the same int PK from the other table, and a pad would then light for someone else's row. It
 * replaced a match on `FxInstance.presetId`, which could never see a Look of static rows at all — a
 * layer exists whether or not the Look holds an FX.
 */
export function lookPresence(applied: readonly ProgrammerAppliedSource[], lookId: number): FxPresence {
  return lookIsApplied(applied, lookId) ? 'all' : 'none'
}

/** [lookPresence] as a yes or no, for a surface that draws lit or dark — an FX cue slot's Look tile. */
export function lookIsApplied(applied: readonly ProgrammerAppliedSource[], lookId: number): boolean {
  return applied.some((entry) => entry.source.kind === 'LOOK' && entry.source.id === lookId)
}
