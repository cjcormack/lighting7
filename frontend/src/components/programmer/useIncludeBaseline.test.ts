// @vitest-environment jsdom
import { act, renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { programmerWs } from '@/test/backendMock'

vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())
vi.mock('@/store/programmer', () => ({ useProgrammerScenery: () => ({ changedSinceInclude: 0 }) }))

import { lightingApi } from '@/api/lightingApi'
import { useIncludeBaseline } from './useIncludeBaseline'

const LOOK = { kind: 'LOOK', lookId: 12, lookName: 'Set — Market' }
const entry = (value: string) => ({ value })
const included = new Map([
  ['mac-1|dimmer', entry('255')],
  ['mac-1|position', entry('108,168')],
])

afterEach(() => programmerWs.reset())

describe('useIncludeBaseline', () => {
  it('waits for the state frame after the include target, so an Include alone counts no change', () => {
    const requestState = vi.spyOn(lightingApi.programmer, 'requestState')
    const { result } = renderHook(() => useIncludeBaseline())
    expect(result.current).toBeNull()

    // The target's own frame carries no entries: what the programmer held before the Include.
    act(() => programmerWs.push({ lastIncluded: LOOK }))
    expect(requestState).toHaveBeenCalledTimes(1)
    expect(result.current).toBe(0)

    // The state frame brings the included values, and they are the baseline.
    act(() => programmerWs.push({ entries: included, snapshotSeq: 1 }))
    expect(result.current).toBe(0)

    // Clearing a value Include staged is a change.
    act(() => programmerWs.push({ entries: new Map([['mac-1|dimmer', entry('255')]]), snapshotSeq: 2 }))
    expect(result.current).toBe(1)
  })

  it('starts again from zero once an Update has written the changes back', () => {
    const { result } = renderHook(() => useIncludeBaseline())
    act(() => programmerWs.push({ lastIncluded: { ...LOOK, baseline: 1 }, entries: included, snapshotSeq: 1 }))
    const cleared = new Map([['mac-1|dimmer', entry('255')]])
    act(() => programmerWs.push({ entries: cleared, snapshotSeq: 2 }))
    expect(result.current).toBe(1)

    // The Update's target frame moves the baseline; the state frame after it is the new baseline.
    act(() => programmerWs.push({ lastIncluded: { ...LOOK, baseline: 2 } }))
    act(() => programmerWs.push({ snapshotSeq: 3 }))
    expect(result.current).toBe(0)
  })

  it('counts the layers the desk says Update would write, so a Look of scenery alone un-greys Update', () => {
    const CUE = { kind: 'CUE', cueId: 4, baseline: 1 }
    const { result } = renderHook(() => useIncludeBaseline())
    act(() => programmerWs.push({ lastIncluded: CUE, entries: included, snapshotSeq: 1 }))
    expect(result.current).toBe(0)

    act(() => programmerWs.push({ layersChangedSinceInclude: 1 }))
    expect(result.current).toBe(1)
  })

  it('takes the baseline at once when the target arrives in a state frame', () => {
    const requestState = vi.spyOn(lightingApi.programmer, 'requestState')
    const { result } = renderHook(() => useIncludeBaseline())
    act(() => programmerWs.push({ lastIncluded: LOOK, entries: included, snapshotSeq: 1 }))
    expect(requestState).not.toHaveBeenCalled()
    expect(result.current).toBe(0)
  })
})
