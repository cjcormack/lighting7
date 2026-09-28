import { useCallback, useEffect, useRef } from 'react'
import type { Active } from '@dnd-kit/core'
import { handHolds, handPickUp, whenHandHolds } from '@/store/hand'
import {
  DESK_DRAG_CHANNEL,
  edgeDragRef,
  edgeSideFor,
  handTargetAt,
  isEdgeDragRelease,
  pointInViewport,
  readWindowFrame,
  screenToClient,
  type EdgeDragHello,
  type EdgeDragHere,
  type EdgeDragRef,
  type EdgeDragRelease,
  type ScreenPoint,
} from './edgeDrag'

/**
 * The **impure half** of the same-machine edge drag — the channel, the listeners, and the cancel.
 * `edgeDrag.ts` is the rule; this is the wiring, and its docblock is the one to read first.
 *
 * Both hooks are called from `DeskDndProvider`, which is the app's one drag context and is mounted
 * once in `Layout.tsx`. That is not incidental: **one page gets exactly one `BroadcastChannel`
 * object**, and it is why the source window never claims its own release. A `BroadcastChannel`
 * delivers to every other channel object of its name *including ones in the same page*, and only
 * never to the object that posted — so two objects here would make the sending window hit-test its
 * own release point, place the record on itself, and the gesture would never leave the screen.
 *
 * ### The channel reaches one browser profile, and nothing else
 *
 * Same origin **and** same browser instance and profile. That is the two desk screens, and it is
 * deliberately never the iPad. It is also **not** a Chrome window beside a Safari window, nor two
 * different profiles, nor one window at `localhost` and one at the LAN name: each of those gets a
 * channel object of its own that no message ever crosses between. The presence handshake below is
 * what keeps that from being a trap — with no peer listening the gesture never arms at all, so a
 * mismatched pair behaves exactly like today rather than like a drag that silently vanishes.
 */

/** How long a `here` keeps a peer counted. Every drag start re-probes, so this only has to outlive one drag. */
export const PEER_TTL_MS = 10_000

/** How long a receiving window will wait for the hand to fill and the band to render. */
export const RELEASE_WAIT_MS = 1_000

/** How often the receiver re-asks "is the hand right and is there a target yet". */
const RELEASE_POLL_MS = 25

/** The floor between two `hello`s, so a pointer parked past the edge cannot become a probe loop. */
const PROBE_THROTTLE_MS = 400

let channel: BroadcastChannel | null = null
let channelChecked = false
let peerSeenAt = 0
let receiverAttached = false
let lastProbeAt = 0

/**
 * The page's one channel, or null where the browser has none.
 *
 * `BroadcastChannel` is the **only** feature detection this gesture makes — see `edgeDrag.ts` for
 * why the plan's `window-management` gate is not built. Where it is missing nothing changes and
 * the hand is the route.
 *
 * Creating it also **opens the presence handshake**: a `hello` goes out, every `hello` is answered
 * with a `here`, and either one records a peer. That listener is the channel's own rather than a
 * hook's, so presence is live from the moment anything touches the channel and does not depend on
 * which hook mounted first.
 */
export function deskDragChannel(): BroadcastChannel | null {
  if (channelChecked) return channel
  channelChecked = true
  if (typeof BroadcastChannel === 'undefined') return null
  const bus = new BroadcastChannel(DESK_DRAG_CHANNEL)
  bus.addEventListener('message', (event: MessageEvent) => {
    const message = event.data as { type?: string } | null
    if (message?.type === 'edge-drag-hello') {
      peerSeenAt = Date.now()
      bus.postMessage({ type: 'edge-drag-here' } satisfies EdgeDragHere)
      return
    }
    if (message?.type === 'edge-drag-here') peerSeenAt = Date.now()
  })
  channel = bus
  bus.postMessage({ type: 'edge-drag-hello' } satisfies EdgeDragHello)
  return channel
}

/**
 * Ask again who is there.
 *
 * **Called at the boundary, never at drag start.** A drag that stays inside this window must put
 * *nothing* on the channel — it behaves exactly as it did before this session existed — so the
 * probe waits until the pointer has actually crossed, and the crossing that provokes it is refused
 * for that one frame. The answer arrives as its own task, well inside the ~8–16ms to the next
 * `pointermove`, so the cost is a frame of latency on the first crossing and nothing at all on
 * every drag that never leaves.
 *
 * Throttled because a pointer parked past the edge of a window with no neighbour keeps producing
 * moves, and each would otherwise post again.
 */
function probePeers(now: number = Date.now()): void {
  if (now - lastProbeAt < PROBE_THROTTLE_MS) return
  lastProbeAt = now
  deskDragChannel()?.postMessage({ type: 'edge-drag-hello' } satisfies EdgeDragHello)
}

/**
 * Is another window of this browser profile listening?
 *
 * **This is the difference between a feature and a regression.** The bounds test alone cannot tell
 * "the pointer crossed onto the next screen" from "the pointer overshot the edge of a windowed
 * browser with nothing beside it" — and the horizontal chrome offset is 0 on every current desktop
 * browser, so the window's outer edge *is* the visible content edge and an ordinary drag toward a
 * cue slot near that edge overshoots it easily. Without this, such a drag was cancelled and pushed
 * into the hand, and the operator watched their drag vanish. With it the hand-off arms only when
 * there is somewhere for the record to go.
 */
export function edgeDragPeerPresent(now: number = Date.now()): boolean {
  return peerSeenAt !== 0 && now - peerSeenAt < PEER_TTL_MS
}

/**
 * Close and forget the page's channel, and every fact learned over it.
 *
 * Exists for tests, which need each case to start from a channel nothing else is listening on, and
 * which need to be able to run the unsupported-browser case after a supported one. Nothing in the
 * app calls it: the channel is app-lifetime, like the socket.
 */
export function closeDeskDragChannel(): void {
  channel?.close()
  channel = null
  channelChecked = false
  peerSeenAt = 0
  receiverAttached = false
  lastProbeAt = 0
}

/**
 * End the live dnd-kit drag, by the same door a real cancelled pointer uses.
 *
 * **A synthetic `pointercancel`, not a synthetic Escape.** dnd-kit's `PointerSensor` binds
 * `pointercancel` on the owner document to the very same `handleCancel` as its Escape handler, so
 * this cancels just as surely — and it leaves the keyboard alone entirely. The Escape route worked,
 * but it put a keydown in front of `HandChip`'s Escape ladder and every other document-level Escape
 * listener on the page, and keeping it harmless rested on a one-shot capture listener marking the
 * event `defaultPrevented` *before* anything else read it — which is true of the window **node**
 * but not guaranteed of listener **order** at that node. `pointercancel` needs no such argument:
 * nothing else on the desk listens for it.
 *
 * Saying "the pointer was cancelled" is also simply more honest than saying "the operator pressed
 * Escape", which is what the gesture had to pretend before.
 *
 * The cancel runs through the provider's existing `onDragCancel`, which is what clears `isDragging`
 * and so keeps the cue-slot panel body mounted. Nothing here goes around it.
 */
export function cancelDragWithPointerCancel(doc: Document = document): void {
  doc.dispatchEvent(new Event('pointercancel', { bubbles: true }))
}

interface EdgeGesture {
  /** What was lifted, as the desk addresses it. */
  record: EdgeDragRef
  /** The physical pointer this gesture belongs to, latched at the first move. */
  pointerId: number | null
  /** Has this gesture already left the window? One pick-up per drag, however often it crosses. */
  handedOff: boolean
  /** Has a pick-up already been refused since the last time the pointer was inside? */
  refused: boolean
  /** True only for the instant this gesture is dispatching its own cancel — see `onPointerEnd`. */
  selfCancelling: boolean
  /** The last point a *move* reported — never a cancel's own, which may be stale or zero. */
  lastPoint: ScreenPoint | null
}

export interface EdgeDragSource {
  /** Call from `onDragStart`. A drag carrying no record arms nothing, and nothing is sent. */
  armEdgeDrag: (active: Active) => void
  /** Call from `onDragEnd` and `onDragCancel`. A handed-off gesture survives it — see below. */
  disarmEdgeDrag: () => void
  /** Has the live drag already been handed to the hand? Its drop must then not also be resolved. */
  edgeDragHandedOff: () => boolean
}

/**
 * The source window's half: watch the pointer, hand off at the boundary, post the release.
 *
 * **The pointer's screen position is read off the event, never reconstructed.** dnd-kit's
 * `onDragMove` gives a `delta` in CSS pixels plus the original activator event, and reconstructing
 * `screenX/screenY` from those goes wrong under browser zoom and a non-1 `devicePixelRatio` —
 * which is exactly the desk's likely setup. A `pointermove` on the **window** carries the real
 * thing, and the source window keeps receiving them past its own edge because the OS delivers a
 * held button's moves to the window that saw the press.
 *
 * **Arming sends nothing.** The peer question is asked at the boundary, not here, so an ordinary
 * drag that stays inside this window is exactly what it was before this session: no channel
 * traffic, no pick-up, no cancel.
 *
 * **The listeners are attached imperatively at arm time**, not by an effect keyed on a boolean.
 * An effect attaches one commit later, and a fast flick can cross the boundary inside that commit;
 * `armEdgeDrag` runs synchronously inside `onDragStart`, so attaching there closes the gap. (The
 * unmount effect below is the only safety net needed, because every other exit runs through
 * `release`.) `BuskSpeedRail`'s hold-to-slide watches the same three events for the same reason and
 * keeps the effect form, which suits it: it has no boundary to miss.
 *
 * **They outlive the drag on purpose.** The hand-off cancels the drag, so `onDragCancel` fires
 * while the operator is still holding the button — and the release, which is the whole payload, has
 * not happened yet. So the teardown is the **pointer's**, and `disarmEdgeDrag` stands down for a
 * gesture that has already handed off.
 *
 * Three things the handlers refuse, each of which was a real way to get this wrong:
 *
 * - **A pointer that is not this gesture's.** The id is latched at the first move and every later
 *   event is filtered on it, so a second finger on a touchscreen desk cannot move or end a drag the
 *   first one is making.
 * - **Our own synthetic cancel.** The hand-off dispatches a `pointercancel` to end the dnd-kit
 *   drag, which would otherwise arrive here as the operator releasing — and the release would then
 *   be posted from the boundary instead of from where the operator actually let go. Dispatch is
 *   synchronous, so a flag held across the call covers exactly that instant.
 * - **A second pick-up after a refusal.** A pick-up that never left the browser leaves the drag
 *   alone, so without this the next `pointermove` — and every one after it, at up to 120Hz — would
 *   try again for as long as the pointer sat past the edge. One attempt per crossing: come back
 *   inside and cross again to retry.
 *
 * **A release is posted whatever the point.** If the operator crosses back and lets go inside this
 * window, the record was handed to the hand at the crossing and the drag is already gone; the
 * posted point then lands in no other window, nobody claims it, and the record is simply still in
 * the hand — which is this gesture's safety property, not a corner case to special-case away.
 */
export function useEdgeDragSource(): EdgeDragSource {
  const gestureRef = useRef<EdgeGesture | null>(null)
  const detachRef = useRef<(() => void) | null>(null)

  const release = useCallback(() => {
    gestureRef.current = null
    detachRef.current?.()
    detachRef.current = null
  }, [])

  const armEdgeDrag = useCallback(
    (active: Active) => {
      if (deskDragChannel() == null) return
      // Refuse to re-arm over a gesture that has handed off and is still owed its release: that is
      // a second physical pointer starting a drag while the first is still down past the edge, and
      // overwriting here would strand the first gesture's record with no release ever posted.
      if (gestureRef.current?.handedOff) return
      const record = edgeDragRef(active.data.current)
      if (record == null) return

      release()
      const gesture: EdgeGesture = {
        record,
        pointerId: null,
        handedOff: false,
        refused: false,
        selfCancelling: false,
        lastPoint: null,
      }
      gestureRef.current = gesture

      const mine = (event: PointerEvent) =>
        gesture.pointerId == null || event.pointerId === gesture.pointerId

      const onPointerMove = (event: PointerEvent) => {
        if (gestureRef.current !== gesture || !mine(event)) return
        gesture.pointerId ??= event.pointerId
        const point = { x: event.screenX, y: event.screenY }
        gesture.lastPoint = point
        if (gesture.handedOff) return
        if (edgeSideFor(point, readWindowFrame()) == null) {
          // Back inside: the next crossing may try again.
          gesture.refused = false
          return
        }
        // Is anyone out there? Asked here rather than at drag start, so a drag that never leaves
        // this window puts nothing on the channel at all. A first crossing that finds presence
        // stale probes and waits a frame for the answer.
        if (!edgeDragPeerPresent()) {
          probePeers()
          return
        }
        if (gesture.refused) return
        // A pick-up that never left the browser is not a pick-up: `sendGesture` refuses a closed
        // socket and has already toasted, so the drag is left alone rather than being cancelled in
        // exchange for nothing. Marked handed off only once the frame is away.
        if (!handPickUp(gesture.record.kind, gesture.record.id)) {
          gesture.refused = true
          return
        }
        gesture.handedOff = true
        // The cancel below bubbles to the window listeners too, where it would read as the operator
        // letting go and post the release at the hand-off rather than at the real release. Dispatch
        // is synchronous, so a flag held across the call is exactly as wide as it needs to be.
        // (`isTrusted` distinguishes them in a browser but cannot be simulated in jsdom, so a test
        // could never cover a guard written that way.)
        gesture.selfCancelling = true
        try {
          cancelDragWithPointerCancel()
        } finally {
          gesture.selfCancelling = false
        }
      }

      const onPointerEnd = (event: PointerEvent) => {
        if (gestureRef.current !== gesture) return
        // Our own cancel, dispatched to end the dnd-kit drag — not the operator letting go.
        if (gesture.selfCancelling) return
        if (!mine(event)) return
        // A real `pointerup` carries the release point; a real `pointercancel`'s own coordinates are
        // not dependable (commonly zero or stale), so the last *move* is what is posted. That is
        // `BuskSpeedRail`'s rule for its own cancel, and for the same reason.
        const point = event.type === 'pointerup' ? { x: event.screenX, y: event.screenY } : null
        const screen = point ?? gesture.lastPoint
        const handedOff = gesture.handedOff
        const record = gesture.record
        release()
        if (!handedOff || screen == null) return
        deskDragChannel()?.postMessage({
          type: 'edge-drag-release',
          screen,
          record,
        } satisfies EdgeDragRelease)
      }

      window.addEventListener('pointermove', onPointerMove)
      window.addEventListener('pointerup', onPointerEnd)
      window.addEventListener('pointercancel', onPointerEnd)
      detachRef.current = () => {
        window.removeEventListener('pointermove', onPointerMove)
        window.removeEventListener('pointerup', onPointerEnd)
        window.removeEventListener('pointercancel', onPointerEnd)
      }
    },
    [release],
  )

  const disarmEdgeDrag = useCallback(() => {
    if (gestureRef.current?.handedOff) return
    release()
  }, [release])

  const edgeDragHandedOff = useCallback(() => gestureRef.current?.handedOff === true, [])

  useEffect(() => release, [release])

  return { armEdgeDrag, disarmEdgeDrag, edgeDragHandedOff }
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

/**
 * The receiving window's half: claim a posted release by hit-testing this window.
 *
 * **A posted release becomes a place by synthesising a click on the hit element**, which is the one
 * decision in this session with a real alternative. `HandPlaceStrip` is already a `<button>` whose
 * `onClick` runs its surface's own place — the right mutation, the right *where* string for the
 * toast, the right Undo, all held in a closure the shell cannot see — and `useHandOffer` has
 * already refused anything the target cannot take, which is why the band is drawn at all. The
 * empty cue-slot tile is the same shape: it carries `data-hand-target` only when it can take what
 * is held, and its `onClick` is the place. So a click reuses every one of those and states no rule
 * twice. The alternative — an element→callback registry — is a second statement of what the button
 * already is, kept in step by hand. `dispatchSyntheticContextMenu` in `hooks/useLongPress.ts` is
 * this codebase's precedent for synthesising the event a control already answers.
 *
 * Two properties come free with it and would each have had to be rebuilt: a `disabled` button
 * dispatches no click at all, so an ineligible cue-slot tile is inert by the platform's rule rather
 * than by a check here; and a target that has scrolled out of view is not at any point.
 *
 * ### Why the click is not immediate
 *
 * **The hand arrives over the WebSocket; the release arrives over a local channel.** The release
 * can therefore beat the `hand.state` frame for its own pick-up, and a click fired then would find
 * no band rendered at all — or, if something else was already in the hand, would find *that*
 * record's band and place the wrong record. So the release **names the record** and this waits for
 * the hand to hold it, then for the band to render, re-asking every {@link RELEASE_POLL_MS} until
 * {@link RELEASE_WAIT_MS} is up. One loop covers both races.
 *
 * There is deliberately **no arbitration between two claimants** — two windows overlapping at the
 * release point place twice. `edgeDrag.ts`'s message docblock is the record of why the tie-break
 * that was here was removed.
 *
 * **A release nothing claims does nothing** — no toast, no error, and above all no drop. The record
 * stays in the hand and every lit band is still lit, which is the whole reason the hand-off is a
 * pick-up rather than a transfer.
 */
export function useEdgeDragReceiver(): void {
  useEffect(() => {
    const bus = deskDragChannel()
    if (bus == null) return
    // One receiver per page. The invariant is `DeskDndProvider` being mounted once, but a second
    // mount (a dev double-mount, a future nested route) would click every release twice, which is
    // two places from one gesture — so it is refused here rather than assumed away.
    if (receiverAttached) return
    receiverAttached = true

    let live = true

    const claim = async (message: EdgeDragRelease) => {
      const frame = readWindowFrame()
      const point = screenToClient(message.screen, frame)
      // A window claims only what lands on it. `elementsFromPoint` would answer `[]` anyway, but a
      // rule that rests on one API's edge behaviour is a rule nothing can check.
      if (!pointInViewport(point, frame)) return

      const held = await whenHandHolds(message.record, RELEASE_WAIT_MS)
      if (!live || held == null) return

      const deadline = Date.now() + RELEASE_WAIT_MS
      let target = handTargetAt(point)
      while (target == null && Date.now() < deadline) {
        await sleep(RELEASE_POLL_MS)
        if (!live) return
        target = handTargetAt(point)
      }
      if (target == null) return

      // **Asked again, in the instant before the click.** Waiting for the hand is not enough: the
      // hand is shared and anything can move it while this window waits for the frame and polls for
      // its target — another window, a MIDI `pickUpPad`, a second pick-up by hand. And the
      // band is the **same DOM node** across renders, so React swaps its `onClick` closure in
      // place: clicking the element found a moment ago would place whatever is held *now*, under a
      // band that still looks eligible because it is. A release places the record it names or
      // nothing at all.
      if (!handHolds(message.record)) return

      target.click()
    }

    const onMessage = (event: MessageEvent) => {
      if (!isEdgeDragRelease(event.data)) return
      void claim(event.data)
    }
    bus.addEventListener('message', onMessage)
    return () => {
      live = false
      receiverAttached = false
      bus.removeEventListener('message', onMessage)
    }
  }, [])
}
