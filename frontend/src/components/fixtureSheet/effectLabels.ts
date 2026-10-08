import { useCallback, useMemo } from 'react'
import { useSpeedMasterLiveQuery } from '@/store/speedMasters'
import { useCurrentProjectQuery } from '@/store/projects'
import { useProjectCueStackListQuery } from '@/store/cueStacks'
import { effectSpeedLabel } from '../fx/fxConstants'
import type { ActiveEffect } from '@/store/fixtureFx'

/**
 * An effect's speed and master as the sheet's chips spell it — `½ · M2`, the busk pad's
 * `EffectPadDetail` vocabulary. A beat effect with no master runs on master 1; a wall-clock one with
 * no rate master is **unscaled**. Read from the live bank once, so a row and a tray chip do not each
 * subscribe.
 */
export function useEffectDetail(): (effect: ActiveEffect) => string {
  const { data } = useSpeedMasterLiveQuery(undefined)
  const indexByUuid = useMemo(() => new Map((data ?? []).map((m) => [m.uuid, m.index])), [data])
  return useCallback(
    (effect: ActiveEffect) => {
      const wallClock = effect.timingSource === 'WALL_CLOCK'
      const uuid = wallClock ? effect.rateSpeedMasterUuid : effect.speedMasterUuid
      const index = uuid != null ? indexByUuid.get(uuid) : undefined
      const master = index != null ? `M${index}` : wallClock && uuid == null ? 'unscaled' : 'M1'
      return [effectSpeedLabel(effect.beatDivision, effect.timingSource), master].filter(Boolean).join(' · ')
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
      for (const cue of stack.cues ?? []) map.set(cue.id, cue.cueNumber ? `Q${cue.cueNumber}` : cue.name)
    }
    return map
  }, [stacks])
  return useCallback((cueId: number) => byId.get(cueId), [byId])
}
