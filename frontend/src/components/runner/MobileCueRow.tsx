import { memo } from 'react'
import { cn } from '@/lib/utils'
import { formatFadeText } from '@/lib/cueUtils'
import { AUTO_CUE_NUMBER_CLASS } from '@/lib/cueNumber'
import { TruncateStart } from '@/components/TruncateStart'
import { useCueFade, useCueAutoProgress } from '@/hooks/useCueFade'
import { cueStatusIcon } from './cueStatusIcon'

interface MobileCueRowProps {
  cueId: number
  cueNumber: string | null
  /** Number was derived from position — rendered fainter to mark it provisional. */
  cueNumberAuto?: boolean
  name: string
  fadeDurationMs: number | null
  fadeCurve: string
  autoAdvance: boolean
  isActive: boolean
  isStandby: boolean
  isDone: boolean
  isTheatre: boolean
  /** The live stack id, or null when this row's stack isn't it — gates this row's own
   *  `useCueFade`/`useCueAutoProgress` subscriptions (see `useCueFade`). */
  fadeStackId: number | null
  onClick: (cueId: number) => void
}

/**
 * Memoized: the sheet can hold hundreds of these on a big show, and every one used to re-render
 * on every fade/auto-advance frame for a drilled `fadeProgress`/`autoProgress` pair that only the
 * active row ever used. Each row now reads its own via `fadeStackId`, and `onClick` takes the
 * row's own cue id so the sheet can pass its stable handler straight through.
 */
export const MobileCueRow = memo(function MobileCueRow({
  cueId,
  cueNumber,
  cueNumberAuto = false,
  name,
  fadeDurationMs,
  fadeCurve,
  autoAdvance,
  isActive,
  isStandby,
  isDone,
  isTheatre,
  fadeStackId,
  onClick,
}: MobileCueRowProps) {
  const { fadeProgress } = useCueFade(fadeStackId, cueId, fadeDurationMs)
  const autoProgress = useCueAutoProgress(fadeStackId, cueId)
  const showFadeBar = fadeProgress != null && fadeProgress > 0 && autoProgress == null
  const showAutoBar = autoProgress != null
  const statusIcon = cueStatusIcon(isActive, isStandby, isDone, autoProgress)
  const fadeText = formatFadeText(fadeDurationMs, fadeCurve)

  return (
    <button
      type="button"
      onClick={() => onClick(cueId)}
      className={cn(
        'relative flex w-full items-center gap-2 h-12 px-4 border-b border-l-[3px] border-l-transparent text-left transition-colors hover:bg-muted/50',
        isDone && !isActive && !isStandby && 'opacity-40',
        isActive && 'border-l-green-500 bg-green-500/[0.08]',
        isStandby && !isActive && 'border-l-blue-500 bg-blue-500/[0.06]',
      )}
    >
      {/* Fade progress bar */}
      {showFadeBar && (
        <div
          className="absolute bottom-0 left-0 h-0.5 bg-gradient-to-r from-green-700 to-green-400 shadow-[0_0_8px_rgba(72,200,96,0.55)]"
          style={{ width: `${(fadeProgress * 100).toFixed(2)}%` }}
        />
      )}
      {/* Auto-advance progress bar */}
      {showAutoBar && (
        <div
          className="absolute bottom-0 left-0 h-0.5 bg-gradient-to-r from-blue-700 to-blue-400 shadow-[0_0_8px_rgba(88,144,240,0.55)]"
          style={{ width: `${((autoProgress ?? 0) * 100).toFixed(2)}%` }}
        />
      )}

      {/* Status */}
      <div className="w-5 shrink-0 flex items-center justify-center">{statusIcon}</div>

      {/* Q-number (theatre only) — clipped at the START so a long number keeps its tail. */}
      {isTheatre && (
        <TruncateStart
          text={cueNumber ? `Q${cueNumber}` : ''}
          title={cueNumber ? `Q${cueNumber}` : undefined}
          className={cn(
            'w-10 shrink-0 font-mono text-xs text-muted-foreground',
            cueNumberAuto && AUTO_CUE_NUMBER_CLASS,
          )}
        />
      )}

      {/* Name */}
      <div
        className={cn(
          'flex-1 text-sm font-medium text-foreground truncate min-w-0',
          isActive && 'text-green-300 font-semibold',
          isStandby && !isActive && 'text-blue-300 font-semibold',
        )}
      >
        {name}
      </div>

      {/* Fade */}
      <div className="shrink-0 font-mono text-[11px] text-muted-foreground">
        {fadeText}
      </div>

      {/* Auto indicator */}
      {autoAdvance && (
        <div
          className="size-1.5 rounded-full bg-blue-500 shrink-0"
          aria-label="auto-advance"
        />
      )}
    </button>
  )
})
