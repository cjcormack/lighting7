/**
 * The 3D scene's clock for an **animated colour band** (fixture-optics plan D8, `lib/colourBands.ts`):
 * a scroll or random band on a colour wheel changes the picture with no channel moving, so on the
 * canvas's `demand` frameloop something has to ask for every frame while one is live — and only
 * while one is.
 *
 * `ColourSync` is imperative (it writes the scene from a channel callback, never through React), so
 * its arms register with this ticker while their band animates and unregister when it stops;
 * `FixtureModel` drives the ticker from a `useFrame`, handing it the scene clock's time. Each frame
 * with a listener runs every listener — they re-apply the colour at the new time — and then asks
 * for the next frame. With none, a frame does nothing and asks for nothing, so a wheel on a fixed
 * colour costs a canvas no frames at all.
 *
 * A plain object rather than a hook, so the test drives it with no canvas: `ColourSync` is rendered
 * outside one there, and `useFrame` cannot be.
 */
export interface ColourTicker {
  /** The scene's time in seconds, as of the last frame. */
  now(): number
  /**
   * Run [onFrame] on every rendered frame until the returned function is called, asking for each
   * next frame while any listener is registered. Registering asks for a frame at once, so the loop
   * starts without waiting for something else to draw.
   */
  onFrame(onFrame: () => void): () => void
}

export interface DrivenColourTicker extends ColourTicker {
  /** Called once per rendered frame with the scene clock's elapsed seconds. */
  frame(timeS: number): void
  /** How many listeners are registered — for the test. */
  readonly listening: number
}

export function createColourTicker(invalidate: () => void): DrivenColourTicker {
  const listeners = new Set<() => void>()
  let time = 0
  return {
    now: () => time,
    onFrame(onFrame) {
      listeners.add(onFrame)
      invalidate()
      return () => {
        listeners.delete(onFrame)
      }
    },
    frame(timeS) {
      time = timeS
      if (listeners.size === 0) return
      for (const l of [...listeners]) l()
      invalidate()
    },
    get listening() {
      return listeners.size
    },
  }
}

/** A ticker that never ticks: time stands at 0 and nothing animates. The default outside a canvas. */
export const STILL_COLOUR_TICKER: ColourTicker = {
  now: () => 0,
  onFrame: () => () => {},
}
