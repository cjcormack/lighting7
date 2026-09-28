import type { PointerEvent } from 'react'
import { cn } from '@/lib/utils'
import type { CueAnchorDto, Rect } from '../../api/promptBooksApi'
import { MARKER_LANE_X, marginRailStyle, rectToStyle } from '../../lib/promptBook/geometry'

/**
 * A cue's run status, driving the anchor's colour language (mirrors the Run view):
 * live=green, next=blue, standby=amber, done=grey. `next` is the cue armed to fire
 * on the next GO; everything after it that hasn't fired is `standby`.
 */
export type CueRunStatus = 'live' | 'next' | 'standby' | 'done'

/** Per-status treatment. Wash reads as a highlighter over the white PDF page. */
const statusStyles: Record<CueRunStatus, { band: string; wash: string; chip: string }> = {
  live: { band: 'bg-emerald-500', wash: 'bg-emerald-400/20', chip: 'bg-emerald-500 text-white' },
  next: { band: 'bg-sky-500', wash: 'bg-sky-400/20', chip: 'bg-sky-500 text-white' },
  standby: { band: 'bg-amber-500', wash: 'bg-amber-400/15', chip: 'bg-amber-500 text-white' },
  done: { band: 'bg-slate-400', wash: 'bg-slate-400/10', chip: 'bg-slate-500 text-white' },
}

/**
 * On-page highlighter wash over the cued line(s). Non-interactive — the drag
 * handle and label live in the margin (see {@link CueMarginMarker}).
 */
export function CueWash({
  rects,
  status,
  isLive,
}: {
  rects: Rect[]
  status: CueRunStatus
  isLive: boolean
}) {
  const s = statusStyles[status]
  return (
    <>
      {rects.map((rect, i) => (
        <div
          key={i}
          style={rectToStyle(rect)}
          className={cn(
            'rounded-sm',
            s.wash,
            isLive && 'shadow-[0_0_0_1px_rgba(16,185,129,0.5),0_0_14px_rgba(16,185,129,0.3)]',
          )}
        />
      ))}
    </>
  )
}

/**
 * Cue marker in the fixed left-margin rail: a solid colour accent band the height
 * of the cued region, with the cue chip beside it (overflowing left into the paper
 * gutter). Anchored to {@link MARKER_LANE_X} so every cue/cut marker lines up
 * vertically, independent of where each region's text starts. This is the drag
 * handle when unlocked; the live cue's chip pulses and shows a status dot.
 */
export function CueMarginMarker({
  anchor,
  label,
  rects,
  status,
  hasWarning,
  locked,
  dragging,
  laneX = MARKER_LANE_X,
  onPointerDown,
}: {
  anchor: CueAnchorDto
  /** Live cue label from the cue stack; falls back to the anchor's cached label. */
  label: string | null
  rects: Rect[]
  status: CueRunStatus
  hasWarning: boolean
  locked: boolean
  dragging: boolean
  /** Normalized x of the shared margin rail (just left of the page's text block). */
  laneX?: number
  onPointerDown: (e: PointerEvent<HTMLDivElement>) => void
}) {
  const s = statusStyles[status]
  const isLive = status === 'live'
  return (
    <div
      data-anchor-cue={anchor.cueId}
      onPointerDown={onPointerDown}
      title={locked ? undefined : 'Drag to nudge — re-anchor by selecting the new text'}
      // Right edge sits in the shared margin lane just left of the text; the box
      // grows leftward to fit the chip, overflowing into the paper gutter.
      style={marginRailStyle(rects, laneX)}
      className={cn(
        'flex items-start',
        locked ? 'pointer-events-none' : 'pointer-events-auto cursor-grab',
        dragging && 'cursor-grabbing',
      )}
    >
      <span
        style={isLive ? { animation: 'r-live-pulse 1.6s ease-in-out infinite' } : undefined}
        className={cn(
          'mr-1 inline-flex items-center gap-1 rounded-md px-1.5 py-0.5 font-mono text-[11px] leading-none font-bold whitespace-nowrap shadow-sm',
          hasWarning ? 'bg-red-500 text-white' : s.chip,
          isLive && 'ring-2 ring-white/90',
        )}
      >
        {isLive && <span className="size-1 rounded-full bg-white/90" />}
        {label ?? `#${anchor.cueId}`}
        {hasWarning && ' ▲'}
      </span>
      <span
        className={cn(
          'h-full w-[3px] shrink-0 rounded-full',
          hasWarning ? 'bg-red-500' : s.band,
          isLive && 'shadow-[0_0_8px_rgba(16,185,129,0.6)]',
        )}
      />
    </div>
  )
}
