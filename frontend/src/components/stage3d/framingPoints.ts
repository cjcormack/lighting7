import { worldPositionLighting } from '../../lib/stageCoords'
import type { LightingPoint } from '../../lib/stageProjection'
import type { FixturePatch } from '../../api/patchApi'
import type { RiggingDto } from '../../api/riggingApi'
import type { StageRegionDto } from '../../api/stageRegionApi'
import type { StageElementDto } from '../../api/stageElementApi'
import type { CueTarget } from '../../api/cuesApi'
import type { SelectionRef } from './useStageSelection'

/**
 * What *Frame the selection* (F) frames, in lighting coordinates: every placement of a selected
 * fixture — a paired dimmer's other lanterns included, since the pair is one circuit — the middle
 * of a selected region (which hangs down from `centerZ`, its top surface), a selected rigging's
 * position and a selected scene element's origin.
 */
export function stageSelectionPoints(
  refs: readonly SelectionRef[],
  patches: readonly FixturePatch[],
  riggings: readonly RiggingDto[],
  regions: readonly StageRegionDto[],
  elements: readonly StageElementDto[] = [],
): LightingPoint[] {
  const out: LightingPoint[] = []
  for (const ref of refs) {
    if (ref.kind === 'patch') {
      const patch = patches.find((p) => p.key === ref.patchKey)
      if (patch) out.push(...patchPoints(patch, riggings))
    } else if (ref.kind === 'region') {
      const region = regions.find((r) => r.uuid === ref.uuid)
      if (region?.centerX != null && region.centerY != null) {
        out.push({ x: region.centerX, y: region.centerY, z: (region.centerZ ?? 0) - (region.heightM ?? 0) / 2 })
      }
    } else if (ref.kind === 'rigging') {
      const rig = riggings.find((r) => r.uuid === ref.uuid)
      if (rig?.positionX != null && rig.positionY != null) {
        out.push({ x: rig.positionX, y: rig.positionY, z: rig.positionZ ?? 0 })
      }
    } else {
      const element = elements.find((e) => e.uuid === ref.uuid)
      if (element) out.push({ x: element.positionX, y: element.positionY, z: element.positionZ })
    }
  }
  return out
}

/**
 * The desk selection's points, for a Stage view whose own selection is empty — a chip pressed in
 * Positions, the busk band, the programmer. A group target frames its members (a group is named by
 * its name on the wire, `CueTarget`); a cell's key is opaque on this side and frames nothing. A
 * patch the Stage view does not draw — `stageHidden`, or infrastructure — frames nothing either: a
 * group reaching one would otherwise pull the frame towards a light that is not on screen. (The
 * Stage's own selection keeps a hidden patch, because the view draws the selected one.)
 */
export function deskSelectionPoints(
  targets: readonly CueTarget[],
  patches: readonly FixturePatch[],
  riggings: readonly RiggingDto[],
): LightingPoint[] {
  const fixtureKeys = new Set<string>()
  const groupNames = new Set<string>()
  for (const target of targets) {
    if (target.type === 'fixture') fixtureKeys.add(target.key)
    else groupNames.add(target.key)
  }
  const out: LightingPoint[] = []
  for (const patch of patches) {
    if (patch.stageHidden || patch.infrastructure) continue
    if (fixtureKeys.has(patch.key) || patch.groups.some((g) => groupNames.has(g.name))) {
      out.push(...patchPoints(patch, riggings))
    }
  }
  return out
}

function patchPoints(patch: FixturePatch, riggings: readonly RiggingDto[]): LightingPoint[] {
  const out: LightingPoint[] = []
  const own = worldPositionLighting(patch, riggings as RiggingDto[])
  if (own) out.push(own)
  for (const placement of patch.extraPlacements ?? []) {
    const at = worldPositionLighting(placement, riggings as RiggingDto[])
    if (at) out.push(at)
  }
  return out
}
