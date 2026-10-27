// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { useState } from 'react'
import type { ActiveFx, FxLibraryEntry } from '@/store/fixtureFx'
import type { PaletteSummary } from '@/api/palettesApi'
import { chan, makeActiveFx, makeFixture, settingProp, sliderProp } from '@/test/fixtureFactories'

const updateFx = vi.hoisted(() => vi.fn((_id: number, _req: unknown) => true))
vi.mock('@/api/lightingApi', async () => {
  const { lightingApiMock } = await import('@/test/backendMock')
  const base = lightingApiMock().lightingApi as Record<string, unknown>
  const fx = { updateFx, subscribe: () => ({ unsubscribe: () => {} }), subscribeToErrors: () => ({ unsubscribe: () => {} }) }
  return { lightingApi: new Proxy(base, { get: (target, prop: string) => (prop === 'fx' ? fx : target[prop]) }) }
})

const wire = vi.hoisted(() => ({
  library: [] as FxLibraryEntry[],
  active: [] as ActiveFx[],
  templates: [] as PaletteSummary[],
}))
const addFixtureFx = vi.hoisted(() =>
  vi.fn((_req: unknown) => ({ unwrap: () => Promise.resolve({ fxId: 77 }) })),
)
const removeFx = vi.hoisted(() => vi.fn((_req: unknown) => ({ unwrap: () => Promise.resolve() })))
const applyGroupFx = vi.hoisted(() => vi.fn((_req: unknown) => ({ unwrap: () => Promise.resolve({ fxId: 88 }) })))
/** The bare effect press: one Local effect over the targets, which took them off effect 5 (stopped). */
const pressFx = vi.hoisted(() =>
  vi.fn((_req: unknown) => ({ unwrap: () => Promise.resolve({ fxId: 99, stopped: [5], narrowed: [] }) })),
)
vi.mock('@/store/fixtureFx', () => ({
  useFxLibraryQuery: () => ({ data: wire.library }),
  useActiveFxQuery: () => ({ data: wire.active }),
  useAddFixtureFxMutation: () => [addFixtureFx],
  usePressProgrammerFxMutation: () => [pressFx],
  useRemoveFxMutation: () => [removeFx],
}))
vi.mock('@/store/groups', () => ({
  useApplyGroupFxMutation: () => [applyGroupFx],
  useRemoveGroupFxMutation: () => [vi.fn()],
}))
vi.mock('@/store/status', () => ({ useIsDeskConnected: () => true }))
// The palette library Palette Steps is offered from (effects plan §4.4).
vi.mock('@/store/palettes', () => ({ usePaletteListQuery: () => ({ data: wire.templates }) }))
// Venue-visit plan D16: a master is tagged for position, none for dimmer.
vi.mock('@/store/speedMasters', () => ({
  useSpeedMasterForFamily: () => (category: string | null | undefined) =>
    category === 'position' ? 'm-position' : category === 'beam' ? 'm-beam' : null,
}))
vi.mock('@/hooks/useFixtureLookup', () => ({ useFixtureLookup: () => ({ fixtures: [], fixtureByKey: new Map() }) }))

import { FxPicker, pressTargetsOf, type AuditionedFx, type DraftFxPick, type FxTarget } from './FxPicker'

const position = (name: string): FxLibraryEntry => ({
  name,
  family: 'position',
  outputType: 'POSITION',
  fxMode: 'STANDARD',
  timingSource: 'BEAT',
  compatibleAttributes: ['position'],
  parameters: [
    { name: 'panCentre', type: 'ubyte', defaultValue: '128', description: '' },
    { name: 'tiltCentre', type: 'ubyte', defaultValue: '128', description: '' },
    { name: 'panRadius', type: 'ubyte', defaultValue: '64', description: '' },
    { name: 'tiltRadius', type: 'ubyte', defaultValue: name === 'Figure 8' ? '32' : '64', description: '' },
  ],
})
const PULSE: FxLibraryEntry = {
  name: 'Pulse',
  family: 'intensity',
  outputType: 'SLIDER',
  fxMode: 'STANDARD',
  compatibleAttributes: ['dimmer', 'uv'],
  parameters: [
    { name: 'min', type: 'ubyte', defaultValue: '0', description: '' },
    { name: 'max', type: 'ubyte', defaultValue: '255', description: '' },
  ],
}

const SPOT = makeFixture('spot-3', [
  sliderProp('dimmer', 'dimmer', chan(1)),
  { type: 'position', name: 'position', displayName: 'Position', category: 'pan', panChannel: chan(2), tiltChannel: chan(3), panMin: 0, panMax: 255, tiltMin: 0, tiltMax: 255 } as never,
])
const TARGET: FxTarget = { type: 'fixture', fixture: SPOT }

/** The tray's own session state, so a second tap sees the first's effect. */
function Harness({ initialFamily = 'POSITION' as const }: { initialFamily?: 'POSITION' | 'INTENSITY' }) {
  const [current, setCurrent] = useState<AuditionedFx | null>(null)
  return <FxPicker target={TARGET} initialFamily={initialFamily} current={current} onCurrent={setCurrent} onEdit={() => {}} />
}

beforeEach(() => {
  wire.library = [PULSE, position('Circle'), position('Figure 8')]
  wire.active = []
  wire.templates = []
  updateFx.mockClear()
  addFixtureFx.mockClear()
  pressFx.mockClear()
  removeFx.mockClear()
})
afterEach(cleanup)

describe('FxPicker', () => {
  it('in draft, a tap hands the host the effect with its starting spec and starts nothing; a second tap picks again', () => {
    const picks: DraftFxPick[] = []
    function Draft() {
      const [current, setCurrent] = useState<{ fxType: string; attribute: string } | null>(null)
      return (
        <FxPicker
          target={TARGET}
          initialFamily="POSITION"
          onEdit={() => {}}
          draft={{
            current,
            onPick: (pick) => {
              picks.push(pick)
              setCurrent({ fxType: pick.fxType, attribute: pick.attribute })
            },
          }}
        />
      )
    }
    render(<Draft />)
    fireEvent.click(screen.getByRole('button', { name: 'Circle' }))
    expect(picks[0]).toMatchObject({
      fxType: 'Circle',
      attribute: 'position',
      blendMode: 'ADDITIVE',
      cycle: 4,
      speedMasterUuid: 'm-position',
      parameters: { panCentre: '128', tiltCentre: '128', panRadius: '64', tiltRadius: '64' },
    })
    expect(screen.getByRole('button', { name: 'Circle' })).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(screen.getByRole('button', { name: 'Figure 8' }))
    expect(picks.map((p) => p.fxType)).toEqual(['Circle', 'Figure 8'])
    expect(addFixtureFx).not.toHaveBeenCalled()
    expect(updateFx).not.toHaveBeenCalled()
  })

  it('starts a programmer-owned effect on a tap, with its defaults, Around for a movement', async () => {
    render(<Harness />)
    fireEvent.click(screen.getByRole('button', { name: 'Circle' }))
    await waitFor(() => expect(addFixtureFx).toHaveBeenCalledTimes(1))
    expect(addFixtureFx.mock.calls[0][0]).toMatchObject({
      fxType: 'Circle',
      fixtureKey: 'spot-3',
      attribute: 'position',
      programmerOwned: true,
      blendMode: 'ADDITIVE',
      parameters: { panCentre: '128', tiltCentre: '128', panRadius: '64', tiltRadius: '64' },
      // D16: one bar, on the master tagged with its family.
      cycle: 4,
      speedMasterUuid: 'm-position',
      // New FX start on Fixtures (FX editor follow-ups D8).
      elementMode: 'WHOLE',
    })
    expect(updateFx).not.toHaveBeenCalled()
  })

  it('starts an FX that steps one step behind, in draft and live (fx-settings plan D7)', async () => {
    const stepper: FxLibraryEntry = {
      ...position('Stepper'),
      stepsFrom: 'points',
      parameters: [{ name: 'points', type: 'string', defaultValue: 'a,b,c', description: '' }],
    }
    const before = wire.library
    wire.library = [...before, stepper]
    try {
      const picks: DraftFxPick[] = []
      const { unmount } = render(
        <FxPicker target={TARGET} initialFamily="POSITION" onEdit={() => {}} draft={{ current: null, onPick: (pick) => picks.push(pick) }} />,
      )
      fireEvent.click(screen.getByRole('button', { name: 'Stepper' }))
      expect(picks[0]).toMatchObject({ fxType: 'Stepper', spread: 1, spreadUnit: 'STEP' })
      fireEvent.click(screen.getByRole('button', { name: 'Circle' }))
      expect(picks[1]).not.toHaveProperty('spreadUnit')
      unmount()

      render(<Harness />)
      fireEvent.click(screen.getByRole('button', { name: 'Stepper' }))
      await waitFor(() => expect(addFixtureFx).toHaveBeenCalledTimes(1))
      expect(addFixtureFx.mock.calls[0][0]).toMatchObject({ fxType: 'Stepper', spreadUnit: 'STEP' })
    } finally {
      wire.library = before
    }
  })

  it('starts on master 1 — no master sent — where no master is tagged for the family', async () => {
    render(<Harness initialFamily="INTENSITY" />)
    fireEvent.click(screen.getByRole('button', { name: 'Pulse' }))
    await waitFor(() => expect(addFixtureFx).toHaveBeenCalledTimes(1))
    expect(addFixtureFx.mock.calls[0][0]).toMatchObject({ fxType: 'Pulse', cycle: 4 })
    expect(addFixtureFx.mock.calls[0][0]).not.toHaveProperty('speedMasterUuid')
  })

  it('swaps on a second tap — one updateFx, no add — keeping the instance', async () => {
    const { rerender } = render(<Harness />)
    fireEvent.click(screen.getByRole('button', { name: 'Circle' }))
    await waitFor(() => expect(addFixtureFx).toHaveBeenCalledTimes(1))
    // The desk now runs it.
    wire.active = [makeActiveFx({ id: 77, fxType: 'Circle', targetKey: 'spot-3', attribute: 'position' })]
    rerender(<Harness />)
    expect(screen.getByRole('button', { name: 'Circle' }).getAttribute('aria-pressed')).toBe('true')
    expect(screen.getByRole('button', { name: 'Edit Circle' })).toBeTruthy()

    fireEvent.click(screen.getByRole('button', { name: 'Figure 8' }))
    expect(addFixtureFx).toHaveBeenCalledTimes(1)
    expect(removeFx).not.toHaveBeenCalled()
    expect(updateFx).toHaveBeenCalledTimes(1)
    expect(updateFx.mock.calls[0][0]).toBe(77)
    expect(updateFx.mock.calls[0][1]).toEqual({
      fxType: 'Figure 8',
      blendMode: 'ADDITIVE',
      parameters: { panCentre: '128', tiltCentre: '128', panRadius: '64', tiltRadius: '32' },
    })
  })

  it('swaps from the type the desk runs after the editor’s header swapped it, a tap back on the old one included', async () => {
    const { rerender } = render(<Harness />)
    fireEvent.click(screen.getByRole('button', { name: 'Circle' }))
    await waitFor(() => expect(addFixtureFx).toHaveBeenCalledTimes(1))
    // The editor's header swapped it to Figure 8: the picker's own record still says Circle.
    wire.active = [makeActiveFx({ id: 77, fxType: 'Figure 8', targetKey: 'spot-3', attribute: 'position' })]
    rerender(<Harness />)
    expect(screen.getByRole('button', { name: 'Figure 8' }).getAttribute('aria-pressed')).toBe('true')
    expect(screen.getByRole('button', { name: 'Circle' }).getAttribute('aria-pressed')).toBe('false')
    fireEvent.click(screen.getByRole('button', { name: 'Circle' }))
    expect(updateFx).toHaveBeenCalledTimes(1)
    expect(updateFx.mock.calls[0][0]).toBe(77)
    expect(updateFx.mock.calls[0][1]).toMatchObject({ fxType: 'Circle' })
  })

  it('swaps back on a tap made before the desk has answered its own swap', async () => {
    const { rerender } = render(<Harness />)
    fireEvent.click(screen.getByRole('button', { name: 'Circle' }))
    await waitFor(() => expect(addFixtureFx).toHaveBeenCalledTimes(1))
    wire.active = [makeActiveFx({ id: 77, fxType: 'Circle', targetKey: 'spot-3', attribute: 'position' })]
    rerender(<Harness />)
    fireEvent.click(screen.getByRole('button', { name: 'Figure 8' }))
    // The running list still says Circle: that is this picker's swap in flight, not one made elsewhere.
    rerender(<Harness />)
    expect(screen.getByRole('button', { name: 'Figure 8' }).getAttribute('aria-pressed')).toBe('true')
    fireEvent.click(screen.getByRole('button', { name: 'Circle' }))
    expect(updateFx).toHaveBeenCalledTimes(2)
    expect(updateFx.mock.calls[1][1]).toMatchObject({ fxType: 'Circle' })
  })

  it('restarts rather than swaps when the tap lands on another property', async () => {
    const { rerender } = render(<Harness />)
    fireEvent.click(screen.getByRole('button', { name: 'Circle' }))
    await waitFor(() => expect(addFixtureFx).toHaveBeenCalledTimes(1))
    wire.active = [makeActiveFx({ id: 77, fxType: 'Circle', targetKey: 'spot-3', attribute: 'position' })]
    rerender(<Harness />)

    fireEvent.click(screen.getByRole('radio', { name: 'Intensity' }))
    fireEvent.click(screen.getByRole('button', { name: 'Pulse' }))
    await waitFor(() => expect(addFixtureFx).toHaveBeenCalledTimes(2))
    expect(removeFx).toHaveBeenCalledWith({ id: 77, fixtureKey: 'spot-3' })
    expect(addFixtureFx.mock.calls[1][0]).toMatchObject({ fxType: 'Pulse', attribute: 'dimmer', programmerOwned: true, blendMode: 'OVERRIDE' })
    expect(updateFx).not.toHaveBeenCalled()
  })

  it('lands a swap across timing sources on one cycle, and keeps the speed within one', async () => {
    const flicker: FxLibraryEntry = { ...PULSE, name: 'CandleFlicker', timingSource: 'WALL_CLOCK' }
    const sine: FxLibraryEntry = { ...PULSE, name: 'SineWave' }
    wire.library = [PULSE, flicker, sine]
    const { rerender } = render(<Harness initialFamily="INTENSITY" />)
    fireEvent.click(screen.getByRole('button', { name: 'Pulse' }))
    await waitFor(() => expect(addFixtureFx).toHaveBeenCalledTimes(1))
    wire.active = [makeActiveFx({ id: 77, fxType: 'Pulse', targetKey: 'spot-3', attribute: 'dimmer', cycle: 16, timingSource: 'BEAT' })]
    rerender(<Harness initialFamily="INTENSITY" />)

    fireEvent.click(screen.getByRole('button', { name: 'SineWave' }))
    expect(updateFx.mock.calls[0][1]).not.toHaveProperty('cycle')
    fireEvent.click(screen.getByRole('button', { name: 'CandleFlicker' }))
    expect(updateFx.mock.calls[1][1]).toMatchObject({ fxType: 'CandleFlicker', cycle: 4 })
  })

  it('starts an FX that bounces at its own half bar, Bounce, and a swap at the old default takes the new one', async () => {
    const fade: FxLibraryEntry = { ...PULSE, name: 'Fade', defaultCycle: 2, defaultDirection: 'BOUNCE' }
    wire.library = [PULSE, fade]
    const { rerender } = render(<Harness initialFamily="INTENSITY" />)
    fireEvent.click(screen.getByRole('button', { name: 'Fade' }))
    await waitFor(() => expect(addFixtureFx).toHaveBeenCalledTimes(1))
    expect(addFixtureFx.mock.calls[0][0]).toMatchObject({ fxType: 'Fade', cycle: 2, direction: 'BOUNCE' })

    wire.active = [makeActiveFx({ id: 77, fxType: 'Fade', targetKey: 'spot-3', attribute: 'dimmer', cycle: 2, direction: 'BOUNCE' })]
    rerender(<Harness initialFamily="INTENSITY" />)
    fireEvent.click(screen.getByRole('button', { name: 'Pulse' }))
    expect(updateFx.mock.calls[0][1]).toMatchObject({ fxType: 'Pulse', cycle: 4, direction: 'FORWARD' })
  })

  it('swaps a second tap made before the effect list has caught up, rather than starting a second effect', async () => {
    render(<Harness />)
    fireEvent.click(screen.getByRole('button', { name: 'Circle' }))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Edit Circle' })).toBeTruthy())
    // The list has not refetched: the desk's answer is the create's id alone.
    fireEvent.click(screen.getByRole('button', { name: 'Figure 8' }))
    expect(addFixtureFx).toHaveBeenCalledTimes(1)
    expect(updateFx).toHaveBeenCalledTimes(1)
    expect(updateFx.mock.calls[0][0]).toBe(77)
  })

  it('disables a family the target cannot take', () => {
    render(<Harness />)
    expect((screen.getByRole('radio', { name: 'Colour' }) as HTMLButtonElement).disabled).toBe(true)
    expect((screen.getByRole('radio', { name: 'Position' }) as HTMLButtonElement).disabled).toBe(false)
    expect((screen.getByRole('radio', { name: 'Beam' }) as HTMLButtonElement).disabled).toBe(true)
  })
})

describe('the Beam family (effects plan §4.4)', () => {
  const beam = (name: string, attribute: string): FxLibraryEntry => ({
    name,
    family: 'beam',
    outputType: 'SLIDER',
    fxMode: 'STANDARD',
    timingSource: 'BEAT',
    compatibleAttributes: [attribute],
    parameters: [{ name: 'min', type: 'ubyte', defaultValue: '64', description: '' }],
  })
  const PROFILE = makeFixture('profile-1', [
    sliderProp('dimmer', 'dimmer', chan(1)),
    sliderProp('zoom', 'zoom', chan(2)),
    sliderProp('iris', 'iris', chan(3)),
  ])

  function BeamHarness() {
    const [current, setCurrent] = useState<AuditionedFx | null>(null)
    return (
      <FxPicker
        target={{ type: 'fixture', fixture: PROFILE }}
        initialFamily="BEAM"
        current={current}
        onCurrent={setCurrent}
        onEdit={() => {}}
      />
    )
  }

  beforeEach(() => {
    wire.library = [
      PULSE,
      beam('Zoom Wave', 'zoom'),
      beam('Iris Pulse', 'iris'),
      beam('Frost Pulse', 'frost'),
      beam('Focus Pull', 'focus'),
    ]
  })

  it('offers each beam effect only on a head with its property', () => {
    render(<BeamHarness />)
    const list = screen.getByRole('list', { name: 'Beam FX' })
    expect(Array.from(list.querySelectorAll('[data-effect-pick]')).map((b) => b.getAttribute('data-effect-pick'))).toEqual([
      'Zoom Wave',
      'Iris Pulse',
    ])
  })

  it('opens a Beam row on Beam only where a beam effect drives its property', () => {
    const withPrism = makeFixture('profile-2', [...PROFILE.attributes!, sliderProp('prism', 'prism', chan(4))])
    const props = { current: null, onCurrent: () => {}, onEdit: () => {}, initialFamily: 'BEAM' as const }
    const target = { type: 'fixture' as const, fixture: withPrism }
    const { unmount } = render(<FxPicker target={target} preferredAttribute="iris" {...props} />)
    expect(screen.getByRole('radio', { name: 'Beam' }).getAttribute('aria-checked')).toBe('true')
    unmount()
    wire.library = [...wire.library, { ...PULSE, name: 'Prism Sweep', family: 'controls', compatibleAttributes: ['slider'] }]
    render(<FxPicker target={target} preferredAttribute="prism" {...props} />)
    expect(screen.getByRole('radio', { name: 'Controls' }).getAttribute('aria-checked')).toBe('true')
  })

  it('starts a beam effect on its own property, on the Beam master', async () => {
    render(<BeamHarness />)
    fireEvent.click(screen.getByRole('button', { name: 'Iris Pulse' }))
    await waitFor(() => expect(addFixtureFx).toHaveBeenCalledTimes(1))
    expect(addFixtureFx.mock.calls[0][0]).toMatchObject({
      fxType: 'Iris Pulse',
      fixtureKey: 'profile-1',
      attribute: 'iris',
      cycle: 4,
      speedMasterUuid: 'm-beam',
    })
  })
})

describe('Wave and its presets (fx-settings plan D4, D5)', () => {
  const WAVE: FxLibraryEntry = {
    name: 'Wave',
    family: 'intensity',
    outputType: 'SLIDER',
    fxMode: 'STANDARD',
    timingSource: 'BEAT',
    compatibleAttributes: ['dimmer', 'uv', 'zoom', 'iris', 'frost', 'focus'],
    parameters: [
      { name: 'shape', type: 'choice', defaultValue: 'SINE', description: '' },
      { name: 'low', type: 'ubyte', defaultValue: '0', description: '' },
      { name: 'high', type: 'ubyte', defaultValue: '255', description: '' },
      { name: 'curve', type: 'easingCurve', defaultValue: 'LINEAR', description: '' },
    ],
    attributeDefaults: { zoom: { low: '64', high: '192' }, iris: { low: '0', high: '160' }, focus: { low: '64', high: '192' } },
    presets: [
      { name: 'Strobe', attribute: 'dimmer', parameters: { shape: 'SQUARE', width: '0.1' } },
      { name: 'Zoom Wave', attribute: 'zoom', parameters: { shape: 'SINE', low: '64', high: '192' } },
      { name: 'Iris Pulse', attribute: 'iris', parameters: { shape: 'PULSE', low: '0', high: '160', curve: 'QUAD_OUT' } },
      { name: 'Frost Pulse', attribute: 'frost', parameters: { shape: 'PULSE', low: '0', high: '160', curve: 'QUAD_OUT' } },
      {
        name: 'Focus Pull',
        attribute: 'focus',
        parameters: { shape: 'RAMP_UP', low: '64', high: '192', curve: 'SINE_IN_OUT' },
        cycle: 2,
        direction: 'BOUNCE',
      },
    ],
  }
  const PROFILE = makeFixture('profile-1', [
    sliderProp('dimmer', 'dimmer', chan(1)),
    sliderProp('zoom', 'zoom', chan(2)),
    sliderProp('iris', 'iris', chan(3)),
    sliderProp('focus', 'focus', chan(4)),
  ])

  function WaveHarness({ initialFamily = 'BEAM' as 'BEAM' | 'INTENSITY' }) {
    const [current, setCurrent] = useState<AuditionedFx | null>(null)
    return (
      <FxPicker
        target={{ type: 'fixture', fixture: PROFILE }}
        initialFamily={initialFamily}
        current={current}
        onCurrent={setCurrent}
        onEdit={() => {}}
      />
    )
  }

  const presetsIn = (list: HTMLElement) =>
    Array.from(list.querySelectorAll('[data-effect-preset]')).map((b) => b.getAttribute('data-effect-preset'))

  beforeEach(() => {
    wire.library = [WAVE]
  })

  it('lists each preset under the family its attribute gives, only where the head has the attribute', () => {
    render(<WaveHarness />)
    expect(presetsIn(screen.getByRole('list', { name: 'Beam FX' }))).toEqual(['Zoom Wave', 'Iris Pulse', 'Focus Pull'])
    fireEvent.click(screen.getByRole('radio', { name: 'Intensity' }))
    const intensity = screen.getByRole('list', { name: 'Intensity FX' })
    expect(Array.from(intensity.querySelectorAll('[data-effect-pick]')).map((b) => b.getAttribute('data-effect-pick'))).toEqual(['Wave'])
    expect(presetsIn(intensity)).toEqual(['Strobe'])
  })

  it('starts a preset as Wave on its attribute, with its parameters, on the Beam master', async () => {
    render(<WaveHarness />)
    fireEvent.click(screen.getByRole('button', { name: 'Iris Pulse' }))
    await waitFor(() => expect(addFixtureFx).toHaveBeenCalledTimes(1))
    expect(addFixtureFx.mock.calls[0][0]).toMatchObject({
      fxType: 'Wave',
      attribute: 'iris',
      cycle: 4,
      speedMasterUuid: 'm-beam',
      parameters: { shape: 'PULSE', low: '0', high: '160', curve: 'QUAD_OUT' },
    })
    expect(screen.getByRole('button', { name: 'Iris Pulse' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('starts a preset at its own cycle and Direction: Focus Pull bounces at half a bar', async () => {
    render(<WaveHarness />)
    fireEvent.click(screen.getByRole('button', { name: 'Focus Pull' }))
    await waitFor(() => expect(addFixtureFx).toHaveBeenCalledTimes(1))
    expect(addFixtureFx.mock.calls[0][0]).toMatchObject({ fxType: 'Wave', attribute: 'focus', cycle: 2, direction: 'BOUNCE' })
  })

  it('swaps from one preset to another on the same attribute with one updateFx carrying the preset’s parameters', async () => {
    const DIMMER = makeFixture('par-1', [sliderProp('dimmer', 'dimmer', chan(1))])
    function Swap() {
      const [current, setCurrent] = useState<AuditionedFx | null>(null)
      return <FxPicker target={{ type: 'fixture', fixture: DIMMER }} initialFamily="INTENSITY" current={current} onCurrent={setCurrent} />
    }
    render(<Swap />)
    fireEvent.click(screen.getByRole('button', { name: 'Wave' }))
    await waitFor(() => expect(addFixtureFx).toHaveBeenCalledTimes(1))
    expect(addFixtureFx.mock.calls[0][0]).toMatchObject({ fxType: 'Wave', attribute: 'dimmer', parameters: { shape: 'SINE', low: '0' } })
    fireEvent.click(screen.getByRole('button', { name: 'Strobe' }))
    expect(addFixtureFx).toHaveBeenCalledTimes(1)
    expect(updateFx).toHaveBeenCalledTimes(1)
    expect(updateFx.mock.calls[0][1]).toMatchObject({ fxType: 'Wave', parameters: { shape: 'SQUARE', width: '0.1' } })
    expect(screen.getByRole('button', { name: 'Strobe' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: 'Wave' })).toHaveAttribute('aria-pressed', 'false')
  })

  it('lists the Wave type under Beam too, on the head’s beam attribute, lit there once it runs on one', async () => {
    render(<WaveHarness />)
    const beam = screen.getByRole('list', { name: 'Beam FX' })
    const wave = beam.querySelector<HTMLElement>('[data-effect-pick="Wave"]')!
    fireEvent.click(wave)
    await waitFor(() => expect(addFixtureFx).toHaveBeenCalledTimes(1))
    expect(addFixtureFx.mock.calls[0][0]).toMatchObject({
      fxType: 'Wave',
      attribute: 'zoom',
      speedMasterUuid: 'm-beam',
      parameters: { shape: 'SINE', low: '64', high: '192' },
    })
    expect(wave).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(screen.getByRole('radio', { name: 'Intensity' }))
    expect(screen.getByRole('list', { name: 'Intensity FX' }).querySelector('[data-effect-pick="Wave"]')).toHaveAttribute('aria-pressed', 'false')
  })

  it('starts the Wave type on a beam attribute at its attribute defaults', async () => {
    const ZOOM_ONLY = makeFixture('zoom-1', [sliderProp('zoom', 'zoom', chan(1))])
    const picks: DraftFxPick[] = []
    render(
      <FxPicker
        target={{ type: 'fixture', fixture: ZOOM_ONLY }}
        initialFamily="INTENSITY"
        draft={{ current: null, onPick: (pick) => picks.push(pick) }}
      />,
    )
    fireEvent.click(screen.getByRole('button', { name: 'Wave' }))
    expect(picks[0]).toMatchObject({ fxType: 'Wave', family: 'intensity', attribute: 'zoom', speedMasterUuid: 'm-beam', parameters: { low: '64', high: '192' } })
  })
})

describe('the step effects (effects plan session 7, §4.4)', () => {
  const PALETTE_STEPS: FxLibraryEntry = {
    name: 'PaletteSteps',
    family: 'steps',
    outputType: 'STEPS',
    fxMode: 'STANDARD',
    timingSource: 'BEAT',
    compatibleAttributes: ['dimmer', 'rgbColour', 'position', 'zoom', 'focus', 'iris', 'frost'],
    parameters: [
      { name: 'palettes', type: 'paletteList', defaultValue: '', description: '' },
      { name: 'fadeRatio', type: 'double', defaultValue: '0.5', description: '' },
    ],
  }
  const SLOT_STEPS: FxLibraryEntry = {
    name: 'SlotSteps',
    // The Steps family's, as the desk files it (terminology plan D23); it is still offered in Beam.
    family: 'steps',
    outputType: 'STEPS',
    fxMode: 'STANDARD',
    timingSource: 'BEAT',
    compatibleAttributes: ['setting'],
    parameters: [{ name: 'slots', type: 'slotList', defaultValue: '', description: '' }],
  }
  const palette = (name: string, family: PaletteSummary['family'], attribute: string, value: string, over: Partial<PaletteSummary> = {}) =>
    ({
      id: name.length,
      uuid: `uuid-${name}`,
      name,
      notes: null,
      fadeDurationMs: null,
      family,
      isGeneric: true,
      kind: 'value',
      requiredEmitters: [],
      rows: [{ targetType: 'deferred', targetKey: '', attribute, value }],
      fx: null,
      ...over,
    }) as unknown as PaletteSummary
  const MOVER = makeFixture('mover-1', [
    sliderProp('dimmer', 'dimmer', chan(1)),
    { type: 'position', name: 'position', displayName: 'Position', category: 'pan', panChannel: chan(2), tiltChannel: chan(3), panMin: 0, panMax: 255, tiltMin: 0, tiltMax: 255 } as never,
    settingProp('gobo', 'gobo', chan(4), [
      { name: 'OPEN', level: 0, displayName: 'Open' },
      { name: 'GOBO_1', level: 10, displayName: 'Gobo 1' },
      { name: 'GOBO_2', level: 20, displayName: 'Gobo 2' },
      { name: 'GOBO_3', level: 30, displayName: 'Gobo 3' },
    ]),
  ])

  // A colour wheel declared before its gobo wheel, so "the first setting" would be the wrong one.
  const WHEEL_FIRST = makeFixture('mover-2', [
    sliderProp('dimmer', 'dimmer', chan(1)),
    settingProp('colourWheel', 'colour', chan(4), [
      { name: 'WHITE', level: 0, displayName: 'White' },
      { name: 'RED', level: 10, displayName: 'Red' },
      { name: 'BLUE', level: 20, displayName: 'Blue' },
    ]),
    settingProp('gobo', 'gobo', chan(5), [
      { name: 'OPEN', level: 0, displayName: 'Open' },
      { name: 'GOBO_1', level: 10, displayName: 'Gobo 1' },
      { name: 'GOBO_2', level: 20, displayName: 'Gobo 2' },
    ]),
  ])

  function StepHarness({
    initialFamily,
    preferredAttribute,
    fixture = MOVER,
  }: {
    initialFamily: 'POSITION' | 'BEAM' | 'INTENSITY'
    preferredAttribute?: string
    fixture?: typeof MOVER
  }) {
    const [current, setCurrent] = useState<AuditionedFx | null>(null)
    return (
      <FxPicker
        target={{ type: 'fixture', fixture }}
        initialFamily={initialFamily}
        preferredAttribute={preferredAttribute}
        current={current}
        onCurrent={setCurrent}
        onEdit={() => {}}
      />
    )
  }

  beforeEach(() => {
    wire.library = [PULSE, position('Circle'), PALETTE_STEPS, SLOT_STEPS]
    wire.templates = [
      palette('Centre', 'POSITION', 'position', 'pos:0,0'),
      palette('Downstage', 'POSITION', 'position', 'pos:-64,32'),
      palette('Half', 'INTENSITY', 'dimmer', 'pct:50'),
      // Neither offered: a per-fixture position and an effect palette are no steps.
      palette('Aimed', 'POSITION', 'position', 'pos:10,10', { isGeneric: false }),
      palette('Orbit', 'POSITION', 'position', 'pos:0,0', { kind: 'fx' }),
    ]
  })

  it('offers Palette Steps in a family with two generic value palettes, and not in one with fewer', () => {
    render(<StepHarness initialFamily="POSITION" />)
    const list = screen.getByRole('list', { name: 'Position FX' })
    expect(Array.from(list.querySelectorAll('[data-effect-pick]')).map((b) => b.getAttribute('data-effect-pick'))).toEqual([
      'Circle',
      'PaletteSteps',
    ])
    fireEvent.click(screen.getByRole('radio', { name: 'Intensity' }))
    const intensity = screen.getByRole('list', { name: 'Intensity FX' })
    expect(intensity.querySelector('[data-effect-pick="PaletteSteps"]')).toBeNull()
  })

  it('starts Palette Steps on the palettes’ property, stepping all of them, on the family’s master', async () => {
    render(<StepHarness initialFamily="POSITION" />)
    fireEvent.click(screen.getByRole('button', { name: 'PaletteSteps' }))
    await waitFor(() => expect(addFixtureFx).toHaveBeenCalledTimes(1))
    expect(addFixtureFx.mock.calls[0][0]).toMatchObject({
      fxType: 'PaletteSteps',
      attribute: 'position',
      blendMode: 'OVERRIDE',
      speedMasterUuid: 'm-position',
      parameters: { palettes: 'pal:uuid-Centre,pal:uuid-Downstage', fadeRatio: '0.5' },
    })
  })

  it('offers Slot Steps in Beam on a gobo row, and starts it on that setting’s first slots past open', async () => {
    render(<StepHarness initialFamily="BEAM" preferredAttribute="gobo" />)
    expect(screen.getByRole('radio', { name: 'Beam' }).getAttribute('aria-checked')).toBe('true')
    fireEvent.click(screen.getByRole('button', { name: 'SlotSteps' }))
    await waitFor(() => expect(addFixtureFx).toHaveBeenCalledTimes(1))
    expect(addFixtureFx.mock.calls[0][0]).toMatchObject({
      fxType: 'SlotSteps',
      attribute: 'gobo',
      speedMasterUuid: 'm-beam',
      parameters: { slots: 'GOBO_1,GOBO_2' },
    })
  })

  it('starts Slot Steps on the gobo wheel where no setting row says otherwise, not the first setting', async () => {
    render(<StepHarness initialFamily="BEAM" fixture={WHEEL_FIRST} />)
    fireEvent.click(screen.getByRole('button', { name: 'SlotSteps' }))
    await waitFor(() => expect(addFixtureFx).toHaveBeenCalledTimes(1))
    expect(addFixtureFx.mock.calls[0][0]).toMatchObject({ fxType: 'SlotSteps', attribute: 'gobo', parameters: { slots: 'GOBO_1,GOBO_2' } })
  })

  /** A host asking `stackOn` of every fresh start, on [fixture], as the FX arm does. */
  function Moving({
    fixture,
    stackOn,
    onLanding,
  }: {
    fixture: typeof MOVER
    stackOn: (attribute: string) => boolean
    onLanding?: (a: readonly string[]) => void
  }) {
    const [current, setCurrent] = useState<AuditionedFx | null>(null)
    return (
      <FxPicker
        target={{ type: 'fixture', fixture }}
        initialFamily="BEAM"
        current={current}
        onCurrent={setCurrent}
        onEdit={() => {}}
        press={{ projectId: 3, families: null, stackOn }}
        onLanding={onLanding}
      />
    )
  }
  const TWO_GOBOS = makeFixture('mover-3', [
    sliderProp('dimmer', 'dimmer', chan(1)),
    settingProp('gobo', 'gobo', chan(4), [
      { name: 'OPEN', level: 0, displayName: 'Open' },
      { name: 'GOBO_1', level: 10, displayName: 'Gobo 1' },
      { name: 'GOBO_2', level: 20, displayName: 'Gobo 2' },
    ]),
    settingProp('rotatingGobo', 'gobo', chan(5), [
      { name: 'OPEN', level: 0, displayName: 'Open' },
      { name: 'SPIRAL', level: 10, displayName: 'Spiral' },
      { name: 'STARS', level: 20, displayName: 'Stars' },
    ]),
  ])

  /** Starts Slot Steps stacked on the gobo wheel, then has `stackOn` answer [after] and moves it [to]. */
  async function moveStacked(fixture: typeof MOVER, to: string, after: boolean, onLanding?: (a: readonly string[]) => void) {
    const answer = { stack: true }
    const stackOn = vi.fn((_attribute: string) => answer.stack)
    const { rerender } = render(<Moving fixture={fixture} stackOn={stackOn} onLanding={onLanding} />)
    fireEvent.click(screen.getByRole('button', { name: 'SlotSteps' }))
    await waitFor(() => expect(pressFx).toHaveBeenCalledTimes(1))
    expect(pressFx.mock.calls[0][0]).toMatchObject({ attribute: 'gobo', stack: true })
    wire.active = [makeActiveFx({ id: 99, fxType: 'SlotSteps', targetKey: fixture.key, attribute: 'gobo' })]
    answer.stack = after
    stackOn.mockClear()
    rerender(<Moving fixture={fixture} stackOn={stackOn} onLanding={onLanding} />)
    fireEvent.click(screen.getByRole('combobox', { name: 'Attribute' }))
    fireEvent.click(screen.getByRole('option', { name: to }))
    await waitFor(() => expect(pressFx).toHaveBeenCalledTimes(2))
    expect(removeFx).toHaveBeenCalledWith(expect.objectContaining({ id: 99 }))
    return { stackOn, moved: JSON.parse(JSON.stringify(pressFx.mock.calls[1][0])) }
  }

  it('keeps a stacked Slot Steps stacked when the On row moves it within its family, without asking', async () => {
    const { stackOn, moved } = await moveStacked(TWO_GOBOS, 'rotatingGobo', false)
    expect(stackOn).not.toHaveBeenCalled()
    expect(moved).toMatchObject({ attribute: 'rotatingGobo', stack: true })
    expect(moved).not.toHaveProperty('blendMode')
  })

  it('asks of an On move into another family, which it reports as a landing first', async () => {
    const onLanding = vi.fn()
    const { stackOn, moved } = await moveStacked(WHEEL_FIRST, 'colourWheel', false, onLanding)
    // While Slot Steps ran on the gobo wheel, the colour wheel was a fresh start in Colour.
    expect(onLanding.mock.calls.some(([a]) => a.includes('colourWheel') && !a.includes('gobo'))).toBe(true)
    expect(stackOn).toHaveBeenCalledWith('colourWheel')
    expect(moved).toMatchObject({ attribute: 'colourWheel' })
    expect(moved).not.toHaveProperty('stack')
    expect(moved).toHaveProperty('blendMode')
  })
})

/**
 * The bare effect press (apply-model plan §3.4, session 6): *+ Effect*, ⌘K *Apply FX* and a band's
 * *+ Effect …* start one Local effect over the selection through `POST /programmer/fx`, with the
 * door's mask — the desk then takes those heads off the family's other Local effects, which is why
 * the answer names effect 5 stopped and the client does nothing about it.
 */
describe('FxPicker on the bare effect press', () => {
  const HEX = [1, 2, 3, 4].map((n) => makeFixture(`hex-${n}`, [sliderProp('dimmer', 'dimmer', chan(n))]))
  const FOUR: FxTarget = {
    type: 'selection',
    targets: HEX.map((f) => ({ type: 'fixture' as const, key: f.key })),
    fixtures: HEX,
    label: 'Hex 1, Hex 2 +2',
  }

  function Pressing({ target = FOUR }: { target?: FxTarget }) {
    const [current, setCurrent] = useState<AuditionedFx | null>(null)
    return (
      <FxPicker
        target={target}
        initialFamily="INTENSITY"
        current={current}
        onCurrent={setCurrent}
        onEdit={() => {}}
        press={{ projectId: 3, families: ['INTENSITY'] }}
      />
    )
  }

  it('starts one effect over a four-head selection through the press, with the mask, and never /fx/add', async () => {
    render(<Pressing />)
    fireEvent.click(screen.getByRole('button', { name: 'Pulse' }))
    await waitFor(() => expect(pressFx).toHaveBeenCalledTimes(1))
    expect(pressFx.mock.calls[0][0]).toMatchObject({
      projectId: 3,
      fxType: 'Pulse',
      attribute: 'dimmer',
      targets: HEX.map((f) => ({ type: 'fixture', key: f.key })),
      families: ['INTENSITY'],
      cycle: 4,
    })
    expect(pressFx.mock.calls[0][0]).not.toHaveProperty('programmerOwned')
    expect(addFixtureFx).not.toHaveBeenCalled()
    expect(applyGroupFx).not.toHaveBeenCalled()
  })

  it('auditions the pressed effect: a second tap swaps it in place rather than pressing again', async () => {
    const { rerender } = render(<Pressing />)
    wire.library = [PULSE, { ...PULSE, name: 'Ramp' }]
    rerender(<Pressing />)
    fireEvent.click(screen.getByRole('button', { name: 'Pulse' }))
    await waitFor(() => expect(pressFx).toHaveBeenCalledTimes(1))
    wire.active = [makeActiveFx({ id: 99, fxType: 'Pulse', targetKey: '[]', attribute: 'dimmer' })]
    rerender(<Pressing />)
    fireEvent.click(screen.getByRole('button', { name: 'Ramp' }))
    expect(pressFx).toHaveBeenCalledTimes(1)
    expect(updateFx).toHaveBeenCalledWith(99, expect.objectContaining({ fxType: 'Ramp' }))
  })

  it('names a group as itself, so its effect is one group effect with its distribution', () => {
    expect(pressTargetsOf({ type: 'group', group: { name: 'front' } as never })).toEqual([{ type: 'group', key: 'front' }])
    expect(pressTargetsOf(FOUR)).toEqual(FOUR.type === 'selection' ? FOUR.targets : [])
  })

  it('sends no mask where the door carries none', async () => {
    function Unmasked() {
      const [current, setCurrent] = useState<AuditionedFx | null>(null)
      return (
        <FxPicker
          target={FOUR}
          initialFamily="INTENSITY"
          current={current}
          onCurrent={setCurrent}
          onEdit={() => {}}
          press={{ projectId: 3, families: null }}
        />
      )
    }
    render(<Unmasked />)
    fireEvent.click(screen.getByRole('button', { name: 'Pulse' }))
    await waitFor(() => expect(pressFx).toHaveBeenCalledTimes(1))
    expect(pressFx.mock.calls[0][0]).not.toHaveProperty('families')
  })

  /** The FX editor's *Stack* (FX editor plan D6): nothing replaced, the family's stacking blend the desk's. */
  function Stacking({ stack }: { stack: boolean }) {
    const [current, setCurrent] = useState<AuditionedFx | null>(null)
    return (
      <FxPicker
        target={FOUR}
        initialFamily="INTENSITY"
        current={current}
        onCurrent={setCurrent}
        onEdit={() => {}}
        press={{ projectId: 3, families: null, stackOn: () => stack }}
      />
    )
  }

  it('sends stack: true and no blend under Stack, so the desk starts it on the family’s stacking blend', async () => {
    render(<Stacking stack />)
    fireEvent.click(screen.getByRole('button', { name: 'Pulse' }))
    await waitFor(() => expect(pressFx).toHaveBeenCalledTimes(1))
    const sent = JSON.parse(JSON.stringify(pressFx.mock.calls[0][0]))
    expect(sent).toMatchObject({ stack: true, fxType: 'Pulse' })
    expect(sent).not.toHaveProperty('blendMode')
  })

  it('sends no stack under Replace, with the FX’s own blend', async () => {
    render(<Stacking stack={false} />)
    fireEvent.click(screen.getByRole('button', { name: 'Pulse' }))
    await waitFor(() => expect(pressFx).toHaveBeenCalledTimes(1))
    expect(pressFx.mock.calls[0][0]).not.toHaveProperty('stack')
    expect(pressFx.mock.calls[0][0]).toHaveProperty('blendMode')
  })

  it('asks stackOn with the attribute the tap lands on, and sends stack: true only where it answers yes', async () => {
    const asked: string[] = []
    const stackOn = (attribute: string) => {
      asked.push(attribute)
      return attribute === 'dimmer'
    }
    const { unmount } = render(
      <FxPicker target={FOUR} initialFamily="INTENSITY" press={{ projectId: 3, families: null, stackOn }} onCurrent={() => {}} />,
    )
    fireEvent.click(screen.getByRole('button', { name: 'Pulse' }))
    await waitFor(() => expect(pressFx).toHaveBeenCalledTimes(1))
    expect(asked).toEqual(['dimmer'])
    const sent = JSON.parse(JSON.stringify(pressFx.mock.calls[0][0]))
    expect(sent).toMatchObject({ stack: true, attribute: 'dimmer' })
    expect(sent).not.toHaveProperty('blendMode')
    unmount()
    pressFx.mockClear()
    render(<FxPicker target={FOUR} initialFamily="INTENSITY" press={{ projectId: 3, families: null, stackOn: () => false }} onCurrent={() => {}} />)
    fireEvent.click(screen.getByRole('button', { name: 'Pulse' }))
    await waitFor(() => expect(pressFx).toHaveBeenCalledTimes(1))
    expect(pressFx.mock.calls[0][0]).not.toHaveProperty('stack')
    expect(pressFx.mock.calls[0][0]).toHaveProperty('blendMode')
  })

  it('reports the attributes the listed FX would land on, and again when the family moves', () => {
    const onLanding = vi.fn()
    render(<FxPicker target={TARGET} initialFamily="INTENSITY" onLanding={onLanding} onCurrent={() => {}} />)
    expect(onLanding).toHaveBeenLastCalledWith(['dimmer'])
    fireEvent.click(screen.getByRole('radio', { name: 'Position' }))
    expect(onLanding).toHaveBeenLastCalledWith(['position'])
  })

  it('draws the host’s slot between the families and the rows', () => {
    render(<FxPicker target={TARGET} initialFamily="INTENSITY" underFamilies={<span data-testid="slot" />} onCurrent={() => {}} />)
    const slot = screen.getByTestId('slot')
    expect(screen.getByRole('radio', { name: 'Intensity' }).compareDocumentPosition(slot) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(slot.compareDocumentPosition(screen.getByRole('button', { name: 'Pulse' })) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('keeps a stacked FX’s blend through a swap', async () => {
    const { rerender } = render(<Stacking stack />)
    wire.library = [PULSE, { ...PULSE, name: 'Ramp' }]
    rerender(<Stacking stack />)
    fireEvent.click(screen.getByRole('button', { name: 'Pulse' }))
    await waitFor(() => expect(pressFx).toHaveBeenCalledTimes(1))
    wire.active = [makeActiveFx({ id: 99, fxType: 'Pulse', targetKey: '[]', attribute: 'dimmer', blendMode: 'MULTIPLY' })]
    rerender(<Stacking stack />)
    fireEvent.click(screen.getByRole('button', { name: 'Ramp' }))
    expect(updateFx).toHaveBeenCalledWith(99, expect.not.objectContaining({ blendMode: expect.anything() }))
  })

  /** A host asking `stackOn` of every fresh start, as `useReplaceStack` answers it. */
  function Asking({ stackOn, onLanding }: { stackOn: (attribute: string) => boolean; onLanding?: (a: readonly string[]) => void }) {
    const [current, setCurrent] = useState<AuditionedFx | null>(null)
    return (
      <FxPicker
        target={TARGET}
        initialFamily="POSITION"
        current={current}
        onCurrent={setCurrent}
        onEdit={() => {}}
        press={{ projectId: 3, families: null, stackOn }}
        onLanding={onLanding}
      />
    )
  }
  const circle = (over: Partial<ActiveFx> = {}) =>
    makeActiveFx({ id: 99, fxType: 'Circle', targetKey: 'spot-3', attribute: 'position', ...over })

  it('swaps by how the FX started, never asking stackOn: a stacked start stays unblended', async () => {
    const answer = { stack: true }
    const stackOn = vi.fn(() => answer.stack)
    const { rerender } = render(<Asking stackOn={stackOn} />)
    fireEvent.click(screen.getByRole('button', { name: 'Circle' }))
    await waitFor(() => expect(pressFx).toHaveBeenCalledTimes(1))
    expect(pressFx.mock.calls[0][0]).toMatchObject({ stack: true })
    wire.active = [circle()]
    answer.stack = false
    stackOn.mockClear()
    rerender(<Asking stackOn={stackOn} />)
    fireEvent.click(screen.getByRole('button', { name: 'Figure 8' }))
    expect(stackOn).not.toHaveBeenCalled()
    expect(updateFx).toHaveBeenCalledWith(99, expect.not.objectContaining({ blendMode: expect.anything() }))
  })

  it('swaps a replacing start with the new type’s blend, whatever stackOn would answer now', async () => {
    const answer = { stack: false }
    const stackOn = () => answer.stack
    const { rerender } = render(<Asking stackOn={stackOn} />)
    fireEvent.click(screen.getByRole('button', { name: 'Circle' }))
    await waitFor(() => expect(pressFx).toHaveBeenCalledTimes(1))
    wire.active = [circle()]
    answer.stack = true
    rerender(<Asking stackOn={stackOn} />)
    fireEvent.click(screen.getByRole('button', { name: 'Figure 8' }))
    expect(updateFx).toHaveBeenCalledWith(99, expect.objectContaining({ blendMode: 'ADDITIVE' }))
  })

  it('asks stackOn afresh of a start on another attribute, which replaces the stacked FX', async () => {
    const answer = { stack: true }
    const stackOn = vi.fn((_attribute: string) => answer.stack)
    const { rerender } = render(<Asking stackOn={stackOn} />)
    fireEvent.click(screen.getByRole('button', { name: 'Circle' }))
    await waitFor(() => expect(pressFx).toHaveBeenCalledTimes(1))
    wire.active = [circle()]
    answer.stack = false
    rerender(<Asking stackOn={stackOn} />)
    fireEvent.click(screen.getByRole('radio', { name: 'Intensity' }))
    fireEvent.click(screen.getByRole('button', { name: 'Pulse' }))
    await waitFor(() => expect(pressFx).toHaveBeenCalledTimes(2))
    expect(removeFx).toHaveBeenCalledWith(expect.objectContaining({ id: 99 }))
    expect(stackOn).toHaveBeenLastCalledWith('dimmer')
    expect(pressFx.mock.calls[1][0]).not.toHaveProperty('stack')
    expect(pressFx.mock.calls[1][0]).toHaveProperty('blendMode')
  })

  it('reports no landing a tap would swap on, and the auditioned FX’s again once it has stopped', async () => {
    const onLanding = vi.fn()
    const { rerender } = render(<Asking stackOn={() => false} onLanding={onLanding} />)
    expect(onLanding).toHaveBeenLastCalledWith(['position'])
    fireEvent.click(screen.getByRole('button', { name: 'Circle' }))
    await waitFor(() => expect(onLanding).toHaveBeenLastCalledWith([]))
    wire.active = [circle()]
    rerender(<Asking stackOn={() => false} onLanding={onLanding} />)
    expect(onLanding).toHaveBeenLastCalledWith([])
    // Released elsewhere: the next tap starts afresh, so it lands, and is asked about, again.
    wire.active = []
    rerender(<Asking stackOn={() => false} onLanding={onLanding} />)
    expect(onLanding).toHaveBeenLastCalledWith(['position'])
  })
})

describe('Path and the legacy colour FX (fx-settings plan session 3)', () => {
  const centred = { param: 'shape', values: ['CIRCLE', 'FIGURE_8', 'RANDOM'] }
  const line = { param: 'shape', values: ['LINE'] }
  const PATH: FxLibraryEntry = {
    name: 'Path',
    family: 'position',
    outputType: 'POSITION',
    fxMode: 'STANDARD',
    timingSource: 'BEAT',
    compatibleAttributes: ['position'],
    parameters: [
      { name: 'shape', type: 'choice', defaultValue: 'CIRCLE', description: '' },
      { name: 'panCentre', type: 'ubyte', defaultValue: '128', description: '', shownWhen: centred },
      { name: 'tiltCentre', type: 'ubyte', defaultValue: '128', description: '', shownWhen: centred },
      { name: 'panSize', type: 'ubyte', defaultValue: '64', description: '', shownWhen: centred },
      { name: 'tiltSize', type: 'ubyte', defaultValue: '64', description: '', shownWhen: centred },
      { name: 'startPan', type: 'ubyte', defaultValue: '64', description: '', shownWhen: line },
      { name: 'endPan', type: 'ubyte', defaultValue: '192', description: '', shownWhen: line },
    ],
    presets: [{ name: 'Sweep', attribute: 'position', parameters: { shape: 'LINE' }, cycle: 2, direction: 'BOUNCE' }],
  }
  const legacyColour = (name: string): FxLibraryEntry => ({
    name,
    family: 'colour',
    outputType: 'COLOUR',
    fxMode: 'STANDARD',
    compatibleAttributes: ['position'],
    parameters: [],
    legacy: true,
  })

  beforeEach(() => {
    wire.library = [PATH, legacyColour('ColourFade')]
  })

  it('starts the Sweep preset as a Line on Bounce at half a bar, on Override: a line has no centre to go Around', async () => {
    render(<Harness />)
    fireEvent.click(screen.getByRole('button', { name: 'Sweep' }))
    await waitFor(() => expect(addFixtureFx).toHaveBeenCalledTimes(1))
    expect(addFixtureFx.mock.calls[0][0]).toMatchObject({
      fxType: 'Path',
      attribute: 'position',
      cycle: 2,
      direction: 'BOUNCE',
      blendMode: 'OVERRIDE',
      parameters: { shape: 'LINE' },
    })
  })

  it('starts the bare Path, a Circle, Around with both centres pinned', async () => {
    render(<Harness />)
    fireEvent.click(screen.getByRole('button', { name: 'Path' }))
    await waitFor(() => expect(addFixtureFx).toHaveBeenCalledTimes(1))
    expect(addFixtureFx.mock.calls[0][0]).toMatchObject({
      fxType: 'Path',
      blendMode: 'ADDITIVE',
      parameters: { shape: 'CIRCLE', panCentre: '128', tiltCentre: '128' },
    })
  })

  it('offers no legacy FX', () => {
    render(<Harness />)
    expect(document.querySelector('[data-effect-pick="ColourFade"]')).toBeNull()
  })
})
