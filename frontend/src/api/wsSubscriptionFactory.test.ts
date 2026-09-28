import { describe, expect, it, vi } from 'vitest'
import { createKeyedWsSubscribable } from './wsSubscriptionFactory'

describe('createKeyedWsSubscribable', () => {
  it('wakes only the subscribers of the key that notified', () => {
    const pool = createKeyedWsSubscribable<number>()
    const onA = vi.fn()
    const onB = vi.fn()
    pool.subscribe('a', onA)
    pool.subscribe('b', onB)

    pool.notify('a', 7)

    // The whole reason the pool exists: a per-key stream that woke everyone would be a shared
    // stream every subscriber has to filter.
    expect(onA).toHaveBeenCalledWith(7)
    expect(onB).not.toHaveBeenCalled()
  })

  it('shares one entry between subscribers of the same key', () => {
    const pool = createKeyedWsSubscribable<number>()
    const first = vi.fn()
    const second = vi.fn()
    const firstSub = pool.subscribe('a', first)
    pool.subscribe('a', second)

    pool.notify('a', 1)
    firstSub.unsubscribe()
    pool.notify('a', 2)

    expect(first.mock.calls).toEqual([[1]])
    expect(second.mock.calls).toEqual([[1], [2]])
    // Still watched, so it must stay in `keys()` — a caller that re-asks upstream per key would
    // otherwise stop asking for a key it is still delivering.
    expect([...pool.keys()]).toEqual(['a'])
  })

  it('drops a key once its last subscriber leaves', () => {
    const pool = createKeyedWsSubscribable<number>()
    const sub = pool.subscribe('a', vi.fn())
    pool.subscribe('b', vi.fn())

    sub.unsubscribe()

    // `keys()` is what callers re-request upstream for on reconnect, so an unpruned map means
    // asking the desk for a stream nothing is listening to, forever.
    expect([...pool.keys()]).toEqual(['b'])
  })

  it('leaves a re-pooled key alone when an old unsubscribe runs a second time', () => {
    const pool = createKeyedWsSubscribable<number>()
    const stale = pool.subscribe('a', vi.fn())
    stale.unsubscribe()

    const onLive = vi.fn()
    pool.subscribe('a', onLive)
    // React can call a cleanup again, and a caller can hold the handle past teardown. Without the
    // identity check the second call finds its own detached entry empty and deletes whatever has
    // since been pooled under the key — stranding every live subscriber on it, with no error.
    stale.unsubscribe()

    pool.notify('a', 5)
    expect(onLive).toHaveBeenCalledWith(5)
    expect([...pool.keys()]).toEqual(['a'])
  })

  it('ignores a notify for a key nobody is watching', () => {
    const pool = createKeyedWsSubscribable<number>()
    expect(() => pool.notify('nobody', 1)).not.toThrow()
    expect([...pool.keys()]).toEqual([])
  })
})
