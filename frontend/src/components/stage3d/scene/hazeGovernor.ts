/**
 * **Haze degrades before frame rate** (stage-view plan §10's rule; its numbers are for the §10
 * measurement pass to fix). The raymarched beam volumes are the costliest thing the Stage view
 * draws per pixel, and the least essential: a gobo through fewer march steps is grainier, not wrong.
 * So while the canvas is drawing a continuous run — an orbit easing, a gobo spinning, a macro
 * running — this watches the time between frames, and when the run is slower than [SLOW_FRAME_MS]
 * it steps the march down a tier; when it has been faster than [FAST_FRAME_MS] for a while, back up.
 * The surfaces' light budget is the viewer's choice and is never touched here.
 *
 * **Only a continuous run is measured.** The canvas renders on demand, so the gap between two frames
 * is usually how long nothing moved, or how often a fader sent DMX — neither is the GPU's cost. A
 * frame counts only when the frame before it asked for it from inside the loop (R3F's
 * `internal.frames > 1` at the end of that frame): then the next one follows as soon as the browser
 * can draw it, and the gap is the frame time.
 *
 * Pure: the component that feeds it lives in `Stage3D`.
 */

export interface HazeQuality {
  /** 0 is full haze; each tier marches fewer steps. */
  tier: number
  /** Scales the volume march's steps (`VOLUMETRIC_STEPS`). */
  stepScale: number
}

export const HAZE_TIERS: readonly HazeQuality[] = [
  { tier: 0, stepScale: 1 },
  { tier: 1, stepScale: 0.66 },
  { tier: 2, stepScale: 0.42 },
  { tier: 3, stepScale: 0.25 },
]

/** A run averaging longer than this between frames (~36 fps) gives up a tier of haze. */
export const SLOW_FRAME_MS = 28
/** A run averaging shorter than this (~52 fps) for [RECOVER_AFTER_MS] takes a tier back. */
export const FAST_FRAME_MS = 19
/** Frames a tier is held before it may change again, so one hitch does not step it. */
export const MIN_SAMPLES = 24
export const RECOVER_AFTER_MS = 3000
/**
 * A gap longer than this is a pause in the run — a hidden tab, a debugger — not a frame. It is
 * deliberately far above any frame worth governing: a software renderer (Chromium's SwiftShader,
 * measured at ~700 ms a frame with the whole rig lit) is exactly the case the rule is for, and a
 * cap under its frame time read every one of its frames as a pause and never gave up a tier.
 */
export const MAX_SAMPLE_MS = 1000
const EMA = 0.15

export class HazeGovernor {
  tier = 0
  /** The smoothed frame time of the current run, ms; NaN before any sample. */
  average = Number.NaN
  private samples = 0
  private fastSince: number | null = null

  get quality(): HazeQuality {
    return HAZE_TIERS[this.tier]
  }

  /**
   * One frame of a continuous run took [ms]; [now] is a clock in ms. Answers the new quality when
   * the tier moved, else null.
   */
  sample(ms: number, now: number): HazeQuality | null {
    if (!(ms > 0) || ms > MAX_SAMPLE_MS) return null
    this.average = Number.isNaN(this.average) ? ms : this.average + EMA * (ms - this.average)
    this.samples++
    if (this.samples < MIN_SAMPLES) return null
    if (this.average > SLOW_FRAME_MS && this.tier < HAZE_TIERS.length - 1) {
      return this.step(this.tier + 1)
    }
    if (this.average < FAST_FRAME_MS && this.tier > 0) {
      this.fastSince ??= now
      if (now - this.fastSince >= RECOVER_AFTER_MS) return this.step(this.tier - 1)
    } else {
      this.fastSince = null
    }
    return null
  }

  /**
   * The continuous run ended — the frame before this one did not ask for it. What was measured of
   * that run is dropped: the next run is judged on its own frames, and the recovery timer does not
   * count through the idle time between the two. The tier itself is kept.
   */
  endRun(): void {
    this.samples = 0
    this.fastSince = null
    this.average = Number.NaN
  }

  private step(tier: number): HazeQuality {
    this.tier = tier
    this.samples = 0
    this.fastSince = null
    // The average carried over would step again the moment the tier's samples were in.
    this.average = Number.NaN
    return HAZE_TIERS[tier]
  }
}
