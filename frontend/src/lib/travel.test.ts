import { describe, expect, it } from 'vitest'
import {
  findTimingChannels,
  isTravelling,
  makeTravelAxis,
  resetTravelAxis,
  stepTravel,
  timedSeconds,
  travelRates,
  NO_TIMING,
  SNAP_RATES,
} from './travel'
import { chan, sliderProp } from '../test/fixtureFactories'

// The Revolution's three timing channels, as `GET /fixture-types` carries them (manual p14–16 [10–12]).
const focusTiming = sliderProp('focusTime', 'speed', chan(9), {
  timing: 'POSITION',
  timingSecondsPerStep: 1,
  timingFastFrom: 255,
})
const colourTiming = sliderProp('colTime', 'speed', chan(10), { timing: 'COLOUR', timingSecondsPerStep: 1 })
const beamTiming = sliderProp('beamTime', 'speed', chan(11), { timing: 'BEAM', timingSecondsPerStep: 1 })

describe('one axis of travel', () => {
  it('lands its first value rather than travelling to it from zero', () => {
    const axis = makeTravelAxis()
    expect(stepTravel(axis, 200, 10, 50, null)).toBe(200)
    expect(isTravelling(axis)).toBe(false)
  })

  it('moves toward a new target at the rate, and lands', () => {
    const axis = makeTravelAxis()
    stepTravel(axis, 0, 0, 100, null)
    expect(stepTravel(axis, 300, 1, 100, null)).toBe(0) // planned: 3 s at 100/s, from this frame
    expect(isTravelling(axis)).toBe(true)
    expect(stepTravel(axis, 300, 2, 100, null)).toBeCloseTo(100)
    expect(stepTravel(axis, 300, 2.5, 100, null)).toBeCloseTo(150)
    expect(stepTravel(axis, 300, 4, 100, null)).toBe(300)
    expect(isTravelling(axis)).toBe(false)
  })

  it('takes a timing channel’s duration whatever the distance — the Revolution’s duration, not a rate', () => {
    const short = makeTravelAxis()
    const long = makeTravelAxis()
    stepTravel(short, 0, 0, 1000, null)
    stepTravel(long, 0, 0, 1000, null)
    stepTravel(short, 10, 0, 1000, 5)
    stepTravel(long, 250, 0, 1000, 5)
    expect(stepTravel(short, 10, 2.5, 1000, 5)).toBeCloseTo(5)
    expect(stepTravel(long, 250, 2.5, 1000, 5)).toBeCloseTo(125)
    expect(stepTravel(short, 10, 5, 1000, 5)).toBe(10)
    expect(stepTravel(long, 250, 5, 1000, 5)).toBe(250)
  })

  it('keeps a timed move’s arrival through a streamed fade, rather than crawling in (the operator’s call)', () => {
    const axis = makeTravelAxis()
    stepTravel(axis, 0, 0, 100, null)
    // A desk fade under Focus Timing 5: a new target every 50 ms from 0 s to 2.5 s, 100 → 150.
    stepTravel(axis, 100, 0, 100, 5)
    let target = 100
    for (let t = 0.05; t <= 2.5001; t += 0.05) {
      target += 1
      stepTravel(axis, target, t, 100, 5)
    }
    expect(target).toBe(150)
    // Re-planning a fresh 5 s per change would still be far short at 5 s; the stream arrives then.
    expect(stepTravel(axis, 150, 4, 100, 5)).toBeLessThan(150)
    expect(stepTravel(axis, 150, 4, 100, 5)).toBeGreaterThan(100)
    expect(stepTravel(axis, 150, 5, 100, 5)).toBe(150)
    expect(isTravelling(axis)).toBe(false)
  })

  it('follows a stream past its timed arrival at the type’s speed, and times a move after a pause in full', () => {
    const axis = makeTravelAxis()
    stepTravel(axis, 0, 0, 100, null)
    stepTravel(axis, 10, 0, 100, 1) // timed 1 s
    // The fade runs on for 3 s, a step every 50 ms: past 1 s it follows at 100 a second.
    let target = 10
    for (let t = 0.05; t <= 3.0001; t += 0.05) {
      target += 1
      stepTravel(axis, target, t, 100, 1)
    }
    expect(stepTravel(axis, target, 3.05, 100, 1)).toBe(target)
    // After a pause, a GO is a new move and takes Focus Timing's whole second again.
    stepTravel(axis, 0, 10, 100, 1)
    expect(stepTravel(axis, 0, 10.5, 100, 1)).toBeCloseTo(target / 2)
    expect(stepTravel(axis, 0, 11, 100, 1)).toBe(0)
  })

  it('snaps a family the type declares no speed for, as everything did before', () => {
    const axis = makeTravelAxis()
    stepTravel(axis, 0, 0, Infinity, null)
    expect(stepTravel(axis, 255, 0.01, Infinity, null)).toBe(255)
    expect(isTravelling(axis)).toBe(false)
  })

  it('re-plans from where it is drawn when the target moves mid-flight — a fade streams targets', () => {
    const axis = makeTravelAxis()
    stepTravel(axis, 0, 0, 100, null)
    stepTravel(axis, 200, 0, 100, null)
    // Half a second in, at 50, the target moves back to 0: the head turns round from where it is.
    expect(stepTravel(axis, 0, 0.5, 100, null)).toBeCloseTo(50)
    expect(stepTravel(axis, 0, 0.75, 100, null)).toBeCloseTo(25)
    expect(stepTravel(axis, 0, 1, 100, null)).toBe(0)
  })

  it('starts a move seen between frames at the next frame, not at the canvas’s stale clock', () => {
    const axis = makeTravelAxis()
    stepTravel(axis, 0, 0, 100, null)
    // A channel callback, minutes after the last frame (an idle demand frameloop): noted, not planned —
    // but already travelling, so the caller asks for the frame that plans it.
    expect(stepTravel(axis, 100, null, 100, null)).toBe(0)
    expect(isTravelling(axis)).toBe(true)
    // The next frame is the move's first: it has not moved yet, then runs its second.
    expect(stepTravel(axis, 100, 600, 100, null)).toBe(0)
    expect(stepTravel(axis, 100, 600.5, 100, null)).toBeCloseTo(50)
    expect(stepTravel(axis, 100, 601, 100, null)).toBe(100)
  })

  it('lands again after a reset — a source switch, a replacement, a repatch', () => {
    const axis = makeTravelAxis()
    stepTravel(axis, 0, 0, 10, null)
    stepTravel(axis, 200, 0, 10, null)
    expect(isTravelling(axis)).toBe(true)
    resetTravelAxis(axis)
    expect(stepTravel(axis, 40, 1, 10, null)).toBe(40)
    expect(isTravelling(axis)).toBe(false)
  })
})

describe('a timing channel', () => {
  it('is one second a step on the Revolution, 0 the fixture’s own speed', () => {
    expect(timedSeconds(colourTiming, 5)).toBe(5)
    expect(timedSeconds(colourTiming, 255)).toBe(255)
    expect(timedSeconds(colourTiming, 0)).toBeNull()
    expect(timedSeconds(undefined, 5)).toBeNull()
  })

  it('reads Focus Timing at 100 % as "more responsive manual control", not 4 min 15 s', () => {
    expect(timedSeconds(focusTiming, 254)).toBe(254)
    expect(timedSeconds(focusTiming, 255)).toBeNull()
  })

  it('is found by the family it stretches, and a desk that sends none times nothing', () => {
    const found = findTimingChannels([focusTiming, colourTiming, beamTiming])
    expect(found.position).toBe(focusTiming)
    expect(found.colour).toBe(colourTiming)
    expect(found.beam).toBe(beamTiming)
    expect(findTimingChannels([sliderProp('speed', 'speed', chan(1))])).toBe(NO_TIMING)
    // The wire sends null, not absent, for a slider that is no timing channel.
    expect(findTimingChannels([sliderProp('speed', 'speed', chan(1), { timing: null })])).toBe(NO_TIMING)
    const all = sliderProp('all', 'speed', chan(2), { timing: 'ALL', timingSecondsPerStep: 0.1 })
    expect(findTimingChannels([all])).toEqual({ position: all, beam: all, colour: all })
  })
})

describe('a type’s speeds', () => {
  it('become rates: degrees a second, and DMX steps a second across the whole range', () => {
    const rates = travelRates({ panDegPerS: 157.27, tiltDegPerS: 108.95, beamMs: 600, colourMs: 2550 })
    expect(rates.pan).toBe(157.27)
    expect(rates.tilt).toBe(108.95)
    expect(rates.beam).toBeCloseTo(425)
    expect(rates.colour).toBeCloseTo(100)
  })

  it('snap every family left undeclared — null on the wire, or no travel at all', () => {
    expect(travelRates(null)).toBe(SNAP_RATES)
    expect(travelRates(undefined)).toBe(SNAP_RATES)
    const rates = travelRates({ panDegPerS: 90, tiltDegPerS: 90, beamMs: null, colourMs: null })
    expect(rates.beam).toBe(Infinity)
    expect(rates.colour).toBe(Infinity)
  })
})

// Fixture-optics plan D14: travel is drawn, never output. The modules that draw it write no channel
// — read as text through Vite's glob, as `svgPlotRetired.test.ts` reads its sources.
const travelSources = import.meta.glob(
  ['/src/lib/travel.ts', '/src/components/stage3d/beamTravel.ts', '/src/components/stage3d/FixtureModel.tsx'],
  { query: '?raw', import: 'default', eager: true },
) as Record<string, string>

describe('travel is drawn, never output', () => {
  it('reads its sources', () => {
    expect(Object.keys(travelSources)).toHaveLength(3)
  })

  it('writes no channel, no programmer value and no gesture from the code that draws it', () => {
    for (const [path, text] of Object.entries(travelSources)) {
      for (const writer of ['updateChannel', 'programmer.set', 'sendGesture', 'channels.update(', 'useUpdate']) {
        expect(text.includes(writer), `${path} mentions ${writer}`).toBe(false)
      }
    }
  })
})
