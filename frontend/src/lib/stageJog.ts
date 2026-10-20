import type { AimBase, AimNear, AimResponse } from '../store/programmerOps'

/**
 * The stage-relative jog's client half (`POST /programmer/aim`, `mode: JOG`): turning a drag into
 * requests. The desk does every solve; this only decides what to send and when.
 */

/** Metres of stage per pixel of drag — the prototype's feel, whatever the pad's size. */
export const JOG_METRES_PER_PX = 0.035

/** An arrow key's nudge, in metres. */
export const JOG_NUDGE_M = 0.25

/** Metres a second a drag held at the pad's edge carries on travelling. */
export const JOG_EDGE_SPEED_M_S = 1.5

/** How much faster it travels for each pixel further past the edge. */
export const JOG_EDGE_SPEED_PER_PX = 0.05

/** The fastest a held edge travels, metres a second. */
export const JOG_EDGE_SPEED_MAX_M_S = 5

/** The furthest one gesture moves the landing points on either axis — the desk's farthest landing. */
export const JOG_MAX_OFFSET_M = 40

/** The edge's speed for a pointer [overshootPx] past it. */
export function edgeSpeed(overshootPx: number): number {
  return Math.min(JOG_EDGE_SPEED_MAX_M_S, JOG_EDGE_SPEED_M_S + overshootPx * JOG_EDGE_SPEED_PER_PX)
}

/**
 * **One drag of the jog pad as an offset in stage metres.** Inside the pad a drag is a
 * displacement, [JOG_METRES_PER_PX] a pixel; held at or past an edge it is also a rate, the offset
 * travelling on towards that edge at [edgeSpeed] for as long as it is held, so one gesture can cross
 * the whole stage. What the edge carried is kept when the pointer comes back inside.
 */
export class JogDrag {
  private x = 0
  private y = 0
  private carryX = 0
  private carryY = 0

  constructor(private readonly half: number) {}

  /** The pointer's offset from where the drag began, in pixels (+y down the screen). */
  point(x: number, y: number): void {
    this.x = x
    this.y = y
  }

  /** Whether the pointer is at or past an edge, where [advance] moves the offset. */
  get atEdge(): boolean {
    return Math.abs(this.x) >= this.half || Math.abs(this.y) >= this.half
  }

  /** Carry the offset on at the edge for [seconds]; whether it moved. */
  advance(seconds: number): boolean {
    const before = this.offset()
    this.carryX = this.carry(this.carryX, this.x, seconds)
    this.carryY = this.carry(this.carryY, -this.y, seconds)
    const after = this.offset()
    return after.dx !== before.dx || after.dy !== before.dy
  }

  /** The whole offset in stage metres: +dx towards SL, +dy upstage. */
  offset(): { dx: number; dy: number } {
    return {
      dx: clampOffset(this.inside(this.x) + this.carryX),
      dy: clampOffset(this.inside(-this.y) + this.carryY),
    }
  }

  private inside(px: number): number {
    return Math.max(-this.half, Math.min(this.half, px)) * JOG_METRES_PER_PX
  }

  private carry(carry: number, px: number, seconds: number): number {
    const over = Math.abs(px) - this.half
    if (over < 0 || seconds <= 0) return carry
    const next = carry + Math.sign(px) * edgeSpeed(over) * seconds
    const inside = this.inside(px)
    return Math.max(-JOG_MAX_OFFSET_M - inside, Math.min(JOG_MAX_OFFSET_M - inside, next))
  }
}

function clampOffset(metres: number): number {
  return Math.max(-JOG_MAX_OFFSET_M, Math.min(JOG_MAX_OFFSET_M, metres))
}

/** The fewest milliseconds between two jog requests: every one is a programmer write broadcast to every window. */
export const JOG_FLOOR_MS = 50

/** What one request of a jog carries beyond the host's own fields. */
export interface JogSend {
  dx: number
  dy: number
  base?: AimBase[]
  near?: AimNear[]
}

interface JogGesture {
  /** Where each head started, by key: every answer's `base` for a head not yet in it, sent back with every later request. */
  anchor: Map<string, AimBase>
  /** Each head's last answer, so its solve stays on the side it is on — kept through a frame it was skipped in. */
  near: Map<string, AimNear>
  /** The latest offset not yet sent. */
  pending: { dx: number; dy: number } | null
}

const newGesture = (): JogGesture => ({ anchor: new Map(), near: new Map(), pending: null })

/**
 * One jog request in flight in this window, from every pad: a new mount's first request must not
 * read the heads before an old mount's last frame has written them.
 */
let inFlight = false
let lastSentAt = -Infinity
const waiting = new Set<JogQueue>()

function release(): void {
  inFlight = false
  const queues = [...waiting]
  waiting.clear()
  for (const queue of queues) queue.pump()
}

/**
 * **One request in flight, the latest offset wins.** A drag reports its whole offset from where it
 * began on every move; while a request is out, moves only replace the pending offset, and the
 * newest is sent when the answer lands, no sooner than [floorMs] after the last. So a slow desk sees
 * fewer, later requests rather than a backlog, and the offset a drag was let go on always lands —
 * a new gesture leaves the old one to finish first, as Spread's fan does.
 *
 * Every request of a gesture carries the gesture's anchor, each head's start as the desk first
 * answered it, so the drag is always measured from its start and errors never build up; a head that
 * joins mid-gesture is anchored by the first answer that names it. The in-flight rule spans
 * gestures and pads, which is what lets the desk (or [bases], for a draft host) read where the last
 * one left the heads.
 */
export class JogQueue {
  private gesture: JogGesture = newGesture()
  private readonly finishing: JogGesture[] = []
  private timer: ReturnType<typeof setTimeout> | null = null

  constructor(
    private readonly send: (step: JogSend) => Promise<AimResponse>,
    /** The heads' starting rows, for a host whose rows are a draft; empty lets the desk read them. */
    private readonly bases: () => AimBase[],
    private readonly onAnswer: (answer: AimResponse) => void,
    private readonly floorMs: number = JOG_FLOOR_MS,
  ) {}

  /** Start a new gesture: the next request reads where the heads are again, once the last one has landed. */
  begin(): void {
    if (this.gesture.pending != null) this.finishing.push(this.gesture)
    this.gesture = newGesture()
  }

  /** The gesture's whole offset from its start, in stage metres. */
  move(dx: number, dy: number): void {
    this.gesture.pending = { dx, dy }
    this.pump()
  }

  /** Send the next pending offset, if the desk is free and the floor allows. */
  pump(): void {
    if (this.timer != null) return
    if (inFlight) {
      waiting.add(this)
      return
    }
    const wait = lastSentAt + this.floorMs - performance.now()
    if (wait > 0) {
      this.timer = setTimeout(() => {
        this.timer = null
        this.pump()
      }, wait)
      return
    }
    while (this.finishing.length > 0 && this.finishing[0].pending == null) this.finishing.shift()
    const gesture = this.finishing[0] ?? this.gesture
    const step = gesture.pending
    if (step == null) return
    gesture.pending = null
    const body: JogSend = { ...step }
    if (gesture.anchor.size > 0) body.base = [...gesture.anchor.values()]
    else {
      const rows = this.bases()
      if (rows.length > 0) body.base = rows
    }
    if (gesture.near.size > 0) body.near = [...gesture.near.values()]
    inFlight = true
    lastSentAt = performance.now()
    this.send(body)
      .then((answer) => {
        for (const base of answer.base ?? []) {
          if (!gesture.anchor.has(base.target.key)) gesture.anchor.set(base.target.key, base)
        }
        for (const { target, panDeg, tiltDeg } of answer.written ?? []) gesture.near.set(target.key, { target, panDeg, tiltDeg })
        this.onAnswer(answer)
      })
      // The refusal is toasted by `errorToastMiddleware`; the next offset is tried as it would have been.
      .catch(() => undefined)
      .finally(() => {
        release()
        this.pump()
      })
  }
}

/** Test seam — the in-flight rule is module state shared by every queue. */
export function resetJogQueues(): void {
  inFlight = false
  lastSentAt = -Infinity
  waiting.clear()
}
