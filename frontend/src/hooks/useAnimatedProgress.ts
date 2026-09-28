import { useEffect, useReducer } from 'react'
import type { RunnerAnimSpan } from '../store/runnerSlice'

/**
 *
 * The runner slice stores `(startMs, durationMs)` per animation instead of streaming progress
 * through Redux — dispatching per rAF made every `selectStackRunner` subscriber re-render 60×/s
 * and put four deep-scanned slices in front of the dev invariant middleware per frame. So the
 * frame-rate part lives here: an rAF loop that only forces a local re-render, with progress
 * computed at render time from the descriptor. Only the components that actually draw an
 * animation pay for it.
 *
 * Returns null when there is nothing to animate, else progress clamped to 0..1. The loop stops
 * itself once the span's end has passed; the final render reports exactly 1. Pass a *stable*
 * Milliseconds left in an animation span, quantised to ~10 Hz — for countdown *text*, not motion.
 *
 * `useAnimatedProgress` below re-renders its host per rAF, which is right for a progress bar and
 * 6× too often for the ShowBar's `0.1 s`-resolution FADING readout. This one ticks a 100 ms
 * interval instead, so a memoized bar re-renders ten times a second during a fade and not at all
 * outside one. Same contract otherwise: pass the slice's own write-once descriptor (the effect
 * keys on its identity), get null when there is nothing counting down.
 */
export function useFadeRemainMs(span: RunnerAnimSpan | null): number | null {
  const [, bump] = useReducer((c: number) => c + 1, 0)

  useEffect(() => {
    if (span == null || span.durationMs <= 0) return
    if (performance.now() - span.startMs >= span.durationMs) return
    const id = setInterval(() => {
      bump()
      // Self-stop once the span has run out — the final bump renders the null below, and the
      // descriptor itself may outlive the fade by a moment (markDone clears it via the store).
      if (performance.now() - span.startMs >= span.durationMs) clearInterval(id)
    }, 100)
    return () => clearInterval(id)
  }, [span])

  if (span == null || span.durationMs <= 0) return null
  const remain = span.durationMs - (performance.now() - span.startMs)
  return remain > 0 ? remain : null
}

/**
 * Drives one component at frame rate from a write-once animation descriptor.
 *
 * The runner slice stores `(startMs, durationMs)` per animation instead of streaming progress
 * through Redux — dispatching per rAF made every `selectStackRunner` subscriber re-render 60×/s
 * and put four deep-scanned slices in front of the dev invariant middleware per frame. So the
 * frame-rate part lives here: an rAF loop that only forces a local re-render, with progress
 * computed at render time from the descriptor. Only the components that actually draw an
 * animation pay for it.
 *
 * Returns null when there is nothing to animate, else progress clamped to 0..1. The loop stops
 * itself once the span's end has passed; the final render reports exactly 1. Pass a *stable*
 * descriptor (the slice's own object, not a per-render literal) — the effect keys on its
 * identity.
 */
export function useAnimatedProgress(span: RunnerAnimSpan | null): number | null {
  const [, bump] = useReducer((c: number) => c + 1, 0)

  useEffect(() => {
    if (span == null || span.durationMs <= 0) return
    if (performance.now() - span.startMs >= span.durationMs) return
    let frame = requestAnimationFrame(function tick(t) {
      bump()
      if (t - span.startMs < span.durationMs) {
        frame = requestAnimationFrame(tick)
      }
    })
    return () => cancelAnimationFrame(frame)
  }, [span])

  if (span == null) return null
  // A zero-length span never gets written today (both drivers guard on duration > 0), but the
  // honest answer for one would be "already finished".
  if (span.durationMs <= 0) return 1
  // Sampling the clock at render time is deliberate, not an oversight: progress is a pure
  // function of the span and the clock, so it can never go stale when the span changes between
  // renders — storing the last tick's value in state could. The double render StrictMode does
  // yields two near-identical clamped samples, and the committed one self-heals next frame.
  return Math.min(Math.max((performance.now() - span.startMs) / span.durationMs, 0), 1)
}
