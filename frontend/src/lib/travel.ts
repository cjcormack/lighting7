import type { ChannelSource } from '../api/channelSource'
import type {
  FixtureTravel,
  PropertyDescriptor,
  SliderPropertyDescriptor,
  TimingRole,
} from '../store/fixtures'

/**
 * **Travel time** (fixture-optics plan D14): a head does not snap to a new pan, a scroller does not
 * jump a frame — they travel, at the type's speed, or over the time the fixture's own timing channel
 * gives. The Stage view draws that by keeping a **displayed value per channel** and moving it toward
 * the DMX value. It is drawn, never output: nothing here writes a channel, and the desk sends every
 * value exactly as composed (lighting7 `docs/fixtures-engineering.md` §"Travel and timing channels").
 *
 * Pure, and time is passed in — never read from a clock inside — so the tests and the profile harness
 * stay reproducible (the `colourBands.ts` / `strobeBands.ts` rule).
 *
 * The model, per axis:
 *
 * - **A move is planned when the target changes**, at the first frame that sees the change, from where
 *   the axis is drawn then. It takes the timing channel's duration where one applies (`v ×
 *   secondsPerStep`, however far it goes — the Revolution's "duration of a movement", manual
 *   p16 [12]), else `distance / rate` at the type's speed, else no time at all: a family the type
 *   declares no speed for snaps, as everything did before.
 * - **Plans are made in frames only.** A channel callback can see a change between frames, when the
 *   canvas's clock is stale — on a `demand` frameloop it may not have ticked for minutes — so outside
 *   a frame ([stepTravel]'s `nowS = null`) a change is only noted ([isTravelling] answers true, so the
 *   caller asks for a frame) and the move is planned at the next frame's time, never across the idle gap.
 * - **A stream keeps a timed move's arrival** (the operator's call, 2026-10-05). A target that changes
 *   again within [STREAM_GAP_S] of the last change is part of one stream — a desk fade, a 16-bit pair
 *   arriving in two halves. While the stream's timed move is in flight, a new target arrives when the
 *   first plan would have; once that arrival has passed, the rest of the stream follows at the type's
 *   speed. Re-planning a full duration per change instead — what a literal reading of "the duration
 *   of a movement" would do — draws a fade under a timing channel crawling in asymptotically, the
 *   "unexpected luminaire behavior" ETC warns against (p16 [12]) and nothing an operator could read.
 *   A change after a pause is a new move, and takes the timing channel's whole duration again.
 * - **Progress is linear in time.** A real head accelerates, but a streamed fade re-plans every
 *   frame from where the last left off, so any ease-in would make it crawl; a linear plan re-planned
 *   every frame at the type's speed is exactly a rate limiter.
 * - **An axis that has never drawn snaps**, and so does one [resetTravelAxis] clears: first paint, a
 *   vis-source switch, a wholesale replacement of the source's values ([sourceEpoch]) and a repatch.
 *   Easing in from zero, or from another source's picture, would draw a move the rig never made.
 */
export interface TravelAxis {
  /** What is drawn. */
  value: number
  /** Where the current move started. */
  from: number
  /** Where it is going — the DMX value it last planned for. */
  to: number
  /** The scene time the current move started. */
  startS: number
  /** How long the move takes; 0 lands at once. */
  durS: number
  /** Whether the current move's duration came from a timing channel. */
  timed: boolean
  /** The scene time of the last change of target, for telling a stream from a new move. */
  lastChangeS: number
  /** A change seen outside a frame, not yet planned. */
  pending: boolean
  /** False until the axis has drawn a value: the first one is landed, never travelled to. */
  ready: boolean
}

/** Two changes of target this close together are one stream (a fade), not two moves. */
export const STREAM_GAP_S = 0.5

export function makeTravelAxis(): TravelAxis {
  return {
    value: 0,
    from: 0,
    to: 0,
    startS: 0,
    durS: 0,
    timed: false,
    lastChangeS: Number.NEGATIVE_INFINITY,
    pending: false,
    ready: false,
  }
}

/** Forget the axis: the next value it is given is landed at once. */
export function resetTravelAxis(axis: TravelAxis): void {
  axis.ready = false
}

/**
 * Advance [axis] toward [target] and answer what to draw. [nowS] is this frame's scene time, or
 * `null` outside a frame (a channel callback), which notes a change for the next frame to plan.
 * [rate] is units a second at the type's speed — `Infinity` snaps — and [timedS], where not null,
 * is a timing channel's duration for a move planned now, whatever its distance.
 */
export function stepTravel(
  axis: TravelAxis,
  target: number,
  nowS: number | null,
  rate: number,
  timedS: number | null,
): number {
  if (!axis.ready) {
    axis.value = axis.from = axis.to = target
    axis.durS = 0
    axis.timed = false
    axis.pending = false
    // A landing is not a move: the first change after it is never part of a stream.
    axis.lastChangeS = Number.NEGATIVE_INFINITY
    axis.ready = true
    return target
  }
  if (nowS == null) {
    axis.pending = target !== axis.to
    return axis.value
  }
  axis.pending = false
  if (axis.value !== axis.to) {
    const t = axis.durS > 0 ? (nowS - axis.startS) / axis.durS : 1
    axis.value = t >= 1 ? axis.to : axis.from + (axis.to - axis.from) * Math.max(0, t)
  }
  if (target !== axis.to) {
    const inStream = nowS - axis.lastChangeS <= STREAM_GAP_S
    const timedInFlight = inStream && axis.timed && axis.value !== axis.to
    const distance = Math.abs(target - axis.value)
    const byRate = rate > 0 && Number.isFinite(rate) ? distance / rate : 0
    let durS: number
    if (timedS != null && !inStream) {
      durS = timedS
      axis.timed = true
    } else if (timedS != null && timedInFlight) {
      durS = Math.max(0, axis.startS + axis.durS - nowS)
      axis.timed = true
    } else {
      durS = byRate
      axis.timed = false
    }
    axis.lastChangeS = nowS
    axis.from = axis.value
    axis.to = target
    axis.durS = durS
    axis.startS = nowS
    if (durS <= 0 || distance === 0) axis.value = target
  }
  return axis.value
}

/**
 * Whether [axis] is drawn short of its target, or has seen a change no frame has planned yet — a
 * move in flight, which asks for every frame.
 */
export function isTravelling(axis: TravelAxis): boolean {
  return axis.ready && (axis.value !== axis.to || axis.pending)
}

/** A DMX channel's full range: what [Travel.beamMs] and [Travel.colourMs] are the time across. */
const DMX_RANGE = 255

/**
 * A type's speeds as rates the axes take: degrees a second for pan and tilt, DMX steps a second for
 * a beam or colour channel. A family left undeclared is `Infinity` — it snaps.
 */
export interface TravelRates {
  pan: number
  tilt: number
  beam: number
  colour: number
}

/** Every family snaps: a type with no travel, and every surface that does not ease (a capture). */
export const SNAP_RATES: Readonly<TravelRates> = { pan: Infinity, tilt: Infinity, beam: Infinity, colour: Infinity }

function positive(n: number | null | undefined): number | null {
  return n != null && Number.isFinite(n) && n > 0 ? n : null
}

export function travelRates(travel: FixtureTravel | null | undefined): TravelRates {
  if (travel == null) return SNAP_RATES
  const beamMs = positive(travel.beamMs)
  const colourMs = positive(travel.colourMs)
  return {
    pan: positive(travel.panDegPerS) ?? Infinity,
    tilt: positive(travel.tiltDegPerS) ?? Infinity,
    beam: beamMs != null ? DMX_RANGE / (beamMs / 1000) : Infinity,
    colour: colourMs != null ? DMX_RANGE / (colourMs / 1000) : Infinity,
  }
}

/** The families a timing channel can stretch. */
export type TravelFamily = 'position' | 'beam' | 'colour'

const ROLE_FAMILIES: Record<TimingRole, readonly TravelFamily[]> = {
  POSITION: ['position'],
  BEAM: ['beam'],
  COLOUR: ['colour'],
  ALL: ['position', 'beam', 'colour'],
}

/** A fixture's own timing channels, by the family each stretches. */
export interface TimingChannels {
  position?: SliderPropertyDescriptor
  beam?: SliderPropertyDescriptor
  colour?: SliderPropertyDescriptor
}

export const NO_TIMING: Readonly<TimingChannels> = {}

/**
 * The fixture's timing channels (`timing` on a slider): the first that stretches each family. A desk
 * that predates the vocabulary sends none, and every family moves at the type's speed.
 */
export function findTimingChannels(properties: readonly PropertyDescriptor[] | undefined): TimingChannels {
  let found: TimingChannels | null = null
  for (const p of properties ?? []) {
    if (p.type !== 'slider' || p.timing == null) continue
    const families = ROLE_FAMILIES[p.timing]
    if (families == null) continue
    found ??= {}
    for (const f of families) found[f] ??= p
  }
  return found ?? NO_TIMING
}

/**
 * A timing channel's duration for a move planned at [raw]: `raw × secondsPerStep`, or `null` — "the
 * type's own speed" — at 0, from its `timingFastFrom` band, or with no channel at all.
 */
export function timedSeconds(prop: SliderPropertyDescriptor | undefined, raw: number): number | null {
  if (prop == null || prop.timingSecondsPerStep == null) return null
  if (raw <= 0) return null
  if (prop.timingFastFrom != null && raw >= prop.timingFastFrom) return null
  return raw * prop.timingSecondsPerStep
}

/**
 * Whether a colour-family channel of [category] is stretched by the colour timing channel. The
 * Revolution's Colour Timing times its gel scroller (a `colour` setting) but not its media frame (a
 * `setting`, whose row in the manual's timing column is blank, p14 [10]): the frame still travels at the
 * type's colour speed, untimed.
 */
export function colourTimed(category: string): boolean {
  return category === 'colour'
}

/**
 * [source]'s replacement counter ([ChannelSource.epoch]): every axis drawn from it lands across a
 * change, since a replacement is a new picture rather than a move. 0 for a source that never replaces.
 */
export function sourceEpoch(source: ChannelSource): number {
  return source.epoch?.() ?? 0
}
