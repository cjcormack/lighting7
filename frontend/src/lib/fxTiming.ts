/**
 * An effect's timing (venue-visit plan D7): it follows a speed master, or it runs on *Seconds* — its
 * own cycle, with no master. The desk owns the rule (lighting7 `fx/FxTimingChoice.kt`); this is the
 * reading the editor and the chips need, and nothing here resolves a tempo the desk would not.
 *
 * - A **beat** effect's `cycle` is beats of its speed master. On Seconds (`seconds: true`)
 *   the same field is its cycle in seconds and no master is followed.
 * - A **wall-clock** effect's division is seconds already. Its Seconds is having no rate master — a
 *   stored row may carry the flag or not — and a master is its rate master, which scales the cycle
 *   by `bpm / 120`.
 */

import type { FxDirection } from '@/api/fxApi'

/** The fields a stored or running effect carries its timing in. */
export interface FxTimingFields {
  seconds?: boolean | null
  rateSpeedMasterUuid?: string | null
}

/**
 * D7's reading: does this effect follow no master? [timingSource] is the effect type's — the
 * library entry's, a palette's resolved one — or a running effect's loop, which reads `WALL_CLOCK`
 * for a beat effect on Seconds; the flag says so either way.
 */
export function isOnSeconds(effect: FxTimingFields, timingSource: string | null | undefined): boolean {
  return effect.seconds === true || (timingSource === 'WALL_CLOCK' && effect.rateSpeedMasterUuid == null)
}

/** The desk's `MasterClock` resolution, the grid a division converted back into beats lands on. */
const TICKS_PER_BEAT = 24

/** A cycle of [beats] at [bpm], in seconds — what keeps an effect's speed when it moves onto Seconds. */
export function beatsToSeconds(beats: number, bpm: number): number {
  return bpm > 0 ? round3((beats * 60) / bpm) : beats
}

/**
 * The inverse: a cycle of [seconds] as beats at [bpm] — what keeps its speed when it moves back.
 *
 * Snapped to the clock's 24 ticks a beat when it lands within what a millisecond's rounding can
 * move it (half a millisecond is 0.0025 beats even at 300 BPM), because the seconds it reads were
 * rounded to the millisecond on the way out: one bar at 90 BPM is 2.667 s,
 * which read back at 90 is 4.0005 beats and at 120 is 5.334 — the round trip must give 4 and 5.333.
 */
export function secondsToBeats(seconds: number, bpm: number): number {
  if (bpm <= 0) return seconds
  const beats = (seconds * bpm) / 60
  const ticks = Math.round(beats * TICKS_PER_BEAT)
  return ticks > 0 && Math.abs(beats - ticks / TICKS_PER_BEAT) < 0.003 ? round3(ticks / TICKS_PER_BEAT) : round3(beats)
}

function round3(n: number): number {
  return Math.round(n * 1000) / 1000
}

/**
 * A new effect's division (venue-visit plan D16): one bar. A wall-clock effect reads it as four
 * seconds, which is where its own Seconds starts.
 */
export const NEW_FX_DIVISION = 4

/** What an FX type says a new one of it runs at: the library entry's two defaults. */
export interface FxDefaults {
  defaultCycle?: number
  defaultDirection?: string
}

/**
 * A new FX's division: its type's own default (half a bar for the FX that bounce, terminology plan
 * D24), else [NEW_FX_DIVISION].
 */
export function newFxDivision(entry: FxDefaults | null | undefined): number {
  return entry?.defaultCycle ?? NEW_FX_DIVISION
}

/** A new FX's Direction: its type's own default, Bounce for the FX that bounce, else Forward. */
export function newFxDirection(entry: FxDefaults | null | undefined): FxDirection {
  const named = entry?.defaultDirection
  return named === 'BACKWARD' || named === 'BOUNCE' ? named : 'FORWARD'
}

/**
 * The division and Direction an FX swapped for another type keeps: each carries over, unless it
 * still stands at the old type's own default, when it takes the new type's — so a Sine Wave swapped
 * for a Sweep bounces at half a bar, as a new Sweep does, and a division or Direction the operator
 * chose stays theirs. [sameUnit] false (a beat FX for a wall-clock one) starts the division afresh.
 */
export function swappedTiming(
  prev: { cycle?: number | null; direction?: FxDirection | null },
  from: FxDefaults | null | undefined,
  to: FxDefaults | null | undefined,
  sameUnit: boolean,
): { cycle: number; direction: FxDirection } {
  const division = prev.cycle
  const direction = prev.direction ?? 'FORWARD'
  return {
    cycle:
      !sameUnit || division == null || division === newFxDivision(from) ? newFxDivision(to) : division,
    direction: direction === newFxDirection(from) ? newFxDirection(to) : direction,
  }
}
