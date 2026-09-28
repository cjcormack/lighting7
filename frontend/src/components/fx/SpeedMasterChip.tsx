import { Badge } from '@/components/ui/badge'
import { useSpeedMasterDisplay } from '../../store/speedMasters'

/**
 * "Runs on M2" chip for effect summaries and FX-sheet chips.
 *
 * Renders **nothing when the effect resolves to master 1** — the rule (and the
 * only-re-render-on-own-master subscription) lives in [useSpeedMasterDisplay], shared with
 * the FX sheet's inline label so the two can't drift.
 */
export function SpeedMasterChip({
  speedMasterUuid,
  kind = 'runsOn',
}: {
  speedMasterUuid: string | null | undefined
  /**
   * `rate` labels a wall-clock effect's cycle-rate master instead of the beat master it
   * runs on. A prop rather than a second component, so the hide-at-M1 resolution stays in
   * one place as the doc comment above requires.
   */
  kind?: 'runsOn' | 'rate'
}) {
  const master = useSpeedMasterDisplay(speedMasterUuid)
  if (!master) return null

  const isRate = kind === 'rate'
  return (
    <Badge
      variant="outline"
      className="text-[10px] leading-tight px-1 py-0 shrink-0 font-mono"
      title={
        isRate
          ? `Cycle rate scaled by speed master ${master.index} — ${master.name}`
          : `Runs on speed master ${master.index} — ${master.name}`
      }
    >
      {isRate ? 'R·' : ''}M{master.index}
    </Badge>
  )
}
