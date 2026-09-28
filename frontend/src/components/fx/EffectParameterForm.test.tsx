// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { EffectLibraryEntry } from '@/store/fixtureFx'

// The master pickers read the live bank; stub it so this is a pure gating test.
vi.mock('../../store/speedMasters', () => ({
  useSpeedMasterLiveQuery: () => ({
    data: [
      { uuid: 'm1', index: 1, name: 'Master 1', bpm: 120, isRunning: true, source: 'MANUAL' },
      { uuid: 'm2', index: 2, name: 'Master 2', bpm: 60, isRunning: true, source: 'MANUAL' },
    ],
  }),
}))

import { EffectParameterForm } from './EffectParameterForm'

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

function entry(overrides: Partial<EffectLibraryEntry> = {}): EffectLibraryEntry {
  return {
    name: 'Pulse',
    category: 'dimmer',
    outputType: 'SLIDER',
    effectMode: 'STANDARD',
    parameters: [],
    compatibleProperties: ['dimmer'],
    ...overrides,
  }
}

function renderForm(
  effect: EffectLibraryEntry,
  parameters: Record<string, string> = {},
  onParametersChange: (v: Record<string, string>) => void = () => {},
) {
  return render(
    <EffectParameterForm
      effect={effect}
      beatDivision={4}
      onBeatDivisionChange={() => {}}
      blendMode="OVERRIDE"
      onBlendModeChange={() => {}}
      phaseOffset={0}
      onPhaseOffsetChange={() => {}}
      startOnBeat
      onStartOnBeatChange={() => {}}
      parameters={parameters}
      onParametersChange={onParametersChange}
      targetPropertyName="dimmer"
      isEdit={false}
      speedMasterUuid={null}
      onSpeedMasterChange={() => {}}
      rateSpeedMasterUuid={null}
      onRateSpeedMasterChange={() => {}}
    />,
  )
}

describe('EffectParameterForm timing-source gating', () => {
  it('shows beat divisions and the speed master for a beat-timed effect', () => {
    renderForm(entry())

    expect(screen.getByText('Speed')).toBeInTheDocument()
    expect(screen.getByText('Speed Master')).toBeInTheDocument()
    expect(screen.queryByText('Cycle length')).not.toBeInTheDocument()
    expect(screen.queryByText('Rate master')).not.toBeInTheDocument()
  })

  it('treats a missing timingSource as BEAT', () => {
    // Every effect shipped before timingSource was modelled here, so the absent case must
    // keep behaving exactly as it did.
    renderForm(entry({ timingSource: undefined }))
    expect(screen.getByText('Speed')).toBeInTheDocument()
  })

  it('swaps to cycle seconds and the rate master for a wall-clock effect', () => {
    renderForm(entry({ name: 'CandleFlicker', effectMode: 'STATEFUL', timingSource: 'WALL_CLOCK' }))

    // beatDivision means *seconds* here, so beat-division chips would be a lie...
    expect(screen.getByText('Cycle length')).toBeInTheDocument()
    expect(screen.getByText('seconds')).toBeInTheDocument()
    expect(screen.queryByText('Speed')).not.toBeInTheDocument()

    // ...and a wall-clock effect never consults a speed master, so that picker would be a
    // control that does nothing. It gets the rate master instead.
    expect(screen.queryByText('Speed Master')).not.toBeInTheDocument()
    expect(screen.getByText('Rate master')).toBeInTheDocument()
  })

  it('shows the rate master for a STANDARD wall-clock effect too', () => {
    // Gating on effectMode would be wrong: calculateWallClockPhase applies the rate scale
    // for every wall-clock effect, and whether a given script reads phase is per-script.
    renderForm(entry({ effectMode: 'STANDARD', timingSource: 'WALL_CLOCK' }))
    expect(screen.getByText('Rate master')).toBeInTheDocument()
  })
})

describe('EffectParameterForm int parameter range', () => {
  // Radix's Slider measures its thumb; jsdom has no ResizeObserver, so stub an inert one.
  beforeEach(() => {
    vi.stubGlobal(
      'ResizeObserver',
      class {
        observe() {}
        disconnect() {}
        unobserve() {}
      },
    )
  })
  afterEach(() => vi.unstubAllGlobals())

  // FluorescentFlicker's real declaration: backend D7 started sending the default, which
  // is what made this range reachable at all.
  const flicker = entry({
    name: 'FluorescentFlicker',
    effectMode: 'STATEFUL',
    timingSource: 'WALL_CLOCK',
    parameters: [
      {
        name: 'flickerDurationMs',
        type: 'int',
        defaultValue: '800',
        description: 'Total duration of the flicker burst in milliseconds',
      },
    ],
  })

  function sliderMax(): string | null {
    return screen.getByRole('slider').getAttribute('aria-valuemax')
  }

  it.each([
    ['below the derived range', '100'],
    ['at the end of the track', '1600'],
    ['above the derived range', '5000'],
  ])('holds the range at the declared default with a value %s', (_label, live) => {
    // The range must be a pure function of the declared default. Deriving it from the
    // live value ratcheted upwards on every drag so the right-hand end was unreachable;
    // merely widening by the live value pinned the thumb at 100% and let the value fall
    // but never rise. Neither survives a fixed max.
    renderForm(flicker, { flickerDurationMs: live })
    expect(sliderMax()).toBe('1600')
  })

  it('lets a value outside the guessed range be typed', () => {
    // The range is a guess — the wire declares no bounds — so the slider alone would
    // make 1601+ permanently unreachable and silently clamp anything already stored
    // above it. The input is the escape hatch, and it carries the raw value.
    const onParametersChange = vi.fn()
    renderForm(flicker, { flickerDurationMs: '5000' }, onParametersChange)

    const input = screen.getByRole('textbox', { name: 'Flicker Duration Ms' })
    expect(input).toHaveValue('5000')

    fireEvent.change(input, { target: { value: '9000' } })
    expect(onParametersChange).toHaveBeenCalledWith({ flickerDurationMs: '9000' })
  })

  it('parks the thumb at the end for a value above the range without rewriting it', () => {
    // Clamping is display-only: the slider cannot represent 5000 on a 0-1600 track, but
    // showing it at the edge must not fire a change that silently lowers the stored value.
    const onParametersChange = vi.fn()
    renderForm(flicker, { flickerDurationMs: '5000' }, onParametersChange)

    expect(screen.getByRole('slider')).toHaveAttribute('aria-valuenow', '1600')
    expect(onParametersChange).not.toHaveBeenCalled()
  })
})
