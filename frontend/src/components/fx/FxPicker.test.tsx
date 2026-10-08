// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { useState } from 'react'
import type { ActiveEffect, EffectLibraryEntry } from '@/store/fixtureFx'
import { chan, makeActiveEffect, makeFixture, sliderProp } from '@/test/fixtureFactories'

const updateFx = vi.hoisted(() => vi.fn((_id: number, _req: unknown) => true))
vi.mock('@/api/lightingApi', async () => {
  const { lightingApiMock } = await import('@/test/backendMock')
  const base = lightingApiMock().lightingApi as Record<string, unknown>
  const fx = { updateFx, subscribe: () => ({ unsubscribe: () => {} }), subscribeToErrors: () => ({ unsubscribe: () => {} }) }
  return { lightingApi: new Proxy(base, { get: (target, prop: string) => (prop === 'fx' ? fx : target[prop]) }) }
})

const wire = vi.hoisted(() => ({ library: [] as EffectLibraryEntry[], active: [] as ActiveEffect[] }))
const addFixtureFx = vi.hoisted(() =>
  vi.fn((_req: unknown) => ({ unwrap: () => Promise.resolve({ effectId: 77 }) })),
)
const removeFx = vi.hoisted(() => vi.fn((_req: unknown) => ({ unwrap: () => Promise.resolve() })))
const applyGroupFx = vi.hoisted(() => vi.fn((_req: unknown) => ({ unwrap: () => Promise.resolve({ effectId: 88 }) })))
vi.mock('@/store/fixtureFx', () => ({
  useEffectLibraryQuery: () => ({ data: wire.library }),
  useActiveEffectsQuery: () => ({ data: wire.active }),
  useAddFixtureFxMutation: () => [addFixtureFx],
  useRemoveFxMutation: () => [removeFx],
}))
vi.mock('@/store/groups', () => ({
  useApplyGroupFxMutation: () => [applyGroupFx],
  useRemoveGroupFxMutation: () => [vi.fn()],
}))
vi.mock('@/store/status', () => ({ useIsDeskConnected: () => true }))
vi.mock('@/hooks/useFixtureLookup', () => ({ useFixtureLookup: () => ({ fixtures: [], fixtureByKey: new Map() }) }))

import { FxPicker, type AuditionedEffect, type FxTarget } from './FxPicker'

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
  updateFx.mockClear()
  addFixtureFx.mockClear()
  removeFx.mockClear()
})
afterEach(cleanup)

describe('FxPicker', () => {
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
    })
    expect(updateFx).not.toHaveBeenCalled()
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
    expect(updateFx.mock.calls[1][1]).toMatchObject({ effectType: 'CandleFlicker', beatDivision: 1 })
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
  })
})
