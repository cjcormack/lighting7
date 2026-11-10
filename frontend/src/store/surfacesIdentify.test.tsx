// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import type { IdentifyEvent } from '@/api/surfacesApi'

const desk = vi.hoisted(() => ({
  listeners: new Set<(event: IdentifyEvent) => void>(),
  begun: 0,
  cancelled: [] as string[],
}))

vi.mock('@/api/lightingApi', async () => {
  const mock = (await import('@/test/backendMock')).lightingApiMock()
  ;(mock.lightingApi as unknown as Record<string, unknown>).surfaces = {
    subscribeIdentify: (fn: (event: IdentifyEvent) => void) => {
      desk.listeners.add(fn)
      return { unsubscribe: () => desk.listeners.delete(fn) }
    },
    beginIdentify: () => {
      desk.begun++
      return true
    },
    cancelIdentify: (sessionId: string) => desk.cancelled.push(sessionId),
    subscribeBindingsChanged: () => ({ unsubscribe: () => {} }),
  }
  return mock
})

import { useIdentify } from './surfaces'

function answer(sessionId: string) {
  act(() => desk.listeners.forEach((fn) => fn({ type: 'state', sessionId, waiting: true, remainingMs: 30_000 } as IdentifyEvent)))
}

beforeEach(() => {
  desk.begun = 0
  desk.cancelled.length = 0
})

describe('useIdentify', () => {
  it('cancels a wait whose hook unmounted before the desk answered the begin', () => {
    const { result, unmount } = renderHook(() => useIdentify(() => {}))
    act(() => result.current.begin(null))
    unmount()
    expect(desk.cancelled).toEqual([])
    answer('late')
    expect(desk.cancelled).toEqual(['late'])
    expect(desk.listeners.size).toBe(0)
  })

  it('keeps a wait a live hook began, and cancels it on unmount', () => {
    const { result, unmount } = renderHook(() => useIdentify(() => {}))
    act(() => result.current.begin(null))
    answer('mine')
    expect(result.current.wait.waiting).toBe(true)
    expect(desk.cancelled).toEqual([])
    unmount()
    expect(desk.cancelled).toEqual(['mine'])
  })

  it('cancels only the abandoned begin when a live hook began after it', () => {
    const first = renderHook(() => useIdentify(() => {}))
    act(() => first.result.current.begin(null))
    first.unmount()
    const second = renderHook(() => useIdentify(() => {}))
    act(() => second.result.current.begin(null))
    answer('abandoned')
    answer('live')
    expect(desk.cancelled).toEqual(['abandoned'])
    expect(second.result.current.wait.waiting).toBe(true)
    second.unmount()
  })
})
