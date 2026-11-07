/**
 * An effect's timing (fx-speed plan D3, D4): it follows a speed master, or runs on its own clock.
 * The desk owns the rule (lighting7 `fx/FxTimingChoice.kt`); this is the reading the editor and the
 * chips need, and nothing here resolves a tempo the desk would not.
 *
 * - A **beat** effect's `cycle` is beats, of its speed master or of its own tempo, `ownBpm`
 *   (20–300), which it carries in place of a master.
 * - A **wall-clock** effect's `cycle` is seconds. With no rate master it is on *Natural*, a fixed
 *   120 BPM; a master is its rate master, which scales the cycle by `bpm / 120`. It never has an
 *   `ownBpm`.
 */

import type { FxDirection } from '@/api/fxApi'

/** The fields a stored or running effect carries its timing in. */
export interface FxTimingFields {
  ownBpm?: number | null
  rateSpeedMasterUuid?: string | null
}

/** The bounds of an Own tempo, the desk's. */
export const OWN_BPM_MIN = 20
export const OWN_BPM_MAX = 300
/** An Own tempo with no master's tempo to start from. */
export const OWN_BPM_DEFAULT = 120

/** A beat effect on its own tempo. */
export function isOnOwnTempo(effect: FxTimingFields): boolean {
  return effect.ownBpm != null
}

/**
 * Is [effect]'s `cycle` seconds? [timingSource] is the effect type's — the library entry's, a
 * palette's resolved one — or a running effect's loop, which reads `WALL_CLOCK` for a beat effect on
 * its own tempo too; its `ownBpm` tells the two apart.
 */
export function isWallClock(effect: FxTimingFields, timingSource: string | null | undefined): boolean {
  return timingSource === 'WALL_CLOCK' && effect.ownBpm == null
}

/** A wall-clock effect with no rate master: *Natural*. */
export function isOnNatural(effect: FxTimingFields, timingSource: string | null | undefined): boolean {
  return isWallClock(effect, timingSource) && effect.rateSpeedMasterUuid == null
}

/** Follows no master: on its own tempo, or on Natural. */
export function followsNoMaster(effect: FxTimingFields, timingSource: string | null | undefined): boolean {
  return isOnOwnTempo(effect) || isOnNatural(effect, timingSource)
}

/** A tempo as the chips say it: whole BPM, a tenth where it has one. */
export function bpmLabel(bpm: number): string {
  return String(Math.round(bpm * 10) / 10)
}

/** A typed Own tempo within the desk's bounds, a tenth of a BPM at most. */
export function clampOwnBpm(bpm: number): number {
  return Math.min(OWN_BPM_MAX, Math.max(OWN_BPM_MIN, Math.round(bpm * 10) / 10))
}

/** A new effect's division (venue-visit plan D16): one bar. A wall-clock effect reads it as four seconds. */
export const NEW_FX_DIVISION = 4

/** What an FX type says a new one of it runs at: the library entry's two defaults. */
export interface FxDefaults {
  defaultCycle?: number
  defaultDirection?: string
}

/**
 * What a picker preset starts at (fx-settings plan D4): its own cycle and Direction, else its FX's —
 * so the *Focus Pull* preset bounces at half a bar, as Focus Pull did.
 */
export function presetDefaults(
  entry: FxDefaults | null | undefined,
  preset: { cycle?: number | null; direction?: string | null } | null | undefined,
): FxDefaults {
  return {
    defaultCycle: preset?.cycle ?? entry?.defaultCycle,
    defaultDirection: preset?.direction ?? entry?.defaultDirection,
  }
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
