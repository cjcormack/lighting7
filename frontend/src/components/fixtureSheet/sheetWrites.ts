import { lightingApi } from '@/api/lightingApi'
import { parseProgrammerEntryValue, serializeLevel } from '@/lib/programmerValue'
import { clampCommitToResolution, type CellCommit } from '../fixtures-list/rowModel'
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
): boolean {
  const clamped = clampCommitToResolution(commit, resolution)
  if (clamped.kind !== 'position') return false
  if (clamped.pan === undefined && clamped.tilt === undefined) return false
  const held = lightingApi.programmer.getKeyState(headKey, 'position').entry
  const parsed = held ? parseProgrammerEntryValue(held) : null
  const staged = parsed?.kind === 'position' ? parsed : null
  const pan = clamped.pan ?? staged?.pan ?? lightingApi.channels.get(resolution.pan.universe, resolution.pan.channelNo)
  const tilt = clamped.tilt ?? staged?.tilt ?? lightingApi.channels.get(resolution.tilt.universe, resolution.tilt.channelNo)
  lightingApi.programmer.setPosition('fixture', headKey, Math.round(pan), Math.round(tilt), fadeMs)
  return true
}

/** A level or a setting's option level, as one property entry. */
export function writeSheetLevel(headKey: string, propertyName: string, level: number, fadeMs?: number): void {
  lightingApi.programmer.set('fixture', headKey, propertyName, serializeLevel(level), fadeMs)
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
