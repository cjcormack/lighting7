import type { Fixture, SettingAttributeDescriptor } from '@/store/fixtures'
import type { FxPreviewAnswer, FxPreviewNote, FxPreviewWheelState } from '@/store/fxPreview'
import { colourWheelOf } from '@/lib/wheelChase'

/**
 * *Lands on* (wheel-colour-chase plan §4, D8): what a colour chase does on each wheel model in the
 * selection, read from the desk's preview — its slots and splits in step order, and the notes in
 * words. The desk decides every slot and every note; this only groups and words them.
 */

/** One wheel state in step order: a slot, or the split of [slot] with [splitWith]. */
export interface LandsOnState {
  slot: string
  splitWith?: string
  /** The desk's colour for the state; a split's, the two slots' mix. */
  preview?: string
  /** A split's two slots' own colours, from the wheel's options, where both have one. */
  halves?: [string, string]
}

export interface LandsOnModel {
  model: string
  /** The wheel the states are on, for a model with more than one. */
  attribute: string
  heads: number
  states: LandsOnState[]
  notes: string[]
}

/**
 * Each wheel model (and wheel) the preview's rows hold for the answer's FX at [fxIndex], in row order: its
 * first head's states in step order, from that head's first cycle start, and every head's notes once each.
 * An RGB head has no wheel lane and adds nothing.
 */
export function landsOnFrom(answer: FxPreviewAnswer | null, fxIndex: number | null, fixtures: readonly Fixture[]): LandsOnModel[] {
  if (answer == null || fxIndex == null) return []
  const byKey = new Map(fixtures.map((f) => [f.key, f]))
  const models = new Map<string, LandsOnModel & { names: Map<string, string>; headKeys: Set<string>; seen: Set<string> }>()
  for (const lane of answer.lanes) {
    for (const attribute of lane.attributes) {
      for (const row of attribute.rows) {
        const output = row.fx.find((o) => o.fx === fxIndex)
        if (row.wheel == null || output == null) continue
        const head = answer.rows[row.row]
        const fixture = head == null ? undefined : byKey.get(head.fixtureKey)
        const modelName = fixture?.model ?? fixture?.typeKey ?? head?.label ?? '?'
        const groupKey = `${modelName}\u0000${attribute.attribute}`
        let model = models.get(groupKey)
        if (model == null) {
          const wheel = fixture != null ? wheelNamed(fixture, attribute.attribute) : undefined
          const names = new Map((wheel?.options ?? []).map((o) => [o.name, o.displayName]))
          const previews = new Map((wheel?.options ?? []).flatMap((o) => (o.colourPreview != null ? [[o.name, o.colourPreview] as const] : [])))
          model = {
            model: modelName,
            attribute: attribute.attribute,
            heads: 0,
            states: statesInOrder(row.wheel.states, row.wheel.samples, answer.fx[fxIndex]?.starts[row.row]?.[0] ?? 0, names, previews),
            notes: [],
            names,
            headKeys: new Set(),
            seen: new Set(),
          }
          models.set(groupKey, model)
        }
        model.headKeys.add(head?.key ?? String(row.row))
        model.heads = model.headKeys.size
        for (const note of output.notes ?? []) {
          const words = noteWords(note, model.names)
          if (!model.seen.has(words)) {
            model.seen.add(words)
            model.notes.push(words)
          }
        }
      }
    }
  }
  return [...models.values()].map(({ model, attribute, heads, states, notes }) => ({ model, attribute, heads, states, notes }))
}

/** The setting the preview row names, else the fixture's colour wheel. */
function wheelNamed(fixture: Fixture, attribute: string): SettingAttributeDescriptor | undefined {
  const named = fixture.attributes?.find((p): p is SettingAttributeDescriptor => p.type === 'setting' && p.name === attribute)
  return named ?? colourWheelOf(fixture)
}

function statesInOrder(
  states: FxPreviewWheelState[],
  samples: number[],
  cycleStart: number,
  names: Map<string, string>,
  previews: ReadonlyMap<string, string>,
): LandsOnState[] {
  const from = cycleStart >= 0 && cycleStart < samples.length ? cycleStart : 0
  const order: number[] = []
  for (const index of [...samples.slice(from), ...samples.slice(0, from)]) if (!order.includes(index)) order.push(index)
  return order
    .map((i) => states[i])
    .filter((s): s is FxPreviewWheelState => s != null)
    .map((s) => {
      const a = previews.get(s.slot)
      const b = s.splitWith != null ? previews.get(s.splitWith) : undefined
      return {
        slot: names.get(s.slot) ?? s.slot,
        ...(s.splitWith != null ? { splitWith: names.get(s.splitWith) ?? s.splitWith } : {}),
        ...(s.preview != null ? { preview: s.preview } : {}),
        ...(a != null && b != null ? { halves: [a, b] as [string, string] } : {}),
      }
    })
}

/** A note in the editor's words, slot names as the wheel's options call them. */
export function noteWords(note: FxPreviewNote, names: ReadonlyMap<string, string> = new Map()): string {
  const name = (n: string | null | undefined) => (n == null ? '?' : (names.get(n) ?? n))
  switch (note.kind) {
    case 'SAME_SLOT':
      return `${(note.colours ?? []).join(', ')} all land on ${name(note.slot)}`
    case 'NO_SPLIT':
      return note.reason === 'NOT_NEIGHBOURS'
        ? `${name(note.from)} and ${name(note.to)} are not neighbours, so the wheel changes at the split’s middle`
        : `This wheel does not split: ${name(note.from)} changes to ${name(note.to)} at the split’s middle`
    case 'TOO_FAST':
      return `Each step is ${note.stepMs ?? '?'} ms; the wheel needs ${note.needMs ?? '?'} ms to get there`
    default:
      return note.kind
  }
}
