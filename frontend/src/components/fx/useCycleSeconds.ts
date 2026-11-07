import { useCallback, useMemo } from 'react'
import { NATURAL_BPM, cycleSeconds, secondsWords } from '@/lib/fxSpeed'
import { bpmLabel } from '@/lib/fxTiming'
import { useSpeedMasterLiveQuery } from '@/store/speedMasters'

/** How an FX is timed, as a chip's hover needs it. */
export interface CycleTiming {
  cycle: number
  /** The cycle is seconds: a wall-clock FX, on a rate master or Natural. */
  wallClock: boolean
  ownBpm?: number | null
  /** Its master — a wall-clock FX's rate master — or null: master 1 on the beat, Natural on the wall clock. */
  masterUuid?: string | null
}

/**
 * A chip's hover (fx-speed plan D2): the cycle in seconds at the tempo the FX runs at, live —
 * `1.82 s a cycle at 132 BPM`. Read from the live bank once per caller, so a row of chips does not
 * each subscribe.
 */
export function useCycleSeconds(): (timing: CycleTiming) => string {
  const { data } = useSpeedMasterLiveQuery(undefined, { selectFromResult: ({ data: masters }) => ({ data: masters }) })
  const bpmByUuid = useMemo(() => new Map((data ?? []).map((m) => [m.uuid, m.bpm])), [data])
  const master1 = data?.find((m) => m.index === 1)?.bpm
  return useCallback(
    ({ cycle, wallClock, ownBpm, masterUuid }: CycleTiming) => {
      const bpm =
        ownBpm != null && !wallClock
          ? ownBpm
          : wallClock && masterUuid == null
            ? NATURAL_BPM
            : ((masterUuid != null ? bpmByUuid.get(masterUuid) : undefined) ?? master1 ?? NATURAL_BPM)
      return `${secondsWords(cycleSeconds(cycle, wallClock, bpm))} a cycle at ${bpmLabel(bpm)} BPM`
    },
    [bpmByUuid, master1],
  )
}
