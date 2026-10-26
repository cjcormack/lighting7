// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { configureStore, SHOULD_AUTOBATCH, type Store } from '@reduxjs/toolkit'
import { store } from '../store'

/**
 * Pins `setup.ts`'s tick batching on the app's store and on one a test builds. On a frame, the
 * first case leaves a timer behind and the second loses the window's animation frames.
 */

function batched(): { type: string; meta: Record<string, boolean> } {
  return { type: 'storeBatching/batched', meta: { [SHOULD_AUTOBATCH]: true } }
}

const stores: Array<[string, () => Store]> = [
  ['the app store', () => store],
  ['a store a test builds', () => configureStore({ reducer: { counter: (n: number = 0) => n } })],
]

afterEach(() => {
  vi.useRealTimers()
})

describe.each(stores)('%s', (_name, makeStore) => {
  it('notifies a batched dispatch at the end of the tick, leaving no timer behind', async () => {
    const subject = makeStore()
    // RTK Query's middleware announces itself, unbatched, on the app store's first action.
    subject.dispatch({ type: 'storeBatching/first' })
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] })
    const listener = vi.fn()
    const unsubscribe = subject.subscribe(listener)
    subject.dispatch(batched())
    expect(vi.getTimerCount()).toBe(0)
    expect(listener).not.toHaveBeenCalled()
    await Promise.resolve()
    expect(listener).toHaveBeenCalled()
    unsubscribe()
  })

  it('leaves the window its frames after a batched dispatch under fake timers', async () => {
    const subject = makeStore()
    vi.useFakeTimers({ shouldAdvanceTime: true })
    subject.dispatch(batched())
    vi.useRealTimers()
    await expect(
      new Promise<void>((resolve, reject) => {
        const timeout = setTimeout(() => reject(new Error('no animation frame within 500 ms')), 500)
        requestAnimationFrame(() => {
          clearTimeout(timeout)
          resolve()
        })
      }),
    ).resolves.toBeUndefined()
  })
})
