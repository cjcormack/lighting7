import type { ProjectDetail } from '@/api/projectApi'
import type { HomeCounts } from '@/navigation'
import { useProjectCueStackListQuery } from '@/store/cueStacks'
import { useProjectCueListQuery } from '@/store/cues'
import { useVisibleFixtureListQuery } from '@/store/fixtures'
import { useGroupListQuery } from '@/store/groups'
import { useLookListQuery } from '@/store/looks'
import { usePatchListQuery } from '@/store/patches'
import { useRiggingListQuery } from '@/store/riggings'
import { useSpeedMasterListQuery } from '@/store/speedMasters'
import { useStageRegionListQuery } from '@/store/stageRegions'
import { useSurfaceBindingsQuery } from '@/store/surfaces'
import { useTemplateListQuery } from '@/store/templates'
import { useGetUniverseQuery } from '@/store/universes'

/**
 * What a project's home counts on its section cards (navigation plan §5.1, P5), each from a query
 * the frontend already makes. The counts on cards that need the rig — Show, fixtures, groups,
 * universes, surface bindings — are read only on the rig, where the card draws them; off it the card
 * says why it is muted instead.
 *
 * Patches are counted raw, infrastructure included, because the Patch List lists them.
 */
export function useHomeCounts(project: ProjectDetail): HomeCounts {
  const id = project.id
  const live = { skip: !project.isCurrent }
  const { data: patches } = usePatchListQuery(id)
  const { data: fixtures } = useVisibleFixtureListQuery(live)
  const { data: groups } = useGroupListQuery(undefined, live)
  const { data: universes } = useGetUniverseQuery(undefined, live)
  const { data: surfaceBindings } = useSurfaceBindingsQuery(id, live)
  const { data: regions } = useStageRegionListQuery(id)
  const { data: riggings } = useRiggingListQuery(id)
  const { data: stacks } = useProjectCueStackListQuery(id, live)
  // The cue list rather than `ProjectDetail.cueCount`: the list's tag is the one cue writes invalidate.
  const { data: cues } = useProjectCueListQuery(id, live)
  const { data: looks } = useLookListQuery({ projectId: id })
  const { data: palettes } = useTemplateListQuery({ projectId: id })
  const { data: speedMasters } = useSpeedMasterListQuery({ projectId: id })

  return {
    patches: patches?.length,
    fixtures: project.isCurrent ? fixtures?.length : undefined,
    groups: project.isCurrent ? groups?.length : undefined,
    universes: project.isCurrent ? universes?.length : undefined,
    surfaceBindings: project.isCurrent ? surfaceBindings?.length : undefined,
    regions: regions?.length,
    riggings: riggings?.length,
    stacks: project.isCurrent ? stacks?.filter((s) => s.type !== 'SEPARATOR').length : undefined,
    cues: project.isCurrent ? cues?.length : undefined,
    looks: looks?.length,
    palettes: palettes?.length,
    speedMasters: speedMasters?.length,
    scripts: project.scriptCount,
  }
}
