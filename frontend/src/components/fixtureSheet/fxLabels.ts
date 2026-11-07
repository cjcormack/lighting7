import { useCallback, useMemo } from 'react'
import { useSpeedMasterLiveQuery } from '@/store/speedMasters'
import { useCurrentProjectQuery } from '@/store/projects'
import { useProjectCueStackListQuery } from '@/store/cueStacks'
import { usePaletteListQuery } from '@/store/palettes'
import { isCueRow } from '@/api/cueStacksApi'
import { fxSpeedLabel } from '../fx/fxConstants'
import { followsNoMaster, isWallClock } from '@/lib/fxTiming'
import type { ActiveFx } from '@/store/fixtureFx'

/**
 * An effect's speed and master as the sheet's chips spell it — `1 bar · M2`, the busk pad's
 * `FxPadDetail` vocabulary. A beat effect with no master runs on master 1; one on its own tempo
 * names its tempo, `1 bar · Own 96`, and a wall-clock one on Natural names none, its `4 s` saying
 * what it runs on. Read from the live bank once, so a row and a tray chip do not each subscribe.
 */
export function useFxDetail(): (effect: ActiveFx) => string {
  const { data } = useSpeedMasterLiveQuery(undefined)
  const indexByUuid = useMemo(() => new Map((data ?? []).map((m) => [m.uuid, m.index])), [data])
  return useCallback(
    (effect: ActiveFx) => {
      const wallClock = isWallClock(effect, effect.timingSource)
      const uuid = wallClock ? effect.rateSpeedMasterUuid : effect.speedMasterUuid
      const index = uuid != null ? indexByUuid.get(uuid) : undefined
      const master = followsNoMaster(effect, effect.timingSource) ? null : index != null ? `M${index}` : 'M1'
      return [fxSpeedLabel(effect.cycle, effect.timingSource, effect.ownBpm), master].filter(Boolean).join(' · ')
    },
    [indexByUuid],
  )
}

/**
 * A cue's number as the chip names it — `Q12`. The provenance frame carries a cue's id and its
 * stack's; the number is the show's, read from the current project's stack list.
 */
export function useCueLabel(): (cueId: number) => string | undefined {
  const { data: project } = useCurrentProjectQuery()
  const { data: stacks } = useProjectCueStackListQuery(project?.id ?? 0, { skip: project == null })
  const byId = useMemo(() => {
    const map = new Map<number, string>()
    for (const stack of stacks ?? []) {
      for (const cue of (stack.cues ?? []).filter(isCueRow)) map.set(cue.id, cue.cueNumber ? `Q${cue.cueNumber}` : cue.name)
    }
    return map
  }, [stacks])
  return useCallback((cueId: number) => byId.get(cueId), [byId])
}

/**
 * A palette's name by uuid, from the current project's library — for a reference whose frame names
 * only the uuid (a cue's provenance, palette-references plan D6). Naming, never resolving.
 */
export function usePaletteNameByUuid(): (uuid: string) => string | undefined {
  const { data: project } = useCurrentProjectQuery()
  const { data: palettes } = usePaletteListQuery({ projectId: project?.id ?? 0 }, { skip: project == null })
  const byUuid = useMemo(() => new Map((palettes ?? []).map((p) => [p.uuid, p.name])), [palettes])
  return useCallback((uuid: string) => byUuid.get(uuid), [byUuid])
}
