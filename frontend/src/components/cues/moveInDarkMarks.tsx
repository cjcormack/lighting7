import type { ReactNode } from 'react'
import { Moon, TriangleAlert } from 'lucide-react'
import { cn } from '@/lib/utils'
import { LIVE_REASON_LABEL, remainingLabel, type DarkCell, type MovingNow } from '@/lib/moveInDark'

/**
 * Move in dark's one look (lighting7 move-in-dark design §5): indigo with a dashed edge and a moon
 * means *moved in dark* and nothing else, beside the live green, next blue and warning amber the desk
 * already draws. ▲ in amber is a head that moves while it is lit.
 */
export const MOVE_IN_DARK_CHIP_CLASS = 'border border-dashed border-indigo-400/60 bg-indigo-500/10 text-indigo-300'
export const MOVES_LIT_CHIP_CLASS = 'border border-amber-500/50 bg-amber-500/10 text-amber-300'

export function DarkChip({ children, lit = false, className, title }: { children: ReactNode; lit?: boolean; className?: string; title?: string }) {
  return (
    <span
      title={title}
      className={cn(
        'inline-flex shrink-0 items-center gap-1 rounded px-1.5 text-[10px] leading-4 whitespace-nowrap',
        lit ? MOVES_LIT_CHIP_CLASS : MOVE_IN_DARK_CHIP_CLASS,
        className,
      )}
    >
      {lit ? <TriangleAlert className="size-2.5" aria-hidden /> : <Moon className="size-2.5" aria-hidden />}
      {children}
    </span>
  )
}

/**
 * One cue's *Dark* read-out (design §5.2): `☾ 6` heads set in the dark for it, `▲ 1` that move lit
 * (with the reason where one is the rest's), or `Off · 4 live`. [wordClass] folds the words on a
 * narrow host, leaving the glyphs and counts.
 */
export function DarkCellFace({ cell, wordClass }: { cell: DarkCell; wordClass?: string }) {
  if (cell.off) {
    return <DarkChip lit>{cell.lit > 0 ? <>Off<span className={wordClass}> · {cell.lit} live</span></> : 'Off'}</DarkChip>
  }
  return (
    <span className="inline-flex min-w-0 items-center gap-1">
      {cell.set > 0 && <DarkChip>{cell.set}</DarkChip>}
      {cell.lit > 0 && (
        <DarkChip lit>
          {cell.lit}
          {cell.reasons.length === 1 && <span className={wordClass}> {LIVE_REASON_LABEL[cell.reasons[0]]}</span>}
        </DarkChip>
      )}
    </span>
  )
}

/**
 * What a stack moves now, as one chip: `☾ 4 moving`, with [detail] the cue they move for and the
 * time left (`for Q12 · 1.4s`), which then needs [nowMs] to tick; and an amber `lit early` beside it.
 */
export function MovingNowChips({ now, nowMs, detail = true, className }: { now: MovingNow; nowMs: number; detail?: boolean; className?: string }) {
  if (now.moving === 0 && now.litEarly === 0) return null
  const left = detail ? remainingLabel(now.endsAtMs, nowMs) : null
  return (
    <span className={cn('inline-flex min-w-0 items-center gap-1', className)} data-moving-now>
      {now.moving > 0 && (
        <DarkChip>
          {now.moving} moving
          {detail && now.label ? ` for ${now.label}` : ''}
          {left ? ` · ${left}` : ''}
        </DarkChip>
      )}
      {now.litEarly > 0 && <DarkChip lit>{now.litEarly} lit early</DarkChip>}
    </span>
  )
}
