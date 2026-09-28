import { useMemo } from 'react'
import { useVisiblePatchListQuery } from '../store/patches'
import { useRiggingListQuery } from '../store/riggings'
import { useProjectQuery } from '../store/projects'
import { useFixtureTypeListQuery, type FixtureTypeInfo } from '../store/fixtures'
import { worldPositionLighting } from '../lib/stageCoords'
import { bodyEndsLighting, drawnLengthM } from '../lib/fixtureLength'
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
import type { FixturePatch, PatchPlacement } from '../api/patchApi'

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
  /**
   * For a fixture whose length is set per install (a lightstrip), its two ends projected into the
   * same plane — what a surface drawn to scale draws it as, so a run round the stage edge reads as
   * the line it is rather than a dot at its middle. Absent for every other type.
   */
  span?: readonly [ScreenPoint, ScreenPoint]
}

/**
 * One of a patch's extra placements — a paired dimmer's other lantern — projected the same way.
 * `patch` is the fixture it belongs to (so it lights, selects and names as that fixture);
 * `placement` is where this lantern hangs.
 */
export interface ProjectedPlacement extends ProjectedPatch {
  placement: PatchPlacement
}

/** Either kind of point, for a surface that draws the whole rig: `placement` set on a lantern. */
export type DrawnPoint = ProjectedPatch & { placement?: PatchPlacement }

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
): {
  points: ProjectedPatch[]
  /**
   * Every placed extra placement, in patch then list order. Kept apart from `points` on purpose:
   * `points` is one entry per fixture, which is what the editors drag, snap, count and marquee,
   * and a lantern that moved the fixture's primary position when dragged would be a trap. A
   * surface that just draws the rig draws both.
   */
  extraPoints: ProjectedPlacement[]
  extent: Extent
  dims: StageDims
} {
  const skip = projectId == null
  // Infrastructure never reaches the plot — not even as the selected patch `includeKey` keeps.
  const { data: patches } = useVisiblePatchListQuery(projectId ?? 0, { skip })
  const { data: riggings } = useRiggingListQuery(projectId ?? 0, { skip })
  const { data: projectDetail } = useProjectQuery(projectId ?? 0, { skip })
  const { data: fixtureTypes } = useFixtureTypeListQuery()

  const dims = useMemo<StageDims>(
    () => ({
      widthM: projectDetail?.stageWidthM ?? DEFAULT_STAGE_DIMS.widthM,
      depthM: projectDetail?.stageDepthM ?? DEFAULT_STAGE_DIMS.depthM,
      heightM: projectDetail?.stageHeightM ?? DEFAULT_STAGE_DIMS.heightM,
    }),
    [projectDetail?.stageWidthM, projectDetail?.stageDepthM, projectDetail?.stageHeightM],
  )

  const extent = useMemo(() => projectionExtent(projection, dims), [projection, dims])

  const typeByKey = useMemo(() => {
    const map = new Map<string, FixtureTypeInfo>()
    for (const type of fixtureTypes ?? []) map.set(type.typeKey, type)
    return map
  }, [fixtureTypes])

  const { points, extraPoints } = useMemo(() => {
    const rigs = riggings ?? []
    const out: ProjectedPatch[] = []
    const extras: ProjectedPlacement[] = []
    // The projected ends of a variable-length body, or undefined for any other type (and for a
    // type list that has not arrived yet — the fixture draws as a dot until it has).
    const spanOf = (
      world: LightingPoint,
      patch: FixturePatch,
      at: Pick<PatchPlacement, 'lengthM' | 'baseYawDeg' | 'basePitchDeg' | 'baseRollDeg'>,
      placement?: PatchPlacement,
    ): ProjectedPatch['span'] => {
      const type = typeByKey.get(patch.fixtureTypeKey)
      if (!type?.acceptsLength) return undefined
      const length = drawnLengthM(type, patch, placement)
      if (length == null || !(length > 0)) return undefined
      const [a, b] = bodyEndsLighting(world, length, at.baseYawDeg, at.basePitchDeg, at.baseRollDeg)
      return [project(a, projection), project(b, projection)]
    }
    for (const patch of patches ?? []) {
      if (patch.stageHidden && patch.key !== includeKey) continue
      const world = worldPositionLighting(patch, rigs)
      if (world) {
        const screen = project(world, projection)
        const span = spanOf(world, patch, patch)
        out.push({ patch, world, screen, ...toPercent(screen, extent), ...(span && { span }) })
      }
      // A lantern is drawn wherever it has a position, whether or not the fixture's own
      // placement does — the pair is one circuit, not a primary and its shadow.
      for (const placement of patch.extraPlacements ?? []) {
        const at = worldPositionLighting(placement, rigs)
        if (!at) continue
        const screen = project(at, projection)
        const span = spanOf(at, patch, placement, placement)
        extras.push({ patch, placement, world: at, screen, ...toPercent(screen, extent), ...(span && { span }) })
      }
    }
    return { points: out, extraPoints: extras }
  }, [patches, riggings, projection, extent, includeKey, typeByKey])

  return { points, extraPoints, extent, dims }
}
