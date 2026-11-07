/**
 * Tap tempo counted in this window, by the desk's `TapTempo` rules (fx-speed plan P2): the intervals
 * between the last four taps averaged, any tap more than two seconds older than the newest dropped,
 * nothing until there are two. Pinned to the desk's by `src/test/resources/fx/fxSpeed.fixture.json`.
 *
 * Only for a tempo no desk clock holds — an FX's Own tempo, a cue's *Tempo on GO* row. A tap on a
 * running master is the desk's to count.
 */
export interface TapCounter {
  /** A tap at [now] (ms); the tempo the taps so far give, or null. */
  tap(now?: number): number | null
}

export function createTapCounter(maxHistory = 4, timeoutMs = 2000): TapCounter {
  let times: number[] = []
  return {
    tap(now = performance.now()) {
      times = times.filter((t) => now - t <= timeoutMs)
      times.push(now)
      while (times.length > maxHistory) times.shift()
      if (times.length < 2) return null
      const intervals = times.slice(1).map((t, i) => t - times[i])
      const average = intervals.reduce((a, b) => a + b, 0) / intervals.length
      return average > 0 ? 60_000 / average : null
    },
  }
}
