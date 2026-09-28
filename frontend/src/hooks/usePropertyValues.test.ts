import { describe, expect, it, vi } from 'vitest'

// usePropertyValues imports the lighting api, which opens a WebSocket at import time.
vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

import { subscribeToChannels } from './usePropertyValues'
import type { ChannelSource } from '@/api/channelSource'

/**
 * A source whose fan-out is per key and synchronous within one batch — the shape both
 * `channelsApi` and `createFanOut` have.
 */
function fakeSource() {
  const listeners = new Map<string, Set<(value: number) => void>>()
  const values = new Map<string, number>()
  const source: ChannelSource = {
    get: (universe, channelNo) => values.get(`${universe}:${channelNo}`) ?? 0,
    getByKey: (key) => values.get(key) ?? 0,
    subscribeToChannel: (key, fn) => {
      let forKey = listeners.get(key)
      if (!forKey) {
        forKey = new Set()
        listeners.set(key, forKey)
      }
      forKey.add(fn)
      return { unsubscribe: () => forKey.delete(fn) }
    },
  }
  return {
    source,
    /** One batch: set every key, then notify each in turn, all in this task. */
    emit(batch: Record<string, number>) {
      for (const [key, value] of Object.entries(batch)) values.set(key, value)
      for (const [key, value] of Object.entries(batch)) {
        listeners.get(key)?.forEach((fn) => fn(value))
      }
    },
    subscriberCount: () => [...listeners.values()].reduce((n, s) => n + s.size, 0),
  }
}

const flush = () => new Promise<void>((resolve) => queueMicrotask(resolve))

const refs = (...keys: string[]) =>
  keys.map((key) => {
    const [universe, channelNo] = key.split(':')
    return { universe: Number(universe), channelNo: Number(channelNo) }
  })

describe('subscribeToChannels', () => {
  it('wakes a multi-channel set once per batch, however many of its channels moved', async () => {
    const { source, emit } = fakeSource()
    const callback = vi.fn()
    subscribeToChannels(refs('0:1', '0:2', '0:3'), callback, source)

    emit({ '0:1': 10, '0:2': 20, '0:3': 30 })
    // Coalesced, so nothing has fired yet — the per-channel path would already be at three.
    expect(callback).not.toHaveBeenCalled()
    await flush()
    expect(callback).toHaveBeenCalledTimes(1)
  })

  it('wakes again on the next batch', async () => {
    const { source, emit } = fakeSource()
    const callback = vi.fn()
    subscribeToChannels(refs('0:1', '0:2'), callback, source)

    emit({ '0:1': 10, '0:2': 20 })
    await flush()
    emit({ '0:2': 30 })
    await flush()
    expect(callback).toHaveBeenCalledTimes(2)
  })

  it('keeps per-channel granularity — a channel outside the set never wakes it', async () => {
    const { source, emit } = fakeSource()
    const callback = vi.fn()
    subscribeToChannels(refs('0:1', '0:2'), callback, source)

    emit({ '0:9': 255 })
    await flush()
    expect(callback).not.toHaveBeenCalled()
  })

  it('does not call back after unsubscribe, even for a batch already in flight', async () => {
    const { source, emit, subscriberCount } = fakeSource()
    const callback = vi.fn()
    const unsubscribe = subscribeToChannels(refs('0:1', '0:2'), callback, source)

    emit({ '0:1': 10, '0:2': 20 })
    unsubscribe()
    await flush()
    expect(callback).not.toHaveBeenCalled()
    expect(subscriberCount()).toBe(0)
  })

  it('stays synchronous for a single-channel set', () => {
    const { source, emit } = fakeSource()
    const callback = vi.fn()
    subscribeToChannels(refs('0:1'), callback, source)

    emit({ '0:1': 10 })
    expect(callback).toHaveBeenCalledTimes(1)
  })

  it('unsubscribes cleanly from a single-channel set', () => {
    const { source, emit, subscriberCount } = fakeSource()
    const callback = vi.fn()
    const unsubscribe = subscribeToChannels(refs('0:1'), callback, source)
    unsubscribe()

    emit({ '0:1': 10 })
    expect(callback).not.toHaveBeenCalled()
    expect(subscriberCount()).toBe(0)
  })
})
