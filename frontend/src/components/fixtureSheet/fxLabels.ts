import { useCallback, useMemo } from 'react'
import { useSpeedMasterLiveQuery } from '@/store/speedMasters'
import { useCurrentProjectQuery } from '@/store/projects'
import { useProjectCueStackListQuery } from '@/store/cueStacks'
import { usePaletteListQuery } from '@/store/palettes'
import { isCueRow } from '@/api/cueStacksApi'
import { NATURAL_BPM, cycleSeconds, fxSpeedLabel, secondsWords } from '@/lib/fxSpeed'
import { bpmLabel, followsNoMaster, isWallClock } from '@/lib/fxTiming'
import type { ActiveFx } from '@/store/fixtureFx'

/**
 * An effect's speed and master as the sheet's chips spell it — `×1 · M2`, the busk pad's
 * `FxPadDetail` vocabulary (fx-speed plan D2). A beat effect with no master runs on master 1; one on
 * its own tempo names its tempo, `×1 · Own 96`, and a wall-clock one on Natural names none. Asked for
 * `seconds`, a chip's hover: the cycle in seconds at the tempo it runs at, `1.82 s a cycle at 132 BPM`.
 * Read from the live bank once, so a row and a tray chip do not each subscribe.
 */
export function useFxDetail(): (effect: ActiveFx, what?: 'label' | 'seconds') => string {
  const { data } = useSpeedMasterLiveQuery(undefined)
  const byUuid = useMemo(() => new Map((data ?? []).map((m) => [m.uuid, m])), [data])
  const master1Bpm = data?.find((m) => m.index === 1)?.bpm
  return useCallback(
    (effect: ActiveFx, what: 'label' | 'seconds' = 'label') => {
      const wallClock = isWallClock(effect, effect.timingSource)
      const uuid = wallClock ? effect.rateSpeedMasterUuid : effect.speedMasterUuid
      const found = uuid != null ? byUuid.get(uuid) : undefined
      if (what === 'seconds') {
        const bpm = effect.ownBpm != null && !wallClock ? effect.ownBpm : wallClock && uuid == null ? NATURAL_BPM : (found?.bpm ?? master1Bpm ?? NATURAL_BPM)
        return `${secondsWords(cycleSeconds(effect.cycle, wallClock, bpm))} a cycle at ${bpmLabel(bpm)} BPM`
      }
      const master = followsNoMaster(effect, effect.timingSource) ? null : found != null ? `M${found.index}` : 'M1'
      return [fxSpeedLabel(effect.cycle, effect.timingSource, effect.ownBpm), master].filter(Boolean).join(' · ')
    },
    [byUuid, master1Bpm],
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
