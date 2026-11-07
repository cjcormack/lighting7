// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, renderHook, screen } from '@testing-library/react'
import type { RecentValuesState } from '@/api/recentValuesApi'

const desk = vi.hoisted(() => {
  let state: RecentValuesState = { projectId: null, lists: {} }
  const listeners = new Set<(s: RecentValuesState) => void>()
  return {
    record: vi.fn(),
    set(next: RecentValuesState) {
      state = next
      listeners.forEach((fn) => fn(state))
    },
    api: {
      get: () => state,
      subscribe: (fn: (s: RecentValuesState) => void) => {
        listeners.add(fn)
        return { unsubscribe: () => listeners.delete(fn) }
      },
    },
  }
})
vi.mock('@/api/lightingApi', () => ({ lightingApi: { recentValues: { ...desk.api, record: desk.record } } }))

import { RECENT_RELEASE_SETTLE_MS, useRecordRecent } from '@/store/recentValues'
import { RECENT_BAND_MAX, RecentValues } from './RecentValues'

afterEach(() => {
  desk.set({ projectId: null, lists: {} })
  vi.clearAllMocks()
  vi.useRealTimers()
})

describe('the Recent band', () => {
  it('draws the list newest first, a swatch per colour with its hex on the hover, and a tap hands the value over', () => {
    desk.set({ projectId: 1, lists: { colour: ['#ff8800;w0', '#102030'] } })
    const onPick = vi.fn()
    render(<RecentValues listKey="colour" onPick={onPick} />)
    const swatches = screen.getAllByRole('button')
    expect(swatches.map((s) => s.getAttribute('title'))).toEqual(['#ff8800', '#102030'])
    fireEvent.click(swatches[0])
    expect(onPick).toHaveBeenCalledWith('#ff8800;w0')
  })

  it('words a level, a position and a warmth as chips', () => {
    desk.set({ projectId: 1, lists: { 'level:dimmer': ['pct:62'], position: ['pos:-20,12'], warmth: ['3200K;80'] } })
    const { rerender } = render(<RecentValues listKey="level:dimmer" onPick={vi.fn()} />)
    expect(screen.getByRole('button', { name: 'Recent 62%' })).toBeInTheDocument()
    rerender(<RecentValues listKey="position" onPick={vi.fn()} />)
    expect(screen.getByRole('button', { name: 'Recent −20, 12' })).toBeInTheDocument()
    rerender(<RecentValues listKey="warmth" onPick={vi.fn()} />)
    expect(screen.getByRole('button', { name: 'Recent 3200 K · 80%' })).toBeInTheDocument()
  })

  it('names the emitters a colour lights, so the white LED is not called black', () => {
    desk.set({ projectId: 1, lists: { colour: ['#000000;w255;a0', '#000000'] } })
    render(<RecentValues listKey="colour" onPick={vi.fn()} />)
    expect(screen.getAllByRole('button').map((b) => b.getAttribute('aria-label'))).toEqual(['Recent #000000 · W 255', 'Recent #000000'])
  })

  it('draws at most eight', () => {
    desk.set({ projectId: 1, lists: { 'level:dimmer': Array.from({ length: 12 }, (_, i) => `pct:${i}`) } })
    render(<RecentValues listKey="level:dimmer" onPick={vi.fn()} />)
    expect(screen.getAllByRole('button')).toHaveLength(RECENT_BAND_MAX)
  })

  it('draws nothing while the list is empty, before the desk sends any, or for a project that is not live', () => {
    const { container, rerender } = render(<RecentValues listKey="colour" onPick={vi.fn()} />)
    expect(container).toBeEmptyDOMElement()
    act(() => desk.set({ projectId: 1, lists: {} }))
    expect(container).toBeEmptyDOMElement()
    act(() => desk.set({ projectId: 1, lists: { colour: ['#ff0000'] } }))
    expect(container.querySelector('[data-recent-values="colour"]')).not.toBeNull()
    rerender(<RecentValues listKey="colour" projectId={2} onPick={vi.fn()} />)
    expect(container).toBeEmptyDOMElement()
  })
})

describe('useRecordRecent', () => {
  it('sends only a picker gesture’s last commit, once its release has settled', () => {
    vi.useFakeTimers()
    desk.set({ projectId: 1, lists: {} })
    const { result } = renderHook(() => useRecordRecent('level:dimmer'))
    result.current.stage('pct:10')
    result.current.stage('pct:20')
    expect(desk.record).not.toHaveBeenCalled()
    result.current.release()
    // A move reported after the release still counts.
    result.current.stage('pct:30')
    vi.advanceTimersByTime(RECENT_RELEASE_SETTLE_MS)
    expect(desk.record).toHaveBeenCalledExactlyOnceWith('level:dimmer', 'pct:30')
  })

  it('flushes a gesture’s last commit at once, and sends nothing with nothing staged', () => {
    desk.set({ projectId: 1, lists: {} })
    const { result } = renderHook(() => useRecordRecent('level:dimmer'))
    result.current.flush()
    expect(desk.record).not.toHaveBeenCalled()
    result.current.stage('pct:10')
    result.current.stage('pct:40')
    result.current.flush()
    result.current.flush()
    expect(desk.record).toHaveBeenCalledExactlyOnceWith('level:dimmer', 'pct:40')
  })

  it('sends a tap at once, after anything staged, and what is staged when the editor closes', () => {
    desk.set({ projectId: 1, lists: {} })
    const { result, unmount } = renderHook(() => useRecordRecent('colour'))
    result.current.stage('#0000ff')
    result.current.now('#ff0000')
    expect(desk.record.mock.calls).toEqual([
      ['colour', '#0000ff'],
      ['colour', '#ff0000'],
    ])
    result.current.stage('#00ff00')
    unmount()
    expect(desk.record).toHaveBeenLastCalledWith('colour', '#00ff00')
  })

  it('keeps the key a value was staged under, and drops it once another project is live', () => {
    desk.set({ projectId: 1, lists: {} })
    const { result, rerender } = renderHook(({ listKey }) => useRecordRecent(listKey), { initialProps: { listKey: 'level:dimmer' } })
    result.current.stage('pct:30')
    rerender({ listKey: 'level:zoom' })
    result.current.flush()
    expect(desk.record).toHaveBeenCalledExactlyOnceWith('level:dimmer', 'pct:30')

    result.current.stage('pct:60')
    desk.set({ projectId: 2, lists: {} })
    result.current.flush()
    expect(desk.record).toHaveBeenCalledTimes(1)
  })

  it('records nothing for an editor of another project', () => {
    desk.set({ projectId: 1, lists: {} })
    const { result } = renderHook(() => useRecordRecent('colour', 2))
    result.current.now('#ff0000')
    expect(desk.record).not.toHaveBeenCalled()
  })
})
