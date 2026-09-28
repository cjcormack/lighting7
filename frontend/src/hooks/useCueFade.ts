import { useSelector } from 'react-redux'
import { runnerSlice, selectStackRunner } from '../store/runnerSlice'
import { useAnimatedProgress } from './useAnimatedProgress'

type RunnerRoot = { runner: ReturnType<typeof runnerSlice.getInitialState> }

/**
 * A single cue row's fade state, read straight from the runner rather than passed down.
 *
 * The fade is the only value on a cue row that changes at frame rate, and prop-drilling it would
 * defeat the reason `ShowView` is memoized in the first place — "so memo keeps the whole editor
 * subtree from reconciling ~60x/sec while a cue fades". A stack is several hundred rows; re-rendering
 * all of them to animate one is the cost that memo was put there to avoid, and threading
 * `fadeProgress` through would reintroduce it with the memo still in place, looking effective.
 *
 * So each row asks for its own. The store only carries the fade's *descriptor* (written once per
 * transition — see the runner slice), so the selectors below return stable references for every row
 * that is not fading and those rows never re-render. The row that is fading animates itself from
 * the descriptor via `useAnimatedProgress` — the frame-rate work stays inside this one component.
 *
 * `stackId` is null for a stack that is not the playhead: a stack being merely read has no fade,
 * and the runner state of the stack that *is* running says nothing about its rows.
 */
export function useCueFade(
  stackId: number | null | undefined,
  cueId: number,
  fadeDurationMs: number | null,
) {
  // `activeCueId` is the optimistic cursor: set the instant GO is pressed, cleared when the fade
  // completes. That is exactly the window a fade should be drawn in.
  const isLive = useSelector((state: RunnerRoot) => {
    if (stackId == null) return false
    return selectStackRunner(state, stackId).activeCueId === cueId
  })
  const fade = useSelector((state: RunnerRoot) => {
    if (stackId == null) return null
    const runner = selectStackRunner(state, stackId)
    if (runner.activeCueId !== cueId) return null
    return runner.fade?.cueId === cueId ? runner.fade : null
  })

  const animated = useAnimatedProgress(fade)
  // Live but no descriptor yet (GO landed, the animation effect hasn't) reads as 0, exactly as the
  // old reset-to-0-on-go did; a computed 1 reads as "not fading", as the old `< 1` gate did.
  const fadeProgress = !isLive ? null : animated == null ? 0 : animated < 1 ? animated : null

  const fadeRemainMs =
    fadeProgress == null || fadeDurationMs == null || fadeDurationMs <= 0
      ? null
      : Math.max(0, fadeDurationMs * (1 - fadeProgress))

  return { fadeProgress, fadeRemainMs }
}

/**
 * A single cue row's auto-advance countdown, read straight from the runner — the auto-advance
 * sibling of `useCueFade` above, for the same reason: a row that reads its own descriptor doesn't
 * force whatever memo sits above it to reconcile at frame rate for a countdown only one row draws.
 *
 * `auto` carries no `cueId` of its own (only one cue per stack can be counting down), so the
 * gate is `runner.activeCueId === cueId` rather than a per-descriptor id check.
 */
export function useCueAutoProgress(stackId: number | null | undefined, cueId: number) {
  const auto = useSelector((state: RunnerRoot) => {
    if (stackId == null) return null
    const runner = selectStackRunner(state, stackId)
    return runner.activeCueId === cueId ? runner.auto : null
  })

  return useAnimatedProgress(auto)
}
