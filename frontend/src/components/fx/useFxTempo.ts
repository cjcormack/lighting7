import { NATURAL_BPM } from '@/lib/fxSpeed'
import { useSpeedMasterLiveQuery } from '@/store/speedMasters'
import type { FxDraft } from './fxEditorModel'

/**
 * The tempo an FX runs against, live (fx-speed plan D2): its Own tempo, else its master's running
 * tempo (a wall-clock FX's rate master), else Natural's 120. A master the bank does not hold runs on
 * master 1, as the desk's `SpeedMasterBank.slotFor` does.
 */
export function useFxTempo(draft: Pick<FxDraft, 'ownBpm' | 'natural' | 'speedMasterUuid' | 'rateSpeedMasterUuid'>, wallClock: boolean): number {
  const uuid = wallClock ? draft.rateSpeedMasterUuid : draft.speedMasterUuid
  const natural = wallClock && (draft.natural || draft.rateSpeedMasterUuid == null)
  const { bpm } = useSpeedMasterLiveQuery(undefined, {
    selectFromResult: ({ data }) => ({
      bpm: (uuid != null ? data?.find((m) => m.uuid === uuid) : undefined)?.bpm ?? data?.find((m) => m.index === 1)?.bpm,
    }),
  })
  if (draft.ownBpm != null && !wallClock) return draft.ownBpm
  if (natural) return NATURAL_BPM
  return bpm ?? NATURAL_BPM
}
