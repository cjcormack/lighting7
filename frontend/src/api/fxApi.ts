import { InternalApiConnection, InternalEventType } from './internalApi'
import { Subscription } from './subscription'

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

type FxMessage =
  | { type: 'fxState'; activeEffects: FxEffectState[] }
  | { type: 'fxChanged'; changeType: string; effectId?: number }

// === API Interface ===

export interface FxApi {
  get(): FxState
  subscribe(fn: (state: FxState) => void): Subscription
}

export function createFxApi(conn: InternalApiConnection): FxApi {
  let nextSubscriptionId = 1
  const stateSubscriptions = new Map<number, (state: FxState) => void>()

  let currentState: FxState = { activeEffects: [] }

  const notifyState = (state: FxState) => {
    stateSubscriptions.forEach((fn) => fn(state))
  }

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
  }
}
