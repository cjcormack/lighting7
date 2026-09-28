import type { BuskPadKind } from '@/api/buskApi'
import type { BuskPaletteDragData } from '@/components/busking/buskDnd'

/**
 * **The same-machine edge drag** — the pure half (multi-screen plan §3.5's last paragraph, §5
 * session 4).
 *
 * A record dragged off the right edge of one screen arrives on the screen to its right. The whole
 * difficulty is that it cannot arrive as a drag: the OS delivers a held button's moves to the
 * window that saw the press, so the neighbour never receives a pointer event of its own. So the
 * gesture is cut in two at the window boundary — the source window hands the record into the
 * **hand** (`hand.pickUp`) and cancels its own drag, and posts only the release point on a
 * `BroadcastChannel`. Whichever window is under that point claims it by hit-testing itself.
 *
 * **The hand is what makes it safe.** A release no window claims is not an error and not a loss:
 * the record is simply still in the hand, and every `HandPlaceStrip` on every window is already lit.
 * That is why the hand-off is a pick-up rather than a transfer, and why nothing here needs to know
 * *which* screen is to the right.
 *
 * Everything in this file is a function of its arguments, so both halves of the gesture are
 * testable without two windows — which is the plan's own reason for the file existing. The impure
 * halves (the channel, the presence handshake, the listeners, the cancel) are `useEdgeDrag.ts`'s.
 *
 * ### The `window-management` permission is **not** required, and there is no gate on it
 *
 * The plan's session-5 bullet gates the gesture on `'getScreenDetails' in window` *and* a granted
 * `window-management` permission. That is stricter than the APIs it actually uses, and the gate is
 * therefore not built:
 *
 * - The bounds test reads `window.screenX / screenY / outerWidth / outerHeight` and the pointer's
 *   own `screenX / screenY`. All six have always reported coordinates in the **virtual desktop**
 *   space — a window on a left-hand monitor has a negative `screenX` on Windows — and none of them
 *   is permissioned.
 * - What `getScreenDetails` adds is *enumeration* of the other screens: their bounds, labels and
 *   scale. The hand-off needs none of it, because the channel is a broadcast and the neighbour
 *   claims the release by hit-testing rather than by being addressed.
 *
 * So the only feature detection is `BroadcastChannel`, which incidentally makes the gesture work in
 * Safari too. Where it is missing the gesture is quiet (D13) and the hand — picked up from a library
 * row or a pad's hold menu, placed on a lit band — is the route.
 *
 * ### What the channel does **not** reach, and why that is not a trap
 *
 * `BroadcastChannel` is same origin **and one browser instance and profile**. So it is the two desk
 * screens, and it is deliberately never the iPad — but it is also not a Chrome window beside a
 * Safari one, not two profiles, and not one window at `localhost` beside one at the LAN name. In
 * every one of those the message would simply never arrive.
 *
 * That would be a bad way to fail — a drag that vanishes with no explanation — so the gesture does
 * not rely on the release finding someone. `useEdgeDrag.ts` runs a **presence handshake** over the
 * same channel and arms the hand-off only while another window is actually listening. With no peer
 * the drag behaves exactly as it did before this session existed, which is also what keeps an
 * ordinary overshoot of a windowed browser's edge from hijacking a same-window drop.
 */

/** A point in the virtual desktop's coordinate space, as `MouseEvent.screenX / screenY` report it. */
export interface ScreenPoint {
  x: number
  y: number
}

/** A point in a window's own viewport, as `clientX / clientY` report it. */
export interface ClientPoint {
  x: number
  y: number
}

/**
 * Where a window is and how big it is, in both frames.
 *
 * Taken as data rather than read from `window` inside the two functions below, so a test can state
 * a two-monitor desktop — including the left-hand monitor's negative coordinates — without a second
 * browsing context.
 */
export interface WindowFrame {
  screenX: number
  screenY: number
  outerWidth: number
  outerHeight: number
  innerWidth: number
  innerHeight: number
}

export function readWindowFrame(w: Window = window): WindowFrame {
  return {
    screenX: w.screenX,
    screenY: w.screenY,
    outerWidth: w.outerWidth,
    outerHeight: w.outerHeight,
    innerWidth: w.innerWidth,
    innerHeight: w.innerHeight,
  }
}

export type EdgeSide = 'left' | 'right'

/**
 * Which edge the pointer has left this window by, or null while it is still inside.
 *
 * **Horizontal only, deliberately.** The gesture is "the screen to its right" (or its left); a
 * pointer dragged off the top or the bottom of a window is over the OS's own furniture or another
 * window of some other app, and handing the record away there would be a hand-off the operator did
 * not ask for. Staying inside vertically is not required either — a pointer past the right edge is
 * past it whatever its `y` is, which is what makes a drag out of a window's top-right corner work.
 *
 * The arithmetic is plain subtraction on purpose: a window at the virtual desktop's origin sits at
 * `screenX: 0`, and the monitor to its **left** gives every point on it a negative `screenX`. That
 * falls out rather than needing a case.
 */
export function edgeSideFor(point: ScreenPoint, frame: WindowFrame): EdgeSide | null {
  if (point.x < frame.screenX) return 'left'
  if (point.x >= frame.screenX + frame.outerWidth) return 'right'
  return null
}

/**
 * A posted screen point, read in this window's own viewport coordinates.
 *
 * **This is a heuristic, and there is no exact API for it.** `window.screenX / screenY` name the
 * top-left of the *outer* window, chrome included; nothing reports the viewport's own origin in
 * screen coordinates. The two terms below are the standard reading of the difference:
 *
 * - vertical: `outerHeight - innerHeight` — every pixel of chrome, assumed to be at the top. Exact
 *   on Chrome and Edge on Windows, which is the desk; a browser with a bottom bar would push the
 *   point up by that bar's height.
 * - horizontal: half of `outerWidth - innerWidth` — symmetric side borders, which is **0** on every
 *   current desktop browser. Clamped at 0 so a window reporting `innerWidth > outerWidth` (a
 *   side-docked devtools panel does this) cannot push the point left.
 *
 * A few pixels out is affordable here and nowhere else in this gesture: what the point is tested
 * against are `HandPlaceStrip` bands and cue-slot tiles, the smallest of which is 28px tall.
 *
 * **Safari on the Mac is a first-class desk browser (D13) and this has not been measured there.**
 * Both terms are arithmetic over values Safari reports like any browser, so it should hold; what is
 * unverified is the "all chrome at the top" assumption under a compact or hidden toolbar. It fails
 * safe — a point a few pixels out misses the band, nobody claims the release, and the record stays
 * in the hand — so it is a miss rather than a wrong placement. If it ever reads wrong on the rig,
 * the fix is to cache `event.screenX - event.clientX` from any real pointer event the receiving
 * window has seen and prefer that measured origin, **not** to reach for `getScreenDetails`, which
 * answers a different question (see this module's docblock).
 */
export function screenToClient(point: ScreenPoint, frame: WindowFrame): ClientPoint {
  const chromeY = frame.outerHeight - frame.innerHeight
  const chromeX = Math.max(0, (frame.outerWidth - frame.innerWidth) / 2)
  return { x: point.x - frame.screenX - chromeX, y: point.y - frame.screenY - chromeY }
}

/** What `hand.pickUp` takes — the record a drag is carrying, as the desk addresses it. */
export interface EdgeDragRef {
  kind: BuskPadKind
  id: number
}

/**
 * The record a lifted drag carries, or null for a drag that carries none.
 *
 * (Unrelated: `CueSlotOverviewPanel` has its own, older "edge drag" — paging the cue-slot overlay
 * when the pointer nears *that panel's* edge. Same words, different edge, no shared state.)
 *
 * **Only a library palette row.** That is the drag the plan describes — a record leaving the
 * library — and it is also the only one whose `data.current` names a record at all: a `busk-pad`
 * drag carries a `PadFace` and a position, which is a rearrangement of the page rather than a
 * record, and a `slot-item` drag is a rearrangement of the cue-slot overlay. Widening this would
 * mean putting an id on the pad's drag data, which is a change to the busk page's own contract and
 * not this session's.
 *
 * `unknown` in, for `slotDrop.ts`'s reason: dnd-kit's `data.current` is untyped at the boundary,
 * and the import above is type-only so the app shell still reaches no busk runtime code.
 */
export function edgeDragRef(activeData: unknown): EdgeDragRef | null {
  const drag = activeData as BuskPaletteDragData | undefined
  if (drag?.type !== 'busk-palette') return null
  const record = drag.record
  if (record.kind === 'TEMPLATE') return { kind: 'TEMPLATE', id: record.template.id }
  if (record.kind === 'LOOK') return { kind: 'LOOK', id: record.look.id }
  return { kind: 'CUE', id: record.cue.id }
}

/**
 * The hand target under a point in this window, or null.
 *
 * `data-hand-target` is the registration, and `HandTarget.tsx`'s docblock is where it is stated:
 * the hand's places are **buttons, not `useDroppable` sites**, so there is nothing of dnd-kit's to
 * ask — and dnd-kit gives no way back from a DOM element to a droppable even if there were, so a
 * droppable would not have helped here either.
 *
 * **The topmost hit only, then a `closest` walk up from it** — which is exactly what a real click
 * does, and the walk is what makes the band's own `<span>` or glyph resolve to the band. It
 * deliberately does **not** keep searching down the `elementsFromPoint` stack: a `data-hand-target`
 * covered by an open dialog or sheet is still *mounted*, so a stack walk would burrow past the
 * overlay and place a record on a target the operator could not see, where a real click there would
 * have hit the overlay and done nothing. Browsers already omit `pointer-events: none` elements from
 * the list, so the topmost entry is the one a click would reach.
 */
export function handTargetAt(point: ClientPoint, doc: Document = document): HTMLElement | null {
  if (typeof doc.elementsFromPoint !== 'function') return null
  const [topmost] = doc.elementsFromPoint(point.x, point.y)
  if (!(topmost instanceof Element)) return null
  const target = topmost.closest('[data-hand-target]')
  return target instanceof HTMLElement ? target : null
}

/**
 * Is a posted point inside this window's viewport at all?
 *
 * `elementsFromPoint` already answers `[]` for a point outside it, so this is not what makes a
 * foreign release safe — it is stated explicitly because it is the *rule* (a window claims only
 * what lands on it) and because a rule resting on one browser API's edge behaviour is a rule no
 * test can see.
 */
export function pointInViewport(point: ClientPoint, frame: WindowFrame): boolean {
  return (
    point.x >= 0 && point.y >= 0 && point.x < frame.innerWidth && point.y < frame.innerHeight
  )
}

/**
 * The channel's name and its one message.
 *
 * Same origin, same profile — which is exactly the two desk screens, and deliberately never the
 * iPad. **One message kind, the release**: the receiving window's chip and every eligible band are
 * already drawn the moment the hand fills, because session 3 built all of that, so a ghost that
 * *follows* the posted point would be new UI on top of an affordance that is already there. If the
 * bands turn out to read poorly on the rig, a `move` message and a frozen, hookless ghost are the
 * additive second half — hookless for `dragOverlayRegistry`'s stated reason.
 */
export const DESK_DRAG_CHANNEL = 'desk-drag'

/**
 * **Three messages, and each answers a way the naive version got it wrong.**
 *
 * - `hello` / `here` are **presence**, posted when the channel opens and again at a boundary
 *   crossing that finds presence stale — never at drag start, so a drag that stays inside this
 *   window puts nothing on the channel at all. Without them the bounds test alone decides, and a
 *   bounds test cannot tell "the pointer crossed onto the next screen" from "the pointer overshot
 *   the edge of a windowed browser on a laptop with nothing beside it" — so an ordinary palette
 *   drag that overshot was cancelled and pushed into the hand. A window announces itself on
 *   `hello` and every crossing re-probes, so the hand-off arms **only while another window of this
 *   browser profile is actually listening**. That is also what makes a Chrome-plus-Safari pairing
 *   degrade to an ordinary in-window drag rather than to a drag that silently vanishes.
 * - `release` **names the record**, not just the point. The channel is local and instant while the
 *   hand arrives over the WebSocket, so a release can outrun its own `hand.state` frame: the
 *   receiver would find no band yet, or — worse — find the band for whatever was *already* in the
 *   hand and place that instead. Naming the record lets the receiver wait for the matching hand and
 *   refuse anything else.
 *
 * ### What is deliberately **not** here: arbitration between two claimants
 *
 * Two windows whose viewports both contain the release point would each find a target and each
 * place the record. A claim message with a lowest-id tie-break was built for that and then removed,
 * because it bought less than it cost:
 *
 * - **It cannot happen on the desk this ships to.** The release is one point in virtual-desktop
 *   space, so both windows would have to contain it — and two windows tiled on two monitors never
 *   overlap. It needs windows stacked on one screen.
 * - **It was a mitigation, not a guarantee.** A claimant can only wait so long before clicking, so
 *   two windows whose target discovery differed by more than that settle window both placed anyway
 *   — which is exactly the timing skew (differing render latency, differing `hand.state` arrival)
 *   that the case is about.
 * - It was the source of a real double-place bug of its own, found in review.
 *
 * So the honest position: **overlapping windows place twice**, both places are ordinary mutations
 * the operator sees toasts for, and the bank place carries Undo. If that ever matters, the fix is
 * arbitration that *waits for an acknowledgement* rather than for a timeout — not the tie-break
 * that was here.
 */
export interface EdgeDragRelease {
  type: 'edge-drag-release'
  /** The release point, in virtual-desktop coordinates. */
  screen: ScreenPoint
  /** The record handed into the hand for this gesture — what the receiver must wait to see held. */
  record: EdgeDragRef
}

/** "Is anyone else there?" — see the note above for when it is posted. */
export interface EdgeDragHello {
  type: 'edge-drag-hello'
}

/** "I am." */
export interface EdgeDragHere {
  type: 'edge-drag-here'
}

function isRef(value: unknown): value is EdgeDragRef {
  const r = value as EdgeDragRef | undefined
  return (
    (r?.kind === 'TEMPLATE' || r?.kind === 'LOOK' || r?.kind === 'CUE') && typeof r.id === 'number'
  )
}

export function isEdgeDragRelease(message: unknown): message is EdgeDragRelease {
  const m = message as EdgeDragRelease | undefined
  return (
    m?.type === 'edge-drag-release' &&
    typeof m.screen?.x === 'number' &&
    typeof m.screen?.y === 'number' &&
    isRef(m.record)
  )
}
