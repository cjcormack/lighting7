import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

import { lightingApi } from '@/api/lightingApi'
import { Status } from '@/api/statusApi'
import { programmerWs } from '@/test/backendMock'
import { programmerBlindOn } from './programmer'

/**
 * *Blind edit in programmer* Includes only once the desk says Blind is on (show-cue-editing plan
 * §5): Blind is a WS gesture and Include a REST call, and nothing orders the two on the desk.
 */
describe('programmerBlindOn', () => {
  beforeEach(() => {
    programmerWs.reset()
    vi.spyOn(lightingApi.status, 'get').mockReturnValue(Status.OPEN)
    vi.useFakeTimers()
  })
  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('sends Blind at the fade and resolves only when the desk reports it on', async () => {
    const setBlind = vi.spyOn(lightingApi.programmer, 'setBlind')
    let resolved: boolean | undefined
    void programmerBlindOn(1500).then((on) => (resolved = on))
    expect(setBlind).toHaveBeenCalledWith(true, 1500)
    await Promise.resolve()
    expect(resolved).toBeUndefined()
    programmerWs.push({ blind: true })
    await Promise.resolve()
    expect(resolved).toBe(true)
  })

  it('resolves false when the desk never says so', async () => {
    let resolved: boolean | undefined
    void programmerBlindOn(0, 3000).then((on) => (resolved = on))
    await vi.advanceTimersByTimeAsync(3000)
    expect(resolved).toBe(false)
    expect(programmerWs.callbacks).toHaveLength(0)
  })

  it('is true at once, sending nothing, when Blind is already on', async () => {
    programmerWs.push({ blind: true })
    const setBlind = vi.spyOn(lightingApi.programmer, 'setBlind')
    await expect(programmerBlindOn(0)).resolves.toBe(true)
    expect(setBlind).not.toHaveBeenCalled()
  })

  it('is false with the desk offline, whatever the cached state says', async () => {
    programmerWs.push({ blind: true })
    vi.spyOn(lightingApi.status, 'get').mockReturnValue(Status.CLOSED)
    const setBlind = vi.spyOn(lightingApi.programmer, 'setBlind')
    await expect(programmerBlindOn(0)).resolves.toBe(false)
    expect(setBlind).not.toHaveBeenCalled()
  })
})
