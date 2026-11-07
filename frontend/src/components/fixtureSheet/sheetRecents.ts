import { dmxToCentre } from '@/lib/positionScale'
import type { CellCommit } from '../fixtures-list/rowModel'
import { recentPositionOf } from '../editor/recentValueSpelling'
import { resolvePosition } from './sheetWrites'
import type { PositionResolution } from './sheetRows'

type PositionCommit = Extract<CellCommit, { kind: 'position' }>

/**
 * The `position` recent a sheet write makes over [heads] (D15): each head's pair as the write
 * resolves it (`resolvePosition` — the untouched axis from the held entry, else the wire), centre-
 * relative on its own travel, or null while the heads differ or a head resolves nothing. Called
 * before the write, which reads the same held entry.
 */
export function sheetPositionRecent(
  heads: readonly { key: string; resolution: PositionResolution }[],
  commit: PositionCommit | ((r: PositionResolution) => PositionCommit),
): string | null {
  const pairs: { pan: number; tilt: number }[] = []
  for (const { key, resolution } of heads) {
    const at = resolvePosition(key, resolution, typeof commit === 'function' ? commit(resolution) : commit)
    if (at == null) return null
    pairs.push({
      pan: dmxToCentre(at.pan, { min: resolution.panMin, max: resolution.panMax }),
      tilt: dmxToCentre(at.tilt, { min: resolution.tiltMin, max: resolution.tiltMax }),
    })
  }
  return recentPositionOf(pairs)
}
