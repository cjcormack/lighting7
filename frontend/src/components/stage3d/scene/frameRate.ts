import { SLOW_FRAME_MS } from './hazeGovernor'

/**
 * The frame-rate readout's arithmetic, pure. On a demand canvas a rate means something only while
 * frames are drawn: fps counts the frames in the trailing second, ms is the median gap between frames
 * less than a second apart, and a second with no frame is *idle*. It counts frames drawn, not GPU time.
 */

/** The trailing window, and the gap that ends a run. */
export const FRAME_WINDOW_MS = 1000
/** How often the readout may write its text: at most four times a second. */
export const READOUT_INTERVAL_MS = 250
/** Past this a frame reads slow — the haze governor's own step-down line. */
export const SLOW_READOUT_MS = SLOW_FRAME_MS
/** Frame times kept: a trailing second at 240 Hz, with room. */
const CAPACITY = 256

export interface FrameRateReading {
  fps: number
  /** The median gap between frames less than a second apart; null with only one frame to go on. */
  ms: number | null
}

export class FrameRate {
  private readonly times = new Float64Array(CAPACITY)
  private size = 0
  private head = 0

  /** A frame was drawn at [now] (ms). */
  frame(now: number): void {
    this.times[this.head] = now
    this.head = (this.head + 1) % CAPACITY
    if (this.size < CAPACITY) this.size++
  }

  /** When the last frame was drawn, or null before any. */
  lastFrameAt(): number | null {
    return this.size === 0 ? null : this.at(this.size - 1)
  }

  /** Frames drawn in the second up to [now]. */
  fps(now: number): number {
    let count = 0
    for (let i = this.size - 1; i >= 0; i--) {
      if (now - this.at(i) >= FRAME_WINDOW_MS) break
      count++
    }
    return count
  }

  /**
   * The median gap between the frames drawn in the second up to the last one, so a run that follows
   * an idle spell is judged on its own frames. Null with fewer than two.
   */
  msPerFrame(): number | null {
    const last = this.lastFrameAt()
    if (last == null) return null
    const gaps: number[] = []
    for (let i = this.size - 1; i > 0; i--) {
      const earlier = this.at(i - 1)
      if (last - earlier >= FRAME_WINDOW_MS) break
      gaps.push(this.at(i) - earlier)
    }
    if (gaps.length === 0) return null
    gaps.sort((a, b) => a - b)
    const mid = gaps.length >> 1
    return gaps.length % 2 === 1 ? gaps[mid] : (gaps[mid - 1] + gaps[mid]) / 2
  }

  /** Nothing drawn for a second up to [now], or nothing drawn at all. */
  idle(now: number): boolean {
    const last = this.lastFrameAt()
    return last == null || now - last >= FRAME_WINDOW_MS
  }

  /** What the readout says at [now]: null when idle. */
  reading(now: number): FrameRateReading | null {
    if (this.idle(now)) return null
    return { fps: this.fps(now), ms: this.msPerFrame() }
  }

  /** The [i]th frame time, oldest first. */
  private at(i: number): number {
    return this.times[(this.head - this.size + i + CAPACITY) % CAPACITY]
  }
}

/** Whether a reading is past the governor's line. */
export function isSlowReading(reading: FrameRateReading | null): boolean {
  return reading?.ms != null && reading.ms > SLOW_READOUT_MS
}

/** `58 fps · 17.2 ms`, or `idle`. */
export function formatFrameRate(reading: FrameRateReading | null): string {
  if (reading == null) return 'idle'
  return reading.ms == null ? `${reading.fps} fps` : `${reading.fps} fps · ${reading.ms.toFixed(1)} ms`
}

export function sameReading(a: FrameRateReading | null, b: FrameRateReading | null): boolean {
  return a === b || (a != null && b != null && a.fps === b.fps && a.ms === b.ms)
}
