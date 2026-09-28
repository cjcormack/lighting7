import { useMemo } from 'react'
import { useVisiblePatchListQuery } from '../../store/patches'
import { useRiggingListQuery } from '../../store/riggings'
import { useStageRegionListQuery } from '../../store/stageRegions'
import { useFixtureLookup } from '../../hooks/useFixtureLookup'
import type { FixturePatch } from '../../api/patchApi'
import type { RiggingDto } from '../../api/riggingApi'
import type { StageRegionDto } from '../../api/stageRegionApi'
import type { Fixture, FixtureTypeInfo } from '../../store/fixtures'
import { buildHarness, isHarnessActive } from './profileHarness'

interface StageData {
  patches: FixturePatch[] | undefined
  regions: StageRegionDto[] | undefined
  riggings: RiggingDto[] | undefined
  fixtureByKey: Map<string, Fixture>
  typeByKey: Map<string, FixtureTypeInfo>
}

// Single source of truth for Stage 3D's input data. When the profiling
// harness is active (?profileHarness=1) it swaps in a synthetic scene and
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

  const harness = useMemo(() => {
    if (!isHarnessActive()) return null
    return buildHarness(stageW, stageD, stageH)
  }, [stageW, stageD, stageH])

  return useMemo(() => {
    if (!harness) {
      return { patches, regions, riggings, fixtureByKey, typeByKey }
    }
    const mergedFixtureByKey = new Map(fixtureByKey)
    for (const p of harness.patches) mergedFixtureByKey.set(p.key, harness.syntheticFixture)
    const mergedTypeByKey = new Map(typeByKey)
    mergedTypeByKey.set(harness.syntheticType.typeKey, harness.syntheticType)
    return {
      patches: harness.patches,
      regions: harness.regions,
      riggings: harness.riggings,
      fixtureByKey: mergedFixtureByKey,
      typeByKey: mergedTypeByKey,
    }
  }, [harness, patches, regions, riggings, fixtureByKey, typeByKey])
}
