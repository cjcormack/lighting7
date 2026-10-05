import { describe, expect, it } from 'vitest'
import type { ChannelSource } from '../../api/channelSource'
import { beginTravelFrame, makeBeamTravel, travelBeam, travelPan, travelTilt, type TimingKeys } from './beamTravel'
import { findTimingChannels, travelRates, NO_TIMING } from '../../lib/travel'
import { chan, sliderProp } from '../../test/fixtureFactories'

/** A source whose values and replacement counter the test sets. */
function testSource(values: Record<string, number> = {}) {
  const map = new Map(Object.entries(values))
  let epoch = 0
  const source: ChannelSource = {
    get: (u, c) => map.get(`${u}:${c}`) ?? 0,
    getByKey: (key) => map.get(key) ?? 0,
    subscribeToChannel: () => ({ unsubscribe: () => {} }),
    epoch: () => epoch,
  }
  return {
    source,
    set: (key: string, value: number) => map.set(key, value),
    replace: () => epoch++,
  }
}

// The Revolution: 90°/s pan and tilt, the beam across its range in 1.5 s (estimates), and its Focus
// and Beam Timing channels at 1 s a step (manual p16 [12]).
const REV = travelRates({ panDegPerS: 90, tiltDegPerS: 90, beamMs: 1500, colourMs: 2500 })
const focusTiming = sliderProp('focusTime', 'speed', chan(9), { timing: 'POSITION', timingSecondsPerStep: 1, timingFastFrom: 255 })
const beamTiming = sliderProp('beamTime', 'speed', chan(11), { timing: 'BEAM', timingSecondsPerStep: 1 })
const TIMING = findTimingChannels([focusTiming, beamTiming])
const TIMING_KEYS: TimingKeys = { position: '0:9', beam: '0:11' }
const KEYS = {}

describe('the beam director’s travel', () => {
  it('swings the head at the type’s speed, and a settled head asks for no frame', () => {
    const { source } = testSource()
    const bt = makeBeamTravel()
    beginTravelFrame(bt, source, KEYS, 0, REV, NO_TIMING, TIMING_KEYS)
    expect(travelPan(bt, 0)).toBe(0)
    expect(bt.moving).toBe(false)

    beginTravelFrame(bt, source, KEYS, 1, REV, NO_TIMING, TIMING_KEYS)
    expect(travelPan(bt, 180)).toBe(0) // 180° at 90°/s: two seconds from this frame
    expect(bt.moving).toBe(true)
    beginTravelFrame(bt, source, KEYS, 2, REV, NO_TIMING, TIMING_KEYS)
    expect(travelPan(bt, 180)).toBeCloseTo(90)
    expect(bt.moving).toBe(true)
    beginTravelFrame(bt, source, KEYS, 3, REV, NO_TIMING, TIMING_KEYS)
    expect(travelPan(bt, 180)).toBe(180)
    expect(bt.moving).toBe(false)
    // Settled: every later frame is still, so the director asks for nothing.
    for (let t = 4; t < 10; t++) {
      beginTravelFrame(bt, source, KEYS, t, REV, NO_TIMING, TIMING_KEYS)
      travelPan(bt, 180)
      travelTilt(bt, 0)
      expect(bt.moving).toBe(false)
    }
  })

  it('takes Focus Timing’s seconds for pan and tilt, Beam Timing’s for the beam, and its own speed at 0 or 255', () => {
    const { source, set } = testSource({ '0:9': 5, '0:11': 3 })
    const bt = makeBeamTravel()
    beginTravelFrame(bt, source, KEYS, 0, REV, TIMING, TIMING_KEYS)
    travelPan(bt, 0)
    travelBeam(bt, bt.zoom, 0)
    // A 10° pan and a full zoom sweep, planned with the channels at 5 and 3.
    travelPan(bt, 10)
    travelBeam(bt, bt.zoom, 255)
    beginTravelFrame(bt, source, KEYS, 2.5, REV, TIMING, TIMING_KEYS)
    expect(travelPan(bt, 10)).toBeCloseTo(5) // half of 5 s, however short the move
    expect(travelBeam(bt, bt.zoom, 255)).toBeCloseTo(212.5) // 2.5 of 3 s
    beginTravelFrame(bt, source, KEYS, 5, REV, TIMING, TIMING_KEYS)
    expect(travelPan(bt, 10)).toBe(10)
    expect(travelBeam(bt, bt.zoom, 255)).toBe(255)

    // Focus Timing at 255 is the console response option: the head's own 90°/s.
    set('0:9', 255)
    beginTravelFrame(bt, source, KEYS, 6, REV, TIMING, TIMING_KEYS)
    travelPan(bt, 100)
    beginTravelFrame(bt, source, KEYS, 7, REV, TIMING, TIMING_KEYS)
    expect(travelPan(bt, 100)).toBeCloseTo(100)
  })

  it('lands rather than travels across a source switch, a replacement of its values and a repatch', () => {
    const a = testSource()
    const b = testSource()
    const bt = makeBeamTravel()
    const land = (source: ChannelSource, keys: object, t: number, deg: number) => {
      beginTravelFrame(bt, source, keys, t, REV, NO_TIMING, TIMING_KEYS)
      return travelPan(bt, deg)
    }
    land(a.source, KEYS, 0, 0)
    expect(land(a.source, KEYS, 1, 90)).toBe(0) // an ordinary move travels
    expect(bt.moving).toBe(true)
    a.replace() // the wire's snapshot on a reconnect
    expect(land(a.source, KEYS, 1.1, 200)).toBe(200)
    expect(land(b.source, KEYS, 2, 270)).toBe(270) // Output → Next GO: another picture
    expect(land(b.source, KEYS, 3, 0)).toBe(270)
    expect(land(b.source, {}, 3.01, 0)).toBe(0) // repatched: new keys
    expect(bt.moving).toBe(false)
  })
})
