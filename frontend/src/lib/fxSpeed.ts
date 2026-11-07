/**
 * **Speed** (fx-speed plan D1, D2, D4): how fast an FX runs against its tempo, ×1 one cycle a bar,
 * each snap doubling or halving it. It is a view of the stored `cycle` — `4 ÷ cycle` on a beat FX
 * (cycle in beats, on a master or its Own tempo), `2 ÷ cycle` on a wall-clock FX (cycle in seconds,
 * on a rate master or Natural) — so ×1 is one bar at the running tempo on either, and nothing stored
 * changes.
 *
 * The one client module that speaks it: the editor's slider, typed value and readout, and every
 * chip's label. The rules the desk computes too — the snaps, the whole ticks a beat cycle runs as —
 * are pinned to the desk's by `src/test/resources/fx/fxSpeed.fixture.json` (P2).
 */

import { bpmLabel } from '@/lib/fxTiming'

/** A beat's master ticks, the desk's `MasterClock.TICKS_PER_BEAT`. */
export const TICKS_PER_BEAT = 24

/** How far a Speed may be typed (D1): a 2048-beat cycle to ⅛ beat. */
export const SPEED_MIN = 1 / 512
export const SPEED_MAX = 32

/** The slider's track, ×1/16 to ×16 on a log scale. */
export const TRACK_MIN = 1 / 16
export const TRACK_MAX = 16

/** The snaps the slider labels, and the ones it only ticks. */
export const LABELLED_SNAPS: readonly number[] = [0.25, 0.5, 1, 2, 4]
export const TICKED_SNAPS: readonly number[] = [1 / 16, 1 / 8, 8, 16]

/** How close to a power of two, in octaves, a drag sticks to it. */
const STICK_OCTAVES = 0.12

/** The Speed of an FX whose `cycle` is beats ([wallClock] false) or seconds. */
export function speedOf(cycle: number, wallClock: boolean): number {
  if (!(cycle > 0)) return 1
  return (wallClock ? 2 : 4) / cycle
}

/** [speed] within what may be typed. */
export function clampSpeed(speed: number): number {
  return Math.min(SPEED_MAX, Math.max(SPEED_MIN, speed))
}

/**
 * The `cycle` the editor writes for [speed]: a beat cycle rounded to whole ticks, which is what the
 * desk runs (`FxPhase.beatCycleTicks` truncates the rest); a wall-clock one to the millisecond.
 */
export function cycleOf(speed: number, wallClock: boolean): number {
  const s = clampSpeed(speed)
  if (wallClock) return Math.max(0.001, Math.round((2 / s) * 1000) / 1000)
  return Math.max(1, Math.round((4 / s) * TICKS_PER_BEAT)) / TICKS_PER_BEAT
}

/** The ticks a beat cycle of [cycle] beats runs as: the desk's `FxPhase.beatCycleTicks`, whole ticks landing on themselves. */
export function cycleTicks(cycle: number): number {
  return Math.trunc(cycle * TICKS_PER_BEAT + 1e-6)
}

/** The Speed that runs for [cycle]: a beat cycle's whole ticks, so ×5 reads ×5.05. */
export function runningSpeed(cycle: number, wallClock: boolean): number {
  if (wallClock) return speedOf(cycle, true)
  const ticks = cycleTicks(cycle)
  return ticks > 0 ? speedOf(ticks / TICKS_PER_BEAT, false) : speedOf(cycle, false)
}

/** Whether [n] is a power of two, the desk's `FxPhase.isSnappedCycle` (within a millionth of an octave). */
export function isPowerOfTwo(n: number): boolean {
  if (!(n > 0) || !Number.isFinite(n)) return false
  const exponent = Math.log2(n)
  return Math.abs(exponent - Math.round(exponent)) < 1e-6
}

/** A drag's Speed, stuck to the nearest power of two when it is close and [free] (⌥) is not held. */
export function stickSpeed(speed: number, free = false): number {
  if (free || !(speed > 0)) return speed
  const exponent = Math.log2(speed)
  const nearest = Math.round(exponent)
  return Math.abs(exponent - nearest) <= STICK_OCTAVES ? 2 ** nearest : speed
}

/**
 * [speed] moved [quarters] quarter-octaves (an arrow key; Shift is four), stopping at a power of two
 * it would pass, as the desk's encoder does (D13), so every snap is reachable from either side.
 */
export function stepSpeed(speed: number, quarters: number): number {
  if (quarters === 0 || !(speed > 0)) return speed
  const from = Math.log2(speed)
  const to = from + quarters / 4
  const between = quarters > 0 ? Math.floor(to - 1e-9) : Math.ceil(to + 1e-9)
  const passes = quarters > 0 ? between > from + 1e-9 && between < to : between < from - 1e-9 && between > to
  return clampSpeed(2 ** (passes ? between : to))
}

/** A surface fader's travel for *FX speed* (D13): ×¼ to ×4. */
export const FADER_MIN = 0.25
export const FADER_MAX = 4

/** An encoder ring's position at ×1: one 7-bit step a quarter of an octave either side. */
export const RING_CENTRE = 64

/**
 * Where a surface's *FX speed* control shows [speed] — the desk's `FxSpeed.ringPosition` and
 * `faderPosition`: an encoder's ring a step a quarter of an octave about ×1 at 64, a fader on ×¼–×4
 * at the nearer end past either.
 */
export function surfacePosition(speed: number, encoder: boolean): number {
  if (!(speed > 0)) return 0
  if (encoder) return Math.min(127, Math.max(0, Math.round(RING_CENTRE + 4 * Math.log2(clampSpeed(speed)))))
  const p = (Math.log2(speed) - Math.log2(FADER_MIN)) / (Math.log2(FADER_MAX) - Math.log2(FADER_MIN))
  return Math.round(Math.min(1, Math.max(0, p)) * 127)
}

/** The Speed a surface's *FX speed* control at [value7Bit] stands for, [surfacePosition]'s inverse. */
export function speedAtSurfacePosition(value7Bit: number, encoder: boolean): number {
  const v = Math.min(127, Math.max(0, value7Bit))
  if (encoder) return clampSpeed(2 ** ((v - RING_CENTRE) / 4))
  return 2 ** (Math.log2(FADER_MIN) + (v / 127) * (Math.log2(FADER_MAX) - Math.log2(FADER_MIN)))
}

/** The Speed a fader at [value7Bit] sets (D13), stuck at each power of two: the desk's `FxSpeed.faderSpeed`. */
export function faderSpeed(value7Bit: number): number {
  return stickSpeed(speedAtSurfacePosition(value7Bit, false))
}

/**
 * What a surface's *FX speed* control at [value7Bit] says of the lead FX, in words: `×2` on a power of
 * two, `about ×1.19` between (a ring is a quarter of an octave a step, a fader about a thirtieth of
 * one), and `×4 or faster` / `×¼ or slower` at a fader's ends, where a lead past them stands.
 */
export function surfaceSpeedWords(value7Bit: number, encoder: boolean): string {
  if (!encoder && value7Bit >= 127) return `${speedLabel(FADER_MAX)} or faster`
  if (!encoder && value7Bit <= 0) return `${speedLabel(FADER_MIN)} or slower`
  const speed = speedAtSurfacePosition(value7Bit, encoder)
  return isPowerOfTwo(speed) ? speedLabel(speed) : `about ${speedLabel(speed)}`
}

/** Where [speed] sits on the track, 0–1, clamped to its ends; [off] when it lies past one. */
export function trackPosition(speed: number): { position: number; off: 'low' | 'high' | null } {
  const lo = Math.log2(TRACK_MIN)
  const span = Math.log2(TRACK_MAX) - lo
  const p = (Math.log2(speed) - lo) / span
  if (p < 0) return { position: 0, off: 'low' }
  if (p > 1) return { position: 1, off: 'high' }
  return { position: p, off: null }
}

/** The Speed at [position] on the track, 0–1. */
export function speedAtPosition(position: number): number {
  const lo = Math.log2(TRACK_MIN)
  return 2 ** (lo + Math.min(1, Math.max(0, position)) * (Math.log2(TRACK_MAX) - lo))
}

/** The glyphs a fraction is written with, and read back from. */
const GLYPHS: readonly [string, number][] = [
  ['½', 1 / 2],
  ['⅓', 1 / 3],
  ['⅔', 2 / 3],
  ['¼', 1 / 4],
  ['¾', 3 / 4],
  ['⅛', 1 / 8],
  ['⅜', 3 / 8],
  ['⅝', 5 / 8],
  ['⅞', 7 / 8],
]

/** [n] to at most two decimals, no trailing zeros. */
function trim2(n: number): string {
  return String(Math.round(n * 100) / 100)
}

/** A Speed as the chips say it: `×1`, `×½`, `×1/16`, `×⅔`, `×5.05`. */
export function speedLabel(speed: number): string {
  if (!(speed > 0)) return '×1'
  if (isPowerOfTwo(speed) && speed < 1) {
    const denominator = Math.round(1 / speed)
    const glyph = GLYPHS.find(([, v]) => Math.abs(v - speed) < 1e-9)
    return `×${glyph?.[0] ?? `1/${denominator}`}`
  }
  if (speed < 1) {
    const glyph = GLYPHS.find(([, v]) => Math.abs(v - speed) < 1e-6)
    if (glyph) return `×${glyph[0]}`
    const inverse = 1 / speed
    if (Math.abs(inverse - Math.round(inverse)) < 1e-6) return `×1/${Math.round(inverse)}`
    return `×${trim2(speed)}`
  }
  return `×${trim2(speed)}`
}

/**
 * A typed Speed: `2`, `×2`, `x0.5`, `⅔`, `2/3`, `1 ⅓` — clamped to [SPEED_MIN]–[SPEED_MAX], or null
 * for anything else.
 */
export function parseSpeed(text: string): number | null {
  let s = text.trim().replace(/^[×xX*]\s*/, '').replace(/\s+/g, ' ')
  if (s === '') return null
  let whole = 0
  const glyph = GLYPHS.find(([g]) => s.endsWith(g))
  if (glyph) {
    const head = s.slice(0, -glyph[0].length).trim()
    if (head !== '' && !/^\d+$/.test(head)) return null
    whole = head === '' ? 0 : Number(head)
    return clampSpeed(whole + glyph[1])
  }
  const fraction = /^(\d+(?:\.\d+)?)\s*\/\s*(\d+(?:\.\d+)?)$/.exec(s)
  if (fraction) {
    const value = Number(fraction[1]) / Number(fraction[2])
    return value > 0 && Number.isFinite(value) ? clampSpeed(value) : null
  }
  s = s.replace(',', '.')
  if (!/^\d*\.?\d+$/.test(s)) return null
  const value = Number(s)
  return value > 0 ? clampSpeed(value) : null
}

/**
 * A cycle of [beats] beats in beats or bars — `⅛ beat`, `2 beats`, `1 bar`, `4 bars` — the readout's
 * words where the Speed is a power of two.
 */
export function beatsLabel(beats: number): string {
  if (beats < 1) {
    const glyph = GLYPHS.find(([, v]) => Math.abs(v - beats) < 1e-6)
    return `${glyph?.[0] ?? `1/${Math.round(1 / beats)}`} beat`
  }
  if (beats >= 4 && Math.abs(beats / 4 - Math.round(beats / 4)) < 1e-6) {
    const bars = Math.round(beats / 4)
    return `${bars} ${bars === 1 ? 'bar' : 'bars'}`
  }
  return `${trim2(beats)} ${beats === 1 ? 'beat' : 'beats'}`
}

/** The tempo a wall-clock FX on Natural runs against. */
export const NATURAL_BPM = 120

/**
 * How long one cycle takes, in seconds, at [bpm] — the FX's master's or its Own tempo, a rate
 * master's, or [NATURAL_BPM]: `cycle × 60 ÷ bpm` beats, `cycle × 120 ÷ bpm` seconds.
 */
export function cycleSeconds(cycle: number, wallClock: boolean, bpm: number): number {
  if (!(bpm > 0)) return wallClock ? cycle : (cycle * 60) / NATURAL_BPM
  return wallClock ? (cycle * NATURAL_BPM) / bpm : (cycle * 60) / bpm
}

/** Seconds as the readout says them — `1.82 s`, `2 s`. */
export function secondsWords(seconds: number): string {
  return `${seconds >= 100 ? Math.round(seconds) : trim2(seconds)} s`
}

/**
 * The readout (D2): the cycle in seconds at the tempo the FX runs at, and in bars or beats where the
 * Speed is a power of two — `1 bar · 1.82 s a cycle at 132 BPM`. A wall-clock FX's beats are its
 * Speed's, one bar at ×1, as on a beat FX.
 */
export function speedReadout(cycle: number, wallClock: boolean, bpm: number): string {
  const speed = runningSpeed(cycle, wallClock)
  const seconds = `${secondsWords(cycleSeconds(cycle, wallClock, bpm))} a cycle at ${bpmLabel(bpm > 0 ? bpm : NATURAL_BPM)} BPM`
  return isPowerOfTwo(speed) ? `${beatsLabel(4 / speed)} · ${seconds}` : seconds
}

/**
 * An FX's Speed as a chip says it, with its unit resolved — `×1`, `×½ · Own 96` on its own tempo
 * (fx-speed plan D3), the caller naming a master beside it. A running FX on Own tempo reports
 * `WALL_CLOCK`, the loop it runs on, and [ownBpm] is what says its cycle is still beats.
 *
 * **Null when the timing source is unknown** and the FX has no Own tempo, and callers then say
 * nothing about the speed: a palette whose stored `fxType` no longer resolves (an import carrying a
 * script FX this desk lacks) cannot say whether its `cycle` is beats or seconds, and a confident
 * `×2` for one that is `×½` is worse than an absent clause.
 */
export function fxSpeedLabel(
  cycle: number,
  timingSource: string | null | undefined,
  ownBpm: number | null = null,
): string | null {
  if (ownBpm != null) return `${speedLabel(runningSpeed(cycle, false))} · ${ownTempoLabel(ownBpm)}`
  if (timingSource == null) return null
  const wallClock = timingSource === 'WALL_CLOCK'
  return speedLabel(runningSpeed(cycle, wallClock))
}

/** An Own tempo as a chip says it — `Own 96`. */
export function ownTempoLabel(bpm: number): string {
  return `Own ${bpmLabel(bpm)}`
}
