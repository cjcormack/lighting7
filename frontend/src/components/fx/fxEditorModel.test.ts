// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest'

vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

import type { FxLibraryEntry } from '@/store/fixtureFx'
import { makeActiveFx } from '@/test/fixtureFactories'
import {
  centreFlip,
  centreModeOf,
  hasCentre,
  isAroundSpelling,
  storedCentreMode,
  differsFromPalette,
  filteredElementCount,
  orderPatch,
  selectionHeadCount,
  spreadHeadCount,
  carriesFxAttribute,
  withPaletteDraft,
  draftOf,
  levelModeOf,
  isParamShown,
  paramAxis,
  paramRole,
  paramView,
  pickerFamilyForSheet,
  pickerFamilyOf,
  startingSpec,
  fxSwapOptions,
  swappedDraft,
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
  compatibleAttributes: ['dimmer'],
  parameters: [],
  ...over,
})
const p = (name: string, type: string, defaultValue = '0') => ({ name, type, defaultValue, description: '' })

const CIRCLE = entry({
  name: 'Circle',
  family: 'position',
  parameters: [p('panCentre', 'ubyte', '128'), p('tiltCentre', 'ubyte', '128'), p('panRadius', 'ubyte', '64'), p('tiltRadius', 'ubyte', '64')],
})
const SWEEP = entry({ name: 'Sweep', family: 'position', parameters: [p('startPan', 'ubyte'), p('curve', 'easingCurve', 'LINEAR')] })
const PULSE = entry({ name: 'Pulse', parameters: [p('min', 'ubyte'), p('max', 'ubyte', '255'), p('attackRatio', 'double', '0.1')] })
const CYCLE = entry({ name: 'Colour Cycle', family: 'colour', parameters: [p('colours', 'colourList'), p('fadeRatio', 'double', '0.5')] })

describe('fxEditorModel', () => {
  it('reads Around only as Additive with both centres at 128', () => {
    expect(centreModeOf('ADDITIVE', { panCentre: '128', tiltCentre: '128' })).toBe('around')
    expect(centreModeOf('ADDITIVE', { panCentre: '100', tiltCentre: '128' })).toBeNull()
    expect(centreModeOf('OVERRIDE', { panCentre: '100', tiltCentre: '40' })).toBe('absolute')
    expect(centreModeOf('MAX', { panCentre: '128', tiltCentre: '128' })).toBeNull()
  })

  it('spells Around as Additive with the centre pinned, and Absolute as Override keeping it', () => {
    const around = withCentreMode({ blendMode: 'OVERRIDE', parameters: { panCentre: '10', tiltCentre: '20', panRadius: '5' } }, 'around')
    expect(around).toEqual({ blendMode: 'ADDITIVE', parameters: { panCentre: '128', tiltCentre: '128', panRadius: '5' } })
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
    expect(roles(CIRCLE)).toEqual({ panCentre: 'centre', tiltCentre: 'centre', panRadius: 'size', tiltRadius: 'size' })
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

  it('reads, writes and compares a palette\'s Over, but not one the instance did not report', () => {
    const palette = { fxType: 'ColourCycle', family: 'colour', cycle: 4, blendMode: 'OVERRIDE', order: 'NORMAL', parameters: {} }
    const draft = paletteDraftOf(palette)
    expect(draft.elementMode).toBe('PER_FIXTURE')
    expect(withPaletteDraft(palette, draft)).not.toHaveProperty('elementMode')
    expect(withPaletteDraft(palette, { ...draft, elementMode: 'WHOLE' })).toMatchObject({ elementMode: 'WHOLE' })
    expect(paletteDraftOf({ ...palette, elementMode: 'FLAT' }).elementMode).toBe('FLAT')
    expect(differsFromPalette({ ...draft, elementMode: 'WHOLE' }, palette, 'm1')).toBe(true)
    // A running instance that lands on no elements reports no mode, so its draft's guess is neither an edit nor written.
    const whole = { ...palette, elementMode: 'WHOLE' }
    expect(differsFromPalette(draft, whole, 'm1', {}, { elementMode: false })).toBe(false)
    expect(withPaletteDraft(whole, draft, { elementMode: false })).toMatchObject({ elementMode: 'WHOLE' })
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
    const par = (key: string) => ({ key, attributes: dimmer })
    const heads = (key: string, n: number) => Array.from({ length: n }, (_, i) => ({ key: `${key}.pixel-${i}`, attributes: dimmer }))
    const bar = { key: 'bar', attributes: [], elements: heads('bar', 12) }
    const strip = { key: 'strip', attributes: [], elements: heads('strip', 4) }
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
    expect(selectionHeadCount([{ key: 'bar.pixel-2', attributes: dimmer }, bar], 'dimmer', 'FLAT', 'ALL')).toBe(12)
    expect(selectionHeadCount([bar, { key: 'bar.pixel-2', attributes: dimmer }], 'dimmer', 'FLAT', 'ALL')).toBe(12)
    // Only heads that carry the property; a unit with neither is skipped.
    const mixed = { key: 'm', attributes: [], elements: [...heads('m', 2), { key: 'm.uv', attributes: [{ name: 'uv', type: 'slider' }] }] }
    expect(selectionHeadCount([mixed], 'dimmer', 'FLAT', 'ALL')).toBe(2)
    expect(selectionHeadCount([par('p1'), { key: 'x', attributes: [] }], 'dimmer', 'PER_FIXTURE', 'ALL')).toBe(1)
  })

  it('counts a live effect\'s heads for the dials as the desk distributes them', () => {
    const bar = { elements: Array.from({ length: 12 }) }
    const par = { elements: null }
    expect(spreadHeadCount([par, par, par], true, 'dimmer', 'PER_FIXTURE', 'ALL')).toBe(3)
    expect(spreadHeadCount([bar], false, 'dimmer', 'PER_FIXTURE', 'ALL')).toBe(12)
    expect(spreadHeadCount([bar], false, 'dimmer', 'PER_FIXTURE', 'ODD')).toBe(6)
    expect(spreadHeadCount([bar, bar], true, 'dimmer', 'FLAT', 'FIRST_HALF')).toBe(12)
    expect(spreadHeadCount([bar, bar], true, 'dimmer', 'PER_FIXTURE', 'ALL')).toBe(12)
    // Fixtures (FX editor follow-ups D7): one unit a fixture with heads, whatever the filter.
    expect(spreadHeadCount([bar, bar, par], true, 'dimmer', 'WHOLE', 'ODD')).toBe(2)
    expect(spreadHeadCount([bar], false, 'dimmer', 'WHOLE', 'ALL')).toBe(1)
    expect(selectionHeadCount([{ key: 'b1', attributes: [], elements: [{ key: 'b1.h1', attributes: [{ name: 'dimmer', type: 'slider' }] }, { key: 'b1.h2', attributes: [{ name: 'dimmer', type: 'slider' }] }] }, { key: 'p', attributes: [{ name: 'dimmer', type: 'slider' }] }], 'dimmer', 'WHOLE', 'ALL')).toBe(2)
  })

  it('counts a fixture that carries the property itself once, whatever heads it has (the desk\'s DIRECT_FIXTURE)', () => {
    // A Slender 14ch: a master dimmer on the fixture, four cells beneath. A Pulse on the master runs
    // once on the desk — one dial, not four — and a group of three such bars runs three members.
    const slender = { attributes: [{ name: 'dimmer', type: 'slider' }], elements: Array.from({ length: 4 }) }
    expect(spreadHeadCount([slender], false, 'dimmer', 'PER_FIXTURE', 'ALL')).toBe(1)
    expect(spreadHeadCount([slender, slender, slender], true, 'dimmer', 'FLAT', 'ALL')).toBe(3)
    expect(spreadHeadCount([slender, slender, slender], true, 'dimmer', 'PER_FIXTURE', 'ODD')).toBe(3)
    // A property only its cells carry spreads over the cells.
    expect(spreadHeadCount([slender], false, 'rgbColour', 'PER_FIXTURE', 'ALL')).toBe(4)
    // Colour and position by trait, as the desk's WithColour / WithPosition.
    expect(carriesFxAttribute([{ name: 'colour', type: 'colour' }], 'rgbColour')).toBe(true)
    expect(carriesFxAttribute([{ name: 'warmth', type: 'whiteMix' }], 'rgbColour')).toBe(true)
    expect(carriesFxAttribute([{ name: 'pt', type: 'position' }], 'position')).toBe(true)
    expect(carriesFxAttribute([{ name: 'dimmer', type: 'slider' }], 'rgbColour')).toBe(false)
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
    expect(paramAxis('panCentre')).toBe('pan')
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
    parameters: { panCentre: '128', tiltCentre: '128', panRadius: '64', tiltRadius: '64' },
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

describe('paramView (fx-settings plan D1)', () => {
  it('reads what the desk declares, and guesses only what it leaves out', () => {
    const attack = { ...p('attackRatio', 'double', '0.1'), label: 'Attack', min: 0, max: 1, unit: 'ratio' as const, role: 'detail' as const }
    const pulse = entry({ parameters: [p('min', 'ubyte'), attack] })
    expect(paramView(pulse, attack)).toEqual({ label: 'Attack', unit: 'ratio', min: 0, max: 1, step: 0.01, fold: 'detail', open: false })
    // Labelled only: the rest is still guessed — a ratio beside a level, folded.
    const speed = { ...p('speedRatio', 'double', '0.3'), label: 'Speed' }
    expect(paramView(entry({ parameters: [p('min', 'ubyte'), speed] }), speed)).toEqual({
      label: 'Speed',
      unit: 'ratio',
      min: 0,
      max: 1,
      step: 0.01,
      fold: 'detail',
      open: false,
    })
  })

  it("guesses as paramRole always has for a parameter that declares nothing", () => {
    const circle = paramView(CIRCLE, CIRCLE.parameters[0])
    expect(circle).toMatchObject({ label: 'Pan', unit: 'position', min: 0, max: 255, fold: 'main' })
    expect(paramView(CIRCLE, CIRCLE.parameters[2])).toMatchObject({ label: 'Pan size', unit: 'dmx' })
    const pulse = entry({ parameters: [p('min', 'ubyte'), p('holdRatio', 'double', '0.3')] })
    expect(paramView(pulse, pulse.parameters[0])).toMatchObject({ label: 'Low', unit: '%' })
    expect(paramView(pulse, pulse.parameters[1])).toMatchObject({ label: 'Hold', unit: 'ratio', fold: 'detail' })
  })

  it('shows a parameter only while its shownWhen holds, against the default when unset', () => {
    const shape = { ...p('shape', 'choice', 'SINE'), options: [{ value: 'SINE', label: 'Sine' }, { value: 'SQUARE', label: 'Square' }] }
    const width = { ...p('width', 'double', '0.5'), shownWhen: { param: 'shape', values: ['SQUARE'] } }
    const wave = entry({ parameters: [shape, width] })
    expect(isParamShown(wave, width, {})).toBe(false)
    expect(isParamShown(wave, width, { shape: 'square' })).toBe(true)
    expect(isParamShown(wave, shape, {})).toBe(true)
  })
})


describe('the Centre question on a Path (fx-settings plan session 3)', () => {
  const centred = { param: 'shape', values: ['CIRCLE', 'FIGURE_8', 'RANDOM'] }
  const PATH: FxLibraryEntry = {
    name: 'Path',
    family: 'position',
    outputType: 'POSITION',
    fxMode: 'STANDARD',
    compatibleAttributes: ['position'],
    parameters: [
      { name: 'shape', type: 'choice', defaultValue: 'CIRCLE', description: '' },
      { name: 'panCentre', type: 'ubyte', defaultValue: '128', description: '', shownWhen: centred },
      { name: 'tiltCentre', type: 'ubyte', defaultValue: '128', description: '', shownWhen: centred },
      { name: 'startPan', type: 'ubyte', defaultValue: '64', description: '', shownWhen: { param: 'shape', values: ['LINE'] } },
    ],
  }

  it('asks it of a shape whose centre is drawn, and never of a Line', () => {
    expect(hasCentre(PATH)).toBe(true)
    expect(hasCentre(PATH, { shape: 'FIGURE_8' })).toBe(true)
    expect(hasCentre(PATH, { shape: 'LINE' })).toBe(false)
    expect(hasCentre(PATH, { shape: 'line' })).toBe(false)
  })

  it('starts a Circle Around, both centres pinned, and a Line on Override', () => {
    expect(startingSpec(PATH)).toEqual({
      blendMode: 'ADDITIVE',
      parameters: { shape: 'CIRCLE', panCentre: '128', tiltCentre: '128', startPan: '64' },
    })
    expect(startingSpec(PATH, { preset: { parameters: { shape: 'LINE' } } }).blendMode).toBe('OVERRIDE')
  })

  it('reads a stored Line as asking nothing', () => {
    expect(storedCentreMode({ blendMode: 'ADDITIVE', parameters: { shape: 'LINE', panCentre: '128', tiltCentre: '128' } }, PATH)).toBeNull()
    expect(storedCentreMode({ blendMode: 'ADDITIVE', parameters: { shape: 'CIRCLE' } }, PATH)).toBe('around')
  })

  it('moves the blend with a shape change across a Line, and leaves any other change alone', () => {
    const around = { blendMode: 'ADDITIVE', parameters: { shape: 'CIRCLE', panCentre: '128', tiltCentre: '128' } }
    expect(centreFlip(PATH, around, 'shape', 'LINE')).toEqual({ blendMode: 'OVERRIDE', parameters: { ...around.parameters, shape: 'LINE' } })
    const line = { blendMode: 'OVERRIDE', parameters: { shape: 'LINE' } }
    expect(centreFlip(PATH, line, 'shape', 'CIRCLE')).toEqual({
      blendMode: 'ADDITIVE',
      parameters: { shape: 'CIRCLE', panCentre: '128', tiltCentre: '128' },
    })
    // An Absolute circle taken through a Line comes back Absolute, where it was.
    const absoluteCircle = { blendMode: 'OVERRIDE', parameters: { shape: 'CIRCLE', panCentre: '40', tiltCentre: '200' } }
    const throughLine = centreFlip(PATH, absoluteCircle, 'shape', 'LINE')!
    expect(throughLine).toEqual({ blendMode: 'OVERRIDE', parameters: { shape: 'LINE', panCentre: '40', tiltCentre: '200' } })
    expect(centreFlip(PATH, throughLine, 'shape', 'CIRCLE')).toEqual({
      blendMode: 'OVERRIDE',
      parameters: { shape: 'CIRCLE', panCentre: '40', tiltCentre: '200' },
    })
    expect(centreFlip(PATH, around, 'shape', 'FIGURE_8')).toBeNull()
    expect(centreFlip(PATH, around, 'panCentre', '40')).toBeNull()
    const absolute = { blendMode: 'MAX', parameters: { shape: 'CIRCLE' } }
    expect(centreFlip(PATH, absolute, 'shape', 'LINE')?.blendMode).toBe('MAX')
  })
})

describe('isAroundSpelling on a Path (fx-settings plan session 3)', () => {
  it('reads a Circle on Additive at 128 as around, and a Line never', () => {
    const at128 = { panCentre: '128', tiltCentre: '128' }
    expect(isAroundSpelling({ family: 'position', blendMode: 'ADDITIVE', parameters: { shape: 'CIRCLE', ...at128 } })).toBe(true)
    expect(isAroundSpelling({ family: 'position', blendMode: 'ADDITIVE', parameters: { shape: 'LINE', ...at128 } })).toBe(false)
  })
})

describe('swappedDraft — the header’s type swap (fx-settings plan D9)', () => {
  const base = () => draftOf(makeActiveFx({ fxType: 'Circle', blendMode: 'ADDITIVE', cycle: 4, parameters: { panRadius: '90' } }))
  const FIGURE8 = entry({ ...CIRCLE, name: 'Figure 8' })

  it('takes the new type’s starting parameters and blend, carrying a timing the operator chose', () => {
    const next = swappedDraft({ ...base(), cycle: 2, direction: 'BACKWARD' }, CIRCLE, PULSE, 'dimmer')
    expect(next.parameters).toEqual({ min: '0', max: '255', attackRatio: '0.1' })
    expect(next.blendMode).toBe('OVERRIDE')
    expect([next.cycle, next.direction]).toEqual([2, 'BACKWARD'])
  })

  it('keeps a blend changed from the old type’s start — a stacked FX’s', () => {
    expect(swappedDraft({ ...base(), blendMode: 'MULTIPLY' }, CIRCLE, PULSE).blendMode).toBe('MULTIPLY')
    // A Circle swapped for a Figure 8 is still Around.
    expect(swappedDraft(base(), CIRCLE, FIGURE8).blendMode).toBe('ADDITIVE')
  })

  it('starts the division afresh across a beat and a wall-clock FX', () => {
    const FLICKER = entry({ name: 'Flicker', timingSource: 'WALL_CLOCK', defaultCycle: 4, parameters: [] })
    expect(swappedDraft({ ...base(), cycle: 16 }, PULSE, FLICKER).cycle).toBe(4)
    expect(swappedDraft({ ...base(), cycle: 16 }, PULSE, entry({ name: 'Other', parameters: [] })).cycle).toBe(16)
  })

  it('moves a step spread onto the cycle at the offsets it ran at over the heads, else the cycle’s own', () => {
    const stepped = { ...base(), spreadUnit: 'STEP' as const, spread: 1, parameters: { colours: '#ff0000,#00ff00,#0000ff,#ffffff' } }
    const FOUR = entry({ ...CYCLE, stepsFrom: 'colours' })
    // Four colours over eight heads, one step each: a quarter of the cycle between neighbours, which
    // the desk's cycleSpread keeps as 8 / 4; over twelve, 3 is capped at the Spread's 200 %.
    expect(swappedDraft(stepped, FOUR, PULSE, 'dimmer', 8)).toMatchObject({ spreadUnit: 'CYCLE', spread: 2 })
    expect(swappedDraft(stepped, FOUR, PULSE, 'dimmer', 6)).toMatchObject({ spreadUnit: 'CYCLE', spread: 1.5 })
    expect(swappedDraft(stepped, FOUR, PULSE, 'dimmer', 12)).toMatchObject({ spreadUnit: 'CYCLE', spread: 2 })
    // No heads to keep the offsets over (a palette): the cycle's own spread.
    expect(swappedDraft({ ...stepped, spread: 2 }, FOUR, PULSE)).toMatchObject({ spreadUnit: 'CYCLE', spread: 1 })
    expect(swappedDraft(stepped, CYCLE, entry({ ...CYCLE, name: 'Palette Steps', stepsFrom: 'palettes' }))).toMatchObject({
      spreadUnit: 'STEP',
      spread: 1,
    })
  })
})

describe('fxSwapOptions', () => {
  it('lists the FX the attribute takes but Palette Steps, the current one first where the listing leaves it out', () => {
    const steps = entry({ name: 'Palette Steps', compatibleAttributes: ['dimmer'] })
    const legacy = entry({ name: 'Old Pulse', legacy: true })
    const library = [PULSE, entry({ ...CIRCLE, compatibleAttributes: ['position'] }), steps, legacy]
    expect(fxSwapOptions(library, 'dimmer', PULSE).map((e) => e.name)).toEqual(['Pulse'])
    expect(fxSwapOptions(library, 'dimmer', legacy).map((e) => e.name)).toEqual(['Old Pulse', 'Pulse'])
  })
})
