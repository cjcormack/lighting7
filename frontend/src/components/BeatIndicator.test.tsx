// @vitest-environment jsdom
import { act, cleanup, render } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

// The beat source is mocked so this stays a component test and never reaches the real
// WebSocket. The point of the suite is *which master* a given prop shape resolves to.
const subscribeToSpeedMasterBeat = vi.fn(
  (_masterUuid: string | null, _fn: (beat: { bpm: number }) => void) => ({
    unsubscribe: vi.fn(),
  }),
)
const requestSpeedMasterBeat = vi.fn()
const MASTER_1_UUID = 'aaaa-1'
// Stands in for the live-BPM push (`speedMasters.changed`), which the dot uses to re-seed its
// interval on a retune rather than waiting for the server's corrective beat frame.
let liveBpm: number | null = null

// The factory is hoisted above the consts above, so it has to call through lazily rather
// than referencing them directly.
vi.mock('../store/speedMasters', () => ({
  subscribeToSpeedMasterBeat: (masterUuid: string | null, fn: (beat: { bpm: number }) => void) =>
    subscribeToSpeedMasterBeat(masterUuid, fn),
  requestSpeedMasterBeat: (masterUuid: string | null) => requestSpeedMasterBeat(masterUuid),
  useMaster1Uuid: () => MASTER_1_UUID,
  useSpeedMasterBpm: () => liveBpm,
}))

import { BeatIndicator } from './BeatIndicator'

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
  vi.useRealTimers()
  liveBpm = null
})

describe('BeatIndicator', () => {
  // Master 1's beat frames carry its real uuid, so a dot standing in for master 1 has to
  // resolve it — subscribing with the write-side `null` would never match a frame.
  it.each([
    ['given no master', undefined],
    ['given master 1 with a null uuid', { uuid: null, index: 1 } as const],
  ])("resolves master 1's real uuid when %s", (_label, master) => {
    render(<BeatIndicator master={master} />)

    expect(subscribeToSpeedMasterBeat).toHaveBeenCalledTimes(1)
    expect(subscribeToSpeedMasterBeat.mock.calls[0][0]).toBe(MASTER_1_UUID)
  })

  it('uses the master it was given for masters beyond the first', () => {
    render(<BeatIndicator master={{ uuid: 'aaaa-2', index: 2 }} />)

    expect(subscribeToSpeedMasterBeat).toHaveBeenCalledTimes(1)
    expect(subscribeToSpeedMasterBeat.mock.calls[0][0]).toBe('aaaa-2')
  })

  it('resubscribes when the master it follows changes', () => {
    const { rerender } = render(<BeatIndicator master={{ uuid: 'aaaa-2', index: 2 }} />)
    expect(subscribeToSpeedMasterBeat.mock.calls[0][0]).toBe('aaaa-2')

    rerender(<BeatIndicator master={{ uuid: 'aaaa-3', index: 3 }} />)

    // The local interval is still ticking at the old master's tempo, so it has to re-bind
    // (and drop back to unsynced) rather than keep flashing at the wrong rate.
    expect(subscribeToSpeedMasterBeat.mock.calls.at(-1)?.[0]).toBe('aaaa-3')
  })

  it('does not resubscribe when it regains sync', () => {
    const { container } = render(<BeatIndicator master={{ uuid: 'aaaa-2', index: 2 }} />)
    const onBeat = subscribeToSpeedMasterBeat.mock.calls[0][1]

    act(() => onBeat({ bpm: 120 }))

    // Gaining sync re-renders, but the subscription is keyed on the master alone — re-binding
    // would send a redundant requestBeat every time the dot recovers.
    expect(subscribeToSpeedMasterBeat).toHaveBeenCalledTimes(1)
    expect((container.firstElementChild as HTMLElement).className).toContain('bg-primary')
  })

  it('asks for a frame when the tab comes back, without resubscribing', () => {
    render(<BeatIndicator master={{ uuid: 'aaaa-2', index: 2 }} />)
    const onBeat = subscribeToSpeedMasterBeat.mock.calls[0][1]
    act(() => onBeat({ bpm: 120 }))
    requestSpeedMasterBeat.mockClear()

    // The local interval drifted while backgrounded, so the dot drops to unsynced — and has
    // to ask, or it waits out the throttle (up to 16 beats) showing an empty ring. jsdom
    // reports `visible` by default, which is the returning-to-the-tab case.
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'))
    })

    expect(requestSpeedMasterBeat).toHaveBeenCalledWith('aaaa-2')
    expect(subscribeToSpeedMasterBeat).toHaveBeenCalledTimes(1)
  })

  it('re-seeds the local interval as soon as the tempo moves', () => {
    vi.useFakeTimers()
    liveBpm = 60
    const { container, rerender } = render(<BeatIndicator master={{ uuid: 'aaaa-2', index: 2 }} />)
    const onBeat = subscribeToSpeedMasterBeat.mock.calls[0][1]
    act(() => onBeat({ bpm: 60 }))
    const dot = () => container.firstElementChild as HTMLElement

    // 60 BPM: nothing at 500 ms, a flash at 1000 ms.
    act(() => void vi.advanceTimersByTime(500))
    expect(dot().className).not.toContain('bg-primary')

    // Now the master is retuned to 240 BPM. The server arms a corrective beat frame for the
    // *next* beat after a tempo move (`SpeedMasterSocket`), so the true-up is one beat away —
    // one beat of the pre-retune grid, which is up to three seconds at the 20 BPM floor. This
    // closes that gap: without the live-BPM re-seed the dot goes on flashing at the old rate
    // for that whole beat, after the number beside it has already changed.
    liveBpm = 240
    rerender(<BeatIndicator master={{ uuid: 'aaaa-2', index: 2 }} />)

    // Half of the old beat had been travelled, so half of a *new* 250 ms beat remains: the dot
    // beats at 625 ms rather than at the 1000 ms the old grid was heading for.
    act(() => void vi.advanceTimersByTime(150))
    expect(dot().className).toContain('bg-primary')
  })

  it('keeps beating on the same grid while the tempo is dragged', () => {
    vi.useFakeTimers()
    liveBpm = 120
    const { container, rerender } = render(<BeatIndicator master={{ uuid: 'aaaa-2', index: 2 }} />)
    const onBeat = subscribeToSpeedMasterBeat.mock.calls[0][1]
    act(() => onBeat({ bpm: 120 })) // flashes, and anchors the grid here — a 500 ms beat
    act(() => void vi.advanceTimersByTime(200))

    // A drag on the BPM field pushes at tap rate. Re-seeding by restarting a fresh full-beat
    // deadline per push would have the next push cancel it before it could ever expire, so the
    // dot would go dark for as long as the operator held the slider. The grid is anchored at
    // the last flash instead, so the beat still lands ~500 ms after it.
    let sawFlash = false
    for (const bpm of [121, 122, 123, 124]) {
      liveBpm = bpm
      rerender(<BeatIndicator master={{ uuid: 'aaaa-2', index: 2 }} />)
      act(() => void vi.advanceTimersByTime(100))
      sawFlash ||= (container.firstElementChild as HTMLElement).className.includes('bg-primary')
    }

    expect(sawFlash).toBe(true)
  })

  it('keeps beating while the tempo is dragged downwards', () => {
    vi.useFakeTimers()
    liveBpm = 60
    const { container, rerender } = render(<BeatIndicator master={{ uuid: 'aaaa-2', index: 2 }} />)
    const onBeat = subscribeToSpeedMasterBeat.mock.calls[0][1]
    act(() => onBeat({ bpm: 60 })) // a 1000 ms beat, anchored here
    act(() => void vi.advanceTimersByTime(900)) // nearly a beat travelled

    // Dragging *down* grows the beat. Re-arming a `lastFlash + period` deadline would push it
    // further out (16.7 ms per BPM at 60 BPM) than the 50 ms of wall clock between pushes, so
    // it would never expire and the dot would stay dark past the beat it owed.
    let sawFlash = false
    for (const bpm of [56, 52, 48, 44, 40, 36]) {
      liveBpm = bpm
      rerender(<BeatIndicator master={{ uuid: 'aaaa-2', index: 2 }} />)
      act(() => void vi.advanceTimersByTime(50))
      sawFlash ||= (container.firstElementChild as HTMLElement).className.includes('bg-primary')
    }

    expect(sawFlash).toBe(true)
  })

  it('renders an unsynced ring until the first frame arrives', () => {
    const { container } = render(<BeatIndicator master={{ uuid: 'aaaa-2', index: 2 }} />)
    const dot = container.firstElementChild as HTMLElement

    // "Not synced" is shown rather than guessed at a tempo we have not been told yet.
    expect(dot.className).toContain('border')
    expect(dot.className).not.toContain('bg-primary')
  })
})
