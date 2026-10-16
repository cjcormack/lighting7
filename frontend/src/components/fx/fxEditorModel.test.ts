// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest'

vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

import type { EffectLibraryEntry } from '@/store/fixtureFx'
import { makeActiveEffect } from '@/test/fixtureFactories'
import {
  centreModeOf,
  differsFromTemplate,
  withTemplateDraft,
  draftOf,
  levelModeOf,
  paramAxis,
  paramRole,
  pickerFamilyOf,
  startingSpec,
  templateDraftOf,
  timingChoicePatch,
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

  it('sends Seconds with no master beside it, and a master with Seconds false (D7)', () => {
    const onMaster = draftOf(makeActiveEffect({ speedMasterUuid: 'm2' }))
    expect(onMaster.seconds).toBe(false)
    const touched = new Set(['seconds', 'speedMasterUuid', 'rateSpeedMasterUuid'] as const)
    const toSeconds = updateRequestOf({ ...onMaster, seconds: true, speedMasterUuid: null }, touched, 'm1')
    expect(toSeconds.seconds).toBe(true)
    expect(toSeconds).not.toHaveProperty('speedMasterUuid')
    expect(toSeconds).not.toHaveProperty('rateSpeedMasterUuid')
    // Revert of a beat effect moved off its master: Seconds false and the master back.
    expect(updateRequestOf(onMaster, touched, 'm1')).toMatchObject({ seconds: false, speedMasterUuid: 'm2' })
  })

  it("Revert puts a wall-clock effect back to unscaled — FU-FX-RATE-MASTER-CLEAR's close", () => {
    const unscaled = draftOf(makeActiveEffect({ timingSource: 'WALL_CLOCK', rateSpeedMasterUuid: null }))
    expect(unscaled.seconds).toBe(true)
    const request = updateRequestOf(unscaled, new Set(['seconds', 'rateSpeedMasterUuid'] as const))
    expect(request.seconds).toBe(true)
    expect(request).not.toHaveProperty('rateSpeedMasterUuid')
  })

  it('reads size, direction and runs off the wire at their defaults, and sends them once touched (D2, D5, D9)', () => {
    const plain = draftOf(makeActiveEffect({}))
    expect([plain.size, plain.direction, plain.nShot]).toEqual([1, 'FORWARD', null])
    expect(updateRequestOf(plain)).not.toHaveProperty('size')
    const shaped = draftOf(makeActiveEffect({ size: 0.5, direction: 'BOUNCE', nShot: 2 }))
    expect([shaped.size, shaped.direction, shaped.nShot]).toEqual([0.5, 'BOUNCE', 2])
    const touched = new Set(['size', 'direction', 'nShot'] as const)
    expect(updateRequestOf(shaped, touched)).toMatchObject({ size: 0.5, direction: 'BOUNCE', nShot: 2 })
    // A null count is said as runForever: the frame's absent nShot means keep.
    const forever = updateRequestOf({ ...shaped, nShot: null }, touched)
    expect(forever.runForever).toBe(true)
    expect(forever).not.toHaveProperty('nShot')
  })

  it('writes a palette effect\'s size, direction and runs where they say something, and reads them as edits', () => {
    const palette = {
      effectType: 'Pulse',
      category: 'dimmer',
      beatDivision: 1,
      blendMode: 'OVERRIDE',
      distribution: 'LINEAR',
      parameters: {},
    }
    const draft = templateDraftOf(palette)
    expect(withTemplateDraft(palette, draft)).not.toHaveProperty('size')
    const shaped = withTemplateDraft(palette, { ...draft, size: 0.4, direction: 'BACKWARD', nShot: 1 })
    expect(shaped).toMatchObject({ size: 0.4, direction: 'BACKWARD', nShot: 1 })
    expect(templateDraftOf(shaped)).toMatchObject({ size: 0.4, direction: 'BACKWARD', nShot: 1 })
    // Put back to the defaults, a palette that had said them says them again rather than dropping them.
    expect(withTemplateDraft(shaped, draft)).toMatchObject({ size: 1, direction: 'FORWARD', nShot: null })
    expect(differsFromTemplate({ ...draft, size: 0.5 }, palette, 'm1', {}, { distribution: false })).toBe(true)
    expect(differsFromTemplate({ ...draft, direction: 'BOUNCE' }, palette, 'm1', {}, { distribution: false })).toBe(true)
    expect(differsFromTemplate({ ...draft, nShot: 2 }, palette, 'm1', {}, { distribution: false })).toBe(true)
    expect(differsFromTemplate(draft, palette, 'm1', {}, { distribution: false })).toBe(false)
  })

  it('reads the desk\'s Seconds off a running effect, whatever loop it reports', () => {
    expect(draftOf(makeActiveEffect({ timingSource: 'WALL_CLOCK', seconds: true })).seconds).toBe(true)
    expect(draftOf(makeActiveEffect({ timingSource: 'WALL_CLOCK', rateSpeedMasterUuid: 'm2' })).seconds).toBe(false)
  })

  it('keeps a beat effect\'s speed when the chip moves it onto Seconds and back', () => {
    const bpm = (uuid: string | null) => (uuid === 'm2' ? 60 : 120)
    const beat = { beatDivision: 4, seconds: false, speedMasterUuid: null, rateSpeedMasterUuid: null }
    // Four beats of master 1 at 120 BPM is two seconds.
    expect(timingChoicePatch(beat, false, null, bpm)).toEqual({
      seconds: true,
      speedMasterUuid: null,
      rateSpeedMasterUuid: null,
      beatDivision: 2,
    })
    // Two seconds onto master 2 at 60 BPM is two beats.
    expect(timingChoicePatch({ ...beat, seconds: true, beatDivision: 2 }, false, 'm2', bpm)).toEqual({
      seconds: false,
      speedMasterUuid: 'm2',
      beatDivision: 2,
    })
    // A master to a master keeps the beats.
    expect(timingChoicePatch(beat, false, 'm2', bpm)).toEqual({ seconds: false, speedMasterUuid: 'm2' })
    // A wall-clock effect's number is seconds either way; its master is its rate master.
    expect(timingChoicePatch({ ...beat, seconds: true }, true, 'm2', bpm)).toEqual({ seconds: false, rateSpeedMasterUuid: 'm2' })
    expect(timingChoicePatch(beat, true, null, bpm)).toEqual({ seconds: true, speedMasterUuid: null, rateSpeedMasterUuid: null })
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

describe("a pad's instance against its palette (§3.3, D20)", () => {
  const template = {
    effectType: 'Circle',
    category: 'position',
    beatDivision: 1,
    blendMode: 'ADDITIVE',
    distribution: 'CENTER_OUT',
    phaseOffset: 0,
    parameters: { panCenter: '128', tiltCenter: '128', panRadius: '64', tiltRadius: '64' },
    speedMasterUuid: null,
    rateSpeedMasterUuid: null,
  }
  // A single fixture's instance: the desk leaves its distribution out, which `draftOf` reads LINEAR.
  const instance = draftOf(
    makeActiveEffect({ effectType: 'Circle', blendMode: 'ADDITIVE', distributionStrategy: null, parameters: { ...template.parameters, panRadius: '64.0' } }),
  )

  it('is not edited for a distribution the instance never said, nor for a number spelled differently', () => {
    expect(instance.distributionStrategy).toBe('LINEAR')
    expect(differsFromTemplate(instance, template, 'm1', {}, { distribution: false })).toBe(false)
    // Asked to compare it, the guess reads as an edit — which is why the editor does not.
    expect(differsFromTemplate(instance, template, 'm1', {}, { distribution: true })).toBe(true)
    expect(differsFromTemplate({ ...instance, parameters: { ...instance.parameters, panRadius: '90' } }, template, 'm1', {}, { distribution: false })).toBe(true)
    // A null master is master 1 on either side.
    expect(differsFromTemplate({ ...instance, speedMasterUuid: 'm1' }, template, 'm1', {}, { distribution: false })).toBe(false)
  })

  it('keeps the palette’s own distribution on Update where the instance said none', () => {
    expect(withTemplateDraft(template, instance, { distribution: false }).distribution).toBe('CENTER_OUT')
    expect(withTemplateDraft(template, { ...instance, distributionStrategy: 'RANDOM' }).distribution).toBe('RANDOM')
  })
})
