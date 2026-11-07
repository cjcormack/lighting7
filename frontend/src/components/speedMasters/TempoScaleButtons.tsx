import { cn } from '@/lib/utils'
import { canScaleTempo } from '@/lib/speedMasterModel'
import { OWN_BPM_MAX, OWN_BPM_MIN } from '@/lib/fxTiming'
import { scaleSpeedMaster } from '@/store/speedMasters'
import { DESK_OFFLINE_LABEL } from '@/api/wsGesture'

const FACTORS = [
  { factor: 0.5, glyph: '×½', verb: 'Halve' },
  { factor: 2, glyph: '×2', verb: 'Double' },
] as const

/**
 * ×½ and ×2 on a master (lighting7 fx-speed plan D12): a tempo write each, through
 * `speedMasters.scale`, so on a held master it moves the hold. Disabled where the result would leave
 * the clock's 20–300 BPM, on a follower (whose leader's buttons do it, [lockedReason] saying so) and
 * offline. A tap afterwards sets the beat it hears: no factor is kept.
 */
export function TempoScaleButtons({
  masterUuid,
  masterIndex,
  bpm,
  connected,
  lockedReason,
  className,
}: {
  masterUuid: string | null
  masterIndex: number
  /** The tempo the master runs at — its hold when held. */
  bpm: number
  connected: boolean
  lockedReason: string | null
  className?: string
}) {
  return (
    <>
      {FACTORS.map(({ factor, glyph, verb }) => {
        const inRange = canScaleTempo(bpm, factor)
        return (
          <button
            key={factor}
            type="button"
            onClick={() => scaleSpeedMaster(masterUuid, factor)}
            disabled={!connected || lockedReason != null || !inRange}
            title={
              lockedReason ??
              (!connected
                ? DESK_OFFLINE_LABEL
                : inRange
                  ? `${verb} the tempo`
                  : `${verb}d it would leave ${OWN_BPM_MIN}–${OWN_BPM_MAX} BPM`)
            }
            aria-label={`${verb} the tempo of master ${masterIndex}`}
            className={cn(
              'flex shrink-0 items-center justify-center rounded-md border font-mono text-xs font-bold tabular-nums transition-colors',
              'hover:bg-accent disabled:pointer-events-none disabled:opacity-50',
              className,
            )}
          >
            {glyph}
          </button>
        )
      })}
    </>
  )
}
