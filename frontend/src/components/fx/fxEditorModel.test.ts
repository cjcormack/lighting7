// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest'

vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

import type { FxLibraryEntry } from '@/store/fixtureFx'
import { makeActiveFx } from '@/test/fixtureFactories'
import {
  centreModeOf,
  differsFromPalette,
  filteredElementCount,
  orderPatch,
  selectionHeadCount,
  spreadHeadCount,
  carriesFxProperty,
  withPaletteDraft,
  draftOf,
  levelModeOf,
  paramAxis,
  paramRole,
  pickerFamilyForSheet,
  pickerFamilyOf,
  startingSpec,
  paletteDraftOf,
  timingChoicePatch,
  updateRequestOf,
  withCentreMode,
} from './fxEditorModel'

const entry = (over: Partial<FxLibraryEntry>): FxLibraryEntry => ({
  name: 'X',
  family: 'intensity',
  outputType: 'SLIDER',
  fxMode: 'STANDARD',
  compatibleProperties: ['dimmer'],
  parameters: [],
  ...over,
})
const p = (name: string, type: string, defaultValue = '0') => ({ name, type, defaultValue, description: '' })

const CIRCLE = entry({
  name: 'Circle',
  family: 'position',
  parameters: [p('panCenter', 'ubyte', '128'), p('tiltCenter', 'ubyte', '128'), p('panRadius', 'ubyte', '64'), p('tiltRadius', 'ubyte', '64')],
})
const SWEEP = entry({ name: 'Sweep', family: 'position', parameters: [p('startPan', 'ubyte'), p('curve', 'easingCurve', 'LINEAR')] })
const PULSE = entry({ name: 'Pulse', parameters: [p('min', 'ubyte'), p('max', 'ubyte', '255'), p('attackRatio', 'double', '0.1')] })
const CYCLE = entry({ name: 'Colour Cycle', family: 'colour', parameters: [p('colours', 'colourList'), p('fadeRatio', 'double', '0.5')] })

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
    const roles = (e: FxLibraryEntry) => Object.fromEntries(e.parameters.map((x) => [x.name, paramRole(e, x)]))
    expect(roles(CIRCLE)).toEqual({ panCenter: 'centre', tiltCenter: 'centre', panRadius: 'size', tiltRadius: 'size' })
    expect(roles(SWEEP)).toEqual({ startPan: 'axis', curve: 'shape' })
    expect(roles(PULSE)).toEqual({ min: 'level', max: 'level', attackRatio: 'shape' })
    // A ratio with no level beside it is the effect, not its shape.
    expect(roles(CYCLE)).toEqual({ colours: 'main', fadeRatio: 'main' })
  })

  it('writes the core every time and an optional field only once touched', () => {
    const draft = draftOf(makeActiveFx({ parameters: { min: '0' }, order: null }))
    expect(draft.order).toBe('NORMAL')
    // Nothing touched: the DTO's absent distribution is a guess, never sent.
    expect(updateRequestOf(draft)).toEqual({ parameters: { min: '0' }, cycle: 1, blendMode: 'OVERRIDE', phaseOffset: 0 })
    const touched = new Set(['order', 'elementFilter', 'speedMasterUuid', 'rateSpeedMasterUuid'] as const)
    expect(updateRequestOf(draft, touched, 'm1')).toEqual({
      parameters: { min: '0' },
      cycle: 1,
      blendMode: 'OVERRIDE',
      phaseOffset: 0,
      order: 'NORMAL',
      elementFilter: 'ALL',
      // A null master is spelled as master 1 where the caller says so (Revert); a null rate
      // master has no spelling and stays absent.
      speedMasterUuid: 'm1',
    })
    expect(updateRequestOf(draft, touched)).not.toHaveProperty('speedMasterUuid')
  })

  it('sends Seconds with no master beside it, and a master with Seconds false (D7)', () => {
    const onMaster = draftOf(makeActiveFx({ speedMasterUuid: 'm2' }))
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
    const unscaled = draftOf(makeActiveFx({ timingSource: 'WALL_CLOCK', rateSpeedMasterUuid: null }))
    expect(unscaled.seconds).toBe(true)
    const request = updateRequestOf(unscaled, new Set(['seconds', 'rateSpeedMasterUuid'] as const))
    expect(request.seconds).toBe(true)
    expect(request).not.toHaveProperty('rateSpeedMasterUuid')
  })

  it('reads size, direction and runs off the wire at their defaults, and sends them once touched (D2, D5, D9)', () => {
    const plain = draftOf(makeActiveFx({}))
    expect([plain.amount, plain.direction, plain.runs]).toEqual([1, 'FORWARD', null])
    expect(updateRequestOf(plain)).not.toHaveProperty('amount')
    const shaped = draftOf(makeActiveFx({ amount: 0.5, direction: 'BOUNCE', runs: 2 }))
    expect([shaped.amount, shaped.direction, shaped.runs]).toEqual([0.5, 'BOUNCE', 2])
    const touched = new Set(['amount', 'direction', 'runs'] as const)
    expect(updateRequestOf(shaped, touched)).toMatchObject({ amount: 0.5, direction: 'BOUNCE', runs: 2 })
    // A null count is said as runForever: the frame's absent runs means keep.
    const forever = updateRequestOf({ ...shaped, runs: null }, touched)
    expect(forever.runForever).toBe(true)
    expect(forever).not.toHaveProperty('runs')
  })

  it('writes a palette effect\'s amount, direction and runs where they say something, and reads them as edits', () => {
    const palette = {
      fxType: 'Pulse',
      family: 'intensity',
      cycle: 1,
      blendMode: 'OVERRIDE',
      order: 'NORMAL',
      parameters: {},
    }
    const draft = paletteDraftOf(palette)
    expect(withPaletteDraft(palette, draft)).not.toHaveProperty('amount')
    const shaped = withPaletteDraft(palette, { ...draft, amount: 0.4, direction: 'BACKWARD', runs: 1 })
    expect(shaped).toMatchObject({ amount: 0.4, direction: 'BACKWARD', runs: 1 })
    expect(paletteDraftOf(shaped)).toMatchObject({ amount: 0.4, direction: 'BACKWARD', runs: 1 })
    // Put back to the defaults, a palette that had said them says them again rather than dropping them.
    expect(withPaletteDraft(shaped, draft)).toMatchObject({ amount: 1, direction: 'FORWARD', runs: null })
    expect(differsFromPalette({ ...draft, amount: 0.5 }, palette, 'm1', {}, { order: false })).toBe(true)
    expect(differsFromPalette({ ...draft, direction: 'BOUNCE' }, palette, 'm1', {}, { order: false })).toBe(true)
    expect(differsFromPalette({ ...draft, runs: 2 }, palette, 'm1', {}, { order: false })).toBe(true)
    expect(differsFromPalette(draft, palette, 'm1', {}, { order: false })).toBe(false)
  })

  it('reads the spread\'s shape off the wire at its defaults, and sends each field once touched (D4)', () => {
    const plain = draftOf(makeActiveFx({}))
    expect([plain.spread, plain.wings, plain.segments, plain.parts]).toEqual([1, 1, 1, 0])
    expect(updateRequestOf(plain)).not.toHaveProperty('spread')
    const shaped = draftOf(makeActiveFx({ spread: 0.5, wings: 2, segments: 3, parts: 4 }))
    expect([shaped.spread, shaped.wings, shaped.segments, shaped.parts]).toEqual([0.5, 2, 3, 4])
    expect(updateRequestOf(shaped, new Set(['spread', 'parts'] as const))).toMatchObject({ spread: 0.5, parts: 4 })
    expect(updateRequestOf(shaped, new Set(['spread'] as const))).not.toHaveProperty('wings')
    // Parts 0 is how the frame turns them off — no flag of its own.
    expect(updateRequestOf({ ...shaped, parts: 0 }, new Set(['parts'] as const))).toMatchObject({ parts: 0 })
  })

  it('takes wings back to 1 when the order moves onto a mirrored one', () => {
    expect(orderPatch({ wings: 2 }, 'CENTRE_OUT')).toEqual({ order: 'CENTRE_OUT', wings: 1 })
    expect(orderPatch({ wings: 2 }, 'CENTRE_IN')).toEqual({ order: 'CENTRE_IN', wings: 1 })
    expect(orderPatch({ wings: 1 }, 'CENTRE_IN')).toEqual({ order: 'CENTRE_IN' })
    expect(orderPatch({ wings: 3 }, 'REVERSE')).toEqual({ order: 'REVERSE' })
  })

  it('writes a palette effect\'s shape where it says something, and reads it as an edit', () => {
    const palette = {
      fxType: 'Pulse',
      family: 'intensity',
      cycle: 1,
      blendMode: 'OVERRIDE',
      order: 'NORMAL',
      parameters: {},
    }
    const draft = paletteDraftOf(palette)
    expect(withPaletteDraft(palette, draft)).not.toHaveProperty('spread')
    const shaped = withPaletteDraft(palette, { ...draft, spread: 0.25, wings: 2, parts: 3 })
    expect(shaped).toMatchObject({ spread: 0.25, wings: 2, parts: 3 })
    expect(shaped).not.toHaveProperty('segments')
    expect(paletteDraftOf(shaped)).toMatchObject({ spread: 0.25, wings: 2, segments: 1, parts: 3 })
    expect(differsFromPalette({ ...draft, segments: 2 }, palette, 'm1', {}, { order: false })).toBe(true)
    expect(differsFromPalette({ ...draft, spread: 0.5 }, palette, 'm1', {}, { order: false })).toBe(true)
    expect(differsFromPalette(draft, palette, 'm1', {}, { order: false })).toBe(false)
  })

  it('counts a selection\'s heads as the desk expands one (effects plan D3)', () => {
    const dimmer = [{ name: 'dimmer', type: 'slider' }]
    const par = (key: string) => ({ key, properties: dimmer })
    const heads = (key: string, n: number) => Array.from({ length: n }, (_, i) => ({ key: `${key}.pixel-${i}`, properties: dimmer }))
    const bar = { key: 'bar', properties: [], elements: heads('bar', 12) }
    const strip = { key: 'strip', properties: [], elements: heads('strip', 4) }
    // Every unit a member: one dial each, a repeat counted once.
    expect(selectionHeadCount([par('p1'), par('p2'), par('p3'), par('p1')], 'dimmer', 'PER_FIXTURE', 'ALL')).toBe(3)
    // Every unit its heads: as a group of bars — each bar its own run, or one strip.
    expect(selectionHeadCount([bar, strip], 'dimmer', 'PER_FIXTURE', 'ALL')).toBe(12)
    expect(selectionHeadCount([bar, strip], 'dimmer', 'FLAT', 'ALL')).toBe(16)
    // A mix: each member a run of one beside the bar's twelve — one strip, or the longest run.
    expect(selectionHeadCount([par('p1'), bar, par('p2')], 'dimmer', 'FLAT', 'ALL')).toBe(14)
    expect(selectionHeadCount([par('p1'), bar, par('p2')], 'dimmer', 'FLAT', 'ODD')).toBe(7)
    expect(selectionHeadCount([par('p1'), bar, par('p2')], 'dimmer', 'PER_FIXTURE', 'ALL')).toBe(12)
    // A head named twice counts once, at its first place: a cell, then its bar's other eleven.
    expect(selectionHeadCount([{ key: 'bar.pixel-2', properties: dimmer }, bar], 'dimmer', 'FLAT', 'ALL')).toBe(12)
    expect(selectionHeadCount([bar, { key: 'bar.pixel-2', properties: dimmer }], 'dimmer', 'FLAT', 'ALL')).toBe(12)
    // Only heads that carry the property; a unit with neither is skipped.
    const mixed = { key: 'm', properties: [], elements: [...heads('m', 2), { key: 'm.uv', properties: [{ name: 'uv', type: 'slider' }] }] }
    expect(selectionHeadCount([mixed], 'dimmer', 'FLAT', 'ALL')).toBe(2)
    expect(selectionHeadCount([par('p1'), { key: 'x', properties: [] }], 'dimmer', 'PER_FIXTURE', 'ALL')).toBe(1)
  })

  it('counts a live effect\'s heads for the dials as the desk distributes them', () => {
    const bar = { elements: Array.from({ length: 12 }) }
    const par = { elements: null }
    expect(spreadHeadCount([par, par, par], true, 'dimmer', 'PER_FIXTURE', 'ALL')).toBe(3)
    expect(spreadHeadCount([bar], false, 'dimmer', 'PER_FIXTURE', 'ALL')).toBe(12)
    expect(spreadHeadCount([bar], false, 'dimmer', 'PER_FIXTURE', 'ODD')).toBe(6)
    expect(spreadHeadCount([bar, bar], true, 'dimmer', 'FLAT', 'FIRST_HALF')).toBe(12)
    expect(spreadHeadCount([bar, bar], true, 'dimmer', 'PER_FIXTURE', 'ALL')).toBe(12)
  })

  it('counts a fixture that carries the property itself once, whatever heads it has (the desk\'s DIRECT_FIXTURE)', () => {
    // A Slender 14ch: a master dimmer on the fixture, four cells beneath. A Pulse on the master runs
    // once on the desk — one dial, not four — and a group of three such bars runs three members.
    const slender = { properties: [{ name: 'dimmer', type: 'slider' }], elements: Array.from({ length: 4 }) }
    expect(spreadHeadCount([slender], false, 'dimmer', 'PER_FIXTURE', 'ALL')).toBe(1)
    expect(spreadHeadCount([slender, slender, slender], true, 'dimmer', 'FLAT', 'ALL')).toBe(3)
    expect(spreadHeadCount([slender, slender, slender], true, 'dimmer', 'PER_FIXTURE', 'ODD')).toBe(3)
    // A property only its cells carry spreads over the cells.
    expect(spreadHeadCount([slender], false, 'rgbColour', 'PER_FIXTURE', 'ALL')).toBe(4)
    // Colour and position by trait, as the desk's WithColour / WithPosition.
    expect(carriesFxProperty([{ name: 'colour', type: 'colour' }], 'rgbColour')).toBe(true)
    expect(carriesFxProperty([{ name: 'warmth', type: 'whiteMix' }], 'rgbColour')).toBe(true)
    expect(carriesFxProperty([{ name: 'pt', type: 'position' }], 'position')).toBe(true)
    expect(carriesFxProperty([{ name: 'dimmer', type: 'slider' }], 'rgbColour')).toBe(false)
  })

  it('filters a FLAT group across the whole strip on its global index, as the desk does', () => {
    const three = { elements: Array.from({ length: 3 }) }
    // Nine heads under Odd keep 1st, 3rd, … 9th: five — not three bars' ceil(3/2) summed, six.
    expect(spreadHeadCount([three, three, three], true, 'rgbColour', 'FLAT', 'ODD')).toBe(5)
    // Six heads' first half is three — not two bars' first halves summed, four.
    expect(spreadHeadCount([three, three], true, 'rgbColour', 'FLAT', 'FIRST_HALF')).toBe(3)
    // Per fixture, each bar is filtered within itself.
    expect(spreadHeadCount([three, three], true, 'rgbColour', 'PER_FIXTURE', 'FIRST_HALF')).toBe(2)
    expect(filteredElementCount(5, 'EVEN')).toBe(2)
    expect(filteredElementCount(5, 'SECOND_HALF')).toBe(2)
  })

  it('reads the desk\'s Seconds off a running effect, whatever loop it reports', () => {
    expect(draftOf(makeActiveFx({ timingSource: 'WALL_CLOCK', seconds: true })).seconds).toBe(true)
    expect(draftOf(makeActiveFx({ timingSource: 'WALL_CLOCK', rateSpeedMasterUuid: 'm2' })).seconds).toBe(false)
  })

  it('keeps a beat effect\'s speed when the chip moves it onto Seconds and back', () => {
    const bpm = (uuid: string | null) => (uuid === 'm2' ? 60 : 120)
    const beat = { cycle: 4, seconds: false, speedMasterUuid: null, rateSpeedMasterUuid: null }
    // Four beats of master 1 at 120 BPM is two seconds.
    expect(timingChoicePatch(beat, false, null, bpm)).toEqual({
      seconds: true,
      speedMasterUuid: null,
      rateSpeedMasterUuid: null,
      cycle: 2,
    })
    // Two seconds onto master 2 at 60 BPM is two beats.
    expect(timingChoicePatch({ ...beat, seconds: true, cycle: 2 }, false, 'm2', bpm)).toEqual({
      seconds: false,
      speedMasterUuid: 'm2',
      cycle: 2,
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

  it('files an unknown category under Controls', () => {
    expect(pickerFamilyOf('intensity')).toBe('INTENSITY')
    expect(pickerFamilyOf('scripted')).toBe('CONTROLS')
  })

  it('files a beam effect under Beam, and a Beam row opens there', () => {
    expect(pickerFamilyOf('beam')).toBe('BEAM')
    expect(pickerFamilyForSheet('BEAM')).toBe('BEAM')
  })
})

describe("a pad's instance against its palette (§3.3, D20)", () => {
  const template = {
    fxType: 'Circle',
    family: 'position',
    cycle: 1,
    blendMode: 'ADDITIVE',
    order: 'CENTRE_OUT',
    phaseOffset: 0,
    parameters: { panCenter: '128', tiltCenter: '128', panRadius: '64', tiltRadius: '64' },
    speedMasterUuid: null,
    rateSpeedMasterUuid: null,
  }
  // A single fixture's instance: the desk leaves its distribution out, which `draftOf` reads NORMAL.
  const instance = draftOf(
    makeActiveFx({ fxType: 'Circle', blendMode: 'ADDITIVE', order: null, parameters: { ...template.parameters, panRadius: '64.0' } }),
  )

  it('is not edited for a distribution the instance never said, nor for a number spelled differently', () => {
    expect(instance.order).toBe('NORMAL')
    expect(differsFromPalette(instance, template, 'm1', {}, { order: false })).toBe(false)
    // Asked to compare it, the guess reads as an edit — which is why the editor does not.
    expect(differsFromPalette(instance, template, 'm1', {}, { order: true })).toBe(true)
    expect(differsFromPalette({ ...instance, parameters: { ...instance.parameters, panRadius: '90' } }, template, 'm1', {}, { order: false })).toBe(true)
    // A null master is master 1 on either side.
    expect(differsFromPalette({ ...instance, speedMasterUuid: 'm1' }, template, 'm1', {}, { order: false })).toBe(false)
  })

  it('keeps the palette’s own distribution on Update where the instance said none', () => {
    expect(withPaletteDraft(template, instance, { order: false }).order).toBe('CENTRE_OUT')
    expect(withPaletteDraft(template, { ...instance, order: 'RANDOM' }).order).toBe('RANDOM')
  })
})
