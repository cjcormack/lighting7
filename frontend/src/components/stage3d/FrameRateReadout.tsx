import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { useFrame } from '@react-three/fiber'
import { cn } from '@/lib/utils'
import {
  FRAME_WINDOW_MS,
  FrameRate,
  READOUT_INTERVAL_MS,
  formatFrameRate,
  isSlowReading,
  type FrameRateReading,
} from './scene/frameRate'
import type { StageStatsStore } from './scene/stageStats'

/**
 * The frame-rate readout: a probe in the frame loop and a chip over the canvas, joined by the
 * canvas's stats store. **It never asks for a frame**, or a demand canvas would draw forever to
 * measure itself: the probe writes the store at most four times a second, the chip paints its own
 * DOM node from the store, and the trailing write and the flip to *idle* are timers.
 */

/** The probe's state, outside React: the frame times, the last write, the two timers. */
class FrameRateFeed {
  private readonly rate = new FrameRate()
  private lastWrite = Number.NEGATIVE_INFINITY
  private idleTimer: ReturnType<typeof setTimeout> | null = null
  private trailingTimer: ReturnType<typeof setTimeout> | null = null
  stats: StageStatsStore

  constructor(stats: StageStatsStore, private readonly clock: () => number) {
    this.stats = stats
  }

  frame(): void {
    const now = this.clock()
    this.rate.frame(now)
    if (this.idleTimer == null) this.armIdle(FRAME_WINDOW_MS)
    const wait = this.lastWrite + READOUT_INTERVAL_MS - now
    if (wait <= 0) this.write(now)
    // A frame inside the interval lands when it ends, so the last frames of a burst are shown.
    else this.trailingTimer ??= setTimeout(() => this.write(this.clock()), wait)
  }

  dispose(): void {
    this.clearTrailing()
    if (this.idleTimer != null) clearTimeout(this.idleTimer)
    this.idleTimer = null
  }

  private write(now: number): void {
    this.clearTrailing()
    this.lastWrite = now
    this.stats.setFrameRate(this.rate.reading(now))
  }

  private clearTrailing(): void {
    if (this.trailingTimer != null) clearTimeout(this.trailingTimer)
    this.trailingTimer = null
  }

  /** One timer a run, re-armed for whatever is left of the second since the last frame. */
  private armIdle(delay: number): void {
    this.idleTimer = setTimeout(() => {
      this.idleTimer = null
      const now = this.clock()
      if (this.rate.idle(now)) {
        this.clearTrailing()
        this.lastWrite = Number.NEGATIVE_INFINITY
        this.stats.setFrameRate(null)
      } else {
        this.armIdle(Math.max(0, (this.rate.lastFrameAt() ?? now) + FRAME_WINDOW_MS - now))
      }
    }, delay)
  }
}

export function FrameRateProbe({
  stats,
  clock = performanceNow,
}: {
  stats: StageStatsStore
  /** The clock frames are timed by; a test passes its own. */
  clock?: () => number
}) {
  const [feed] = useState(() => new FrameRateFeed(stats, clock))
  feed.stats = stats
  useEffect(
    () => () => {
      feed.dispose()
      stats.setFrameRate(null)
    },
    [feed, stats],
  )
  useFrame(() => feed.frame(), 2)
  return null
}

function performanceNow(): number {
  return performance.now()
}

/** Writes [reading] into the chip, touching the DOM only for what changed. */
export function paintFrameRate(el: HTMLElement, reading: FrameRateReading | null): void {
  const text = formatFrameRate(reading)
  if (el.textContent !== text) el.textContent = text
  const slow = isSlowReading(reading) ? 'true' : 'false'
  if (el.dataset.slow !== slow) el.dataset.slow = slow
}

/**
 * The chip where it is drawn: only while this window has the readout on, never while the context is
 * lost (the paused card owns the canvas) and never in a `render_view` capture, which draws the stage
 * and nothing over it.
 */
export function FrameRateReadout({
  stats,
  on,
  contextLost,
  capturing,
  raised,
  onOpen,
}: {
  stats: StageStatsStore | null
  on: boolean
  contextLost: boolean
  capturing: boolean
  raised: boolean
  onOpen?: () => void
}) {
  if (stats == null || !on || contextLost || capturing) return null
  return <FrameRateChip stats={stats} raised={raised} onOpen={onOpen} />
}

/**
 * The chip, in the canvas's bottom-left corner — one row up on a section in Edit, above the section
 * HUD's cursor strip. Clicking it opens the View popover on Performance.
 */
export function FrameRateChip({
  stats,
  raised,
  onOpen,
}: {
  stats: StageStatsStore
  raised: boolean
  onOpen?: () => void
}) {
  const ref = useRef<HTMLButtonElement | null>(null)
  useLayoutEffect(() => {
    const el = ref.current
    if (el == null) return
    const paint = () => paintFrameRate(el, stats.getSnapshot().frameRate)
    paint()
    return stats.subscribe(paint)
  }, [stats])
  return (
    <button
      ref={ref}
      type="button"
      data-stage-frame-rate
      onClick={onOpen}
      title="This canvas's frame rate — open Performance"
      className={cn(
        'absolute left-2 rounded-[5px] border border-transparent bg-background/80 px-[7px] py-0.5 font-mono text-[10.5px] tabular-nums text-muted-foreground backdrop-blur-sm',
        'data-[slow=true]:border-amber-500/45 data-[slow=true]:text-amber-500 dark:data-[slow=true]:text-amber-400',
        raised ? 'bottom-[34px]' : 'bottom-2',
      )}
    />
  )
}
