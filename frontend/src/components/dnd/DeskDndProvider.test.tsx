// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import type { Active } from '@dnd-kit/core'
import { handWs } from '@/test/backendMock'

// `lightingApi` opens a real WebSocket at import time (jsdom has none). The mock's `hand` namespace
// records every pick-up and drop, `pickUpReaches` is how a dead socket is stated, and `fire` is how
// a `hand.state` frame arrives.
vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

import { DESK_DRAG_CHANNEL, type EdgeDragRelease } from './edgeDrag'
import {
  closeDeskDragChannel,
  deskDragChannel,
  useEdgeDragReceiver,
  useEdgeDragSource,
} from './useEdgeDrag'

/**
 * The same-machine edge drag's two impure halves, as `DeskDndProvider` mounts them (multi-screen
 * plan §5 session 4).
 *
 * **Why the hooks rather than a rendered drag.** dnd-kit resolves a drag from measured rects, and
 * every rect in jsdom is zero — so a "drag" driven through the provider would exercise the sensor's
 * own bookkeeping and none of the rules below. What is actually new in this session is a presence
 * handshake, a pointer watcher, a bounds test, one pick-up, a cancel and a channel exchange, and
 * all of them are reachable directly.
 *
 * Two facts about `BroadcastChannel` this file relies on, both verified rather than assumed (in the
 * real Chromium pane as well as here): a message reaches **every other channel object of its name,
 * including ones in the same page**, and **never the object that posted it**. The second is the
 * design invariant — one channel object per page is what stops the sending window claiming its own
 * release — and the first is what lets a single page stand in for two.
 */

const WINDOW_WIDTH = 1024

function statWindow() {
  for (const [key, value] of Object.entries({
    screenX: 0,
    screenY: 0,
    outerWidth: WINDOW_WIDTH,
    outerHeight: 800,
    innerWidth: WINDOW_WIDTH,
    innerHeight: 760,
  })) {
    Object.defineProperty(window, key, { value, configurable: true, writable: true })
  }
}

const TEMPLATE_7 = { kind: 'TEMPLATE', id: 7 } as const

const paletteDrag = (id = 7): Active =>
  ({
    id: 'busk-palette-template-7',
    data: { current: { type: 'busk-palette', record: { kind: 'TEMPLATE', template: { id } } } },
  }) as unknown as Active

const slotDrag = (): Active =>
  ({
    id: 'slot-item-1',
    data: { current: { type: 'slot-item', page: 0, slotIndex: 1 } },
  }) as unknown as Active

/** The operator's own pointer. jsdom has no `PointerEvent`, so `pointerId` is added by hand. */
function pointer(type: string, screenX: number, screenY = 400, pointerId = 1) {
  act(() => {
    const event = new MouseEvent(type, { screenX, screenY, bubbles: true })
    Object.defineProperty(event, 'pointerId', { value: pointerId })
    window.dispatchEvent(event)
  })
}

/** Let posted `BroadcastChannel` messages be delivered — each arrives as its own task. */
async function settle(ms = 0) {
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, ms))
  })
}

/**
 * Open a second window on the channel.
 *
 * It answers `hello` with `here`, which is what a real neighbour's channel does, because **the
 * source refuses to arm with no peer listening** — the fix for an edge drag that used to hijack any
 * drag overshooting a windowed browser's edge.
 */
function openPeer(): BroadcastChannel {
  const peer = new BroadcastChannel(DESK_DRAG_CHANNEL)
  peer.addEventListener('message', (event) => {
    if ((event as MessageEvent).data?.type === 'edge-drag-hello') {
      peer.postMessage({ type: 'edge-drag-here' })
    }
  })
  return peer
}

/** A peer, and the handshake settled — the state every source test starts from. */
async function withPeer(): Promise<BroadcastChannel> {
  const peer = openPeer()
  deskDragChannel()
  await settle()
  return peer
}

let peers: BroadcastChannel[] = []

beforeEach(() => {
  handWs.reset()
  statWindow()
  closeDeskDragChannel()
  document.body.innerHTML = ''
  peers = []
})

afterEach(() => {
  for (const peer of peers) peer.close()
  closeDeskDragChannel()
  vi.restoreAllMocks()
})

describe('presence — the gesture arms only when there is somewhere to hand off to', () => {
  it('does nothing at all when this is the only window', async () => {
    // The regression this gate exists for: on a non-maximized single-monitor window the outer edge
    // IS the visible content edge (the horizontal chrome offset is 0 on every desktop browser), so
    // an ordinary palette drag toward a cue slot overshoots it easily. Before the handshake that
    // drag was cancelled and its record pushed into the hand — the operator's drag simply vanished.
    deskDragChannel()
    await settle()

    const { result } = renderHook(() => useEdgeDragSource())
    act(() => result.current.armEdgeDrag(paletteDrag()))
    pointer('pointermove', 1100)
    await settle()
    pointer('pointermove', 1200)
    pointer('pointerup', 1200)
    await settle()

    expect(handWs.pickedUp).toEqual([])
    expect(result.current.edgeDragHandedOff()).toBe(false)
  })

  it('a drag that never leaves this window puts nothing on the channel', async () => {
    // Stated as a rule rather than left implied: an in-window drag must be exactly what it was
    // before this session existed. That is why the peer probe is asked at the boundary and not at
    // drag start — at drag start it would have been one `hello` per palette drag, for ever.
    const heard: unknown[] = []
    const listener = openPeer()
    peers.push(listener)
    listener.addEventListener('message', (e) => heard.push((e as MessageEvent).data))
    deskDragChannel()
    await settle()
    heard.length = 0

    const { result } = renderHook(() => useEdgeDragSource())
    act(() => result.current.armEdgeDrag(paletteDrag()))
    pointer('pointermove', 200)
    pointer('pointermove', 1023)
    act(() => result.current.disarmEdgeDrag())
    pointer('pointerup', 1023)
    await settle()

    expect(heard).toEqual([])
    expect(handWs.pickedUp).toEqual([])
  })

  it('probes at the first crossing and hands off on the next move', async () => {
    // A window that opened after us, or presence gone stale, is discovered by the crossing itself.
    const peer = openPeer()
    peers.push(peer)
    deskDragChannel()
    // Deliberately NOT settled: this window has heard nothing from the peer yet.

    const { result } = renderHook(() => useEdgeDragSource())
    act(() => result.current.armEdgeDrag(paletteDrag(7)))
    pointer('pointermove', 1100)
    expect(handWs.pickedUp).toEqual([])

    await settle()
    pointer('pointermove', 1120)
    expect(handWs.pickedUp).toEqual([{ kind: 'TEMPLATE', id: 7 }])
  })

  it('arms once another window has answered the handshake', async () => {
    peers.push(await withPeer())

    const { result } = renderHook(() => useEdgeDragSource())
    act(() => result.current.armEdgeDrag(paletteDrag(7)))
    pointer('pointermove', 1100)

    expect(handWs.pickedUp).toEqual([{ kind: 'TEMPLATE', id: 7 }])
  })
})

describe('the source window — handing a drag off at the boundary', () => {
  beforeEach(async () => {
    peers.push(await withPeer())
  })

  it('a drag wholly inside the window sends nothing at all', () => {
    const { result } = renderHook(() => useEdgeDragSource())
    act(() => result.current.armEdgeDrag(paletteDrag()))

    pointer('pointermove', 10)
    pointer('pointermove', 1023)
    pointer('pointerup', 900)

    expect(handWs.pickedUp).toEqual([])
  })

  it('leaving the bounds sends exactly one hand.pickUp and cancels the drag', () => {
    // dnd-kit's PointerSensor binds `pointercancel` on the **document** to the same `handleCancel`
    // as its Escape handler. Listening where it listens is what makes this assert the cancel rather
    // than merely that some event was dispatched.
    const cancels: Event[] = []
    const keys: Event[] = []
    const onCancel = (event: Event) => cancels.push(event)
    const onKey = (event: Event) => keys.push(event)
    document.addEventListener('pointercancel', onCancel)
    document.addEventListener('keydown', onKey)

    const { result } = renderHook(() => useEdgeDragSource())
    act(() => result.current.armEdgeDrag(paletteDrag(7)))
    pointer('pointermove', 1100)

    expect(handWs.pickedUp).toEqual([{ kind: 'TEMPLATE', id: 7 }])
    expect(cancels).toHaveLength(1)
    // The cancel is a pointer event, never a key. An Escape here would sit in front of HandChip's
    // Escape ladder and every other document-level Escape listener on the page.
    expect(keys).toEqual([])
    expect(result.current.edgeDragHandedOff()).toBe(true)
    document.removeEventListener('pointercancel', onCancel)
    document.removeEventListener('keydown', onKey)
  })

  it('does not read its own synthetic cancel as the operator letting go', async () => {
    const heard: unknown[] = []
    const listener = openPeer()
    peers.push(listener)
    listener.addEventListener('message', (e) => heard.push((e as MessageEvent).data))

    const { result } = renderHook(() => useEdgeDragSource())
    act(() => result.current.armEdgeDrag(paletteDrag()))
    pointer('pointermove', 1100)
    await settle()

    // The synthetic `pointercancel` bubbles to the window listeners too. If it were taken as the
    // end of the gesture, the release would be posted at the hand-off instead of at the release.
    expect(heard.filter((m) => (m as { type?: string })?.type === 'edge-drag-release')).toEqual([])
    expect(result.current.edgeDragHandedOff()).toBe(true)
  })

  it('re-entering and leaving again does not send a second pick-up', () => {
    const { result } = renderHook(() => useEdgeDragSource())
    act(() => result.current.armEdgeDrag(paletteDrag()))

    pointer('pointermove', 1100)
    pointer('pointermove', 500)
    pointer('pointermove', 1200)

    expect(handWs.pickedUp).toHaveLength(1)
  })

  it('leaves the drag alone when the pick-up never reached the rig, and tries once per crossing', () => {
    // `sendGesture` refuses a closed socket and has already toasted. Cancelling the drag in
    // exchange for a frame that went nowhere would take the gesture away and give nothing back —
    // and retrying on every move would be an attempt per frame for as long as the pointer sat there.
    handWs.pickUpReaches = false
    const cancels: Event[] = []
    const onCancel = (event: Event) => cancels.push(event)
    document.addEventListener('pointercancel', onCancel)

    const { result } = renderHook(() => useEdgeDragSource())
    act(() => result.current.armEdgeDrag(paletteDrag()))
    pointer('pointermove', 1100)
    pointer('pointermove', 1150)
    pointer('pointermove', 1200)

    expect(handWs.pickedUp).toHaveLength(1)
    expect(cancels).toEqual([])

    // Back inside and out again is a new crossing, and does try again.
    pointer('pointermove', 500)
    pointer('pointermove', 1100)
    expect(handWs.pickedUp).toHaveLength(2)
    document.removeEventListener('pointercancel', onCancel)
  })

  it('ignores a second pointer while one gesture is running', () => {
    const { result } = renderHook(() => useEdgeDragSource())
    act(() => result.current.armEdgeDrag(paletteDrag()))

    pointer('pointermove', 500, 400, 1)
    // A second finger on a touchscreen desk must not move or end the first one's drag.
    pointer('pointermove', 1100, 400, 2)
    expect(handWs.pickedUp).toEqual([])

    pointer('pointermove', 1100, 400, 1)
    expect(handWs.pickedUp).toHaveLength(1)
  })

  it('posts the release naming the record, once the pointer is let go', async () => {
    const heard: EdgeDragRelease[] = []
    const listener = openPeer()
    peers.push(listener)
    listener.addEventListener('message', (e) => {
      const data = (e as MessageEvent).data
      if (data?.type === 'edge-drag-release') heard.push(data)
    })

    const { result } = renderHook(() => useEdgeDragSource())
    act(() => result.current.armEdgeDrag(paletteDrag()))
    pointer('pointermove', 1100)
    pointer('pointerup', 1150, 420)
    await settle()

    expect(heard).toEqual([
      { type: 'edge-drag-release', screen: { x: 1150, y: 420 }, record: TEMPLATE_7 },
    ])
  })

  it('posts the last MOVE point when the pointer is cancelled rather than released', async () => {
    // A real `pointercancel`'s own coordinates are not dependable — commonly zero or stale — and
    // `BuskSpeedRail` already refuses to trust them for its own gesture.
    const heard: EdgeDragRelease[] = []
    const listener = openPeer()
    peers.push(listener)
    listener.addEventListener('message', (e) => {
      const data = (e as MessageEvent).data
      if (data?.type === 'edge-drag-release') heard.push(data)
    })

    const { result } = renderHook(() => useEdgeDragSource())
    act(() => result.current.armEdgeDrag(paletteDrag()))
    pointer('pointermove', 1100, 430)
    pointer('pointercancel', 0, 0)
    await settle()

    expect(heard).toEqual([
      { type: 'edge-drag-release', screen: { x: 1100, y: 430 }, record: TEMPLATE_7 },
    ])
  })

  it('posts nothing for a drag that ended inside the window', async () => {
    const heard: unknown[] = []
    const listener = openPeer()
    peers.push(listener)
    listener.addEventListener('message', (e) => heard.push((e as MessageEvent).data))

    const { result } = renderHook(() => useEdgeDragSource())
    act(() => result.current.armEdgeDrag(paletteDrag()))
    pointer('pointermove', 400)
    // dnd-kit's own drop runs `onDragEnd` → `clearDrag` → this, before the pointer is released.
    act(() => result.current.disarmEdgeDrag())
    pointer('pointerup', 400)
    await settle()

    expect(heard.filter((m) => (m as { type?: string })?.type === 'edge-drag-release')).toEqual([])
  })

  it('arms nothing for a drag that carries no record', () => {
    const { result } = renderHook(() => useEdgeDragSource())
    act(() => result.current.armEdgeDrag(slotDrag()))
    pointer('pointermove', 1100)

    expect(handWs.pickedUp).toEqual([])
  })

  it('will not re-arm over a handed-off gesture still owed its release', () => {
    const { result } = renderHook(() => useEdgeDragSource())
    act(() => result.current.armEdgeDrag(paletteDrag(7)))
    pointer('pointermove', 1100)
    expect(result.current.edgeDragHandedOff()).toBe(true)

    // A second pointer starting a drag while the first is still down past the edge. Overwriting
    // here would strand the first record with no release ever posted.
    act(() => result.current.armEdgeDrag(paletteDrag(9)))
    pointer('pointermove', 1200, 400, 2)
    expect(handWs.pickedUp).toEqual([{ kind: 'TEMPLATE', id: 7 }])
  })
})

describe('an unsupported browser', () => {
  it('opens no channel and changes nothing', () => {
    const real = globalThis.BroadcastChannel
    // @ts-expect-error — standing in for a browser without the API.
    delete globalThis.BroadcastChannel
    closeDeskDragChannel()
    try {
      const { result } = renderHook(() => useEdgeDragSource())
      act(() => result.current.armEdgeDrag(paletteDrag()))
      pointer('pointermove', 1100)
      expect(handWs.pickedUp).toEqual([])
    } finally {
      globalThis.BroadcastChannel = real
      closeDeskDragChannel()
    }
  })
})

describe('the receiving window — claiming a posted release', () => {
  /** Stand in for the real hit test: jsdom has no `elementsFromPoint`. */
  function hitTestReturns(...elements: Element[]) {
    Object.defineProperty(document, 'elementsFromPoint', {
      value: () => elements,
      configurable: true,
    })
  }

  /** Long enough for the receiver to see the hand, find the band and click it. */
  const RESOLVE_MS = 80

  function band(attr = 'bank') {
    const element = document.createElement('button')
    element.setAttribute('data-hand-target', attr)
    document.body.append(element)
    return element
  }

  /** The hand holds the record the release names — the state a place needs. */
  function handHolds(kind = 'TEMPLATE', id = 7) {
    act(() => {
      handWs.fire({ kind, id, uuid: `${kind}-${id}`, holdId: 1 })
    })
  }

  async function post(message: unknown) {
    const source = new BroadcastChannel(DESK_DRAG_CHANNEL)
    source.postMessage(message)
    await settle(RESOLVE_MS)
    source.close()
  }

  const release = (screen = { x: 300, y: 500 }): EdgeDragRelease => ({
    type: 'edge-drag-release',
    screen,
    record: TEMPLATE_7,
  })

  it('places by clicking the hand target under the point', async () => {
    const placed = vi.fn()
    const target = band()
    target.addEventListener('click', placed)
    hitTestReturns(target)
    renderHook(() => useEdgeDragReceiver())
    handHolds()

    await post(release())

    expect(placed).toHaveBeenCalledTimes(1)
  })

  it('waits for the hand to hold the record the release names', async () => {
    // The release travels over a LOCAL channel and the hand over the WebSocket, so the release can
    // outrun its own `hand.state` frame. Acting immediately would find no band rendered at all.
    const placed = vi.fn()
    const target = band()
    target.addEventListener('click', placed)
    hitTestReturns(target)
    renderHook(() => useEdgeDragReceiver())

    const source = new BroadcastChannel(DESK_DRAG_CHANNEL)
    source.postMessage(release())
    await settle(40)
    expect(placed).not.toHaveBeenCalled()

    handHolds()
    await settle(RESOLVE_MS)
    source.close()

    expect(placed).toHaveBeenCalledTimes(1)
  })

  it('refuses to place a record the release did not name', async () => {
    // The hand already held something else from an earlier, unplaced pick-up. Clicking the band
    // drawn for THAT record would place the wrong thing and leave the dragged one in the hand.
    const placed = vi.fn()
    const target = band()
    target.addEventListener('click', placed)
    hitTestReturns(target)
    renderHook(() => useEdgeDragReceiver())
    handHolds('LOOK', 4)

    await post(release())

    expect(placed).not.toHaveBeenCalled()
    expect(handWs.dropped).toEqual([])
  })

  it('refuses to place a record the hand moved on to WHILE it was resolving', async () => {
    // The band is the same DOM node across renders, so its `onClick` closure is swapped in place
    // when the hand changes — clicking the node found a moment ago would place whatever is held
    // *now*. The window is the poll for the band: another window, or a MIDI `pickUpPad`, can move
    // the hand while this one is still waiting for its target to render.
    const placed = vi.fn()
    const target = band()
    target.addEventListener('click', placed)
    let visible = false
    Object.defineProperty(document, 'elementsFromPoint', {
      value: () => (visible ? [target] : []),
      configurable: true,
    })
    renderHook(() => useEdgeDragReceiver())
    handHolds('TEMPLATE', 7)

    const source = new BroadcastChannel(DESK_DRAG_CHANNEL)
    source.postMessage(release())
    await settle(40)
    // Someone else picks something up, and only then does this window's band appear.
    handHolds('TEMPLATE', 99)
    visible = true
    await settle(RESOLVE_MS)
    source.close()

    expect(placed).not.toHaveBeenCalled()
    expect(handWs.dropped).toEqual([])
  })

  it('a release over nothing leaves the record in the hand', async () => {
    const placed = vi.fn()
    const plain = document.createElement('div')
    plain.addEventListener('click', placed)
    document.body.append(plain)
    hitTestReturns(plain)
    renderHook(() => useEdgeDragReceiver())
    handHolds()

    await post(release())
    await settle(1100)

    expect(placed).not.toHaveBeenCalled()
    // The load-bearing half: nothing let go. A test asserting only "no mutation ran" would pass
    // just as happily with the record silently dropped, which is the failure this gesture's whole
    // safety property is about.
    expect(handWs.dropped).toEqual([])
  })

  it('does not claim a release that landed outside this window', async () => {
    const placed = vi.fn()
    const target = band()
    target.addEventListener('click', placed)
    hitTestReturns(target)
    renderHook(() => useEdgeDragReceiver())
    handHolds()

    await post(release({ x: 5000, y: 500 }))

    expect(placed).not.toHaveBeenCalled()
  })

  it('does not click a disabled target — the platform refuses it, so nothing here need check', async () => {
    const placed = vi.fn()
    const tile = band('slot')
    ;(tile as HTMLButtonElement).disabled = true
    tile.addEventListener('click', placed)
    hitTestReturns(tile)
    renderHook(() => useEdgeDragReceiver())
    handHolds()

    await post(release())

    expect(placed).not.toHaveBeenCalled()
  })

  it('ignores a message that is not a release — the channel carries four', async () => {
    const placed = vi.fn()
    const target = band()
    target.addEventListener('click', placed)
    hitTestReturns(target)
    renderHook(() => useEdgeDragReceiver())
    handHolds()

    await post({ type: 'edge-drag-hello' })

    expect(placed).not.toHaveBeenCalled()
  })

  it('never claims its own window’s release', async () => {
    const placed = vi.fn()
    const target = band()
    target.addEventListener('click', placed)
    hitTestReturns(target)
    peers.push(await withPeer())
    handHolds()

    // Source and receiver together, as `DeskDndProvider` mounts them — which is what makes them
    // share the page's **one** channel object. Two objects here and this window would hit-test its
    // own release and place the record on itself, and the gesture would never leave the screen.
    const { result } = renderHook(() => {
      useEdgeDragReceiver()
      return useEdgeDragSource()
    })
    act(() => result.current.armEdgeDrag(paletteDrag()))
    pointer('pointermove', 1100)
    pointer('pointerup', 1150)
    await settle(RESOLVE_MS)

    expect(placed).not.toHaveBeenCalled()
  })
})
