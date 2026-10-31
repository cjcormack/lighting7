import { useMemo } from 'react'
import { useMoveInDarkHeads } from '@/store/moveInDark'
import { useNow } from '@/components/rigSafety/useNow'
import { movingNow } from '@/lib/moveInDark'
import { MovingNowChips } from './moveInDarkMarks'

/**
 * What one stack is moving in the dark now (lighting7 move-in-dark design §5.4), from
 * `moveInDark.state`: the Show view's stack row says `☾ 4 moving`, the phone runner's *Up next*
 * card `☾ 4 moving for Q12 · 1.4s`, and either adds an amber *lit early*. Nothing while nothing
 * moves, which is every stopped stack. No controls.
 */
export function StackMovingChip({ stackId, detail = false, className }: { stackId: number; detail?: boolean; className?: string }) {
  const heads = useMoveInDarkHeads(stackId)
  const now = useMemo(() => movingNow(heads), [heads])
  const nowMs = useNow(detail && now.endsAtMs != null, now.endsAtMs ?? undefined, 200)
  return <MovingNowChips now={now} nowMs={nowMs} detail={detail} className={className} />
}
