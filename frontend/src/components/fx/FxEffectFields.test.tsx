// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import type { EffectLibraryEntry } from '@/store/fixtureFx'

/**
 * `FxEffectFields` — the effect editor's fields, which the live `FxEditor` and `TemplateEditor`
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
    <input
      type="range"
      aria-label={label}
      min={min}
      max={max}
      step={step}
      value={value[0]}
      disabled={disabled}
      onChange={(e) => onValueChange?.([Number(e.target.value)])}
      onPointerUp={(e) => onValueCommit?.([Number((e.target as HTMLInputElement).value)])}
    />
  ),
}))

vi.mock('@/store/speedMasters', () => ({
  useSpeedMasterLiveQuery: () => ({ data: [{ uuid: 'm1', index: 1, name: 'Main', bpm: 120 }, { uuid: 'm2', index: 2, name: 'Slow', bpm: 60 }] }),
}))
vi.mock('./FxColourPicker', () => ({ FxColourPicker: () => null }))
vi.mock('./FxColourListPicker', () => ({ FxColourListPicker: () => null }))

import { FxEffectFields } from './FxEffectFields'
import type { FxDraft, FxScope } from './fxEditorModel'

const FLICKER: EffectLibraryEntry = {
  name: 'Flicker',
  category: 'dimmer',
  outputType: 'SLIDER',
  effectMode: 'STANDARD',
  timingSource: 'WALL_CLOCK',
  compatibleProperties: ['dimmer'],
  parameters: [
    { name: 'flickerDurationMs', type: 'int', defaultValue: '200', description: 'How long one flicker lasts' },
    { name: 'max', type: 'ubyte', defaultValue: '255', description: 'The brightest a flicker reaches' },
  ],
}
const PULSE: EffectLibraryEntry = { ...FLICKER, name: 'Pulse', timingSource: 'BEAT', parameters: [] }

const SCOPE: FxScope = { heads: false, elementMode: false, elementFilter: false }
const draft = (over: Partial<FxDraft> = {}): FxDraft => ({
  parameters: { flickerDurationMs: '200', max: '255' },
  beatDivision: 2,
  blendMode: 'OVERRIDE',
  phaseOffset: 0,
  stepTiming: false,
  distributionStrategy: 'LINEAR',
  elementMode: 'PER_FIXTURE',
  elementFilter: 'ALL',
  speedMasterUuid: null,
  rateSpeedMasterUuid: null,
  seconds: false,
  size: 1,
  direction: 'FORWARD',
  nShot: null,
  ...over,
})

function fields(entry: EffectLibraryEntry, d: FxDraft) {
  const set = vi.fn()
  const setParam = vi.fn()
  render(
    <FxEffectFields
      entry={entry}
      timingSource={entry.timingSource}
      draft={d}
      scope={SCOPE}
      extendedChannels={undefined}
      settingProperty={undefined}
      readOnly={false}
      set={set}
      setParam={setParam}
    />,
  )
  return { set, setParam }
}

afterEach(cleanup)

describe('FxEffectFields', () => {
  it('gives a wall-clock effect with no rate master Seconds, and a beat effect beats and its speed master', () => {
    fields(FLICKER, draft())
    expect(screen.getByRole('spinbutton', { name: /Cycle seconds/ }).closest('div')).toBeTruthy()
    expect(screen.queryByRole('radiogroup', { name: 'Speed' })).toBeNull()
    expect(screen.getByRole('button', { name: 'Timing: Seconds' })).toBeTruthy()
    cleanup()
    fields(PULSE, draft())
    expect(screen.getByRole('radiogroup', { name: 'Speed' })).toBeTruthy()
    expect(screen.queryByRole('spinbutton', { name: /Cycle seconds/ })).toBeNull()
    expect(screen.getByRole('button', { name: 'Timing: M1 · 120' })).toBeTruthy()
  })

  it('types a beat effect on Seconds in seconds, with no master named', () => {
    fields(PULSE, draft({ seconds: true, beatDivision: 10 }))
    expect(screen.getByRole('spinbutton', { name: /Cycle seconds/ })).toBeTruthy()
    expect(screen.queryByRole('radiogroup', { name: 'Speed' })).toBeNull()
    expect(screen.getByRole('button', { name: 'Timing: Seconds' })).toBeTruthy()
    expect(screen.getByText('seconds a cycle · follows no master')).toBeTruthy()
  })

  it('names a stored rate master the bank does not hold as M1, the master the desk runs it on — not Seconds', () => {
    fields(FLICKER, draft({ rateSpeedMasterUuid: 'gone' }))
    expect(screen.getByRole('button', { name: 'Timing: rate M1 · 120' })).toBeTruthy()
  })

  it('speaks the segment in the one spelling the chips use — beats a cycle, bars from four', () => {
    fields(PULSE, draft({ beatDivision: 4 }))
    const segment = screen.getByRole('radiogroup', { name: 'Speed' })
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

  it('puts Size in percent and Direction as three glyphs beside Speed (effects plan D11)', () => {
    const { set } = fields(PULSE, draft({ size: 0.6, direction: 'BACKWARD' }))
    const size = screen.getByRole('spinbutton', { name: /Size/ }) as HTMLInputElement
    expect(size.value).toBe('60')
    fireEvent.change(size, { target: { value: '40' } })
    fireEvent.keyDown(size, { key: 'Enter' })
    expect(set).toHaveBeenLastCalledWith({ size: 0.4 }, true)
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
    expect(set).toHaveBeenLastCalledWith({ nShot: 1 })
    cleanup()
    const second = fields(PULSE, draft({ nShot: 3 }))
    // Folded, the summary says the count.
    expect(screen.getByRole('button', { name: /Advanced.*3 runs/ })).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: /Advanced/ }))
    const count = screen.getByRole('spinbutton', { name: 'Runs' })
    fireEvent.change(count, { target: { value: '2' } })
    fireEvent.keyDown(count, { key: 'Enter' })
    expect(second.set).toHaveBeenLastCalledWith({ nShot: 2 })
    fireEvent.click(screen.getByRole('radio', { name: 'Forever' }))
    expect(second.set).toHaveBeenLastCalledWith({ nShot: null })
  })

  it("draws a parameter's own sentence under its control", () => {
    fields(FLICKER, draft())
    expect(screen.getByText('How long one flicker lasts')).toBeTruthy()
    expect(screen.getByText('The brightest a flicker reaches')).toBeTruthy()
  })
})
