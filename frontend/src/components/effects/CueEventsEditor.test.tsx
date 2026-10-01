// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import type { CueEvent } from '@/api/cuesApi'
import type { TriggerPropertyDescriptor } from '@/store/fixtures'

const trigger = (name: string, label: string, ch: number): TriggerPropertyDescriptor => ({
  type: 'trigger', name, displayName: `Tube ${label}`, category: 'trigger', label,
  channel: { universe: 0, channelNo: ch }, armChannel: { universe: 0, channelNo: 3 }, armName: 'master',
})

vi.mock('@/store/patches', () => ({
  useVisiblePatchListQuery: () => ({
    data: [
      { id: 7, key: 'adv1', displayName: 'ADV1 Twin Shot', fixtureTypeKey: 'equinox-twin-shot-mkii' },
      { id: 8, key: 'par', displayName: 'Par', fixtureTypeKey: 'generic-dimmer' },
    ],
  }),
}))
vi.mock('@/store/fixtures', async (orig) => ({
  ...(await orig<typeof import('@/store/fixtures')>()),
  useFixtureTypeListQuery: () => ({
    data: [
      { typeKey: 'equinox-twin-shot-mkii', properties: [trigger('output1', 'A', 1), trigger('output2', 'B', 2)] },
      { typeKey: 'generic-dimmer', properties: [] },
    ],
  }),
}))

import { CueEventsEditor, CueEventsReadout } from './CueEventsEditor'

const event = (over: Partial<CueEvent>): CueEvent => ({
  uuid: 'u', patchId: 7, patchUuid: 'p', fixtureKey: 'adv1', fixtureName: 'ADV1 Twin Shot',
  trigger: 'output1', triggerLabel: 'A', offsetMs: 600, sortOrder: 0, ...over,
})

beforeEach(() => {
  vi.useFakeTimers()
})
afterEach(() => {
  cleanup()
  vi.useRealTimers()
})

describe('CueEventsEditor', () => {
  it('offers only fixtures with triggers, and adds the first free tube as a whole-list save', () => {
    const onSave = vi.fn(() => Promise.resolve())
    render(<CueEventsEditor projectId={1} events={[event({})]} idPrefix="t" onSave={onSave} />)
    fireEvent.click(screen.getByRole('button', { name: /Add event/ }))
    expect(onSave).toHaveBeenCalledWith([
      { patchId: 7, trigger: 'output1', offsetMs: 600 },
      { patchId: 7, trigger: 'output2', offsetMs: 600 },
    ])
  })

  it('saves a typed offset once, on blur', () => {
    const onSave = vi.fn(() => Promise.resolve())
    render(<CueEventsEditor projectId={1} events={[event({})]} idPrefix="t" onSave={onSave} />)
    const offset = screen.getByLabelText('Offset after GO (ms)')
    fireEvent.focus(offset)
    fireEvent.change(offset, { target: { value: '750' } })
    fireEvent.blur(offset)
    expect(onSave).toHaveBeenCalledTimes(1)
    expect(onSave).toHaveBeenLastCalledWith([{ patchId: 7, trigger: 'output1', offsetMs: 750 }])
    act(() => {
      vi.advanceTimersByTime(2000)
    })
    expect(onSave).toHaveBeenCalledTimes(1)
  })

  it('keeps its own list over a refetch that lands while a save is in flight', () => {
    let release: () => void = () => {}
    const onSave = vi.fn(() => new Promise<void>((resolve) => { release = resolve }))
    const { rerender } = render(<CueEventsEditor projectId={1} events={[event({})]} idPrefix="t" onSave={onSave} />)
    fireEvent.click(screen.getByRole('button', { name: /Remove ADV1 Twin Shot/ }))
    expect(screen.queryByRole('button', { name: /Remove ADV1 Twin Shot/ })).toBeNull()
    rerender(<CueEventsEditor projectId={1} events={[event({ uuid: 'u2' })]} idPrefix="t" onSave={onSave} />)
    expect(screen.queryByRole('button', { name: /Remove ADV1 Twin Shot/ })).toBeNull()
    act(() => release())
  })

  it('shows a refusal and goes back to what the desk holds', async () => {
    const onSave = vi.fn(() => Promise.reject({ status: 400, data: { error: "events[0] ('ADV1 Twin Shot') fires A twice" } }))
    render(<CueEventsEditor projectId={1} events={[event({})]} idPrefix="t" onSave={onSave} />)
    fireEvent.click(screen.getByRole('button', { name: /Remove ADV1 Twin Shot/ }))
    await act(async () => {
      await Promise.resolve()
      await Promise.resolve()
    })
    expect(screen.getByText(/fires A twice/)).toBeTruthy()
    expect(screen.getByRole('button', { name: /Remove ADV1 Twin Shot/ })).toBeTruthy()
  })

  it('reads a cannon per line with its tubes and offsets', () => {
    render(<CueEventsReadout events={[event({}), event({ uuid: 'v', trigger: 'output2', triggerLabel: 'B', offsetMs: 750, sortOrder: 1 })]} />)
    expect(screen.getByText('ADV1 Twin Shot')).toBeTruthy()
    expect(screen.getByText(/fire A · \+0\.6 s/)).toBeTruthy()
    expect(screen.getByText(/B · \+0\.75 s/)).toBeTruthy()
  })
})
