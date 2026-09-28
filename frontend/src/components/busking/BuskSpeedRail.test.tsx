// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { SpeedMasterLiveState } from '@/api/speedMastersWsApi'
import type { SpeedMaster } from '@/api/speedMastersApi'
import {
  SLIDE_HOLD_MS,
  SLIDE_MAX_BPM,
  SLIDE_MIN_BPM,
  SLIDE_PUSH_MS,
} from '@/lib/speedMasterModel'

// Store-connected, so the store module is mocked — which also keeps the import graph away from
// lightingApi's real WebSocket, exactly as `SpeedMasters.test.tsx` does.
const setSpeedMasterBpm = vi.fn()
const tapSpeedMaster = vi.fn()
const saveMaster = vi.fn((_body: Record<string, unknown>) => ({
  unwrap: () => Promise.resolve({}),
}))
let liveMasters: SpeedMasterLiveState[] = []
let rows: SpeedMaster[] = []
vi.mock('@/store/speedMasters', () => ({
  useSpeedMasterLiveQuery: () => ({ data: liveMasters }),
  useSpeedMasterListQuery: () => ({ data: rows }),
  useSaveSpeedMasterMutation: () => [saveMaster, { isLoading: false }],
  // The detail sheet the rail now opens reaches for this one.
  useDeleteSpeedMasterMutation: () => [vi.fn(), { isLoading: false }],
  setSpeedMasterBpm: (...args: unknown[]) => setSpeedMasterBpm(...args),
  tapSpeedMaster: (...args: unknown[]) => tapSpeedMaster(...args),
  subscribeToSpeedMasterBeat: () => ({ unsubscribe: () => {} }),
  useMaster1Uuid: () => liveMasters.find((m) => m.index === 1)?.uuid ?? null,
  requestSpeedMasterBeat: () => {},
  useSpeedMasterBpm: (uuid: string | null) =>
    liveMasters.find((m) => m.uuid === uuid)?.bpm ?? null,
}))
const deskConnected = { current: true }
vi.mock('@/store/status', () => ({
  useIsDeskConnected: () => deskConnected.current,
}))
// Reaches `useParams` for the project id, and renders nothing without one.
vi.mock('@/components/SpeedMasters', () => ({
  ManageMastersLink: () => <a href="/speed-masters">Manage speed masters</a>,
}))
vi.mock('react-router', () => ({
  useParams: () => ({ projectId: '7' }),
}))

import { BuskSpeedRail } from './BuskSpeedRail'

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
  vi.useRealTimers()
  deskConnected.current = true
})

function cardFor(name: string): HTMLElement {
  const card: HTMLElement = screen.getByText(name).closest('div.rounded-lg')!
  // jsdom lays nothing out, and the fader maps a viewport x onto the card's own box. 200px wide at
  // the origin makes the arithmetic legible: x is the percentage along the 60..180 travel.
  card.getBoundingClientRect = () =>
    ({ left: 0, top: 0, width: 200, height: 80, right: 200, bottom: 80, x: 0, y: 0 }) as DOMRect
  return card
}

/**
 * Hold the card past the arming delay, drag to `toX`, and release.
 *
 * `upTarget` is where the release lands — the drag may cross TAP or a ratio chip on its way, and
 * that the click those would fire is swallowed is half the point of the gesture.
 */
function dragTempo(card: HTMLElement, toX: number, upTarget: Element = card) {
  arm(card)
  moveTo(toX)
  act(() => {
    window.dispatchEvent(new PointerEvent('pointerup', { clientX: toX, clientY: 10 }))
  })
  fireEvent.pointerUp(upTarget, { clientX: toX, clientY: 10 })
  fireEvent.click(upTarget)
}

/** Press and hold until the fader arms, without releasing. */
function arm(card: HTMLElement, atX = 0) {
  vi.useFakeTimers()
  fireEvent.pointerDown(card, { clientX: atX, clientY: 10 })
  act(() => {
    vi.advanceTimersByTime(SLIDE_HOLD_MS + 50)
  })
}

/**
 * Move the drag, then let the throttle's floor lapse.
 *
 * The floor is what keeps a `pointermove` per frame from becoming a broadcast per frame; a test
 * asserting the rig moved has to let it pass, or it is asserting about the timer rather than the
 * gesture.
 */
function moveTo(x: number) {
  act(() => {
    window.dispatchEvent(new PointerEvent('pointermove', { clientX: x, clientY: 10 }))
  })
  act(() => {
    vi.advanceTimersByTime(SLIDE_PUSH_MS + 10)
  })
}

function live(index: number, overrides: Partial<SpeedMasterLiveState> = {}): SpeedMasterLiveState {
  return {
    uuid: `aaaaaaaa-0000-0000-0000-00000000000${index}`,
    index,
    name: `Master ${index}`,
    bpm: 120,
    isRunning: true,
    source: 'MANUAL',
    ...overrides,
  }
}

function row(index: number, overrides: Partial<SpeedMaster> = {}): SpeedMaster {
  return {
    id: index * 10,
    uuid: `aaaaaaaa-0000-0000-0000-00000000000${index}`,
    masterIndex: index,
    name: `Master ${index}`,
    bpm: 120,
    source: 'MANUAL',
    referenceCount: 0,
    ...overrides,
  }
}

describe('BuskSpeedRail — the three arms', () => {
  it('gives master 1 a TAP and no usage badge', () => {
    liveMasters = [live(1, { name: 'Master' })]
    rows = [row(1)]
    render(<BuskSpeedRail />)

    expect(screen.getByLabelText('Tap tempo for master 1')).toBeInTheDocument()
    // M1 is where every unmatched category lands by definition, so a badge naming one would
    // understate it. No routable usage label may appear on its card.
    expect(screen.queryByText(/^(Dimmer|Colour|Movement|Position)$/)).toBeNull()
  })

  it('gives a manual master its own TAP and its usage badge', () => {
    liveMasters = [live(1), live(4, { name: 'Strobe', usage: 'dimmer' })]
    rows = [row(1), row(4, { name: 'Strobe', usage: 'dimmer' })]
    render(<BuskSpeedRail />)

    expect(screen.getByLabelText('Tap tempo for master 4')).toBeInTheDocument()
    expect(screen.getByText('Dimmer')).toBeInTheDocument()
    expect(screen.getByText('manual')).toBeInTheDocument()
  })

  /**
   * The rule the whole follower arm exists for: the server refuses both tempo writes on a
   * follower (`SPEED_MASTER_FOLLOWER`), so this surface must not offer either. It is the fourth
   * surface to need this arm, after the ShowBar tile, the phone popover row and the manage page.
   */
  it('gives a follower ratio chips instead of TAP, and refuses a typed tempo', () => {
    liveMasters = [
      live(1),
      live(2, { name: 'Movement', usage: 'position', bpm: 60, followNum: 1, followDen: 2 }),
    ]
    rows = [row(1), row(2, { name: 'Movement', usage: 'position', followNum: 1, followDen: 2 })]
    render(<BuskSpeedRail />)

    expect(screen.queryByLabelText('Tap tempo for master 2')).toBeNull()
    expect(screen.getByText('follows M1 · ½')).toBeInTheDocument()

    const chips = within(screen.getByRole('group', { name: 'Master 2 time signature' }))
    for (const label of ['2×', '1×', '½', '⅓', '¼']) {
      expect(chips.getByText(label)).toBeInTheDocument()
    }

    // The bpm is a readout, not a draft opener.
    fireEvent.click(screen.getByText('60'))
    expect(screen.queryByLabelText('Master 2 BPM')).toBeNull()
  })
})

describe('BuskSpeedRail — the ratio chips', () => {
  it('names the master a follower actually follows', () => {
    // Follow targets: master 3 follows master 2, so neither the chip nor the ratio buttons may
    // say "M1" — the one thing every read-only surface got wrong for free before the target
    // existed, because there was only ever one answer.
    liveMasters = [
      live(1),
      live(2, { name: 'Movement', bpm: 90 }),
      live(3, {
        name: 'Crawl',
        bpm: 45,
        followNum: 1,
        followDen: 2,
        followTargetUuid: 'aaaaaaaa-0000-0000-0000-000000000002',
      }),
    ]
    rows = [
      row(1),
      row(2, { name: 'Movement' }),
      row(3, {
        name: 'Crawl',
        followNum: 1,
        followDen: 2,
        followTargetUuid: 'aaaaaaaa-0000-0000-0000-000000000002',
      }),
    ]
    render(<BuskSpeedRail />)

    expect(screen.getByText('follows M2 · ½')).toBeInTheDocument()
    expect(screen.getByLabelText('Follow Movement at 1/3')).toBeInTheDocument()
  })

  /**
   * The two rules the detail sheet's save comment spells out, restated here because this is the
   * second surface that can write a follow ratio: both halves of the pair, and **never** `bpm`
   * beside them — the server 400s that combination on a follower.
   */
  it('sends both halves of the pair and no tempo', async () => {
    liveMasters = [live(1), live(2, { bpm: 60, followNum: 1, followDen: 2 })]
    rows = [row(1), row(2, { followNum: 1, followDen: 2 })]
    render(<BuskSpeedRail />)

    fireEvent.click(screen.getByLabelText('Follow Master 1 at 1/3'))

    expect(saveMaster).toHaveBeenCalledTimes(1)
    const body = saveMaster.mock.calls[0]![0]
    expect(body).toMatchObject({ projectId: 7, masterId: 20, followNum: 1, followDen: 3 })
    expect(body).not.toHaveProperty('bpm')
  })

  it('is inert until the REST row carrying the id has arrived', () => {
    // Everything drawn comes from the live frame, but the PUT needs the numeric id, which only
    // the list has. A chip with nothing to address must not pretend otherwise.
    liveMasters = [live(1), live(2, { bpm: 60, followNum: 1, followDen: 2 })]
    rows = []
    render(<BuskSpeedRail />)

    expect(screen.getByLabelText('Follow Master 1 at 1/3')).toBeDisabled()
  })
})

/**
 * The third way to set a tempo, after typing it and tapping it — hold the card and drag.
 *
 * It is the busk view's own hold-to-slide gesture, the one the property pads carried before they
 * were removed, and it exists for the thing a busking operator does most: *trimming* a tempo that
 * is nearly right, which neither TAP nor a typed number does well.
 */
describe('BuskSpeedRail — hold to drag the tempo', () => {
  it('leaves the tempo where the finger let go', () => {
    liveMasters = [live(1, { name: 'Master' })]
    rows = [row(1, { name: 'Master' })]
    render(<BuskSpeedRail />)

    // Half way along a 200px card is half way along the 60..180 travel.
    dragTempo(cardFor('M1 · Master'), 100)

    expect(setSpeedMasterBpm).toHaveBeenLastCalledWith(liveMasters[0]!.uuid, 120)
  })

  /**
   * The point of a fader rather than a number field: the operator judges the tempo against a show
   * that is running, so the rig has to move under the drag rather than on the release.
   */
  it('applies the tempo as the drag goes, not only on release', () => {
    liveMasters = [live(1, { name: 'Master', bpm: 90 })]
    rows = [row(1, { name: 'Master' })]
    render(<BuskSpeedRail />)

    const card = cardFor('M1 · Master')
    arm(card)
    expect(setSpeedMasterBpm).toHaveBeenLastCalledWith(liveMasters[0]!.uuid, SLIDE_MIN_BPM)

    moveTo(100)
    expect(setSpeedMasterBpm).toHaveBeenLastCalledWith(liveMasters[0]!.uuid, 120)

    moveTo(150)
    expect(setSpeedMasterBpm).toHaveBeenLastCalledWith(liveMasters[0]!.uuid, 150)

    // All of that before the pointer has been released at all.
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  /**
   * `pointermove` fires up to once a frame and every write is broadcast to every socket on the
   * desk, so the sends are floored and deduplicated on the rounded BPM. Both halves matter: the
   * floor bounds a fast drag, the dedupe bounds a slow one — the travel is well under a BPM per
   * pixel, so most of a real drag's moves land on the tempo the last one did.
   */
  it('throttles and deduplicates the writes a drag makes', () => {
    liveMasters = [live(1, { name: 'Master' })]
    rows = [row(1, { name: 'Master' })]
    render(<BuskSpeedRail />)

    arm(cardFor('M1 · Master'))
    setSpeedMasterBpm.mockClear()

    // Ten moves inside one throttle window, all to different pixels but only two distinct tempos.
    act(() => {
      for (let x = 100; x < 110; x++) {
        window.dispatchEvent(new PointerEvent('pointermove', { clientX: x, clientY: 10 }))
      }
    })
    act(() => {
      vi.advanceTimersByTime(SLIDE_PUSH_MS + 10)
    })

    // Two writes for ten moves — one leading, because the floor had already lapsed when the burst
    // began, and one trailing carrying the latest value. The deferred value is held, not dropped.
    expect(setSpeedMasterBpm).toHaveBeenCalledTimes(2)
    expect(setSpeedMasterBpm).toHaveBeenLastCalledWith(liveMasters[0]!.uuid, 125)

    // A move that does not change the whole BPM says nothing on the wire. A real drag is full of
    // these: a pointer reports far more often than a hand crosses a pixel, and the travel is well
    // under a BPM per pixel.
    setSpeedMasterBpm.mockClear()
    moveTo(110)
    expect(setSpeedMasterBpm).toHaveBeenCalledTimes(1)
    setSpeedMasterBpm.mockClear()
    moveTo(110.4)
    expect(setSpeedMasterBpm).not.toHaveBeenCalled()
  })

  /** The release bypasses the floor: the value let go on is the one that has to land. */
  it('sends the released value even inside the throttle window', () => {
    liveMasters = [live(1, { name: 'Master' })]
    rows = [row(1, { name: 'Master' })]
    render(<BuskSpeedRail />)

    const card = cardFor('M1 · Master')
    arm(card)
    moveTo(100)
    setSpeedMasterBpm.mockClear()

    // Move and release immediately, with no time for the floor to lapse.
    act(() => {
      window.dispatchEvent(new PointerEvent('pointermove', { clientX: 180, clientY: 10 }))
      window.dispatchEvent(new PointerEvent('pointerup', { clientX: 180, clientY: 10 }))
    })

    expect(setSpeedMasterBpm).toHaveBeenCalledTimes(1)
    expect(setSpeedMasterBpm).toHaveBeenLastCalledWith(liveMasters[0]!.uuid, 168)
  })

  it('maps the ends of the card onto the ends of the control range', () => {
    liveMasters = [live(1, { name: 'Master' })]
    rows = [row(1, { name: 'Master' })]
    render(<BuskSpeedRail />)

    dragTempo(cardFor('M1 · Master'), 0)
    expect(setSpeedMasterBpm).toHaveBeenLastCalledWith(liveMasters[0]!.uuid, SLIDE_MIN_BPM)

    // Past the right-hand edge clamps rather than running on.
    dragTempo(cardFor('M1 · Master'), 400)
    expect(setSpeedMasterBpm).toHaveBeenLastCalledWith(liveMasters[0]!.uuid, SLIDE_MAX_BPM)
  })

  it('reads out the dragged tempo rather than the live frame', () => {
    // The live frame is the desk catching up behind the drag; the card must show where the finger
    // is, or a slow round trip would drag the number backwards under the pointer.
    liveMasters = [live(1, { name: 'Master', bpm: 90 })]
    rows = [row(1, { name: 'Master' })]
    render(<BuskSpeedRail />)

    arm(cardFor('M1 · Master'))
    moveTo(150)

    expect(screen.getByText('150')).toBeInTheDocument()
  })

  /**
   * The whole card is the fader, so a drag crosses TAP and the ratio chips freely. The click that
   * ends it is swallowed in the capture phase — otherwise trimming a tempo would also tap one.
   */
  it('does not tap the tempo when the drag ends over TAP', () => {
    liveMasters = [live(1, { name: 'Master' })]
    rows = [row(1, { name: 'Master' })]
    render(<BuskSpeedRail />)

    dragTempo(cardFor('M1 · Master'), 100, screen.getByLabelText('Tap tempo for master 1'))

    expect(setSpeedMasterBpm).toHaveBeenCalledWith(liveMasters[0]!.uuid, 120)
    expect(tapSpeedMaster).not.toHaveBeenCalled()
  })

  it('still taps on a short press', () => {
    liveMasters = [live(1, { name: 'Master' })]
    rows = [row(1, { name: 'Master' })]
    render(<BuskSpeedRail />)

    fireEvent.click(screen.getByLabelText('Tap tempo for master 1'))
    expect(tapSpeedMaster).toHaveBeenCalledTimes(1)
    expect(setSpeedMasterBpm).not.toHaveBeenCalled()
  })

  /** The same refusal that takes TAP and click-to-type away from a follower takes the drag too. */
  it('refuses to drag a follower', () => {
    liveMasters = [live(1), live(2, { name: 'Movement', bpm: 60, followNum: 1, followDen: 2 })]
    rows = [row(1), row(2, { name: 'Movement', followNum: 1, followDen: 2 })]
    render(<BuskSpeedRail />)

    dragTempo(cardFor('M2 · Movement'), 150)
    expect(setSpeedMasterBpm).not.toHaveBeenCalled()
  })

  it('refuses to drag while the desk is offline', () => {
    deskConnected.current = false
    liveMasters = [live(1, { name: 'Master' })]
    rows = [row(1, { name: 'Master' })]
    render(<BuskSpeedRail />)

    dragTempo(cardFor('M1 · Master'), 150)
    expect(setSpeedMasterBpm).not.toHaveBeenCalled()
  })

  /**
   * `pointercancel` ends the drag as surely as a release does, and on a touchscreen it is the
   * *likely* ending: the rail is a scroller, so a drag the browser reclaims as a pan never sends a
   * `pointerup` at all. A card left armed keeps its window listeners, and the next pointer movement
   * anywhere on the page would write a tempo with nothing held down.
   */
  it('ends the drag when the browser cancels the gesture', () => {
    liveMasters = [live(1, { name: 'Master' })]
    rows = [row(1, { name: 'Master' })]
    render(<BuskSpeedRail />)

    const card = cardFor('M1 · Master')
    arm(card)
    moveTo(100)
    setSpeedMasterBpm.mockClear()

    act(() => {
      window.dispatchEvent(new PointerEvent('pointercancel', { clientX: 100, clientY: 10 }))
    })

    moveTo(180)
    expect(setSpeedMasterBpm).not.toHaveBeenCalled()
  })

  /**
   * The throttle holds the *latest* value, not the one that armed it. A drag full of small
   * corrections crosses the same BPM repeatedly, and stranding the rig on one the finger has
   * already moved back off is the failure the dedupe exists to avoid, not to cause.
   */
  it('does not fire a tempo the drag has already moved back off', () => {
    liveMasters = [live(1, { name: 'Master' })]
    rows = [row(1, { name: 'Master' })]
    render(<BuskSpeedRail />)

    arm(cardFor('M1 · Master'), 100)
    // Lands 125 with the floor already lapsed, so the next move is the one that opens a window.
    act(() => {
      window.dispatchEvent(new PointerEvent('pointermove', { clientX: 108, clientY: 10 }))
    })
    setSpeedMasterBpm.mockClear()

    // Two moves inside that window: away to 120 and straight back to the 125 already sent.
    act(() => {
      window.dispatchEvent(new PointerEvent('pointermove', { clientX: 100, clientY: 10 }))
      window.dispatchEvent(new PointerEvent('pointermove', { clientX: 108, clientY: 10 }))
    })
    act(() => {
      vi.advanceTimersByTime(SLIDE_PUSH_MS + 10)
    })

    expect(setSpeedMasterBpm).not.toHaveBeenCalled()
  })

  /**
   * The dedupe is against *this* drag's own moves and nothing else. Between drags the tempo moves
   * by every other route — TAP, the bpm field, another tab, a MIDI surface — so a second drag
   * arming on the value the first one ended at still has to state it, or the card would read a
   * tempo the desk is not running for as long as the finger stayed still.
   */
  it('does not assume the desk is still where the last drag left it', () => {
    liveMasters = [live(1, { name: 'Master' })]
    rows = [row(1, { name: 'Master' })]
    render(<BuskSpeedRail />)

    dragTempo(cardFor('M1 · Master'), 100)
    expect(setSpeedMasterBpm).toHaveBeenLastCalledWith(liveMasters[0]!.uuid, 120)
    setSpeedMasterBpm.mockClear()

    arm(cardFor('M1 · Master'), 100)
    act(() => {
      vi.advanceTimersByTime(SLIDE_PUSH_MS + 10)
    })
    expect(setSpeedMasterBpm).toHaveBeenCalledWith(liveMasters[0]!.uuid, 120)
  })
})

/**
 * The rail is the only place on this page a master's *settings* can be reached — its name, its
 * usage, the tempo it boots at, and whether it follows master 1. The glyph is the whole of that
 * route: the hold belongs to the tempo fader, and a card cannot answer a hold two ways.
 */
describe('BuskSpeedRail — the detail sheet', () => {
  it('opens the sheet from the settings glyph', () => {
    liveMasters = [live(1), live(2, { name: 'Movement', usage: 'position' })]
    rows = [row(1), row(2, { name: 'Movement', usage: 'position' })]
    render(<BuskSpeedRail />)

    expect(screen.queryByRole('dialog')).toBeNull()
    fireEvent.click(screen.getByLabelText('Master 2 settings'))
    expect(within(screen.getByRole('dialog')).getByText(/M2 · Movement/)).toBeInTheDocument()
  })

  /**
   * The sheet edits the REST row, which the live frame is not — so with no row there is nothing to
   * open. The *fader* is unaffected: it needs only the master's uuid, which the live frame carries.
   */
  it('hides the glyph until the REST row has arrived, and still drags', () => {
    liveMasters = [live(1), live(2, { name: 'Movement' })]
    rows = []
    render(<BuskSpeedRail />)

    expect(screen.queryByLabelText('Master 2 settings')).toBeNull()
    dragTempo(cardFor('M2 · Movement'), 100)
    expect(setSpeedMasterBpm).toHaveBeenCalledWith(liveMasters[1]!.uuid, 120)
  })

  /**
   * The open sheet is held by **id and re-resolved**, never as the row object that was clicked —
   * the same call `routes/SpeedMasters.tsx` makes. The list refetches on every
   * `speedMasters.listChanged`, so a captured snapshot would show a pre-edit name after a rename in
   * another tab, and would leave the sheet open on a master deleted elsewhere: a row whose Save and
   * Delete both address an id the server no longer has.
   */
  it('closes the sheet when its master leaves the list', () => {
    liveMasters = [live(1), live(2, { name: 'Movement' })]
    rows = [row(1), row(2, { name: 'Movement' })]
    const { rerender } = render(<BuskSpeedRail />)

    fireEvent.click(screen.getByLabelText('Master 2 settings'))
    expect(screen.getByRole('dialog')).toBeInTheDocument()

    liveMasters = [live(1)]
    rows = [row(1)]
    rerender(<BuskSpeedRail />)

    expect(screen.queryByRole('dialog')).toBeNull()
  })
})
