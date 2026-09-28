import type { CueTarget } from '@/api/cuesApi'
import type { ProgrammerAppliedSource } from '@/store/programmer'
import type { EffectPresence } from './buskingTypes'

/**
 * How much of the selection one library record covers, from the desk's **resolved** applied state.
 *
 * The desk answers the hard half: `programmer.layerState` carries, beside the layer list, one entry
 * per Look or template naming every target it covers — each covered fixture, and each group marked
 * `all` or `some` by how many of its heads the record holds (`ProgrammerLayerStack.appliedState`).
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
  kind: ProgrammerAppliedSource['source']['kind'],
  sourceId: number,
): EffectPresence {
  if (targets.length === 0) return 'none'

  const record = applied.find(
    (entry) => entry.source.kind === kind && entry.source.id === sourceId,
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
 * Whether a Look is on the rig for a pad's selected targets.
 *
 * This replaced a match on `FxInstance.presetId`, which was wrong in two ways at once. The toggle
 * route used to stamp the *Look* id into a field naming a `DaoFxPreset`, so the ring worked by
 * accident; nothing stamps it any more. And it could only ever see a Look that spawned effects — a
 * Look made of static rows lit no ring at all, however plainly it was on stage. A layer is the
 * honest source: it exists whether or not the Look contains an effect, which is why the desk
 * resolves its applied state from the layer stack.
 *
 * Matched on the source being a **Look** with this id, not on the id alone: a template layer can
 * carry the same int PK from the other table, and a pad would then light for someone else's row.
 */
export function lookLayerPresence(
  applied: readonly ProgrammerAppliedSource[],
  targets: readonly CueTarget[],
  lookId: number,
): EffectPresence {
  return appliedPresence(applied, targets, 'LOOK', lookId)
}

/**
 * The same question for a **template** pad: is this template applied to the selection?
 *
 * A separate function rather than a parameter on [lookLayerPresence], because the two match on
 * different things: a Look is found by `source.id` *within the LOOK arm*, a template within the
 * TEMPLATE arm, and the two id spaces are different tables that can collide. Folding them into one
 * would need a kind argument at every call site to avoid exactly that.
 *
 * This is the *only* way a template's pad can light, and since fx-templates that rule is **more**
 * load-bearing rather than less. It used to hold because a template contained no effects at all, so
 * the running-effect presence a Look's ring is read from had nothing to say about one. A template
 * can hold one effect now — and matching on the running instance would therefore light for an
 * effect template while leaving every value template's pad dark, which is the worst of both
 * answers. The layer is what a press adds and what a second press removes, whatever the template
 * holds.
 */
export function templateLayerPresence(
  applied: readonly ProgrammerAppliedSource[],
  targets: readonly CueTarget[],
  templateId: number,
): EffectPresence {
  return appliedPresence(applied, targets, 'TEMPLATE', templateId)
}

/**
 * Is this Look on the rig **at all** — the only presence question a surface with no selection can
 * ask.
 *
 * [lookLayerPresence] folds coverage over a *selection*, and answers `'none'` for an empty target
 * list by design: a ring asking "is this record on for what I have selected?" has no answer when
 * nothing is selected. An FX cue slot has no selection at all — a Look eligible for one is bound to
 * its own fixtures, and pressing the tile derives the targets from them — so reading its tile
 * through the selection-scoped fold would leave every Look tile dark.
 *
 * Matched on the source being a **Look** with this id, for [lookLayerPresence]'s reason: a template
 * layer can carry the same int PK from the other table.
 */
export function lookIsApplied(
  applied: readonly ProgrammerAppliedSource[],
  lookId: number,
): boolean {
  return applied.some(
    (entry) =>
      entry.source.kind === 'LOOK' && entry.source.id === lookId && entry.targets.length > 0,
  )
}
