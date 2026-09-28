import { InternalApiConnection } from './internalApi'
import { Subscription } from './subscription'
import { createKeyedWsSubscribable, createWsSubscribable } from './wsSubscriptionFactory'
import { sendGesture } from './wsGesture'

/** One master's live state, as streamed by `speedMasters.state` / `speedMasters.changed`. */
export interface SpeedMasterLiveState {
  /** Null only for the synthetic pre-load master 1 (mid-boot). */
  uuid: string | null
  /** 1-based display index; 1 is the global master. */
  index: number
  name: string
  bpm: number
  isRunning: boolean
  /** How the tempo was last set — display only. */
  source: 'MANUAL' | 'TAP'
  /**
   * Effect-library category this master is the apply-time routing default for, or null.
   *
   * Carried on `speedMasters.state` only — `.changed` is the tempo push and says nothing about
   * routing, so anything reading this must be fed by a state frame, not a change frame.
   */
  usage?: string | null
  /** Follow ratio over {@link followTargetUuid} (`bpm = leader x num/den`); both null = manual. `.state` only. */
  followNum?: number | null
  followDen?: number | null
  /** The master being followed; null/absent means master 1. Resolved server-side. `.state` only. */
  followTargetUuid?: string | null
}

/**
 * A tempo write the server refused — `speedMasters.error`, unicast to whoever sent it.
 *
 * [code] is `SPEED_MASTER_FOLLOWER` (the master derives its tempo from the one it follows) or
 * `SPEED_MASTER_UNKNOWN` (the uuid names no master, and the write was dropped rather than
 * redirected). [message] is the server's single operator-facing phrasing, shared with the MIDI
 * surface, so clients display it rather than composing their own. A full `speedMasters.state`
 * frame always follows, which is what snaps a stale client back — there is nothing to refetch.
 */
export interface SpeedMasterError {
  masterUuid: string | null
  code: string
  message: string
}

/** One master crossing a beat boundary, as streamed by `speedMasters.beat`. */
export interface SpeedMasterBeat {
  /**
   * The master's uuid — master 1 included. Null ONLY for the synthetic pre-load master 1,
   * the same rule as `SpeedMasterLiveState.uuid`; this is *not* the write messages'
   * null-means-master-1 convention, so a subscriber standing in for master 1 has to resolve
   * its real uuid (`useMaster1Uuid`) rather than pass null.
   */
  masterUuid: string | null
  index: number
  beatNumber: number
  bpm: number
  timestampMs: number
}

export interface SpeedMastersWsApi {
  /**
   * CRUD notifications — created, renamed, deleted. Drives RTK Query cache invalidation.
   * Deliberately never fired per tempo change (that would be an invalidation storm behind
   * a tap button); live BPM arrives via [subscribe] instead.
   */
  subscribeList(fn: () => void): Subscription
  /** Live bank pushes — the full masters array after every state frame or tempo change. */
  subscribe(fn: (masters: SpeedMasterLiveState[]) => void): Subscription
  /** Refused tempo writes — see {@link SpeedMasterError}. Unicast, so only this tab's own. */
  subscribeError(fn: (error: SpeedMasterError) => void): Subscription
  /** Latest known bank, slot order (master 1 first). Empty before the first state frame. */
  getState(): SpeedMasterLiveState[]
  /**
   * Beat boundaries for **one** master, addressed by the uuid its frames carry (see
   * [SpeedMasterBeat.masterUuid] — for master 1 that is its real uuid, not null). Keyed so an
   * indicator beside a master-2 effect pulses at master 2's tempo.
   *
   * Server frames are throttled (every 16 beats), so subscribers are expected to free-run a
   * local timer in between and use these to correct drift. Subscribing sends a `requestBeat`
   * so a freshly-mounted indicator locks phase promptly instead of waiting out the throttle.
   */
  subscribeBeat(masterUuid: string | null, fn: (beat: SpeedMasterBeat) => void): Subscription
  /**
   * Ask for one immediate beat frame without re-subscribing. For a subscriber that knows its
   * local timer has gone stale — a backgrounded tab, whose interval drifted while it was
   * hidden — but whose subscription is still perfectly good.
   */
  requestBeat(masterUuid: string | null): void
  /** Set one master's BPM (null uuid → master 1). Fire-and-forget. */
  setBpm(masterUuid: string | null, bpm: number): void
  /** Tap one master's tempo (null uuid → master 1). */
  tap(masterUuid: string | null): void
}

type SpeedMasterInMessage =
  | { type: 'speedMasters.state'; masters: SpeedMasterLiveState[] }
  | {
      type: 'speedMasters.changed'
      masterUuid: string | null
      index: number
      bpm: number
      source: 'MANUAL' | 'TAP'
      timestampMs: number
    }
  | ({ type: 'speedMasters.beat' } & SpeedMasterBeat)
  | ({ type: 'speedMasters.error' } & SpeedMasterError)
  | { type: 'speedMasters.listChanged' }

export function createSpeedMastersWsApi(conn: InternalApiConnection): SpeedMastersWsApi {
  const listChanged = createWsSubscribable<void>()
  const stateChanged = createWsSubscribable<SpeedMasterLiveState[]>()
  const errors = createWsSubscribable<SpeedMasterError>()

  // One subscribable per master rather than one shared stream that every indicator filters:
  // a bank of four masters at 120 BPM is a steady trickle of frames, and waking every
  // indicator on every master's beat only to discard it is exactly the re-render pattern
  // `useSpeedMasterDisplay`'s selectFromResult exists to avoid. Pooled and pruned by
  // `createKeyedWsSubscribable`, so `keys()` below is the set of masters something is watching
  // now — not every master this tab has ever displayed.
  const beats = createKeyedWsSubscribable<SpeedMasterBeat>()
  /** Only reachable before the bank has loaded — after that master 1 keys by its real uuid. */
  const MASTER_1_KEY = ''
  const beatKey = (masterUuid: string | null) => masterUuid ?? MASTER_1_KEY

  /** Ask for an immediate beat frame for each key — `''` sends an omitted uuid (master 1). */
  const requestBeatsFor = (keys: Iterable<string>) => {
    for (const key of keys) {
      conn.send(
        JSON.stringify({
          type: 'speedMasters.requestBeat',
          masterUuid: key === MASTER_1_KEY ? undefined : key,
        }),
      )
    }
  }

  let masters: SpeedMasterLiveState[] = []

  conn.subscribe((evType, _ev, frame) => {
    if (evType === 'open') {
      // Beat requests are one-shot and live on the server's per-connection scope, so a
      // reconnect loses every pending one. Without re-asking, an indicator keeps free-
      // running its local timer at the pre-drop tempo until the next throttled frame —
      // up to 16 beats later, and at the wrong rate if the tempo moved meanwhile. That
      // is the *only* thing this branch owes: no `speedMasters.state` request (the server
      // pushes one per connection, a reconnect included) and no `listChanged` notify (the
      // reconnect resync in `store/status.ts` invalidates `SpeedMaster`/`SpeedMasterList`
      // with every other tag).
      requestBeatsFor(beats.keys())
    } else if (evType === 'message') {
      // The connection parses each frame once for every bridge, so the substring
      // pre-filter this used to run against the torrent of unrelated frames has
      // nothing left to save: discriminate on the parsed `type` instead.
      const message = frame as SpeedMasterInMessage | null
      if (message == null) return
      if (message.type === 'speedMasters.state') {
        masters = message.masters
        stateChanged.notify(masters)
      } else if (message.type === 'speedMasters.changed') {
        // Patch the one master in place; identity of untouched members is preserved so
        // field-wise cache writes downstream stay cheap.
        masters = masters.map((m) =>
          m.uuid === message.masterUuid
            ? { ...m, bpm: message.bpm, source: message.source }
            : m,
        )
        stateChanged.notify(masters)
      } else if (message.type === 'speedMasters.error') {
        // Report only. The state frame the server sends straight after carries the truth this
        // client disagreed with, so there is nothing to patch or refetch here.
        errors.notify(message)
      } else if (message.type === 'speedMasters.beat') {
        beats.notify(beatKey(message.masterUuid), message)
      } else if (message.type === 'speedMasters.listChanged') {
        // Membership changed — re-request the live bank alongside the REST invalidation,
        // since the server does not push a state frame on CRUD.
        conn.send(JSON.stringify({ type: 'speedMasters.state' }))
        listChanged.notify()
      }
    }
  })

  return {
    subscribeList: listChanged.api.subscribe,
    subscribe: stateChanged.api.subscribe,
    subscribeError: errors.api.subscribe,
    getState: () => masters,
    subscribeBeat(masterUuid, fn) {
      const key = beatKey(masterUuid)
      const subscription = beats.subscribe(key, fn)
      requestBeatsFor([key])
      return subscription
    },
    requestBeat(masterUuid) {
      requestBeatsFor([beatKey(masterUuid)])
    },
    setBpm(masterUuid, bpm) {
      sendGesture(conn, { type: 'speedMasters.setBpm', masterUuid: masterUuid ?? undefined, bpm })
    },
    tap(masterUuid) {
      sendGesture(conn, { type: 'speedMasters.tap', masterUuid: masterUuid ?? undefined })
    },
  }
}
