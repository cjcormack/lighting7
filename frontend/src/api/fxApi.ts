import { InternalApiConnection, InternalEventType } from './internalApi'
import { Subscription } from './subscription'
import { sendGesture } from './wsGesture'

// === Types ===

/**
 * One running effect, exactly as the backend's single `EffectDto` (`fx/EffectDto.kt`) reports
 * it — the `fxState` frame and `GET /api/rest/fx/active` now return the same shape, so this and
 * `ActiveEffect` in `store/fixtureFx.ts` describe one wire type. Collapsing the two TS
 * declarations is frontend-sweep work; the fields below are the source of truth.
 *
 * Backend sweep item F8 changed three things here: `phase` is now `currentPhase`, `targetKey` is
 * the bare fixture/group key with `propertyName` alongside it (it used to be the composite
 * `"key.property"`), and `effectType` is the registry id rather than the effect's display name.
 */
export interface FxEffectState {
  id: number
  /** Registry id, the string an Update hands back — not the effect's display name. */
  effectType: string
  /** Fixture or group key, *without* the property suffix. */
  targetKey: string
  propertyName: string
  beatDivision: number
  blendMode: string
  isRunning: boolean
  phaseOffset: number
  currentPhase: number
  parameters: Record<string, string>
  isGroupTarget: boolean
  distributionStrategy?: string | null
  elementMode?: string | null
  elementFilter?: string | null
  stepTiming?: boolean
  /** The Look this effect came out of, when it came out of one. */
  lookId?: number | null
  /** The programmer layer that spawned it. Null for an effect the operator busked directly. */
  programmerLayerId?: number | null
  cueId: number | null
  cueStackId: number | null
  timingSource?: string
  /** True when the effect sits in the programmer's priority band. */
  programmerOwned?: boolean
  /** Fade envelope in [0, 1]; the effect's output is scaled by this before blending. */
  intensityMultiplier?: number
  /** Speed master this effect subscribes to (null → master 1). */
  speedMasterUuid?: string | null
  /** Wall-clock rate master (null → unscaled); only WALL_CLOCK effects read it. */
  rateSpeedMasterUuid?: string | null
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
  activeEffects: FxEffectState[]
}

/**
 * An edit to a running effect: every field optional, absent = keep. The body of `PUT /fx/{id}` and
 * the `updateFx` frame both — the desk parses the two through one function
 * (`applyEffectUpdate`), so this one declaration serves both doors.
 */
export interface UpdateFxRequest {
  effectType?: string
  parameters?: Record<string, string>
  beatDivision?: number
  blendMode?: string
  phaseOffset?: number
  distributionStrategy?: string
  elementMode?: string
  elementFilter?: string
  stepTiming?: boolean
  /**
   * Reassign the effect's speed master (omitted = no change, like every other field). The
   * picker always sends a concrete uuid — master 1's uuid means "back to the default".
   */
  speedMasterUuid?: string
  /** Reassign the wall-clock rate master; omitted = no change, as above. */
  rateSpeedMasterUuid?: string
}

/**
 * A refused `updateFx`, unicast to the socket that sent it (fixture-fx-sheets plan W3).
 * `FX_NOT_FOUND` for an effect that is not running, `FX_UPDATE_REFUSED` for a field the desk's
 * strict policy refuses — `PUT /fx/{id}`'s 404 and 400. Keyed by the effect, so a live drag that
 * keeps being refused can replace one toast rather than stack one per frame.
 */
export interface FxError {
  effectId: number
  code: 'FX_NOT_FOUND' | 'FX_UPDATE_REFUSED' | string
  message: string
}

type FxMessage =
  | { type: 'fxState'; activeEffects: FxEffectState[] }
  | { type: 'fxChanged'; changeType: string; effectId?: number }
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
  updateFx(effectId: number, update: UpdateFxRequest): boolean
  /** Every `fxError` this socket is sent — `store/fixtureFx.ts` toasts each, keyed per effect. */
  subscribeToErrors(fn: (error: FxError) => void): Subscription
}

export function createFxApi(conn: InternalApiConnection): FxApi {
  let nextSubscriptionId = 1
  const stateSubscriptions = new Map<number, (state: FxState) => void>()

  let currentState: FxState = { activeEffects: [] }

  const notifyState = (state: FxState) => {
    stateSubscriptions.forEach((fn) => fn(state))
  }

  const errorSubscriptions = new Map<number, (error: FxError) => void>()

  conn.subscribe((evType, _ev, frame) => {
    if (evType === InternalEventType.message) {
      const message = frame as FxMessage | null
      if (message == null) return

      if (message.type === 'fxState') {
        currentState = { activeEffects: message.activeEffects }
        notifyState(currentState)
      } else if (message.type === 'fxChanged') {
        // Re-request full state to get updated effect list
        conn.send(JSON.stringify({ type: 'fxState' }))
      } else if (message.type === 'fxError') {
        const error: FxError = { effectId: message.effectId, code: message.code, message: message.message }
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

    updateFx(effectId: number, update: UpdateFxRequest): boolean {
      return sendGesture(conn, { type: 'updateFx', effectId, ...update })
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
