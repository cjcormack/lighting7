import { useMemo } from 'react'
import { useVisiblePatchListQuery } from '../store/patches'
import { useRiggingListQuery } from '../store/riggings'
import { useProjectQuery } from '../store/projects'
import { worldPositionLighting } from '../lib/stageCoords'
import {
  STAGE_PROJECTIONS,
  project,
  projectionExtent,
  toPercent,
  type Extent,
  type LightingPoint,
  type ScreenPoint,
  type StageDims,
  type StageProjection,
} from '../lib/stageProjection'
import type { FixturePatch } from '../api/patchApi'

/** Envelope defaults when the project hasn't declared its stage dimensions. */
export const DEFAULT_STAGE_DIMS: StageDims = { widthM: 10, depthM: 8, heightM: 6 }

export interface ProjectedPatch {
  patch: FixturePatch
  /** Composed world position in lighting metres (rig offsets already applied). */
  world: LightingPoint
  /** Projected into the requested plane, in screen-metres. */
  screen: ScreenPoint
  /** Position within the stage envelope, for DOM-positioned views. */
  leftPct: number
  topPct: number
}

export interface UseProjectedPatchesOptions {
  projection?: StageProjection
  /** Keep this patch even when `stageHidden` — the selected one stays drawn. */
  includeKey?: string | null
}

/**
 * Every placed patch's position, composed through its rigging and projected into
 * one plane.
 *
 * The single source of stage-map coordinates. Before this existed each surface
 * did its own arithmetic and they disagreed: the cue-card MiniStage treated
 * metric `stageX`/`stageY` as CSS percentages, so a fixture 3 m stage-right
 * rendered 3% across the card, and truss-mounted fixtures were placed by their
 * rig-local offset rather than their world position.
 *
 * Patches with no resolvable position are dropped — `worldPositionLighting`
 * returns null when `stageX` or `stageY` is null. Those fixtures are invisible on
 * every stage surface, which is what the unplaced-fixture tray exists to solve.
 */
export function useProjectedPatches(
  projectId: number | undefined,
  { projection = STAGE_PROJECTIONS.plan, includeKey = null }: UseProjectedPatchesOptions = {},
): { points: ProjectedPatch[]; extent: Extent; dims: StageDims } {
  const skip = projectId == null
  // Infrastructure never reaches the plot — not even as the selected patch `includeKey` keeps.
  const { data: patches } = useVisiblePatchListQuery(projectId ?? 0, { skip })
  const { data: riggings } = useRiggingListQuery(projectId ?? 0, { skip })
  const { data: projectDetail } = useProjectQuery(projectId ?? 0, { skip })

  const dims = useMemo<StageDims>(
    () => ({
      widthM: projectDetail?.stageWidthM ?? DEFAULT_STAGE_DIMS.widthM,
      depthM: projectDetail?.stageDepthM ?? DEFAULT_STAGE_DIMS.depthM,
      heightM: projectDetail?.stageHeightM ?? DEFAULT_STAGE_DIMS.heightM,
    }),
    [projectDetail?.stageWidthM, projectDetail?.stageDepthM, projectDetail?.stageHeightM],
  )

  const extent = useMemo(() => projectionExtent(projection, dims), [projection, dims])

  const points = useMemo(() => {
    const rigs = riggings ?? []
    const out: ProjectedPatch[] = []
    for (const patch of patches ?? []) {
      if (patch.stageHidden && patch.key !== includeKey) continue
      const world = worldPositionLighting(patch, rigs)
      if (!world) continue
      const screen = project(world, projection)
      out.push({ patch, world, screen, ...toPercent(screen, extent) })
    }
    return out
  }, [patches, riggings, projection, extent, includeKey])

  return { points, extent, dims }
}
