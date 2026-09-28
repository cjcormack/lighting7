// @vitest-environment jsdom
import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Provider } from 'react-redux'
import type { ReactNode } from 'react'

vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

import { store } from '../store'
import { lockRequested } from '../store/editLockSlice'
import { useEditLock } from './useEditLock'

/**
 * The lock is a show-safety mechanism, so its edges are pinned rather than assumed: what it does
 * with the show stopped, what it refuses, and the two ways it re-arms itself.
 */

function wrapper({ children }: { children: ReactNode }) {
  return <Provider store={store}>{children}</Provider>
}

interface Args {
  canEdit: boolean
  isShowActive: boolean
  onLock?: () => void
}

function draw(args: Args) {
  return renderHook((p: Args) => useEditLock(p), { wrapper, initialProps: args })
}

beforeEach(() => {
  // A running show always opens locked; reset the shared slice between cases.
  store.dispatch(lockRequested())
})

afterEach(() => vi.clearAllMocks())

describe('useEditLock', () => {
  it('is unlocked while the show is stopped', () => {
    // Nothing to protect, so nothing is protected — and no lock chrome is shown, because there is
    // no state to warn about.
    const { result } = draw({ canEdit: true, isShowActive: false })
    expect(result.current.locked).toBe(false)
    expect(result.current.lockRelevant).toBe(false)
  })

  it('is locked while the show runs', () => {
    const { result } = draw({ canEdit: true, isShowActive: true })
    expect(result.current.locked).toBe(true)
    expect(result.current.lockRelevant).toBe(true)
  })

  it('stays locked when the backend would refuse the write anyway', () => {
    // `canEdit` is not a role — the backend computes it as "is this the current project". A show
    // that cannot be edited must not offer an unlock that would 4xx.
    const { result } = draw({ canEdit: false, isShowActive: true })
    expect(result.current.locked).toBe(true)
    expect(result.current.lockRelevant).toBe(false)

    act(() => result.current.toggleLock())
    expect(result.current.locked).toBe(true)
  })

  it('unlocks and re-locks on request while running', () => {
    const { result } = draw({ canEdit: true, isShowActive: true })
    act(() => result.current.toggleLock())
    expect(result.current.locked).toBe(false)

    act(() => result.current.toggleLock())
    expect(result.current.locked).toBe(true)
  })

  it('re-arms when the show starts', () => {
    // An edit session begun while stopped must not silently carry into a running show.
    const { result, rerender } = draw({ canEdit: true, isShowActive: false })
    expect(result.current.locked).toBe(false)

    rerender({ canEdit: true, isShowActive: true })
    expect(result.current.locked).toBe(true)
  })

  it('re-locks on GO', () => {
    const { result } = draw({ canEdit: true, isShowActive: true })
    act(() => result.current.toggleLock())
    expect(result.current.locked).toBe(false)

    act(() => result.current.noteGo())
    expect(result.current.locked).toBe(true)
  })

  it('stands surface state down with the lock', () => {
    const onLock = vi.fn()
    const { result } = draw({ canEdit: true, isShowActive: true, onLock })
    act(() => result.current.toggleLock())
    onLock.mockClear()

    act(() => result.current.toggleLock())
    expect(onLock).toHaveBeenCalled()
  })

  it('shares one lock across surfaces', () => {
    // The reason this lives in the store. Unlocking on one route and navigating to the other must
    // not silently re-lock: "I am in a fix-it session" is one fact about the operator.
    const first = draw({ canEdit: true, isShowActive: true })
    act(() => first.result.current.toggleLock())
    expect(first.result.current.locked).toBe(false)
    first.unmount()

    const second = draw({ canEdit: true, isShowActive: true })
    expect(second.result.current.locked).toBe(false)
  })
})
