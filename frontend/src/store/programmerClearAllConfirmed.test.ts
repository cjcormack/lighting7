import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

import { lightingApi } from '@/api/lightingApi'
import { Status } from '@/api/statusApi'
import { programmerWs } from '@/test/backendMock'
import { programmerClearAllConfirmed } from './programmer'

/** Revert Includes again only once the desk says its clear landed: the clear is WS, Include REST. */
describe('programmerClearAllConfirmed', () => {
  beforeEach(() => {
    programmerWs.reset()
    programmerWs.push({ lastIncluded: { kind: 'LOOK', lookId: 12 } })
    vi.spyOn(lightingApi.status, 'get').mockReturnValue(Status.OPEN)
    vi.useFakeTimers()
  })
  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('sends the clear and resolves only when the include target has gone', async () => {
    const clearAll = vi.spyOn(lightingApi.programmer, 'clearAll')
    let resolved: boolean | undefined
    void programmerClearAllConfirmed(0).then((cleared) => (resolved = cleared))
    expect(clearAll).toHaveBeenCalledWith(0)
    await Promise.resolve()
    expect(resolved).toBeUndefined()
    programmerWs.push({ lastIncluded: null })
    await Promise.resolve()
    expect(resolved).toBe(true)
  })

  it('resolves false when the desk never says so', async () => {
    let resolved: boolean | undefined
    void programmerClearAllConfirmed(0, 3000).then((cleared) => (resolved = cleared))
    await vi.advanceTimersByTimeAsync(3000)
    expect(resolved).toBe(false)
    expect(programmerWs.callbacks).toHaveLength(0)
  })

  it('is false with the desk offline, sending nothing', async () => {
    vi.spyOn(lightingApi.status, 'get').mockReturnValue(Status.CLOSED)
    const clearAll = vi.spyOn(lightingApi.programmer, 'clearAll')
    await expect(programmerClearAllConfirmed(0)).resolves.toBe(false)
    expect(clearAll).not.toHaveBeenCalled()
  })
})
