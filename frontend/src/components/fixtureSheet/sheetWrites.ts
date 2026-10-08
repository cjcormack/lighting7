import { lightingApi } from '@/api/lightingApi'
import { parseProgrammerEntryValue, serializeLevel } from '@/lib/programmerValue'
import { clampCommitToResolution, type CellCommit } from '../fixtures-list/rowModel'
import type { ChannelRef, ColourPropertyDescriptor } from '@/store/fixtures'
import type { PickWrite } from './sheetPick'
import type { PositionResolution } from './sheetRows'

/**
 * A Position row's write (D8): **one `programmer.setPosition`**, never the two raw axes and never
 * `channels.update`. The commit is in the row's unit — degrees where both axes annotate travel,
 * bytes elsewhere — and is resolved to this head's own bytes through the grid's
 * `clampCommitToResolution`, so 270° lands where the programmer's position cell would land it.
 *
 * A one-axis commit (a pan typed, a tilt dragged) is a whole position on the wire, so the other axis
 * is taken from what the programmer already holds on `position`, else from the wire — the grid's
 * `writePosition` rule: an effect moving the untouched axis would otherwise bake in an instant.
 *
 * Returns false when nothing could be written (a degree on a head that does not annotate it).
 */
export function writeSheetPosition(
  headKey: string,
  resolution: PositionResolution,
  commit: Extract<CellCommit, { kind: 'position' }>,
  fadeMs?: number,
  /** The group a member's write came through (a subset of a group sheet's members, D13). */
  sourceGroup?: string,
): boolean {
  const at = resolvePosition(headKey, resolution, commit)
  if (at == null) return false
  lightingApi.programmer.setPosition('fixture', headKey, at.pan, at.tilt, fadeMs, sourceGroup)
  return true
}

/**
 * One head's whole position for a commit in the row's unit: the commit through
 * `clampCommitToResolution`, the untouched axis from the held `position` entry, else the wire.
 * Null when nothing resolves (a degree on a head that does not annotate it).
 */
function resolvePosition(
  headKey: string,
  resolution: PositionResolution,
  commit: Extract<CellCommit, { kind: 'position' }>,
): { pan: number; tilt: number } | null {
  const clamped = clampCommitToResolution(commit, resolution)
  if (clamped.kind !== 'position') return null
  if (clamped.pan === undefined && clamped.tilt === undefined) return null
  const held = lightingApi.programmer.getKeyState(headKey, 'position').entry
  const parsed = held ? parseProgrammerEntryValue(held) : null
  const staged = parsed?.kind === 'position' ? parsed : null
  const pan = clamped.pan ?? staged?.pan ?? lightingApi.channels.get(resolution.pan.universe, resolution.pan.channelNo)
  const tilt = clamped.tilt ?? staged?.tilt ?? lightingApi.channels.get(resolution.tilt.universe, resolution.tilt.channelNo)
  return { pan: Math.round(pan), tilt: Math.round(tilt) }
}

/** A level or a setting's option level, as one property entry. */
export function writeSheetLevel(headKey: string, propertyName: string, level: number, fadeMs?: number, sourceGroup?: string): void {
  lightingApi.programmer.set('fixture', headKey, propertyName, serializeLevel(level), fadeMs, sourceGroup)
}

/**
 * The row's × (D6): every one of its keys the programmer holds, out of Local at the programmer
 * fade — `programmer.clearEntry`, which releases every owner's slot but a layer's. The row then
 * falls to what is underneath and its chip says what that is.
 */
export function clearSheetRow(headKey: string, keys: readonly string[], fadeMs: number): void {
  for (const key of keys) {
    if (lightingApi.programmer.getKeyState(headKey, key).entry) {
      lightingApi.programmer.clearEntry('fixture', headKey, key, fadeMs)
    }
  }
}

// ─── Over a pick (D13) ──────────────────────────────────────────────────────

/**
 * A level over a pick: a group's *All* is **one** group entry; anything else is each head's own
 * entry, a group member's carrying the group as `sourceGroup`.
 */
export function writePickLevel(write: PickWrite, propertyName: string, level: number, fadeMs?: number): void {
  if (write.kind === 'group') {
    lightingApi.programmer.set('group', write.group, propertyName, serializeLevel(level), fadeMs)
    return
  }
  for (const key of write.keys) {
    lightingApi.programmer.set('fixture', key, propertyName, serializeLevel(level), fadeMs, write.sourceGroup)
  }
}

export interface PickColour {
  r: number
  g: number
  b: number
  w?: number
  a?: number
  uv?: number
}

/**
 * A colour over a pick. A head is sent only the extended components it has a channel for, so a head
 * with no white is not handed one it cannot render; and a component it has but the colour does not
 * say is filled from the head's own channel, because the desk reads a missing one as 0
 * (`useCellWriters`' `writeColour` rule) — a pick whose first head has no white must not black out
 * the next head's. A group's *All* is one group entry only where every member has the same
 * emitters; otherwise each member's own entry, carrying the group, so each keeps its own.
 */
export function writePickColour(
  write: PickWrite,
  heads: readonly { key: string; property: ColourPropertyDescriptor }[],
  colour: PickColour,
  fadeMs?: number,
): void {
  const first = heads[0]?.property
  if (first == null) return
  const emitters = (p: ColourPropertyDescriptor) => `${!!p.whiteChannel}${!!p.amberChannel}${!!p.uvChannel}`
  const own = (property: ColourPropertyDescriptor) => {
    const fill = (given: number | undefined, ref: ChannelRef | undefined) =>
      ref == null ? undefined : (given ?? lightingApi.channels.get(ref.universe, ref.channelNo))
    return {
      r: colour.r,
      g: colour.g,
      b: colour.b,
      w: fill(colour.w, property.whiteChannel),
      a: fill(colour.a, property.amberChannel),
      uv: fill(colour.uv, property.uvChannel),
    }
  }
  if (write.kind === 'group' && heads.every((h) => emitters(h.property) === emitters(first))) {
    lightingApi.programmer.setColour('group', write.group, first.name, own(first), fadeMs)
    return
  }
  const sourceGroup = write.kind === 'group' ? write.group : write.sourceGroup
  for (const { key, property } of heads) {
    lightingApi.programmer.setColour('fixture', key, property.name, own(property), fadeMs, sourceGroup)
  }
}

/**
 * A position over a pick, resolved **per head** (D8): each head's own bytes for the commit, its
 * untouched axis from its own held entry. A group's *All* is one group entry when every member
 * lands on the same bytes — a group of one model moved as one — and otherwise each member's own
 * entry carrying the group, since one group entry would hand every member the first's bytes.
 */
export function writePickPosition(
  write: PickWrite,
  heads: readonly { key: string; resolution: PositionResolution }[],
  /** The commit, or one per head — the pad's point lands on each head's own range. */
  commit: Extract<CellCommit, { kind: 'position' }> | ((resolution: PositionResolution) => Extract<CellCommit, { kind: 'position' }>),
  fadeMs?: number,
): boolean {
  const resolved = heads.flatMap(({ key, resolution }) => {
    const at = resolvePosition(key, resolution, typeof commit === 'function' ? commit(resolution) : commit)
    return at == null ? [] : [{ key, ...at }]
  })
  if (resolved.length === 0) return false
  const first = resolved[0]
  if (
    write.kind === 'group' &&
    resolved.length === heads.length &&
    resolved.every((r) => r.pan === first.pan && r.tilt === first.tilt)
  ) {
    lightingApi.programmer.setPosition('group', write.group, first.pan, first.tilt, fadeMs)
    return true
  }
  const sourceGroup = write.kind === 'group' ? write.group : write.sourceGroup
  for (const r of resolved) lightingApi.programmer.setPosition('fixture', r.key, r.pan, r.tilt, fadeMs, sourceGroup)
  return true
}

/**
 * A dimmerless colour head's level over a pick (`useVirtualDimmer`'s rule per head): each head's
 * colour scaled to the new max(R, G, B), its W / A / UV carried. Never one group entry — the heads'
 * colours differ — so a group's members each carry the group.
 */
export function writePickVirtualDimmer(
  write: PickWrite,
  heads: readonly { key: string; property: ColourPropertyDescriptor }[],
  level: number,
  fadeMs?: number,
): void {
  const clamped = Math.max(0, Math.min(255, Math.round(level)))
  const sourceGroup = write.kind === 'group' ? write.group : write.sourceGroup
  const read = (ref: { universe: number; channelNo: number } | undefined) =>
    ref == null ? undefined : lightingApi.channels.get(ref.universe, ref.channelNo)
  for (const { key, property } of heads) {
    const r = read(property.redChannel) ?? 0
    const g = read(property.greenChannel) ?? 0
    const b = read(property.blueChannel) ?? 0
    const max = Math.max(r, g, b)
    // A dark head has no hue to scale: it comes up white at the level set, so the row reads back
    // what was typed.
    const scale = (c: number) => Math.min(255, Math.round(max > 0 ? (c * clamped) / max : clamped))
    lightingApi.programmer.setColour(
      'fixture',
      key,
      property.name,
      { r: scale(r), g: scale(g), b: scale(b), w: read(property.whiteChannel), a: read(property.amberChannel), uv: read(property.uvChannel) },
      fadeMs,
      sourceGroup,
    )
  }
}

/**
 * A row's × over a pick: a group's *All* clears the group entry on each of the row's keys a member
 * holds; anything else clears each head's held keys (`clearSheetRow` per head).
 */
export function clearPickRow(write: PickWrite, keys: readonly string[], fadeMs: number): void {
  if (write.kind === 'group') {
    for (const key of keys) {
      if (write.keys.some((head) => lightingApi.programmer.getKeyState(head, key).entry)) {
        lightingApi.programmer.clearEntry('group', write.group, key, fadeMs)
      }
    }
    return
  }
  for (const head of write.keys) clearSheetRow(head, keys, fadeMs)
}
