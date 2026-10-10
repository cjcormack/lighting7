import { useMemo } from 'react'
import { useVisiblePatchListQuery } from '../../store/patches'
import { useRiggingListQuery } from '../../store/riggings'
import { useStageRegionListQuery } from '../../store/stageRegions'
import { useFixtureLookup } from '../../hooks/useFixtureLookup'
import type { FixturePatch } from '../../api/patchApi'
import type { RiggingDto } from '../../api/riggingApi'
import type { StageElementDto } from '../../api/stageElementApi'
import type { StageRegionDto } from '../../api/stageRegionApi'
import type { Fixture, FixtureTypeInfo } from '../../store/fixtures'
import { buildHarness, harnessMode } from './profileHarness'
import { useHarnessImages } from './harnessImages'
import { useLanternIndex } from '../../hooks/useLanternIndex'
import type { LanternIndex } from '../../lib/lanterns'
import { useGelIndex } from '../../hooks/useGelIndex'
import type { GelIndex } from '../../lib/gels'

interface StageData {
  patches: FixturePatch[] | undefined
  regions: StageRegionDto[] | undefined
  riggings: RiggingDto[] | undefined
  fixtureByKey: Map<string, Fixture>
  typeByKey: Map<string, FixtureTypeInfo>
  /** The lantern library, which a generic dimmer's body is chosen from. */
  lanterns: LanternIndex
  /** The gel library, which a unit's fitted gels and a `gelCode` are drawn from. Read here and
   *  passed down, never in the scene: a capture canvas bridges only the channel source. */
  gels: GelIndex
  /** The harness's own scene, which replaces the project's stage elements; undefined otherwise. */
  harnessElements?: StageElementDto[]
}

// Single source of truth for Stage 3D's input data. When the profiling
// harness is active (?profileHarness=1, =focus, or another named scene) it swaps in a synthetic scene and
// augments the fixture-lookup maps so the harness patches resolve to a
// beam-emitting fixture type. Otherwise it returns the live RTK Query data.
export function useStageData(
  projectId: number,
  stageW: number,
  stageD: number,
  stageH: number,
): StageData {
  // Infrastructure patches are not stage objects under any flag, so the scene never sees them.
  const { data: patches } = useVisiblePatchListQuery(projectId)
  const { data: regions } = useStageRegionListQuery(projectId)
  const { data: riggings } = useRiggingListQuery(projectId)
  const { fixtureByKey, typeByKey } = useFixtureLookup()
  const lanterns = useLanternIndex()
  const gels = useGelIndex()

  // A cloth scene's paint is stored through the desk's own scene-image route before it is drawn.
  const mode = harnessMode()
  const harnessImages = useHarnessImages(projectId, mode)
  const harness = useMemo(() => {
    if (mode == null) return null
    return buildHarness(stageW, stageD, stageH, mode, harnessImages)
  }, [mode, stageW, stageD, stageH, harnessImages])

  return useMemo(() => {
    if (!harness) {
      return { patches, regions, riggings, fixtureByKey, typeByKey, lanterns, gels }
    }
    const mergedFixtureByKey = new Map(fixtureByKey)
    for (const p of harness.patches) mergedFixtureByKey.set(p.key, harness.fixtureFor?.get(p.key) ?? harness.syntheticFixture)
    const mergedTypeByKey = new Map(typeByKey)
    mergedTypeByKey.set(harness.syntheticType.typeKey, harness.syntheticType)
    return {
      patches: harness.patches,
      regions: harness.regions,
      riggings: harness.riggings,
      fixtureByKey: mergedFixtureByKey,
      typeByKey: mergedTypeByKey,
      lanterns,
      gels,
      harnessElements: harness.elements,
    }
  }, [harness, patches, regions, riggings, fixtureByKey, typeByKey, lanterns, gels])
}
