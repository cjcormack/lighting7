// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { useState } from 'react'
import type { ActiveEffect, EffectLibraryEntry } from '@/store/fixtureFx'
import type { TemplateSummary } from '@/api/templatesApi'
import { chan, makeActiveEffect, makeFixture, settingProp, sliderProp } from '@/test/fixtureFactories'

const updateFx = vi.hoisted(() => vi.fn((_id: number, _req: unknown) => true))
vi.mock('@/api/lightingApi', async () => {
  const { lightingApiMock } = await import('@/test/backendMock')
  const base = lightingApiMock().lightingApi as Record<string, unknown>
  const fx = { updateFx, subscribe: () => ({ unsubscribe: () => {} }), subscribeToErrors: () => ({ unsubscribe: () => {} }) }
  return { lightingApi: new Proxy(base, { get: (target, prop: string) => (prop === 'fx' ? fx : target[prop]) }) }
})

const wire = vi.hoisted(() => ({
  library: [] as EffectLibraryEntry[],
  active: [] as ActiveEffect[],
  templates: [] as TemplateSummary[],
}))
const addFixtureFx = vi.hoisted(() =>
  vi.fn((_req: unknown) => ({ unwrap: () => Promise.resolve({ effectId: 77 }) })),
)
const removeFx = vi.hoisted(() => vi.fn((_req: unknown) => ({ unwrap: () => Promise.resolve() })))
const applyGroupFx = vi.hoisted(() => vi.fn((_req: unknown) => ({ unwrap: () => Promise.resolve({ effectId: 88 }) })))
/** The bare effect press: one Local effect over the targets, which took them off effect 5 (stopped). */
const pressEffect = vi.hoisted(() =>
  vi.fn((_req: unknown) => ({ unwrap: () => Promise.resolve({ effectId: 99, stopped: [5], narrowed: [] }) })),
)
vi.mock('@/store/fixtureFx', () => ({
  useEffectLibraryQuery: () => ({ data: wire.library }),
  useActiveEffectsQuery: () => ({ data: wire.active }),
  useAddFixtureFxMutation: () => [addFixtureFx],
  usePressProgrammerEffectMutation: () => [pressEffect],
  useRemoveFxMutation: () => [removeFx],
}))
vi.mock('@/store/groups', () => ({
  useApplyGroupFxMutation: () => [applyGroupFx],
  useRemoveGroupFxMutation: () => [vi.fn()],
}))
vi.mock('@/store/status', () => ({ useIsDeskConnected: () => true }))
// The palette library Palette Steps is offered from (effects plan §4.4).
vi.mock('@/store/templates', () => ({ useTemplateListQuery: () => ({ data: wire.templates }) }))
// Venue-visit plan D16: a master is tagged for position, none for dimmer.
vi.mock('@/store/speedMasters', () => ({
  useSpeedMasterForCategory: () => (category: string | null | undefined) =>
    category === 'position' ? 'm-position' : category === 'beam' ? 'm-beam' : null,
}))
vi.mock('@/hooks/useFixtureLookup', () => ({ useFixtureLookup: () => ({ fixtures: [], fixtureByKey: new Map() }) }))

import { FxPicker, pressTargetsOf, type AuditionedEffect, type DraftEffectPick, type FxTarget } from './FxPicker'

const position = (name: string): EffectLibraryEntry => ({
  name,
  category: 'position',
  outputType: 'POSITION',
  effectMode: 'STANDARD',
  timingSource: 'BEAT',
  compatibleProperties: ['position'],
  parameters: [
    { name: 'panCenter', type: 'ubyte', defaultValue: '128', description: '' },
    { name: 'tiltCenter', type: 'ubyte', defaultValue: '128', description: '' },
    { name: 'panRadius', type: 'ubyte', defaultValue: '64', description: '' },
    { name: 'tiltRadius', type: 'ubyte', defaultValue: name === 'Figure 8' ? '32' : '64', description: '' },
  ],
})
const PULSE: EffectLibraryEntry = {
  name: 'Pulse',
  category: 'dimmer',
  outputType: 'SLIDER',
  effectMode: 'STANDARD',
  compatibleProperties: ['dimmer', 'uv'],
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
  const [current, setCurrent] = useState<AuditionedEffect | null>(null)
  return <FxPicker target={TARGET} initialFamily={initialFamily} current={current} onCurrent={setCurrent} onEdit={() => {}} />
}

beforeEach(() => {
  wire.library = [PULSE, position('Circle'), position('Figure 8')]
  wire.active = []
  wire.templates = []
  updateFx.mockClear()
  addFixtureFx.mockClear()
  pressEffect.mockClear()
  removeFx.mockClear()
})
afterEach(cleanup)

describe('FxPicker', () => {
  it('in draft, a tap hands the host the effect with its starting spec and starts nothing; a second tap picks again', () => {
    const picks: DraftEffectPick[] = []
    function Draft() {
      const [current, setCurrent] = useState<{ effectType: string; propertyName: string } | null>(null)
      return (
        <FxPicker
          target={TARGET}
          initialFamily="POSITION"
          onEdit={() => {}}
          draft={{
            current,
            onPick: (pick) => {
              picks.push(pick)
              setCurrent({ effectType: pick.effectType, propertyName: pick.propertyName })
            },
          }}
        />
      )
    }
    render(<Draft />)
    fireEvent.click(screen.getByRole('button', { name: 'Circle' }))
    expect(picks[0]).toMatchObject({
      effectType: 'Circle',
      propertyName: 'position',
      blendMode: 'ADDITIVE',
      beatDivision: 4,
      speedMasterUuid: 'm-position',
      parameters: { panCenter: '128', tiltCenter: '128', panRadius: '64', tiltRadius: '64' },
    })
    expect(screen.getByRole('button', { name: 'Circle' })).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(screen.getByRole('button', { name: 'Figure 8' }))
    expect(picks.map((p) => p.effectType)).toEqual(['Circle', 'Figure 8'])
    expect(addFixtureFx).not.toHaveBeenCalled()
    expect(updateFx).not.toHaveBeenCalled()
  })

  it('starts a programmer-owned effect on a tap, with its defaults, Around for a movement', async () => {
    render(<Harness />)
    fireEvent.click(screen.getByRole('button', { name: 'Circle' }))
    await waitFor(() => expect(addFixtureFx).toHaveBeenCalledTimes(1))
    expect(addFixtureFx.mock.calls[0][0]).toMatchObject({
      effectType: 'Circle',
      fixtureKey: 'spot-3',
      propertyName: 'position',
      programmerOwned: true,
      blendMode: 'ADDITIVE',
      parameters: { panCenter: '128', tiltCenter: '128', panRadius: '64', tiltRadius: '64' },
      // D16: one bar, on the master tagged for its family's usage.
      beatDivision: 4,
      speedMasterUuid: 'm-position',
    })
    expect(updateFx).not.toHaveBeenCalled()
  })

  it('starts on master 1 — no master sent — where no master is tagged for the family', async () => {
    render(<Harness initialFamily="INTENSITY" />)
    fireEvent.click(screen.getByRole('button', { name: 'Pulse' }))
    await waitFor(() => expect(addFixtureFx).toHaveBeenCalledTimes(1))
    expect(addFixtureFx.mock.calls[0][0]).toMatchObject({ effectType: 'Pulse', beatDivision: 4 })
    expect(addFixtureFx.mock.calls[0][0]).not.toHaveProperty('speedMasterUuid')
  })

  it('swaps on a second tap — one updateFx, no add — keeping the instance', async () => {
    const { rerender } = render(<Harness />)
    fireEvent.click(screen.getByRole('button', { name: 'Circle' }))
    await waitFor(() => expect(addFixtureFx).toHaveBeenCalledTimes(1))
    // The desk now runs it.
    wire.active = [makeActiveEffect({ id: 77, effectType: 'Circle', targetKey: 'spot-3', propertyName: 'position' })]
    rerender(<Harness />)
    expect(screen.getByRole('button', { name: 'Circle' }).getAttribute('aria-pressed')).toBe('true')
    expect(screen.getByRole('button', { name: 'Edit Circle' })).toBeTruthy()

    fireEvent.click(screen.getByRole('button', { name: 'Figure 8' }))
    expect(addFixtureFx).toHaveBeenCalledTimes(1)
    expect(removeFx).not.toHaveBeenCalled()
    expect(updateFx).toHaveBeenCalledTimes(1)
    expect(updateFx.mock.calls[0][0]).toBe(77)
    expect(updateFx.mock.calls[0][1]).toEqual({
      effectType: 'Figure 8',
      blendMode: 'ADDITIVE',
      parameters: { panCenter: '128', tiltCenter: '128', panRadius: '64', tiltRadius: '32' },
    })
  })

  it('restarts rather than swaps when the tap lands on another property', async () => {
    const { rerender } = render(<Harness />)
    fireEvent.click(screen.getByRole('button', { name: 'Circle' }))
    await waitFor(() => expect(addFixtureFx).toHaveBeenCalledTimes(1))
    wire.active = [makeActiveEffect({ id: 77, effectType: 'Circle', targetKey: 'spot-3', propertyName: 'position' })]
    rerender(<Harness />)

    fireEvent.click(screen.getByRole('radio', { name: 'Intensity' }))
    fireEvent.click(screen.getByRole('button', { name: 'Pulse' }))
    await waitFor(() => expect(addFixtureFx).toHaveBeenCalledTimes(2))
    expect(removeFx).toHaveBeenCalledWith({ id: 77, fixtureKey: 'spot-3' })
    expect(addFixtureFx.mock.calls[1][0]).toMatchObject({ effectType: 'Pulse', propertyName: 'dimmer', programmerOwned: true, blendMode: 'OVERRIDE' })
    expect(updateFx).not.toHaveBeenCalled()
  })

  it('lands a swap across timing sources on one cycle, and keeps the speed within one', async () => {
    const flicker: EffectLibraryEntry = { ...PULSE, name: 'CandleFlicker', timingSource: 'WALL_CLOCK' }
    const sine: EffectLibraryEntry = { ...PULSE, name: 'SineWave' }
    wire.library = [PULSE, flicker, sine]
    const { rerender } = render(<Harness initialFamily="INTENSITY" />)
    fireEvent.click(screen.getByRole('button', { name: 'Pulse' }))
    await waitFor(() => expect(addFixtureFx).toHaveBeenCalledTimes(1))
    wire.active = [makeActiveEffect({ id: 77, effectType: 'Pulse', targetKey: 'spot-3', propertyName: 'dimmer', beatDivision: 16, timingSource: 'BEAT' })]
    rerender(<Harness initialFamily="INTENSITY" />)

    fireEvent.click(screen.getByRole('button', { name: 'SineWave' }))
    expect(updateFx.mock.calls[0][1]).not.toHaveProperty('beatDivision')
    fireEvent.click(screen.getByRole('button', { name: 'CandleFlicker' }))
    expect(updateFx.mock.calls[1][1]).toMatchObject({ effectType: 'CandleFlicker', beatDivision: 4 })
  })

  it('starts an FX that bounces at its own half bar, Bounce, and a swap at the old default takes the new one', async () => {
    const fade: EffectLibraryEntry = { ...PULSE, name: 'Fade', defaultBeatDivision: 2, defaultDirection: 'BOUNCE' }
    wire.library = [PULSE, fade]
    const { rerender } = render(<Harness initialFamily="INTENSITY" />)
    fireEvent.click(screen.getByRole('button', { name: 'Fade' }))
    await waitFor(() => expect(addFixtureFx).toHaveBeenCalledTimes(1))
    expect(addFixtureFx.mock.calls[0][0]).toMatchObject({ effectType: 'Fade', beatDivision: 2, direction: 'BOUNCE' })

    wire.active = [makeActiveEffect({ id: 77, effectType: 'Fade', targetKey: 'spot-3', propertyName: 'dimmer', beatDivision: 2, direction: 'BOUNCE' })]
    rerender(<Harness initialFamily="INTENSITY" />)
    fireEvent.click(screen.getByRole('button', { name: 'Pulse' }))
    expect(updateFx.mock.calls[0][1]).toMatchObject({ effectType: 'Pulse', beatDivision: 4, direction: 'FORWARD' })
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
  const beam = (name: string, property: string): EffectLibraryEntry => ({
    name,
    category: 'beam',
    outputType: 'SLIDER',
    effectMode: 'STANDARD',
    timingSource: 'BEAT',
    compatibleProperties: [property],
    parameters: [{ name: 'min', type: 'ubyte', defaultValue: '64', description: '' }],
  })
  const PROFILE = makeFixture('profile-1', [
    sliderProp('dimmer', 'dimmer', chan(1)),
    sliderProp('zoom', 'zoom', chan(2)),
    sliderProp('iris', 'iris', chan(3)),
  ])

  function BeamHarness() {
    const [current, setCurrent] = useState<AuditionedEffect | null>(null)
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
    const withPrism = makeFixture('profile-2', [...PROFILE.properties!, sliderProp('prism', 'prism', chan(4))])
    const props = { current: null, onCurrent: () => {}, onEdit: () => {}, initialFamily: 'BEAM' as const }
    const target = { type: 'fixture' as const, fixture: withPrism }
    const { unmount } = render(<FxPicker target={target} preferredProperty="iris" {...props} />)
    expect(screen.getByRole('radio', { name: 'Beam' }).getAttribute('aria-checked')).toBe('true')
    unmount()
    wire.library = [...wire.library, { ...PULSE, name: 'Prism Sweep', category: 'controls', compatibleProperties: ['slider'] }]
    render(<FxPicker target={target} preferredProperty="prism" {...props} />)
    expect(screen.getByRole('radio', { name: 'Controls' }).getAttribute('aria-checked')).toBe('true')
  })

  it('starts a beam effect on its own property, on the Beam master', async () => {
    render(<BeamHarness />)
    fireEvent.click(screen.getByRole('button', { name: 'Iris Pulse' }))
    await waitFor(() => expect(addFixtureFx).toHaveBeenCalledTimes(1))
    expect(addFixtureFx.mock.calls[0][0]).toMatchObject({
      effectType: 'Iris Pulse',
      fixtureKey: 'profile-1',
      propertyName: 'iris',
      beatDivision: 4,
      speedMasterUuid: 'm-beam',
    })
  })
})

describe('the step effects (effects plan session 7, §4.4)', () => {
  const PALETTE_STEPS: EffectLibraryEntry = {
    name: 'PaletteSteps',
    category: 'palette',
    outputType: 'STEPS',
    effectMode: 'STANDARD',
    timingSource: 'BEAT',
    compatibleProperties: ['dimmer', 'rgbColour', 'position', 'zoom', 'focus', 'iris', 'frost'],
    parameters: [
      { name: 'palettes', type: 'paletteList', defaultValue: '', description: '' },
      { name: 'fadeRatio', type: 'double', defaultValue: '0.5', description: '' },
    ],
  }
  const SLOT_STEPS: EffectLibraryEntry = {
    name: 'SlotSteps',
    // The Steps family's, as the desk files it (terminology plan D23); it is still offered in Beam.
    category: 'palette',
    outputType: 'STEPS',
    effectMode: 'STANDARD',
    timingSource: 'BEAT',
    compatibleProperties: ['setting'],
    parameters: [{ name: 'slots', type: 'slotList', defaultValue: '', description: '' }],
  }
  const palette = (name: string, family: TemplateSummary['family'], propertyName: string, value: string, over: Partial<TemplateSummary> = {}) =>
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
      rows: [{ targetType: 'deferred', targetKey: '', propertyName, value }],
      effect: null,
      ...over,
    }) as unknown as TemplateSummary
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
    preferredProperty,
    fixture = MOVER,
  }: {
    initialFamily: 'POSITION' | 'BEAM' | 'INTENSITY'
    preferredProperty?: string
    fixture?: typeof MOVER
  }) {
    const [current, setCurrent] = useState<AuditionedEffect | null>(null)
    return (
      <FxPicker
        target={{ type: 'fixture', fixture }}
        initialFamily={initialFamily}
        preferredProperty={preferredProperty}
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
      palette('Orbit', 'POSITION', 'position', 'pos:0,0', { kind: 'effect' }),
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
      effectType: 'PaletteSteps',
      propertyName: 'position',
      blendMode: 'OVERRIDE',
      speedMasterUuid: 'm-position',
      parameters: { palettes: 'tmpl:uuid-Centre,tmpl:uuid-Downstage', fadeRatio: '0.5' },
    })
  })

  it('offers Slot Steps in Beam on a gobo row, and starts it on that setting’s first slots past open', async () => {
    render(<StepHarness initialFamily="BEAM" preferredProperty="gobo" />)
    expect(screen.getByRole('radio', { name: 'Beam' }).getAttribute('aria-checked')).toBe('true')
    fireEvent.click(screen.getByRole('button', { name: 'SlotSteps' }))
    await waitFor(() => expect(addFixtureFx).toHaveBeenCalledTimes(1))
    expect(addFixtureFx.mock.calls[0][0]).toMatchObject({
      effectType: 'SlotSteps',
      propertyName: 'gobo',
      speedMasterUuid: 'm-beam',
      parameters: { slots: 'GOBO_1,GOBO_2' },
    })
  })

  it('starts Slot Steps on the gobo wheel where no setting row says otherwise, not the first setting', async () => {
    render(<StepHarness initialFamily="BEAM" fixture={WHEEL_FIRST} />)
    fireEvent.click(screen.getByRole('button', { name: 'SlotSteps' }))
    await waitFor(() => expect(addFixtureFx).toHaveBeenCalledTimes(1))
    expect(addFixtureFx.mock.calls[0][0]).toMatchObject({ effectType: 'SlotSteps', propertyName: 'gobo', parameters: { slots: 'GOBO_1,GOBO_2' } })
  })
})

/**
 * The bare effect press (apply-model plan §3.4, session 6): *+ Effect*, ⌘K *Apply FX* and a band's
 * *+ Effect …* start one Local effect over the selection through `POST /programmer/effects`, with the
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
    const [current, setCurrent] = useState<AuditionedEffect | null>(null)
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
    await waitFor(() => expect(pressEffect).toHaveBeenCalledTimes(1))
    expect(pressEffect.mock.calls[0][0]).toMatchObject({
      projectId: 3,
      effectType: 'Pulse',
      propertyName: 'dimmer',
      targets: HEX.map((f) => ({ type: 'fixture', key: f.key })),
      families: ['INTENSITY'],
      beatDivision: 4,
    })
    expect(pressEffect.mock.calls[0][0]).not.toHaveProperty('programmerOwned')
    expect(addFixtureFx).not.toHaveBeenCalled()
    expect(applyGroupFx).not.toHaveBeenCalled()
  })

  it('auditions the pressed effect: a second tap swaps it in place rather than pressing again', async () => {
    const { rerender } = render(<Pressing />)
    wire.library = [PULSE, { ...PULSE, name: 'Ramp' }]
    rerender(<Pressing />)
    fireEvent.click(screen.getByRole('button', { name: 'Pulse' }))
    await waitFor(() => expect(pressEffect).toHaveBeenCalledTimes(1))
    wire.active = [makeActiveEffect({ id: 99, effectType: 'Pulse', targetKey: '[]', propertyName: 'dimmer' })]
    rerender(<Pressing />)
    fireEvent.click(screen.getByRole('button', { name: 'Ramp' }))
    expect(pressEffect).toHaveBeenCalledTimes(1)
    expect(updateFx).toHaveBeenCalledWith(99, expect.objectContaining({ effectType: 'Ramp' }))
  })

  it('names a group as itself, so its effect is one group effect with its distribution', () => {
    expect(pressTargetsOf({ type: 'group', group: { name: 'front' } as never })).toEqual([{ type: 'group', key: 'front' }])
    expect(pressTargetsOf(FOUR)).toEqual(FOUR.type === 'selection' ? FOUR.targets : [])
  })

  it('sends no mask where the door carries none', async () => {
    function Unmasked() {
      const [current, setCurrent] = useState<AuditionedEffect | null>(null)
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
    await waitFor(() => expect(pressEffect).toHaveBeenCalledTimes(1))
    expect(pressEffect.mock.calls[0][0]).not.toHaveProperty('families')
  })
})
