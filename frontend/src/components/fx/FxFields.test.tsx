// @vitest-environment jsdom
import { useState, type ComponentProps } from 'react'
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
import { FxChipSheetContext } from './fxChipSheet'
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
  spreadUnit: 'CYCLE',
  ...over,
})

function fields(
  entry: FxLibraryEntry,
  d: FxDraft,
  scope: FxScope = SCOPE,
  more: Pick<ComponentProps<typeof FxFields>, 'attribute' | 'layout' | 'swap'> = {},
) {
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
      {...more}
    />,
  )
  return { set, setParam }
}

/** A host that holds the draft as `FxEditor` does, and lays [external] over it as a write from outside. */
function Harness({ entry, initial, scope, external }: { entry: FxLibraryEntry; initial: FxDraft; scope: FxScope; external?: Partial<FxDraft> }) {
  const [held, setHeld] = useState(initial)
  const [applied, setApplied] = useState<Partial<FxDraft> | undefined>(undefined)
  if (external !== applied) {
    setApplied(external)
    if (external != null) setHeld((d) => ({ ...d, ...external }))
  }
  return (
    <FxFields
      entry={entry}
      timingSource={entry.timingSource}
      draft={held}
      scope={scope}
      extendedChannels={undefined}
      settingAttribute={undefined}
      readOnly={false}
      set={(patch) => setHeld((d) => ({ ...d, ...patch }))}
      setParam={() => {}}
    />
  )
}

afterEach(cleanup)

/** The Cycle chip's popover: its steps, its typed field and *Same cycle as…*. */
function openCycle() {
  fireEvent.click(screen.getByRole('button', { name: /^Cycle: / }))
}

describe('FxFields', () => {
  it('gives a wall-clock effect with no rate master Seconds, and a beat effect beats and its speed master', () => {
    fields(FLICKER, draft())
    openCycle()
    expect(screen.getByRole('spinbutton', { name: /Cycle seconds/ }).closest('div')).toBeTruthy()
    expect(screen.queryByRole('radiogroup', { name: 'Cycle' })).toBeNull()
    expect(screen.getByRole('button', { name: 'Timing: Seconds' })).toBeTruthy()
    cleanup()
    fields(PULSE, draft())
    openCycle()
    expect(screen.getByRole('radiogroup', { name: 'Cycle' })).toBeTruthy()
    expect(screen.queryByRole('spinbutton', { name: /Cycle seconds/ })).toBeNull()
    expect(screen.getByRole('button', { name: 'Timing: M1 · 120' })).toBeTruthy()
  })

  it('types a beat effect on Seconds in seconds, with no master named', () => {
    fields(PULSE, draft({ seconds: true, cycle: 10 }))
    expect(screen.getByRole('button', { name: 'Cycle: 10 s' })).toBeTruthy()
    openCycle()
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
    expect(screen.getByRole('button', { name: 'Cycle: 1 bar' })).toBeTruthy()
    openCycle()
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

  it('puts Amount in percent under Mix and Direction as three glyphs in the Timing strip', () => {
    const { set } = fields(PULSE, draft({ amount: 0.6, direction: 'BACKWARD' }))
    const size = screen.getByRole('spinbutton', { name: /Amount/ }) as HTMLInputElement
    expect(size.value).toBe('60')
    fireEvent.change(size, { target: { value: '40' } })
    fireEvent.keyDown(size, { key: 'Enter' })
    expect(set).toHaveBeenLastCalledWith({ amount: 0.4 }, true)
    expect(screen.getByRole('region', { name: 'Mix' }).contains(size)).toBe(true)
    const direction = screen.getByRole('radiogroup', { name: 'Direction' })
    expect(screen.getByRole('region', { name: 'Timing' }).contains(direction)).toBe(true)
    expect(Array.from(direction.querySelectorAll('button')).map((b) => b.getAttribute('aria-label'))).toEqual([
      'Forward',
      'Backward',
      'Bounce',
    ])
    expect(screen.getByRole('radio', { name: 'Backward' }).getAttribute('aria-checked')).toBe('true')
    fireEvent.click(screen.getByRole('radio', { name: 'Bounce' }))
    expect(set).toHaveBeenLastCalledWith({ direction: 'BOUNCE' })
  })

  it('chips Runs in the Timing strip: forever, ×1 to ×4, or a typed count lit when it is none of them', () => {
    const { set } = fields(PULSE, draft())
    expect(screen.getByRole('button', { name: 'Runs: forever' }).textContent).toContain('∞')
    fireEvent.click(screen.getByRole('button', { name: 'Runs: forever' }))
    expect(screen.getByRole('radio', { name: 'Forever' }).getAttribute('aria-checked')).toBe('true')
    expect(document.querySelector('[data-typed-cell]')?.hasAttribute('data-lit')).toBe(false)
    fireEvent.click(screen.getByRole('radio', { name: '2 runs' }))
    expect(set).toHaveBeenLastCalledWith({ runs: 2 })
    cleanup()
    const second = fields(PULSE, draft({ runs: 7 }))
    fireEvent.click(screen.getByRole('button', { name: 'Runs: 7 runs' }))
    expect(document.querySelector('[data-typed-cell]')?.hasAttribute('data-lit')).toBe(true)
    const count = screen.getByRole('spinbutton', { name: 'Runs count' })
    fireEvent.change(count, { target: { value: '9' } })
    fireEvent.keyDown(count, { key: 'Enter' })
    expect(second.set).toHaveBeenLastCalledWith({ runs: 9 })
    fireEvent.click(screen.getByRole('radio', { name: 'Forever' }))
    expect(second.set).toHaveBeenLastCalledWith({ runs: null })
  })

  it('opens the eight steps from the Cycle chip, the typed division lit only off them', () => {
    const { set } = fields(PULSE, draft({ cycle: 1 / 3 }))
    expect(screen.getByRole('button', { name: 'Cycle: ⅓ beat' })).toBeTruthy()
    openCycle()
    expect(screen.getByRole('spinbutton', { name: 'Beats a cycle' }).closest('[data-typed-cell]')?.hasAttribute('data-lit')).toBe(true)
    fireEvent.click(screen.getByRole('radio', { name: '2 beats a cycle' }))
    expect(set).toHaveBeenLastCalledWith({ cycle: 2 })
    cleanup()
    fields(PULSE, draft({ cycle: 2 }))
    openCycle()
    expect(screen.getByRole('spinbutton', { name: 'Beats a cycle' }).closest('[data-typed-cell]')?.hasAttribute('data-lit')).toBe(false)
  })

  it("draws a parameter's own sentence under its control", () => {
    fields(FLICKER, draft())
    expect(screen.getByText('How long one flicker lasts')).toBeTruthy()
    expect(screen.getByText('The brightest a flicker reaches')).toBeTruthy()
  })

  // ─── Across the heads (fx-settings plan D10) ───────────────────────────────────────────────────

  const HEADS: FxScope = { heads: true, elementMode: false, elementFilter: false }
  const COLOUR_CYCLE: FxLibraryEntry = {
    ...PULSE,
    name: 'Colour Cycle',
    family: 'colour',
    stepsFrom: 'colours',
    parameters: [{ name: 'colours', type: 'colourList', defaultValue: '#ff0000,#00ff00,#0000ff', description: '' }],
  }
  const ladderNumbers = () =>
    [...screen.getByRole('list', { name: 'Head ladder' }).querySelectorAll('[data-ladder-head]')].map((h) => Number(h.getAttribute('data-start')))
  const laterBy = () => screen.getByRole('radiogroup', { name: 'Each head later by' })

  it('draws the heads’ questions only where there are heads, the Order only under Custom', () => {
    fields(PULSE, draft())
    expect(screen.queryByRole('group', { name: 'Patterns' })).toBeNull()
    expect(screen.queryByRole('region', { name: 'Across the heads' })).toBeNull()
    cleanup()
    fields(PULSE, draft({ order: 'NORMAL' }), HEADS)
    const across = screen.getByRole('region', { name: 'Across the heads' })
    for (const control of [
      screen.getByRole('list', { name: 'Head ladder' }),
      screen.getByRole('group', { name: 'Patterns' }),
      laterBy(),
      screen.getByRole('radiogroup', { name: 'Segments' }),
      screen.getByRole('radiogroup', { name: 'Parts' }),
      screen.getByRole('radiogroup', { name: 'Wings' }),
    ]) {
      expect(across.contains(control)).toBe(true)
    }
    // Chase is lit, so the Order is not asked.
    expect(screen.getByRole('button', { name: /Chase/ }).getAttribute('aria-pressed')).toBe('true')
    expect(screen.queryByRole('combobox', { name: 'Order' })).toBeNull()
    expect(screen.queryByRole('button', { name: /^Spread/ })).toBeNull()
    expect(screen.queryByRole('img', { name: /^Phase of/ })).toBeNull()
  })

  it('opens the Order under Custom, and lights Custom itself while no pattern matches', () => {
    const { set } = fields(PULSE, draft(), HEADS)
    fireEvent.click(screen.getByRole('button', { name: 'Custom' }))
    expect(screen.getByRole('button', { name: 'Custom' }).getAttribute('aria-pressed')).toBe('true')
    expect(screen.getByRole('button', { name: /Chase/ }).getAttribute('aria-pressed')).toBe('false')
    expect(screen.getByRole('combobox', { name: 'Order' })).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: /Back/ }))
    expect(set).toHaveBeenLastCalledWith({ order: 'REVERSE', wings: 1, segments: 1, parts: 0 })
    expect(screen.queryByRole('combobox', { name: 'Order' })).toBeNull()
    cleanup()
    fields(PULSE, draft({ order: 'POSITIONAL' }), HEADS)
    expect(screen.getByRole('button', { name: 'Custom' }).getAttribute('aria-pressed')).toBe('true')
    expect(screen.getByRole('combobox', { name: 'Order' })).toBeTruthy()
  })

  it('leaves Spread and its unit alone when a pattern is picked', () => {
    const { set } = fields(COLOUR_CYCLE, draft({ spreadUnit: 'STEP', spread: 1 }), HEADS)
    fireEvent.click(screen.getByRole('button', { name: /Pairs/ }))
    expect(set).toHaveBeenLastCalledWith({ order: 'NORMAL', wings: 1, segments: 2, parts: 0 })
  })

  it('writes Fit the cycle as CYCLE 1, 1 step as STEP 1, and Free as a value of its own', () => {
    const { set } = fields(COLOUR_CYCLE, draft({ spread: 0.5 }), { ...HEADS, headCount: 4 })
    expect(screen.getByRole('radio', { name: 'Free' }).getAttribute('aria-checked')).toBe('true')
    fireEvent.click(screen.getByRole('radio', { name: '1 step' }))
    expect(set).toHaveBeenLastCalledWith({ spread: 1, spreadUnit: 'STEP' }, true)
    fireEvent.click(screen.getByRole('radio', { name: 'Fit the cycle' }))
    expect(set).toHaveBeenLastCalledWith({ spread: 1, spreadUnit: 'CYCLE' }, true)
    cleanup()

    const stepped = fields(COLOUR_CYCLE, draft({ spread: 1, spreadUnit: 'STEP' }), { ...HEADS, headCount: 4 })
    expect(screen.getByRole('radio', { name: '1 step' }).getAttribute('aria-checked')).toBe('true')
    expect(screen.queryByRole('slider', { name: 'Spread' })).toBeNull()
    stepped.set.mockClear()
    fireEvent.click(screen.getByRole('radio', { name: 'Free' }))
    expect(stepped.set).not.toHaveBeenCalled()
    // Free on a step spread is a slider in steps.
    const field = screen.getByRole('spinbutton', { name: 'Spread steps' })
    fireEvent.change(field, { target: { value: '2' } })
    fireEvent.keyDown(field, { key: 'Enter' })
    expect(stepped.set).toHaveBeenLastCalledWith({ spread: 2 }, true)
    cleanup()

    const cycle = fields(PULSE, draft({ spread: 0.5 }), HEADS)
    const percent = screen.getByRole('spinbutton', { name: 'Spread percent' }) as HTMLInputElement
    expect(percent.value).toBe('50')
    fireEvent.change(percent, { target: { value: '75' } })
    fireEvent.keyDown(percent, { key: 'Enter' })
    expect(cycle.set).toHaveBeenLastCalledWith({ spread: 0.75 }, true)
  })

  it('offers 1 step only on an FX with steps', () => {
    fields(PULSE, draft(), HEADS)
    expect([...laterBy().querySelectorAll('[role="radio"]')].map((r) => r.textContent)).toEqual(['Fit the cycle', 'Free'])
    cleanup()
    fields(COLOUR_CYCLE, draft(), HEADS)
    expect([...laterBy().querySelectorAll('[role="radio"]')].map((r) => r.textContent)).toEqual(['Fit the cycle', '1 step', 'Free'])
  })

  it('reads the §1 chase: four heads one step apart on three colours, each starting a third of a cycle after the last', () => {
    fields(COLOUR_CYCLE, draft({ spread: 1, spreadUnit: 'STEP', cycle: 4 }), { ...HEADS, headCount: 4 })
    expect(ladderNumbers()).toEqual([1, 2, 3, 4])
    expect(screen.getByText('⅓ cycle · 1 step · 1⅓ beats · 120° · spread 133 %')).toBeTruthy()
  })

  it('reads the gap in a head’s own cycle under One cycle for: Each head', () => {
    // Four heads of one beat each: a head a whole beat after the last, not a quarter of one.
    fields(PULSE, draft({ cycle: 1, timing: 'HEAD' }), { ...HEADS, headCount: 4 })
    expect(screen.getByText('¼ cycle · 1 beat · 90°')).toBeTruthy()
  })

  it('keeps Free while its slider passes through a snap, and reads a spread written from outside afresh', () => {
    const { set } = fields(PULSE, draft({ spread: 0.5 }), { ...HEADS, headCount: 4 })
    const slider = screen.getByRole('slider', { name: 'Spread' })
    fireEvent.change(slider, { target: { value: '1' } })
    expect(set).toHaveBeenLastCalledWith({ spread: 1 }, false)
    cleanup()
    // The host re-renders with the value the slider wrote: still Free, the slider still under the finger.
    const free = render(
      <Harness entry={PULSE} initial={draft({ spread: 0.5 })} scope={{ ...HEADS, headCount: 4 }} />,
    )
    fireEvent.change(screen.getByRole('slider', { name: 'Spread' }), { target: { value: '1' } })
    expect(screen.getByRole('radio', { name: 'Free' }).getAttribute('aria-checked')).toBe('true')
    expect(screen.getByRole('slider', { name: 'Spread' })).toBeTruthy()
    fireEvent.change(screen.getByRole('slider', { name: 'Spread' }), { target: { value: '0.75' } })
    // Written from outside (Revert), 1 reads as Fit the cycle.
    free.rerender(<Harness entry={PULSE} initial={draft({ spread: 0.5 })} scope={{ ...HEADS, headCount: 4 }} external={{ spread: 1 }} />)
    expect(screen.getByRole('radio', { name: 'Fit the cycle' }).getAttribute('aria-checked')).toBe('true')
  })

  it('lets Custom go when the fields change from outside', () => {
    const view = render(<Harness entry={PULSE} initial={draft()} scope={HEADS} />)
    fireEvent.click(screen.getByRole('button', { name: 'Custom' }))
    expect(screen.getByRole('button', { name: /^Chase/ }).getAttribute('aria-pressed')).toBe('false')
    view.rerender(<Harness entry={PULSE} initial={draft()} scope={HEADS} external={{ order: 'REVERSE' }} />)
    expect(screen.getByRole('button', { name: /^Back/ }).getAttribute('aria-pressed')).toBe('true')
    expect(screen.queryByRole('combobox', { name: 'Order' })).toBeNull()
  })

  it('writes Segments, Parts and Wings from their segments and typed counts, and says what they do', () => {
    const { set } = fields(PULSE, draft({ parts: 2 }), { ...HEADS, headCount: 8 })
    expect(screen.getByText(/Parts 2: the pattern repeats every 2 heads/)).toBeTruthy()
    expect(ladderNumbers()).toEqual([1, 2, 1, 2, 1, 2, 1, 2])
    fireEvent.click(screen.getByRole('radio', { name: 'Segments 2' }))
    expect(set).toHaveBeenLastCalledWith({ segments: 2 })
    fireEvent.click(screen.getByRole('radio', { name: 'Parts off' }))
    expect(set).toHaveBeenLastCalledWith({ parts: 0 })
    fireEvent.click(screen.getByRole('radio', { name: 'Wings 3' }))
    expect(set).toHaveBeenLastCalledWith({ wings: 3 })

    const segments = screen.getByRole('spinbutton', { name: 'Segments count' })
    fireEvent.change(segments, { target: { value: '6' } })
    fireEvent.keyDown(segments, { key: 'Enter' })
    expect(set).toHaveBeenLastCalledWith({ segments: 6 })
    set.mockClear()
    fireEvent.change(segments, { target: { value: '0' } })
    fireEvent.keyDown(segments, { key: 'Enter' })
    expect(set).not.toHaveBeenCalled()
  })

  it('lights a count’s typed cell while it is none of the segment’s', () => {
    fields(PULSE, draft({ segments: 6 }), HEADS)
    const cell = screen.getByRole('spinbutton', { name: 'Segments count' }).closest('[data-typed-cell]')
    expect(cell?.hasAttribute('data-lit')).toBe(true)
    expect(screen.getByText(/Segments 6: neighbours move in sixes/)).toBeTruthy()
  })

  it('hides Wings under a mirrored Order, which is two wings already', () => {
    fields(PULSE, draft({ order: 'CENTRE_OUT' }), HEADS)
    expect(screen.queryByRole('radiogroup', { name: 'Wings' })).toBeNull()
    expect(screen.getByText(/Centre out is two wings already/)).toBeTruthy()
    expect(screen.getByRole('radiogroup', { name: 'Segments' })).toBeTruthy()
  })

  it('draws an FX palette over eight notional heads, and a live FX over its own, sixteen and then +n', () => {
    fields(PULSE, draft({ order: 'CENTRE_OUT' }), HEADS)
    expect(ladderNumbers()).toEqual([4, 3, 2, 1, 1, 2, 3, 4])
    expect(screen.getByText('Drawn on eight heads')).toBeTruthy()
    const drawing = (name: RegExp) => screen.getByRole('button', { name }).querySelectorAll('[data-shape-drawing] rect')
    expect(drawing(/Unison/)).toHaveLength(8)
    cleanup()
    fields(PULSE, draft(), { ...HEADS, headCount: 4 })
    expect(drawing(/Unison/)).toHaveLength(4)
    expect(screen.queryByText('Drawn on eight heads')).toBeNull()
    cleanup()
    fields(PULSE, draft(), { ...HEADS, headCount: 20 })
    expect(ladderNumbers()).toHaveLength(16)
    expect(screen.getByText('+4')).toBeTruthy()
    expect(drawing(/Unison/)).toHaveLength(16)
  })

  it('asks nothing of the spread under Unison', () => {
    fields(PULSE, draft({ order: 'UNIFIED' }), HEADS)
    expect(screen.queryByRole('radiogroup', { name: 'Each head later by' })).toBeNull()
    expect(screen.queryByRole('radiogroup', { name: 'Segments' })).toBeNull()
    expect(screen.getByText('Every head starts together, so there is nothing to spread.')).toBeTruthy()
    expect(ladderNumbers()).toEqual([1, 1, 1, 1, 1, 1, 1, 1])
  })

  it('draws Over first where a fixture has elements, and nothing of it in Advanced (FX editor follow-ups D7)', () => {
    fields(PULSE, draft(), HEADS)
    expect(screen.queryByRole('radiogroup', { name: 'Over' })).toBeNull()
    cleanup()
    const { set } = fields(PULSE, draft({ elementMode: 'PER_FIXTURE' }), { heads: true, elementMode: true, elementFilter: true })
    const over = screen.getByRole('radiogroup', { name: 'Over' })
    expect(over.compareDocumentPosition(screen.getByRole('list', { name: 'Head ladder' })) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect([...over.querySelectorAll('[role="radio"]')].map((r) => r.textContent)).toEqual(['Fixtures', 'Elements', 'Each fixture'])
    expect(screen.getByRole('radio', { name: 'Each fixture' }).getAttribute('aria-checked')).toBe('true')
    fireEvent.click(screen.getByRole('radio', { name: 'Fixtures' }))
    expect(set).toHaveBeenLastCalledWith({ elementMode: 'WHOLE' })
    fireEvent.click(screen.getByRole('button', { name: /Advanced/ }))
    expect(screen.queryByText('Element mode')).toBeNull()
    expect(screen.getByRole('combobox', { name: 'Heads' })).toBeTruthy()
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
      source: 'USER',
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
    // A guessed detail folds under More, as a declared one does.
    expect(screen.queryByRole('button', { name: /^Shape/ })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: /^More/ }))
    expect(screen.getByRole('spinbutton', { name: 'Attack percent' })).toBeTruthy()
    expect(screen.getByRole('region', { name: 'Parameters' })).toBeTruthy()
  })
})

describe('FxFields in four questions (fx-settings plan D9)', () => {
  const HEADS: FxScope = { heads: true, elementMode: true, elementFilter: false }
  const WAVE: FxLibraryEntry = {
    ...PULSE,
    name: 'Wave',
    source: 'BUILT_IN',
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
          { value: 'PULSE', label: 'Pulse' },
        ],
      },
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
        shownWhen: { param: 'shape', values: ['SQUARE'] },
      },
      {
        name: 'widthUnit',
        type: 'choice',
        defaultValue: 'RATIO',
        description: '',
        label: 'On for',
        options: [
          { value: 'RATIO', label: 'Width' },
          { value: 'HEAD', label: 'One head' },
        ],
        shownWhen: { param: 'shape', values: ['SQUARE', 'PULSE'] },
      },
    ],
  }
  const sections = () => [...document.querySelectorAll('section')].map((el) => el.getAttribute('aria-label'))

  it.each(['editor', 'inspector'] as const)('draws Timing, the FX’s own section, Across the heads, Mix and then Advanced in the %s layout', (layout) => {
    fields(WAVE, draft({ parameters: { shape: 'SINE' } }), HEADS, { layout, attribute: 'dimmer' })
    expect(sections()).toEqual(['Timing', 'Levels', 'Across the heads', 'Mix'])
    const advanced = screen.getByRole('button', { name: /^Advanced/ })
    const mix = screen.getByRole('region', { name: 'Mix' })
    expect(mix.compareDocumentPosition(advanced) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    // The raw blend is Advanced's, in both layouts.
    expect(screen.queryByRole('combobox', { name: 'Blend' })).toBeNull()
    expect(advanced.textContent).toContain('override')
    fireEvent.click(advanced)
    expect(screen.getByRole('combobox', { name: 'Blend' })).toBeTruthy()
    expect(screen.getByRole('slider', { name: 'Phase' })).toBeTruthy()
    // Over and the patterns are the heads' question.
    const across = screen.getByRole('region', { name: 'Across the heads' })
    expect(across.contains(screen.getByRole('radiogroup', { name: 'Over' }))).toBe(true)
    expect(across.contains(screen.getByRole('group', { name: 'Patterns' }))).toBe(true)
  })

  it('names the FX’s own section for what it holds', () => {
    const named = (entry: Partial<FxLibraryEntry>, attribute: string) => {
      fields({ ...PULSE, parameters: [], ...entry }, draft(), SCOPE, { attribute })
      const name = sections()[1]
      cleanup()
      return name
    }
    expect(named({ name: 'Wave', family: 'intensity' }, 'dimmer')).toBe('Levels')
    expect(named({ name: 'Wave', family: 'intensity' }, 'zoom')).toBe('Levels')
    expect(named({ name: 'Colour Cycle', family: 'colour' }, 'rgbColour')).toBe('Colours')
    expect(named({ name: 'Path', family: 'position' }, 'position')).toBe('Path')
    expect(named({ name: 'Slot Steps', family: 'steps' }, 'gobo')).toBe('Steps')
    expect(named({ name: 'CandleFlicker', family: 'intensity' }, 'dimmer')).toBe('Flicker')
    expect(named({ name: 'Mine', family: 'intensity', source: 'USER' }, 'dimmer')).toBe('Parameters')
  })

  it('reads One cycle for: All heads · Each head, writing the same timing values', () => {
    const { set } = fields(PULSE, draft({ timing: 'HEAD' }), HEADS)
    const advanced = screen.getByRole('button', { name: /^Advanced/ })
    expect(advanced.textContent).toContain('one cycle for each head')
    fireEvent.click(advanced)
    expect(screen.queryByText('Per head')).toBeNull()
    expect(screen.getByRole('radio', { name: 'Each head' }).getAttribute('aria-checked')).toBe('true')
    fireEvent.click(screen.getByRole('radio', { name: 'All heads' }))
    expect(set).toHaveBeenLastCalledWith({ timing: 'CYCLE' })
  })

  it('swaps the FX from its section’s header, and draws the name alone with nothing to swap to', () => {
    const onSwap = vi.fn()
    const OTHER: FxLibraryEntry = { ...PULSE, name: 'Flicker', parameters: [] }
    fields(WAVE, draft({ parameters: { shape: 'SINE' } }), SCOPE, { attribute: 'dimmer', swap: { options: [WAVE, OTHER], onSwap } })
    fireEvent.pointerDown(screen.getByRole('button', { name: 'FX type: Wave' }), { button: 0, ctrlKey: false, pointerType: 'mouse' })
    fireEvent.click(screen.getByRole('menuitemradio', { name: 'Flicker' }))
    expect(onSwap).toHaveBeenCalledWith(OTHER)
    cleanup()
    fields(WAVE, draft({ parameters: { shape: 'SINE' } }), SCOPE, { attribute: 'dimmer' })
    expect(screen.queryByRole('button', { name: /^FX type/ })).toBeNull()
    expect(screen.getByRole('region', { name: 'Levels' }).textContent).toContain('Wave')
  })

  it('draws each shape option’s picture over its word', () => {
    fields(WAVE, draft({ parameters: { shape: 'SINE' } }))
    const pictures = [...screen.getByRole('radiogroup', { name: 'Shape' }).querySelectorAll('[data-shape-icon]')]
    expect(pictures.map((p) => p.getAttribute('data-shape-icon'))).toEqual(['SINE', 'SQUARE', 'PULSE'])
  })

  it('ends Width’s presets in a typed percentage, lit when the width matches none, with One head among them', () => {
    const { set } = fields(WAVE, draft({ parameters: { shape: 'SQUARE', width: '0.35', widthUnit: 'RATIO' } }))
    const segment = screen.getByRole('radiogroup', { name: 'Width' })
    expect([...segment.querySelectorAll('button')].map((b) => b.textContent)).toEqual(['10 %', '50 %', 'One head'])
    // The choice is inside the segment rather than a row of its own.
    expect(screen.queryByRole('radiogroup', { name: 'On for' })).toBeNull()
    const typed = screen.getByRole('spinbutton', { name: 'Width percent' }) as HTMLInputElement
    expect(typed.value).toBe('35')
    expect(typed.closest('[data-typed-cell]')?.hasAttribute('data-lit')).toBe(true)
    fireEvent.click(screen.getByRole('radio', { name: 'One head' }))
    expect(set).toHaveBeenLastCalledWith({ parameters: { shape: 'SQUARE', width: '0.35', widthUnit: 'HEAD' } }, true)
    fireEvent.click(screen.getByRole('radio', { name: '10 %' }))
    expect(set).toHaveBeenLastCalledWith({ parameters: { shape: 'SQUARE', width: '0.1', widthUnit: 'RATIO' } }, true)
    fireEvent.change(typed, { target: { value: '20' } })
    fireEvent.keyDown(typed, { key: 'Enter' })
    expect(set).toHaveBeenLastCalledWith({ parameters: { shape: 'SQUARE', width: '0.2', widthUnit: 'RATIO' } }, true)
    cleanup()
    fields(WAVE, draft({ parameters: { shape: 'SQUARE', width: '0.5', widthUnit: 'HEAD' } }))
    expect(screen.getByRole('radio', { name: 'One head' }).getAttribute('aria-checked')).toBe('true')
    expect(screen.getByRole('radio', { name: '50 %' }).getAttribute('aria-checked')).toBe('false')
    expect(screen.getByRole('spinbutton', { name: 'Width percent' }).closest('[data-typed-cell]')?.hasAttribute('data-lit')).toBe(false)
    cleanup()
    // A Pulse has no width to type: On for stays a choice of its own.
    fields(WAVE, draft({ parameters: { shape: 'PULSE' } }))
    expect(screen.getByRole('radiogroup', { name: 'On for' })).toBeTruthy()
    expect(screen.queryByRole('radiogroup', { name: 'Width' })).toBeNull()
  })

  it('opens a chip as a bottom sheet below the FX editor’s 760 arm', () => {
    render(
      <FxChipSheetContext.Provider value={true}>
        <FxFields
          entry={PULSE}
          timingSource="BEAT"
          draft={draft()}
          scope={SCOPE}
          extendedChannels={undefined}
          settingAttribute={undefined}
          readOnly={false}
          set={vi.fn()}
          setParam={vi.fn()}
        />
      </FxChipSheetContext.Provider>,
    )
    fireEvent.click(screen.getByRole('button', { name: /^Cycle: / }))
    const sheet = screen.getByRole('dialog', { name: 'Cycle' })
    expect(sheet.querySelector('[data-chip-sheet]')).toBeTruthy()
    expect(sheet.contains(screen.getByRole('radiogroup', { name: 'Cycle' }))).toBe(true)
  })
})
