// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import type { FxLibraryEntry } from '@/store/fixtureFx'

/**
 * `FxFields` — the effect editor's fields, which the live `FxEditor` and `PaletteEditor`
 * (fixture-fx-sheets plan D16) both mount. These are the pins the deleted `EffectParameterForm`'s
 * suite held, re-made on the fields that replaced it: the timing-source gate (a wall-clock effect
 * types seconds and chips its **rate** master; a beat effect segments beats and chips its speed
 * master), an int's slider range from its declared default alone with the field reaching past it,
 * and a parameter's own sentence drawn under its control.
 */
vi.mock('@/components/ui/slider', () => ({
  Slider: ({
    min,
    max,
    step,
    value,
    disabled,
    onValueChange,
    onValueCommit,
    'aria-label': label,
  }: {
    min: number
    max: number
    step: number
    value: number[]
    disabled?: boolean
    onValueChange?: (v: number[]) => void
    onValueCommit?: (v: number[]) => void
    'aria-label'?: string
  }) => (
    <>
      {value.map((v, i) => (
        <input
          key={i}
          type="range"
          aria-label={value.length > 1 ? `${label} ${i + 1}` : label}
          min={min}
          max={max}
          step={step}
          value={v}
          disabled={disabled}
          onChange={(e) => onValueChange?.(value.map((x, j) => (j === i ? Number(e.target.value) : x)).sort((a, b) => a - b))}
          onPointerUp={(e) =>
            onValueCommit?.(value.map((x, j) => (j === i ? Number((e.target as HTMLInputElement).value) : x)).sort((a, b) => a - b))
          }
        />
      ))}
    </>
  ),
}))

vi.mock('@/store/speedMasters', () => ({
  useSpeedMasterLiveQuery: () => ({ data: [{ uuid: 'm1', index: 1, name: 'Main', bpm: 120 }, { uuid: 'm2', index: 2, name: 'Slow', bpm: 60 }] }),
}))
vi.mock('./FxColourPicker', () => ({ FxColourPicker: () => null }))
vi.mock('./FxColourListPicker', () => ({ FxColourListPicker: () => null }))

import { FxFields } from './FxFields'
import type { FxDraft, FxScope } from './fxEditorModel'

const FLICKER: FxLibraryEntry = {
  name: 'Flicker',
  family: 'intensity',
  outputType: 'SLIDER',
  fxMode: 'STANDARD',
  timingSource: 'WALL_CLOCK',
  compatibleAttributes: ['dimmer'],
  parameters: [
    { name: 'flickerDurationMs', type: 'int', defaultValue: '200', description: 'How long one flicker lasts' },
    { name: 'max', type: 'ubyte', defaultValue: '255', description: 'The brightest a flicker reaches' },
  ],
}
const PULSE: FxLibraryEntry = { ...FLICKER, name: 'Pulse', timingSource: 'BEAT', parameters: [] }

const SCOPE: FxScope = { heads: false, elementMode: false, elementFilter: false }
const draft = (over: Partial<FxDraft> = {}): FxDraft => ({
  parameters: { flickerDurationMs: '200', max: '255' },
  cycle: 2,
  blendMode: 'OVERRIDE',
  phaseOffset: 0,
  timing: 'CYCLE',
  order: 'NORMAL',
  elementMode: 'PER_FIXTURE',
  elementFilter: 'ALL',
  speedMasterUuid: null,
  rateSpeedMasterUuid: null,
  seconds: false,
  amount: 1,
  direction: 'FORWARD',
  runs: null,
  spread: 1,
  wings: 1,
  segments: 1,
  parts: 0,
  ...over,
})

function fields(entry: FxLibraryEntry, d: FxDraft, scope: FxScope = SCOPE) {
  const set = vi.fn()
  const setParam = vi.fn()
  render(
    <FxFields
      entry={entry}
      timingSource={entry.timingSource}
      draft={d}
      scope={scope}
      extendedChannels={undefined}
      settingAttribute={undefined}
      readOnly={false}
      set={set}
      setParam={setParam}
    />,
  )
  return { set, setParam }
}

afterEach(cleanup)

describe('FxFields', () => {
  it('gives a wall-clock effect with no rate master Seconds, and a beat effect beats and its speed master', () => {
    fields(FLICKER, draft())
    expect(screen.getByRole('spinbutton', { name: /Cycle seconds/ }).closest('div')).toBeTruthy()
    expect(screen.queryByRole('radiogroup', { name: 'Cycle' })).toBeNull()
    expect(screen.getByRole('button', { name: 'Timing: Seconds' })).toBeTruthy()
    cleanup()
    fields(PULSE, draft())
    expect(screen.getByRole('radiogroup', { name: 'Cycle' })).toBeTruthy()
    expect(screen.queryByRole('spinbutton', { name: /Cycle seconds/ })).toBeNull()
    expect(screen.getByRole('button', { name: 'Timing: M1 · 120' })).toBeTruthy()
  })

  it('types a beat effect on Seconds in seconds, with no master named', () => {
    fields(PULSE, draft({ seconds: true, cycle: 10 }))
    expect(screen.getByRole('spinbutton', { name: /Cycle seconds/ })).toBeTruthy()
    expect(screen.queryByRole('radiogroup', { name: 'Cycle' })).toBeNull()
    expect(screen.getByRole('button', { name: 'Timing: Seconds' })).toBeTruthy()
    expect(screen.getByText('seconds a cycle · follows no master')).toBeTruthy()
  })

  it('names a stored rate master the bank does not hold as M1, the master the desk runs it on — not Seconds', () => {
    fields(FLICKER, draft({ rateSpeedMasterUuid: 'gone' }))
    expect(screen.getByRole('button', { name: 'Timing: rate M1 · 120' })).toBeTruthy()
  })

  it('speaks the segment in the one spelling the chips use — beats a cycle, bars from four', () => {
    fields(PULSE, draft({ cycle: 4 }))
    const segment = screen.getByRole('radiogroup', { name: 'Cycle' })
    const labels = Array.from(segment.querySelectorAll('button')).map((b) => b.textContent)
    expect(labels).toEqual(['⅛ beat', '¼ beat', '½ beat', '1 beat', '2 beats', '1 bar', '2 bars', '4 bars'])
    expect(screen.getByRole('radio', { name: '1 bar a cycle' }).getAttribute('aria-checked')).toBe('true')
  })

  it('holds an int slider at twice its declared default, and lets the field type past it', () => {
    const { setParam } = fields(FLICKER, draft())
    const slider = screen.getByRole('slider', { name: 'Flicker Duration Ms' })
    expect(slider.getAttribute('max')).toBe('400')
    const field = screen.getByRole('spinbutton', { name: /Flicker Duration Ms/ })
    fireEvent.change(field, { target: { value: '900' } })
    fireEvent.keyDown(field, { key: 'Enter' })
    expect(setParam).toHaveBeenLastCalledWith('flickerDurationMs', '400', true)
  })

  it('puts Amount in percent and Direction as three glyphs beside Cycle (effects plan D11)', () => {
    const { set } = fields(PULSE, draft({ amount: 0.6, direction: 'BACKWARD' }))
    const size = screen.getByRole('spinbutton', { name: /Amount/ }) as HTMLInputElement
    expect(size.value).toBe('60')
    fireEvent.change(size, { target: { value: '40' } })
    fireEvent.keyDown(size, { key: 'Enter' })
    expect(set).toHaveBeenLastCalledWith({ amount: 0.4 }, true)
    const direction = screen.getByRole('radiogroup', { name: 'Direction' })
    expect(Array.from(direction.querySelectorAll('button')).map((b) => b.getAttribute('aria-label'))).toEqual([
      'Forward',
      'Backward',
      'Bounce',
    ])
    expect(screen.getByRole('radio', { name: 'Backward' }).getAttribute('aria-checked')).toBe('true')
    fireEvent.click(screen.getByRole('radio', { name: 'Bounce' }))
    expect(set).toHaveBeenLastCalledWith({ direction: 'BOUNCE' })
  })

  it('keeps Runs under Advanced: Forever, or a count (effects plan D9)', () => {
    const { set } = fields(PULSE, draft())
    expect(screen.queryByRole('radiogroup', { name: 'Runs' })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: /Advanced/ }))
    expect(screen.getByRole('radio', { name: 'Forever' }).getAttribute('aria-checked')).toBe('true')
    fireEvent.click(screen.getByRole('radio', { name: 'Count' }))
    expect(set).toHaveBeenLastCalledWith({ runs: 1 })
    cleanup()
    const second = fields(PULSE, draft({ runs: 3 }))
    // Folded, the summary says the count.
    expect(screen.getByRole('button', { name: /Advanced.*3 runs/ })).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: /Advanced/ }))
    const count = screen.getByRole('spinbutton', { name: 'Runs' })
    fireEvent.change(count, { target: { value: '2' } })
    fireEvent.keyDown(count, { key: 'Enter' })
    expect(second.set).toHaveBeenLastCalledWith({ runs: 2 })
    fireEvent.click(screen.getByRole('radio', { name: 'Forever' }))
    expect(second.set).toHaveBeenLastCalledWith({ runs: null })
  })

  it("draws a parameter's own sentence under its control", () => {
    fields(FLICKER, draft())
    expect(screen.getByText('How long one flicker lasts')).toBeTruthy()
    expect(screen.getByText('The brightest a flicker reaches')).toBeTruthy()
  })

  // ─── Spread (effects plan D11, §4.1) ───────────────────────────────────────────────────────────

  const HEADS: FxScope = { heads: true, elementMode: false, elementFilter: false }

  it('draws Spread and Order only where there are heads, with the Order always visible beside Direction', () => {
    fields(PULSE, draft())
    expect(screen.queryByRole('button', { name: /^Spread/ })).toBeNull()
    expect(screen.queryByRole('combobox', { name: 'Order' })).toBeNull()
    cleanup()
    fields(PULSE, draft({ order: 'NORMAL', spread: 0.5, wings: 2 }), HEADS)
    // Folded, the summary says the Shape and the non-defaults. The label and the summary are two
    // spans with only a CSS gap between them, so the accessible name has no space after "Spread".
    expect(screen.getByRole('button', { name: /^Spread\s*· Custom · 50 % · 2 wings$/ })).toBeTruthy()
    expect(screen.getByRole('combobox', { name: 'Order' })).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: /Advanced/ }))
    expect(screen.queryByRole('combobox', { name: 'Distribution' })).toBeNull()
  })

  it('draws Over beside Order where a fixture has elements, and nothing of it in Advanced (FX editor follow-ups D7)', () => {
    fields(PULSE, draft(), HEADS)
    expect(screen.queryByRole('radiogroup', { name: 'Over' })).toBeNull()
    cleanup()
    const { set } = fields(PULSE, draft({ elementMode: 'PER_FIXTURE' }), { heads: true, elementMode: true, elementFilter: true })
    const over = screen.getByRole('radiogroup', { name: 'Over' })
    expect([...over.querySelectorAll('[role="radio"]')].map((r) => r.textContent)).toEqual(['Fixtures', 'Elements', 'Each fixture'])
    expect(screen.getByRole('radio', { name: 'Each fixture' }).getAttribute('aria-checked')).toBe('true')
    fireEvent.click(screen.getByRole('radio', { name: 'Fixtures' }))
    expect(set).toHaveBeenLastCalledWith({ elementMode: 'WHOLE' })
    fireEvent.click(screen.getByRole('button', { name: /Advanced/ }))
    expect(screen.queryByText('Element mode')).toBeNull()
    expect(screen.getByRole('combobox', { name: 'Heads' })).toBeTruthy()
  })

  it('writes Spread in percent and the three counts on Enter, refusing a count out of range', () => {
    // Parts start at 2 so that typing 0 is a change: a field already showing 0 fires no onChange.
    const { set } = fields(PULSE, draft({ parts: 2 }), HEADS)
    fireEvent.click(screen.getByRole('button', { name: /^Spread/ }))
    const spread = screen.getByRole('spinbutton', { name: /Spread percent/ }) as HTMLInputElement
    expect(spread.value).toBe('100')
    fireEvent.change(spread, { target: { value: '50' } })
    fireEvent.keyDown(spread, { key: 'Enter' })
    expect(set).toHaveBeenLastCalledWith({ spread: 0.5 }, true)

    const wings = screen.getByRole('spinbutton', { name: 'Wings' })
    fireEvent.change(wings, { target: { value: '2' } })
    fireEvent.keyDown(wings, { key: 'Enter' })
    expect(set).toHaveBeenLastCalledWith({ wings: 2 })

    const parts = screen.getByRole('spinbutton', { name: 'Parts' })
    fireEvent.change(parts, { target: { value: '0' } })
    fireEvent.keyDown(parts, { key: 'Enter' })
    expect(set).toHaveBeenLastCalledWith({ parts: 0 })

    set.mockClear()
    const segments = screen.getByRole('spinbutton', { name: 'Segments' })
    fireEvent.change(segments, { target: { value: '0' } })
    fireEvent.keyDown(segments, { key: 'Enter' })
    expect(set).not.toHaveBeenCalled()
  })

  it('hides Wings under a mirrored order, which is two wings already', () => {
    fields(PULSE, draft({ order: 'CENTRE_OUT' }), HEADS)
    fireEvent.click(screen.getByRole('button', { name: /^Spread/ }))
    expect(screen.queryByRole('spinbutton', { name: 'Wings' })).toBeNull()
    expect(screen.getByText('two already')).toBeTruthy()
    expect(screen.getByRole('spinbutton', { name: 'Segments' })).toBeTruthy()
  })

  it('previews an effect palette over eight notional heads, and a live effect over its own up to sixteen', () => {
    fields(PULSE, draft({ spread: 0.5 }), HEADS)
    fireEvent.click(screen.getByRole('button', { name: /^Spread/ }))
    const dials = screen.getByRole('img', { name: /^Phase of 8 heads/ })
    // Spread 50 % over eight: an eighth of a half-cycle apart.
    expect(dials.getAttribute('aria-label')).toBe('Phase of 8 heads: 0°, 23°, 45°, 68°, 90°, 113°, 135°, 158°')
    expect(screen.getByText('Previewed over eight heads')).toBeTruthy()
    cleanup()
    fields(PULSE, draft(), { ...HEADS, headCount: 20 })
    fireEvent.click(screen.getByRole('button', { name: /^Spread/ }))
    const live = screen.getByRole('img', { name: /^Phase of 20 heads/ })
    expect(live.querySelectorAll('svg')).toHaveLength(16)
    expect(screen.getByText('+4')).toBeTruthy()
    expect(screen.getByText('Over its 20 heads')).toBeTruthy()
  })

  // ─── Parameter metadata (fx-settings plan D1) ──────────────────────────────────────────────────

  const WAVE: FxLibraryEntry = {
    ...PULSE,
    name: 'Probe',
    parameters: [
      {
        name: 'shape',
        type: 'choice',
        defaultValue: 'SINE',
        description: '',
        label: 'Shape',
        options: [
          { value: 'SINE', label: 'Sine' },
          { value: 'SQUARE', label: 'Square' },
        ],
      },
      { name: 'high', type: 'ubyte', defaultValue: '255', description: '', label: 'High', min: 0, max: 255, unit: '%' },
      { name: 'low', type: 'ubyte', defaultValue: '0', description: '', label: 'Low', min: 0, max: 255, unit: '%', pair: 'high' },
      {
        name: 'width',
        type: 'double',
        defaultValue: '0.5',
        description: '',
        label: 'Width',
        min: 0,
        max: 1,
        step: 0.01,
        unit: 'ratio',
        role: 'detail',
        shownWhen: { param: 'shape', values: ['SQUARE'] },
      },
      { name: 'burst', type: 'int', defaultValue: '800', description: '', label: 'Burst', min: 0, max: 5000, step: 10, unit: 'ms' },
    ],
  }
  const waveDraft = (parameters: Record<string, string>) => draft({ parameters })

  it('draws a choice as a segment of its options, landing on the press', () => {
    const { setParam } = fields(WAVE, waveDraft({ shape: 'SINE' }))
    const segment = screen.getByRole('radiogroup', { name: 'Shape' })
    expect(Array.from(segment.querySelectorAll('button')).map((b) => b.textContent)).toEqual(['Sine', 'Square'])
    fireEvent.click(screen.getByRole('radio', { name: 'Square' }))
    expect(setParam).toHaveBeenLastCalledWith('shape', 'SQUARE', true)
  })

  it('draws a pair as one range with a field for each end, where the first of the two is declared', () => {
    const { set } = fields(WAVE, waveDraft({ low: '51', high: '204' }))
    expect(screen.getByText('Low – High')).toBeTruthy()
    const low = screen.getByRole('spinbutton', { name: 'Low percent' }) as HTMLInputElement
    const high = screen.getByRole('spinbutton', { name: 'High percent' }) as HTMLInputElement
    expect([low.value, high.value]).toEqual(['20', '80'])
    expect(screen.queryByText('High', { selector: 'label, span' })).toBeNull()
    fireEvent.change(high, { target: { value: '100' } })
    fireEvent.keyDown(high, { key: 'Enter' })
    expect(set).toHaveBeenLastCalledWith({ parameters: { low: '51', high: '255' } }, true)
  })

  it('writes both ends of a range in one write, so a thumb dragged past the other loses neither', () => {
    const { set, setParam } = fields(WAVE, waveDraft({ low: '50', high: '100' }))
    // The low thumb dragged to 120: Radix answers the pair sorted, [100, 120].
    fireEvent.change(screen.getByRole('slider', { name: 'Low – High 1' }), { target: { value: '120' } })
    expect(set).toHaveBeenLastCalledWith({ parameters: { low: '100', high: '120' } }, false)
    expect(set).toHaveBeenCalledTimes(1)
    expect(setParam).not.toHaveBeenCalled()
    // A release that moved nothing still lands, once.
    fireEvent.pointerUp(screen.getByRole('slider', { name: 'Low – High 2' }))
    expect(set).toHaveBeenLastCalledWith({ parameters: { low: '50', high: '100' } }, true)
    expect(set).toHaveBeenCalledTimes(2)
  })

  it('writes an int pair whole', () => {
    const COUNTS: FxLibraryEntry = {
      ...PULSE,
      name: 'Counts',
      parameters: [
        { name: 'fewest', type: 'int', defaultValue: '2', description: '', label: 'Fewest', min: 0, max: 20, pair: 'most' },
        { name: 'most', type: 'int', defaultValue: '8', description: '', label: 'Most', min: 0, max: 20 },
      ],
    }
    const { set } = fields(COUNTS, waveDraft({ fewest: '2', most: '8' }))
    const field = screen.getByRole('spinbutton', { name: 'Fewest' })
    fireEvent.change(field, { target: { value: '4.6' } })
    fireEvent.keyDown(field, { key: 'Enter' })
    expect(set).toHaveBeenLastCalledWith({ parameters: { fewest: '5', most: '8' } }, true)
  })

  it('hides a parameter whose shownWhen is false, and folds a detail one under More', () => {
    fields(WAVE, waveDraft({ shape: 'SINE' }))
    expect(screen.queryByRole('button', { name: /^More/ })).toBeNull()
    expect(screen.queryByRole('spinbutton', { name: /Width/ })).toBeNull()
    cleanup()
    fields(WAVE, waveDraft({ shape: 'SQUARE', width: '0.25' }))
    expect(screen.queryByRole('button', { name: /^Shape/ })).toBeNull()
    const more = screen.getByRole('button', { name: /^More/ })
    expect(more.textContent).toContain('width 25%')
    fireEvent.click(more)
    expect((screen.getByRole('spinbutton', { name: 'Width percent' }) as HTMLInputElement).value).toBe('25')
  })

  it('draws a declared unit in its field and holds the field to the declared range', () => {
    const { setParam } = fields(WAVE, waveDraft({ burst: '800' }))
    const slider = screen.getByRole('slider', { name: 'Burst' })
    expect([slider.getAttribute('max'), slider.getAttribute('step')]).toEqual(['5000', '10'])
    const field = screen.getByRole('spinbutton', { name: 'Burst ms' })
    fireEvent.change(field, { target: { value: '9000' } })
    fireEvent.keyDown(field, { key: 'Enter' })
    expect(setParam).toHaveBeenLastCalledWith('burst', '5000', true)
  })

  it('guesses for a script FX that declares nothing, as it always has', () => {
    const SCRIPT: FxLibraryEntry = {
      ...PULSE,
      name: 'Mine',
      parameters: [
        { name: 'min', type: 'ubyte', defaultValue: '0', description: '' },
        { name: 'max', type: 'ubyte', defaultValue: '255', description: '' },
        { name: 'attackRatio', type: 'double', defaultValue: '0.1', description: '' },
      ],
    }
    fields(SCRIPT, draft({ parameters: { min: '0', max: '255', attackRatio: '0.1' } }))
    expect(screen.getByRole('spinbutton', { name: 'Low percent' })).toBeTruthy()
    expect(screen.getByRole('spinbutton', { name: 'High percent' })).toBeTruthy()
    expect(screen.queryByText('Low – High')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: /^Shape/ }))
    expect(screen.getByRole('spinbutton', { name: 'Attack percent' })).toBeTruthy()
  })
})
