// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest'

vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

import type { EffectLibraryEntry } from '@/store/fixtureFx'
import { chan, makeActiveEffect, makeFixture, sliderProp } from '@/test/fixtureFactories'
import {
  centreModeOf,
  degreesToSize,
  draftOf,
  levelModeOf,
  paramAxis,
  paramRole,
  pickerFamilyOf,
  positionAxesOf,
  sizeToDegrees,
  startingSpec,
  updateRequestOf,
  withCentreMode,
} from './fxEditorModel'

const entry = (over: Partial<EffectLibraryEntry>): EffectLibraryEntry => ({
  name: 'X',
  category: 'dimmer',
  outputType: 'SLIDER',
  effectMode: 'STANDARD',
  compatibleProperties: ['dimmer'],
  parameters: [],
  ...over,
})
const p = (name: string, type: string, defaultValue = '0') => ({ name, type, defaultValue, description: '' })

const CIRCLE = entry({
  name: 'Circle',
  category: 'position',
  parameters: [p('panCenter', 'ubyte', '128'), p('tiltCenter', 'ubyte', '128'), p('panRadius', 'ubyte', '64'), p('tiltRadius', 'ubyte', '64')],
})
const SWEEP = entry({ name: 'Sweep', category: 'position', parameters: [p('startPan', 'ubyte'), p('curve', 'easingCurve', 'LINEAR')] })
const PULSE = entry({ name: 'Pulse', parameters: [p('min', 'ubyte'), p('max', 'ubyte', '255'), p('attackRatio', 'double', '0.1')] })
const CYCLE = entry({ name: 'Colour Cycle', category: 'colour', parameters: [p('colours', 'colourList'), p('fadeRatio', 'double', '0.5')] })

describe('fxEditorModel', () => {
  it('reads Around only as Additive with both centres at 128', () => {
    expect(centreModeOf('ADDITIVE', { panCenter: '128', tiltCenter: '128' })).toBe('around')
    expect(centreModeOf('ADDITIVE', { panCenter: '100', tiltCenter: '128' })).toBeNull()
    expect(centreModeOf('OVERRIDE', { panCenter: '100', tiltCenter: '40' })).toBe('absolute')
    expect(centreModeOf('MAX', { panCenter: '128', tiltCenter: '128' })).toBeNull()
  })

  it('spells Around as Additive with the centre pinned, and Absolute as Override keeping it', () => {
    const around = withCentreMode({ blendMode: 'OVERRIDE', parameters: { panCenter: '10', tiltCenter: '20', panRadius: '5' } }, 'around')
    expect(around).toEqual({ blendMode: 'ADDITIVE', parameters: { panCenter: '128', tiltCenter: '128', panRadius: '5' } })
    expect(withCentreMode(around, 'absolute').blendMode).toBe('OVERRIDE')
  })

  it('starts a movement effect Around and everything else Override', () => {
    expect(startingSpec(CIRCLE).blendMode).toBe('ADDITIVE')
    expect(startingSpec(SWEEP).blendMode).toBe('OVERRIDE')
    expect(startingSpec(PULSE)).toEqual({ blendMode: 'OVERRIDE', parameters: { min: '0', max: '255', attackRatio: '0.1' } })
  })

  it('reads Replace and Within', () => {
    expect(levelModeOf('OVERRIDE')).toBe('replace')
    expect(levelModeOf('MULTIPLY')).toBe('within')
    expect(levelModeOf('ADDITIVE')).toBeNull()
  })

  it('files a parameter by role: centre, size, axis, level, shape, main', () => {
    const roles = (e: EffectLibraryEntry) => Object.fromEntries(e.parameters.map((x) => [x.name, paramRole(e, x)]))
    expect(roles(CIRCLE)).toEqual({ panCenter: 'centre', tiltCenter: 'centre', panRadius: 'size', tiltRadius: 'size' })
    expect(roles(SWEEP)).toEqual({ startPan: 'axis', curve: 'shape' })
    expect(roles(PULSE)).toEqual({ min: 'level', max: 'level', attackRatio: 'shape' })
    // A ratio with no level beside it is the effect, not its shape.
    expect(roles(CYCLE)).toEqual({ colours: 'main', fadeRatio: 'main' })
  })

  it('says a size in degrees of travel and back — 28 of 255 is about 60° on a 540° pan', () => {
    const pan = { ...sliderProp('pan', 'pan', chan(1), { axis: 'PAN' }), degMin: 0, degMax: 540 }
    expect(Math.round(sizeToDegrees(28, pan))).toBe(59)
    expect(degreesToSize(60, pan)).toBe(28)
    expect(degreesToSize(10_000, pan)).toBe(255)
  })

  it('speaks degrees only where every head annotates both axes', () => {
    const mover = makeFixture('m', [
      sliderProp('pan', 'pan', chan(1), { axis: 'PAN', degMin: 0, degMax: 540 }),
      sliderProp('tilt', 'tilt', chan(2), { axis: 'TILT', degMin: 0, degMax: 270 }),
    ])
    const silent = makeFixture('s', [sliderProp('pan', 'pan', chan(3), { axis: 'PAN' }), sliderProp('tilt', 'tilt', chan(4), { axis: 'TILT' })])
    const par = makeFixture('p', [sliderProp('dimmer', 'dimmer', chan(5))])
    expect(positionAxesOf([mover, par])?.pan.degMax).toBe(540)
    expect(positionAxesOf([mover, silent])).toBeNull()
  })

  it('writes the core every time and an optional field only once touched', () => {
    const draft = draftOf(makeActiveEffect({ parameters: { min: '0' }, distributionStrategy: null }))
    expect(draft.distributionStrategy).toBe('LINEAR')
    // Nothing touched: the DTO's absent distribution is a guess, never sent.
    expect(updateRequestOf(draft)).toEqual({ parameters: { min: '0' }, beatDivision: 1, blendMode: 'OVERRIDE', phaseOffset: 0 })
    const touched = new Set(['distributionStrategy', 'elementFilter', 'speedMasterUuid', 'rateSpeedMasterUuid'] as const)
    expect(updateRequestOf(draft, touched, 'm1')).toEqual({
      parameters: { min: '0' },
      beatDivision: 1,
      blendMode: 'OVERRIDE',
      phaseOffset: 0,
      distributionStrategy: 'LINEAR',
      elementFilter: 'ALL',
      // A null master is spelled as master 1 where the caller says so (Revert); a null rate
      // master has no spelling and stays absent.
      speedMasterUuid: 'm1',
    })
    expect(updateRequestOf(draft, touched)).not.toHaveProperty('speedMasterUuid')
  })

  it('reads an axis from a camelCase word, never a substring', () => {
    expect(paramAxis('panCenter')).toBe('pan')
    expect(paramAxis('startPan')).toBe('pan')
    expect(paramAxis('pan')).toBe('pan')
    expect(paramAxis('endTilt')).toBe('tilt')
    expect(paramAxis('spanWidth')).toBeNull()
    expect(paramAxis('expansion')).toBeNull()
    expect(paramAxis('panorama')).toBeNull()
  })

  it('files a composite under Intensity and an unknown category under Controls', () => {
    expect(pickerFamilyOf('composite')).toBe('INTENSITY')
    expect(pickerFamilyOf('scripted')).toBe('CONTROLS')
  })
})
