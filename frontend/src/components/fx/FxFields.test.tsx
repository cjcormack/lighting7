// @vitest-environment jsdom
import { useState, type ComponentProps } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import type { FxLibraryEntry } from '@/store/fixtureFx'

/**
 * `FxFields` — the effect editor's fields, which the live `FxEditor` and `PaletteEditor`
 * (fixture-fx-sheets plan D16) both mount. These are the pins the deleted `EffectParameterForm`'s
 * suite held, re-made on the fields that replaced it: the timing-source gate (a wall-clock effect
 * types seconds and chips its **rate** master or Natural; a beat effect segments beats and chips its
 * speed master or its Own tempo), an int's slider range from its declared default alone with the field reaching past it,
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

const BANK = [{ uuid: 'm1', index: 1, name: 'Main', bpm: 120 }, { uuid: 'm2', index: 2, name: 'Slow', bpm: 60 }]
const tempoCalls = vi.hoisted(() => ({
  tapSpeedMaster: vi.fn(),
  setSpeedMasterBpm: vi.fn(),
  programmerTapTempo: vi.fn(),
  programmerSetTempo: vi.fn(),
}))
vi.mock('@/store/speedMasters', () => ({
  useSpeedMasterLiveQuery: (_arg?: unknown, options?: { selectFromResult?: (r: { data: typeof BANK }) => unknown }) =>
    options?.selectFromResult ? options.selectFromResult({ data: BANK }) : { data: BANK },
  tapSpeedMaster: tempoCalls.tapSpeedMaster,
  setSpeedMasterBpm: tempoCalls.setSpeedMasterBpm,
}))
vi.mock('@/store/programmer', () => ({
  useProgrammerTempo: () => ({ projectId: 1, masters: [{ speedMasterUuid: 'm1', bpm: 120 }] }),
  programmerTapTempo: tempoCalls.programmerTapTempo,
  programmerSetTempo: tempoCalls.programmerSetTempo,
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
  ownBpm: null,
  natural: false,
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
  more: Pick<ComponentProps<typeof FxFields>, 'attribute' | 'layout' | 'swap' | 'tempoHost'> = {},
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

afterEach(() => {
  vi.restoreAllMocks()
  for (const call of Object.values(tempoCalls)) call.mockClear()
})

/** Taps at [times] (ms), counted where the window counts them. */
function tapAt(times: number[]) {
  const now = vi.spyOn(performance, 'now')
  for (const t of times) {
    now.mockReturnValueOnce(t)
    fireEvent.click(screen.getByRole('button', { name: /^Tap / }))
  }
}

describe('FxFields', () => {
  it('gives a wall-clock effect with no rate master Natural, with no Tap, and a beat effect its master and Tap', () => {
    fields(FLICKER, draft())
    expect(screen.getByRole('button', { name: 'Tempo: Natural' })).toBeTruthy()
    expect(screen.queryByRole('button', { name: /^Tap / })).toBeNull()
    expect(screen.getByText('120 BPM · Natural')).toBeTruthy()
    // Two seconds a cycle is ×1 on the wall clock.
    expect((screen.getByRole('textbox', { name: 'Speed value' }) as HTMLInputElement).value).toBe('×1')
    cleanup()
    fields(PULSE, draft({ cycle: 4 }))
    expect(screen.getByRole('button', { name: 'Tempo: M1 · Main' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Tap the master’s tempo' })).toBeTruthy()
    expect((screen.getByRole('spinbutton', { name: /^Tempo/ }) as HTMLInputElement).value).toBe('120')
    expect(screen.getByText('1 bar · 2 s a cycle at 120 BPM')).toBeTruthy()
  })

  it('reads a beat effect on its own tempo in beats against that tempo, its chip saying Own', () => {
    fields(PULSE, draft({ ownBpm: 96, cycle: 4 }))
    expect(screen.getByRole('button', { name: 'Tempo: Own 96' })).toBeTruthy()
    expect(screen.getByText('1 bar · 2.5 s a cycle at 96 BPM')).toBeTruthy()
  })

  it('types and taps an Own tempo into the FX alone, counted by the desk’s rules', () => {
    const { set } = fields(PULSE, draft({ ownBpm: 96 }))
    const bpm = screen.getByRole('spinbutton', { name: /Own tempo/ }) as HTMLInputElement
    expect(bpm.value).toBe('96')
    fireEvent.change(bpm, { target: { value: '140' } })
    fireEvent.keyDown(bpm, { key: 'Enter' })
    expect(set).toHaveBeenLastCalledWith({ ownBpm: 140, speedMasterUuid: null, rateSpeedMasterUuid: null }, true)
    tapAt([10_000, 10_500, 11_000])
    expect(set).toHaveBeenLastCalledWith({ ownBpm: 120, speedMasterUuid: null, rateSpeedMasterUuid: null }, true)
    expect(tempoCalls.tapSpeedMaster).not.toHaveBeenCalled()
  })

  it('moves a beat effect from its master onto Own tempo at the tempo that master is running at', () => {
    const { set } = fields(PULSE, draft({ speedMasterUuid: 'm2', cycle: 4 }))
    fireEvent.click(screen.getByRole('button', { name: 'Tempo: M2 · Slow' }))
    fireEvent.click(screen.getByRole('radio', { name: 'Own tempo' }))
    // The cycle stays in beats, so the speed is kept.
    expect(set).toHaveBeenLastCalledWith({ ownBpm: 60, speedMasterUuid: null, rateSpeedMasterUuid: null }, true)
  })

  it('lists the masters before Own tempo, and moves off Own tempo by naming a master, the cycle kept', () => {
    const { set } = fields(PULSE, draft({ ownBpm: 96, cycle: 4 }))
    fireEvent.click(screen.getByRole('button', { name: 'Tempo: Own 96' }))
    const options = Array.from(screen.getByRole('radiogroup', { name: 'Tempo' }).querySelectorAll('button')).map((b) => b.getAttribute('aria-label'))
    expect(options).toEqual(['M1 · Main', 'M2 · Slow', 'Own tempo'])
    expect(screen.getByRole('radio', { name: 'Own tempo' }).getAttribute('aria-checked')).toBe('true')
    expect(screen.queryByRole('radio', { name: 'Natural' })).toBeNull()
    fireEvent.click(screen.getByRole('radio', { name: 'M1 · Main' }))
    expect(set).toHaveBeenLastCalledWith({ ownBpm: null, speedMasterUuid: 'm1' }, true)
  })

  it('offers Natural on a wall-clock effect, which sends natural and no rate master', () => {
    const { set } = fields(FLICKER, draft({ rateSpeedMasterUuid: 'm2' }))
    fireEvent.click(screen.getByRole('button', { name: 'Tempo: rate M2 · Slow' }))
    expect(screen.queryByRole('radio', { name: 'Own tempo' })).toBeNull()
    fireEvent.click(screen.getByRole('radio', { name: 'Natural' }))
    expect(set).toHaveBeenLastCalledWith({ natural: true, rateSpeedMasterUuid: null }, true)
  })

  it('names a stored rate master the bank does not hold as M1, the master the desk runs it on — not Natural', () => {
    fields(FLICKER, draft({ rateSpeedMasterUuid: 'gone' }))
    expect(screen.getByRole('button', { name: 'Tempo: rate M1 · Main' })).toBeTruthy()
  })

  it('labels the slider’s snaps ×¼ to ×4, and sticks a drag to a power of two', () => {
    const { set } = fields(PULSE, draft({ cycle: 4 }))
    const labels = Array.from(document.querySelectorAll('[aria-hidden] > span')).map((s) => s.textContent).filter(Boolean)
    expect(labels).toEqual(['×¼', '×½', '×1', '×2', '×4'])
    const slider = screen.getByRole('slider', { name: 'Speed' }) as HTMLInputElement
    expect(slider.value).toBe('500')
    // 0.5 + 1/8 of an octave's share above ×2 (an octave is 125 of 1000) — near enough to stick.
    fireEvent.change(slider, { target: { value: '630' } })
    expect(set).toHaveBeenLastCalledWith({ cycle: 2 }, false)
    // Between ×2 and ×4 it slides, in whole ticks: ×2.83 is 34 ticks.
    fireEvent.change(slider, { target: { value: '687' } })
    expect(set).toHaveBeenLastCalledWith({ cycle: 34 / 24 }, false)
    fireEvent.pointerUp(slider)
    expect(set).toHaveBeenLastCalledWith({ cycle: 34 / 24 }, true)
  })

  it('moves a quarter of an octave an arrow, and a whole one with Shift', () => {
    const { set } = fields(PULSE, draft({ cycle: 4 }))
    const slider = screen.getByRole('slider', { name: 'Speed' })
    fireEvent.keyDown(slider, { key: 'ArrowRight', shiftKey: true })
    expect(set).toHaveBeenLastCalledWith({ cycle: 2 }, true)
    fireEvent.keyDown(slider, { key: 'ArrowLeft' })
    // ×2^-¼ is 4 ÷ 0.84 beats, 114 ticks.
    expect(set).toHaveBeenLastCalledWith({ cycle: 114 / 24 }, true)
  })

  it('types a fraction or a decimal, says the Speed that runs, and lights the typed value off a snap', () => {
    const { set } = fields(PULSE, draft({ cycle: 4 }))
    const typed = screen.getByRole('textbox', { name: 'Speed value' }) as HTMLInputElement
    expect(typed.closest('[data-typed-cell]')?.hasAttribute('data-lit')).toBe(false)
    fireEvent.change(typed, { target: { value: '⅔' } })
    fireEvent.keyDown(typed, { key: 'Enter' })
    expect(set).toHaveBeenLastCalledWith({ cycle: 6 }, true)
    fireEvent.change(typed, { target: { value: '5' } })
    fireEvent.keyDown(typed, { key: 'Enter' })
    expect(set).toHaveBeenLastCalledWith({ cycle: 19 / 24 }, true)
    cleanup()
    fields(PULSE, draft({ cycle: 19 / 24 }))
    const five = screen.getByRole('textbox', { name: 'Speed value' }) as HTMLInputElement
    expect(five.value).toBe('×5.05')
    expect(five.closest('[data-typed-cell]')?.hasAttribute('data-lit')).toBe(true)
  })

  it('draws a Speed off the track at its end, writing nothing until the thumb is dragged', () => {
    const { set } = fields(PULSE, draft({ cycle: 128 }))
    expect((screen.getByRole('slider', { name: 'Speed' }) as HTMLInputElement).value).toBe('0')
    expect((screen.getByRole('textbox', { name: 'Speed value' }) as HTMLInputElement).value).toBe('×1/32')
    expect(set).not.toHaveBeenCalled()
  })

  it.each([
    ['programmer', { kind: 'programmer' } as const, 'programmerTapTempo', 'programmerSetTempo'],
    ['live', { kind: 'live' } as const, 'tapSpeedMaster', 'setSpeedMasterBpm'],
  ] as const)('sends a Tap and a typed tempo on a master to the %s', (_host, tempoHost, tap, typedTo) => {
    fields(PULSE, draft({ speedMasterUuid: 'm1' }), SCOPE, { tempoHost })
    fireEvent.click(screen.getByRole('button', { name: 'Tap the master’s tempo' }))
    expect(tempoCalls[tap]).toHaveBeenCalledWith('m1')
    const field = screen.getByRole('spinbutton', { name: /^Tempo/ })
    fireEvent.change(field, { target: { value: '130' } })
    fireEvent.keyDown(field, { key: 'Enter' })
    expect(tempoCalls[typedTo]).toHaveBeenCalledWith('m1', 130)
  })

  it('marks a master the programmer holds in amber in the live editor', () => {
    fields(PULSE, draft({ speedMasterUuid: 'm1' }), SCOPE, { tempoHost: { kind: 'programmer' } })
    expect(document.querySelector('[data-held-tempo]')).toBeTruthy()
    expect(screen.getByText('held in the programmer')).toBeTruthy()
  })

  it('taps a master into the cue’s Tempo on GO in the cue FX tab, sending nothing live', () => {
    const setRow = vi.fn()
    fields(PULSE, draft({ speedMasterUuid: 'm2' }), SCOPE, { tempoHost: { kind: 'cue', rows: [{ speedMasterUuid: 'm2', bpm: 90 }], setRow } })
    expect((screen.getByRole('spinbutton', { name: /^Tempo/ }) as HTMLInputElement).value).toBe('90')
    tapAt([0, 400, 800])
    expect(setRow).toHaveBeenLastCalledWith('m2', 150)
    expect(tempoCalls.tapSpeedMaster).not.toHaveBeenCalled()
    expect(tempoCalls.programmerTapTempo).not.toHaveBeenCalled()
  })

  it('offers no Tap or tempo on a follower master, whose leader sets it', () => {
    BANK.push({ uuid: 'm3', index: 3, name: 'Half', bpm: 60, followNum: 1, followDen: 2, followTargetUuid: 'm1' } as (typeof BANK)[number])
    try {
      fields(PULSE, draft({ speedMasterUuid: 'm3' }), SCOPE, { tempoHost: { kind: 'cue', rows: [], setRow: vi.fn() } })
      expect(screen.queryByRole('button', { name: /^Tap / })).toBeNull()
      expect(screen.queryByRole('spinbutton', { name: /^Tempo/ })).toBeNull()
      expect(screen.getByText('60 BPM · follows M1, whose tempo it takes')).toBeTruthy()
    } finally {
      BANK.pop()
    }
  })

  it('keeps a typed tempo while the master’s tempo moves under it', () => {
    const { rerender } = render(
      <FxFields entry={PULSE} timingSource="BEAT" draft={draft({ speedMasterUuid: 'm1' })} scope={SCOPE} extendedChannels={undefined} settingAttribute={undefined} readOnly={false} set={vi.fn()} setParam={vi.fn()} />,
    )
    const field = screen.getByRole('spinbutton', { name: /^Tempo/ }) as HTMLInputElement
    field.focus()
    fireEvent.change(field, { target: { value: '12' } })
    BANK[0].bpm = 121
    try {
      rerender(
        <FxFields entry={PULSE} timingSource="BEAT" draft={draft({ speedMasterUuid: 'm1' })} scope={SCOPE} extendedChannels={undefined} settingAttribute={undefined} readOnly={false} set={vi.fn()} setParam={vi.fn()} />,
      )
      expect((screen.getByRole('spinbutton', { name: /^Tempo/ }) as HTMLInputElement).value).toBe('12')
    } finally {
      BANK[0].bpm = 120
    }
  })

  it('says a stateful flicker runs at its own pace, with no tempo, Tap or slider (D4)', () => {
    fields({ ...FLICKER, name: 'Candle Flicker', ownPace: true }, draft())
    expect(screen.getByText('Runs at its own pace')).toBeTruthy()
    expect(screen.queryByRole('button', { name: /^Tempo: / })).toBeNull()
    expect(screen.queryByRole('button', { name: /^Tap / })).toBeNull()
    expect(screen.queryByRole('slider', { name: 'Speed' })).toBeNull()
    expect(screen.getByRole('radiogroup', { name: 'Direction' })).toBeTruthy()
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

  it('puts Amount in percent under Mix and Direction as three glyphs under Speed', () => {
    const { set } = fields(PULSE, draft({ amount: 0.6, direction: 'BACKWARD' }))
    const size = screen.getByRole('spinbutton', { name: /Amount/ }) as HTMLInputElement
    expect(size.value).toBe('60')
    fireEvent.change(size, { target: { value: '40' } })
    fireEvent.keyDown(size, { key: 'Enter' })
    expect(set).toHaveBeenLastCalledWith({ amount: 0.4 }, true)
    expect(screen.getByRole('region', { name: 'Mix' }).contains(size)).toBe(true)
    const direction = screen.getByRole('radiogroup', { name: 'Direction' })
    expect(screen.getByRole('region', { name: 'Speed' }).contains(direction)).toBe(true)
    expect(Array.from(direction.querySelectorAll('button')).map((b) => b.getAttribute('aria-label'))).toEqual([
      'Forward',
      'Backward',
      'Bounce',
    ])
    expect(screen.getByRole('radio', { name: 'Backward' }).getAttribute('aria-checked')).toBe('true')
    fireEvent.click(screen.getByRole('radio', { name: 'Bounce' }))
    expect(set).toHaveBeenLastCalledWith({ direction: 'BOUNCE' })
  })

  it('chips Runs under Speed: forever, ×1 to ×4, or a typed count lit when it is none of them', () => {
    const { set } = fields(PULSE, draft())
    expect(screen.getByRole('button', { name: 'Runs: forever' }).textContent).toContain('∞')
    fireEvent.click(screen.getByRole('button', { name: 'Runs: forever' }))
    expect(screen.getByRole('radio', { name: 'Forever' }).getAttribute('aria-checked')).toBe('true')
    expect(screen.getByRole('spinbutton', { name: 'Runs count' }).closest('[data-typed-cell]')?.hasAttribute('data-lit')).toBe(false)
    fireEvent.click(screen.getByRole('radio', { name: '2 runs' }))
    expect(set).toHaveBeenLastCalledWith({ runs: 2 })
    cleanup()
    const second = fields(PULSE, draft({ runs: 7 }))
    fireEvent.click(screen.getByRole('button', { name: 'Runs: 7 runs' }))
    expect(screen.getByRole('spinbutton', { name: 'Runs count' }).closest('[data-typed-cell]')?.hasAttribute('data-lit')).toBe(true)
    const count = screen.getByRole('spinbutton', { name: 'Runs count' })
    fireEvent.change(count, { target: { value: '9' } })
    fireEvent.keyDown(count, { key: 'Enter' })
    expect(second.set).toHaveBeenLastCalledWith({ runs: 9 })
    fireEvent.click(screen.getByRole('radio', { name: 'Forever' }))
    expect(second.set).toHaveBeenLastCalledWith({ runs: null })
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

  const PATH: FxLibraryEntry = {
    ...PULSE,
    name: 'Path',
    family: 'position',
    compatibleAttributes: ['position'],
    parameters: [
      {
        name: 'shape',
        type: 'choice',
        defaultValue: 'CIRCLE',
        description: '',
        label: 'Shape',
        options: [
          { value: 'CIRCLE', label: 'Circle' },
          { value: 'LINE', label: 'Line' },
        ],
      },
      ...['panSize', 'tiltSize'].map((name) => ({
        name,
        type: 'ubyte',
        defaultValue: '64',
        description: '',
        label: name === 'panSize' ? 'Pan size' : 'Tilt size',
        min: 0,
        max: 255,
        step: 1,
        unit: 'dmx' as const,
        shownWhen: { param: 'shape', values: ['CIRCLE'] },
      })),
      { name: 'startPan', type: 'ubyte', defaultValue: '64', description: '', label: 'Start pan', min: 0, max: 255, unit: 'position', shownWhen: { param: 'shape', values: ['LINE'] } },
    ],
    controls: [{ name: 'size', label: 'Size', scales: ['panSize', 'tiltSize'], shownWhen: { param: 'shape', values: ['CIRCLE'] } }],
  }

  it('draws Size for a Circle, its sizes under More, and not for a Line (venue-rig-fixes plan D21)', () => {
    fields(PATH, waveDraft({ shape: 'CIRCLE', panSize: '40', tiltSize: '80' }))
    expect(screen.getByRole('slider', { name: 'Size' }).getAttribute('value')).toBe('80')
    expect(screen.queryByRole('slider', { name: 'Pan size' })).toBeNull()
    const more = screen.getByRole('button', { name: /^More/ })
    expect(more.textContent).toContain('pan size 40')
    fireEvent.click(more)
    expect(screen.getByRole('slider', { name: 'Pan size' })).toBeTruthy()
    cleanup()
    fields(PATH, waveDraft({ shape: 'LINE' }))
    expect(screen.queryByRole('slider', { name: 'Size' })).toBeNull()
    expect(screen.getByRole('slider', { name: 'Start pan' })).toBeTruthy()
  })

  it('scales both sizes from the drag’s start, so a drag through 0 and back keeps their ratio', () => {
    const set = vi.fn()
    function Host() {
      const [d, setD] = useState(waveDraft({ shape: 'CIRCLE', panSize: '40', tiltSize: '80' }))
      return (
        <FxFields
          entry={PATH}
          timingSource={PATH.timingSource}
          draft={d}
          scope={SCOPE}
          extendedChannels={undefined}
          settingAttribute={undefined}
          readOnly={false}
          set={(patch, release) => {
            set(patch, release)
            setD((was) => ({ ...was, ...patch }))
          }}
          setParam={() => {}}
        />
      )
    }
    render(<Host />)
    const slider = screen.getByRole('slider', { name: 'Size' })
    fireEvent.change(slider, { target: { value: '0' } })
    expect(set).toHaveBeenLastCalledWith({ parameters: expect.objectContaining({ panSize: '0', tiltSize: '0' }) }, false)
    fireEvent.change(slider, { target: { value: '160' } })
    expect(set).toHaveBeenLastCalledWith({ parameters: expect.objectContaining({ panSize: '80', tiltSize: '160' }) }, false)
    fireEvent.pointerUp(slider)
    expect(set).toHaveBeenLastCalledWith({ parameters: expect.objectContaining({ panSize: '80', tiltSize: '160' }) }, true)
    const field = screen.getByRole('spinbutton', { name: 'Size' })
    fireEvent.change(field, { target: { value: '40' } })
    fireEvent.keyDown(field, { key: 'Enter' })
    expect(set).toHaveBeenLastCalledWith({ parameters: expect.objectContaining({ panSize: '20', tiltSize: '40' }) }, true)
  })

  it('scales from the members’ values at each gesture’s start, though a drag back to its start never committed', () => {
    const set = vi.fn()
    function Host() {
      const [d, setD] = useState(waveDraft({ shape: 'CIRCLE', panSize: '40', tiltSize: '80' }))
      return (
        <FxFields
          entry={PATH}
          timingSource={PATH.timingSource}
          draft={d}
          scope={SCOPE}
          extendedChannels={undefined}
          settingAttribute={undefined}
          readOnly={false}
          set={(patch, release) => {
            set(patch, release)
            setD((was) => ({ ...was, ...patch }))
          }}
          setParam={(name, value) => setD((was) => ({ ...was, parameters: { ...was.parameters, [name]: value } }))}
        />
      )
    }
    render(<Host />)
    const size = screen.getByRole('slider', { name: 'Size' })
    fireEvent.pointerDown(size)
    fireEvent.change(size, { target: { value: '0' } })
    fireEvent.change(size, { target: { value: '80' } })
    fireEvent.click(screen.getByRole('button', { name: /^More/ }))
    const pan = screen.getByRole('slider', { name: 'Pan size' })
    fireEvent.change(pan, { target: { value: '20' } })
    fireEvent.pointerUp(pan)
    fireEvent.pointerDown(size)
    fireEvent.change(size, { target: { value: '160' } })
    expect(set).toHaveBeenLastCalledWith({ parameters: expect.objectContaining({ panSize: '40', tiltSize: '160' }) }, false)
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

  it.each(['editor', 'inspector'] as const)('draws Speed, the FX’s own section, Across the heads, Mix and then Advanced in the %s layout', (layout) => {
    fields(WAVE, draft({ parameters: { shape: 'SINE' } }), HEADS, { layout, attribute: 'dimmer' })
    expect(sections()).toEqual(['Speed', 'Levels', 'Across the heads', 'Mix'])
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
    fireEvent.click(screen.getByRole('button', { name: /^Tempo: / }))
    const sheet = screen.getByRole('dialog', { name: 'Tempo' })
    expect(sheet.querySelector('[data-chip-sheet]')).toBeTruthy()
    expect(sheet.contains(screen.getByRole('radiogroup', { name: 'Tempo' }))).toBe(true)
  })
})
