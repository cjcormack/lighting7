import { InternalApiConnection, InternalEventType } from './internalApi'
import { Subscription } from './subscription'
import { sendGesture } from './wsGesture'

// === Types ===

/**
 * Which way each head runs its cycle (effects plan D5): the desk applies it to every member's phase.
 * Forward is the absence on every wire shape.
 */
export type FxDirection = 'FORWARD' | 'BACKWARD' | 'BOUNCE'

/** An FX's Timing (terminology plan D22): one cycle for the whole FX, or one per head. */
export type Timing = 'CYCLE' | 'HEAD'

/**
 * One running effect, exactly as the backend's single `FxDto` (`fx/FxDto.kt`) reports
 * it — the `fxState` frame and `GET /api/rest/fx/active` now return the same shape, so this and
 * `ActiveFx` in `store/fixtureFx.ts` describe one wire type. Collapsing the two TS
 * declarations is frontend-sweep work; the fields below are the source of truth.
 *
 * Backend sweep item F8 changed three things here: `phase` is now `currentPhase`, `targetKey` is
 * the bare fixture/group key with `attribute` alongside it (it used to be the composite
 * `"key.attribute"`), and `fxType` is the registry id rather than the effect's display name.
 */
export interface ActiveFxState {
  id: number
  /** Registry id, the string an Update hands back — not the effect's display name. */
  fxType: string
  /** Fixture or group key, *without* the property suffix. */
  targetKey: string
  attribute: string
  cycle: number
  blendMode: string
  isRunning: boolean
  phaseOffset: number
  currentPhase: number
  parameters: Record<string, string>
  isGroupTarget: boolean
  order?: string | null
  elementMode?: string | null
  elementFilter?: string | null
  timing?: Timing
  /** The Look this effect came out of, when it came out of one. */
  lookId?: number | null
  /** The programmer layer that spawned it. Null for an effect the operator busked directly. */
  programmerLayerId?: number | null
  cueId: number | null
  cueStackId: number | null
  timingSource?: string
  /** True when the effect sits in the programmer's priority band. */
  programmerOwned?: boolean
  /** The envelope — see `ActiveFx.intensityMultiplier`. */
  intensityMultiplier?: number
  /** Speed master this effect subscribes to (null → master 1). */
  speedMasterUuid?: string | null
  /** Wall-clock rate master (null → unscaled); only WALL_CLOCK effects read it. */
  rateSpeedMasterUuid?: string | null
  /** D7's reading: follows no master. See `ActiveFx.seconds`. */
  seconds?: boolean
  /** Amount (effects plan D2), 0–1: how much reaches the stage over what is underneath. Absent at 1. */
  amount?: number
  /** Direction (D5): `FORWARD`, `BACKWARD` or `BOUNCE`. Absent when forward. */
  direction?: FxDirection
  /** Runs (D9): the cycles an N-shot runs; absent or null runs forever. */
  runs?: number | null
  /** The spread's shape (effects plan D4) — `lib/spreadShape.ts`; each absent at its default. */
  spread?: number
  wings?: number
  segments?: number
  parts?: number
  // `speedMasterIndex` and `rateSpeedMasterIndex` — the 1-based display positions the server
  // numbered the bank at — were declared beside the two uuids and never read. The FX-sheet chip
  // resolves both indices from the live master list, which is the only way they can't drift from a
  // bank the operator has since reordered. The server still sends them; leaving them undeclared is
  // what stops the next reader taking a mirror for a source.
}

/**
 * The active-effect list. Carries no tempo: it used to report master 1's bpm because it
 * predates the speed-master bank — tempo now lives on the `speedMasters.*` family, per
 * master (see `store/speedMasters.ts`).
 */
export interface FxState {
  activeFx: ActiveFxState[]
}

/**
 * An edit to a running effect: every field optional, absent = keep. The body of `PUT /fx/{id}` and
 * the `updateFx` frame both — the desk parses the two through one function
 * (`applyFxUpdate`), so this one declaration serves both doors.
 */
export interface UpdateFxRequest {
  fxType?: string
  parameters?: Record<string, string>
  cycle?: number
  blendMode?: string
  phaseOffset?: number
  order?: string
  elementMode?: string
  elementFilter?: string
  timing?: Timing
  /**
   * Reassign the effect's speed master (omitted = no change, like every other field). The
   * picker always sends a concrete uuid — master 1's uuid means "back to the default".
   */
  speedMasterUuid?: string
  /** Reassign the wall-clock rate master; omitted = no change, as above. */
  rateSpeedMasterUuid?: string
  /**
   * Move the effect between *Seconds* and a master (venue-visit plan D7); omitted = no change.
   * `true` puts it on Seconds — a beat effect's division read as seconds, no master; a wall-clock
   * effect's rate master dropped, the *unscaled* a null could never say (`FU-FX-RATE-MASTER-CLEAR`).
   * Refused beside a master. `false` puts it on the master sent beside it (a beat effect's absent
   * master is master 1; a wall-clock effect must name one).
   */
  seconds?: boolean
  /** Amount (effects plan D2), 0–1. Edited in place: the phase is kept. */
  amount?: number
  /** Direction (D5). Edited in place. */
  direction?: FxDirection
  /** Runs (D9), at least 1. A new count runs from the next pass. */
  runs?: number
  /** True puts an N-shot back to running forever; never beside [runs]. */
  runForever?: boolean
  /**
   * The spread's shape (effects plan D4); each absent keeps it, parts 0 turns them off. Edited in
   * place like the Order, phase kept; wings under a mirrored order are refused.
   */
  spread?: number
  wings?: number
  segments?: number
  parts?: number
  /**
   * The heads this edit is for (apply-model plan §3.4, D5): absent, or every head the effect paints,
   * is the in-place edit; some of them splits those off — only a Local effect's, and no change to
   * the spread's shape. Over REST the split answers `FxSplitResponse`; the frame answers only
   * `fxChanged` REMOVED and ADDED, so an editor that must follow the edited copy splits over REST.
   */
  targets?: { type: 'fixture' | 'group'; key: string }[]
}

/**
 * A refused `updateFx`, unicast to the socket that sent it (fixture-fx-sheets plan W3).
 * `FX_NOT_FOUND` for an effect that is not running, `FX_UPDATE_REFUSED` for a field the desk's
 * strict policy refuses — `PUT /fx/{id}`'s 404 and 400. Keyed by the effect, so a live drag that
 * keeps being refused can replace one toast rather than stack one per frame. A refused
 * `programmer.moveFx` is `FX_NOT_FOUND` or `FX_MOVE_NOT_LOCAL`, keyed by the FX it moved.
 */
export interface FxError {
  fxId: number
  code: 'FX_NOT_FOUND' | 'FX_UPDATE_REFUSED' | 'FX_MOVE_NOT_LOCAL' | string
  message: string
}

type FxMessage =
  | { type: 'fxState'; activeFx: ActiveFxState[] }
  | { type: 'fxChanged'; changeType: string; fxId?: number }
  | ({ type: 'fxError' } & FxError)

// === API Interface ===

export interface FxApi {
  get(): FxState
  subscribe(fn: (state: FxState) => void): Subscription
  /**
   * Edit a running effect in place over the socket — the id and the phase kept, every field
   * optional and "absent keeps it", exactly `PUT /fx/{id}`'s body (the desk parses both through
   * one function). For a live editor whose every drag is a write; the answer is the ordinary
   * `fxChanged`, or an `fxError` on [subscribeToErrors]. An operator gesture: a closed socket
   * toasts and answers false.
   */
  updateFx(fxId: number, update: UpdateFxRequest): boolean
  /**
   * `programmer.moveFx` (FX editor plan §3.5): re-rank Local FX [fxId] directly below [above]'s,
   * or at the bottom of the Local FX for null. Answered `fxChanged` UPDATED for each FX whose
   * priority moved, or an `fxError` (`FX_MOVE_NOT_LOCAL` for an FX that is not Local). An operator
   * gesture, as [updateFx] is.
   */
  moveFx(fxId: number, above: number | null): boolean
  /** Every `fxError` this socket is sent — `store/fixtureFx.ts` toasts each, keyed per effect. */
  subscribeToErrors(fn: (error: FxError) => void): Subscription
}

export function createFxApi(conn: InternalApiConnection): FxApi {
  let nextSubscriptionId = 1
  const stateSubscriptions = new Map<number, (state: FxState) => void>()

  let currentState: FxState = { activeFx: [] }

  const notifyState = (state: FxState) => {
    stateSubscriptions.forEach((fn) => fn(state))
  }

  const errorSubscriptions = new Map<number, (error: FxError) => void>()

  conn.subscribe((evType, _ev, frame) => {
    if (evType === InternalEventType.message) {
      const message = frame as FxMessage | null
      if (message == null) return

      if (message.type === 'fxState') {
        currentState = { activeFx: message.activeFx }
        notifyState(currentState)
      } else if (message.type === 'fxChanged') {
        // Re-request full state to get updated effect list
        conn.send(JSON.stringify({ type: 'fxState' }))
      } else if (message.type === 'fxError') {
        const error: FxError = { fxId: message.fxId, code: message.code, message: message.message }
        errorSubscriptions.forEach((fn) => fn(error))
      }
    }
  })

  return {
    get(): FxState {
      return currentState
    },

    subscribe(fn: (state: FxState) => void): Subscription {
      const thisId = nextSubscriptionId++
      stateSubscriptions.set(thisId, fn)
      return {
        unsubscribe: () => {
          stateSubscriptions.delete(thisId)
        },
      }
    },

    updateFx(fxId: number, update: UpdateFxRequest): boolean {
      return sendGesture(conn, { type: 'updateFx', fxId, ...update })
    },

    moveFx(fxId: number, above: number | null): boolean {
      return sendGesture(conn, { type: 'programmer.moveFx', fxId, above })
    },

    subscribeToErrors(fn: (error: FxError) => void): Subscription {
      const thisId = nextSubscriptionId++
      errorSubscriptions.set(thisId, fn)
      return {
        unsubscribe: () => {
          errorSubscriptions.delete(thisId)
        },
      }
    },
  }
}
