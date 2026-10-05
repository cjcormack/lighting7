import type { ChannelSource } from '../../api/channelSource'
import {
  isTravelling,
  makeTravelAxis,
  resetTravelAxis,
  sourceEpoch,
  stepTravel,
  timedSeconds,
  SNAP_RATES,
  type TimingChannels,
  type TravelAxis,
  type TravelRates,
} from '../../lib/travel'

/**
 * The beam director's **travel** (fixture-optics plan D14, `lib/travel.ts`): one displayed value per
 * channel it draws from — pan and tilt in degrees, every beam channel in DMX — each moved toward the
 * DMX value at the type's speed, or over the fixture's own timing channel's duration.
 *
 * A plain object the director owns and steps from its frame loop, kept out of `FixtureModel` so a
 * test can drive it with no canvas. Allocation-free per frame, like the director.
 *
 * Every axis lands — never travels — on the first frame, and on a frame where the picture was
 * replaced rather than moved: another vis source, the same source's values replaced wholesale
 * ([ChannelSource.epoch] — the wire's snapshot on a (re)connect, a new Next GO preview), or a
 * repatch (the director's channel keys changed). See `docs/stage-vis-engineering.md` §"Travel time".
 */
export interface BeamTravel {
  pan: TravelAxis
  tilt: TravelAxis
  focus: TravelAxis
  zoom: TravelAxis
  iris: TravelAxis
  frost: TravelAxis
  gobo: TravelAxis
  gobo2: TravelAxis
  goboRot: TravelAxis
  /** One per blade, in `BLADE_ORDER`. */
  depth: TravelAxis[]
  rotation: TravelAxis[]
  /** Whether any axis stepped this frame is drawn short of its target — the director asks for the next frame. */
  moving: boolean
  // This frame's inputs, set by [beginTravelFrame].
  nowS: number
  rates: TravelRates
  positionS: number | null
  beamS: number | null
  // What the axes were drawn under, so a change lands them.
  source: ChannelSource | null
  epoch: number
  keys: object | null
}

export function makeBeamTravel(): BeamTravel {
  const blades = () => [makeTravelAxis(), makeTravelAxis(), makeTravelAxis(), makeTravelAxis()]
  return {
    pan: makeTravelAxis(),
    tilt: makeTravelAxis(),
    focus: makeTravelAxis(),
    zoom: makeTravelAxis(),
    iris: makeTravelAxis(),
    frost: makeTravelAxis(),
    gobo: makeTravelAxis(),
    gobo2: makeTravelAxis(),
    goboRot: makeTravelAxis(),
    depth: blades(),
    rotation: blades(),
    moving: false,
    nowS: 0,
    rates: SNAP_RATES,
    positionS: null,
    beamS: null,
    source: null,
    epoch: 0,
    keys: null,
  }
}

function resetAll(bt: BeamTravel): void {
  resetTravelAxis(bt.pan)
  resetTravelAxis(bt.tilt)
  resetTravelAxis(bt.focus)
  resetTravelAxis(bt.zoom)
  resetTravelAxis(bt.iris)
  resetTravelAxis(bt.frost)
  resetTravelAxis(bt.gobo)
  resetTravelAxis(bt.gobo2)
  resetTravelAxis(bt.goboRot)
  for (const a of bt.depth) resetTravelAxis(a)
  for (const a of bt.rotation) resetTravelAxis(a)
}

/** The keys the director reads a fixture's timing channels by. */
export interface TimingKeys {
  position: string | null
  beam: string | null
}

/**
 * Start a frame at [nowS]: land every axis if the picture was replaced since the last (see the
 * interface), and read this frame's timing — a timing channel's duration applies to a move planned
 * on this frame. [keys] is any object whose identity changes when the director's channels do.
 */
export function beginTravelFrame(
  bt: BeamTravel,
  source: ChannelSource,
  keys: object,
  nowS: number,
  rates: TravelRates,
  timing: TimingChannels,
  timingKeys: TimingKeys,
): void {
  const epoch = sourceEpoch(source)
  if (bt.source !== source || bt.epoch !== epoch || bt.keys !== keys) {
    resetAll(bt)
    bt.source = source
    bt.epoch = epoch
    bt.keys = keys
  }
  bt.moving = false
  bt.nowS = nowS
  bt.rates = rates
  bt.positionS = timedSeconds(timing.position, timingKeys.position ? source.getByKey(timingKeys.position) : 0)
  bt.beamS = timedSeconds(timing.beam, timingKeys.beam ? source.getByKey(timingKeys.beam) : 0)
}

function step(bt: BeamTravel, axis: TravelAxis, target: number, rate: number, timedS: number | null): number {
  const value = stepTravel(axis, target, bt.nowS, rate, timedS)
  if (isTravelling(axis)) bt.moving = true
  return value
}

/** Pan as drawn, in degrees. */
export function travelPan(bt: BeamTravel, deg: number): number {
  return step(bt, bt.pan, deg, bt.rates.pan, bt.positionS)
}

/** Tilt as drawn, in degrees. */
export function travelTilt(bt: BeamTravel, deg: number): number {
  return step(bt, bt.tilt, deg, bt.rates.tilt, bt.positionS)
}

/** A beam channel's level as drawn, in DMX. */
export function travelBeam(bt: BeamTravel, axis: TravelAxis, raw: number): number {
  return step(bt, axis, raw, bt.rates.beam, bt.beamS)
}
