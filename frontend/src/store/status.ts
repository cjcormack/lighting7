import { restApi, REST_TAG_TYPES } from "./restApi"
import type { RestTagType } from "./restApi"
import { lightingApi } from "../api/lightingApi"
import { store } from "./index"
import { Status } from "../api/statusApi"

/**
 * Tags deliberately left out of the reconnect resync below. Keep it short, and say why per entry.
 *
 * `Auth`: the very first open is the page's own initial connect, when `AuthGate` is already
 * fetching `auth/status` unconditionally — refetching it here just races a second identical
 * request onto the critical path. Genuine *re*-opens are covered instead by `authWsApi`'s
 * `ownAccountChanged` catch-up, which carries exactly that `seenOpen` first-open guard.
 */
const RECONNECT_EXCLUDED_TAGS: ReadonlySet<RestTagType> = new Set<RestTagType>(['Auth'])

/**
 * Every REST cache to re-read when the WebSocket (re)connects, so both queries that failed while
 * the backend was away and caches that quietly went stale while it was rebuilt come back.
 *
 * Derived from `REST_TAG_TYPES` rather than listed, because a list is what rotted: the hand-written
 * predecessor covered 15 tags of 47 under a comment claiming "all REST caches", and the twenty with
 * no path at all included `ProgramState` — patched only by `showChanged`, never invalidated — so a
 * show started or stopped elsewhere during a sleep left this tab's transport permanently wrong.
 *
 * Invalidation only refetches *subscribed* queries, so the width costs nothing on a route that
 * doesn't show those entities, and the admin-only families already pass `skip: !isAdmin`. The
 * burst it does produce is spread over `RECONNECT_RESYNC_WAVES` below.
 */
export const RECONNECT_RESYNC_TAGS: readonly RestTagType[] = REST_TAG_TYPES.filter(
  (tag) => !RECONNECT_EXCLUDED_TAGS.has(tag),
)

/**
 * Tags to put in the first wave. A **priority hint only** — never a coverage list, which is the
 * distinction that keeps this from rotting the way its predecessor did: anything omitted here
 * still resyncs, just a wave later. These are the caches whose staleness the operator sees
 * immediately (a greyed-out transport, a stale patch list on the fixtures table and all three
 * stage views), so they should not queue behind the Cloud Sync and OAuth families.
 */
const RESYNC_PRIORITY_TAGS: readonly RestTagType[] = [
  'ProgramState',
  'Patch',
  'UniverseConfig',
  'Fixture',
  'Cue',
  'CueList',
  'CueStackList',
  'CueSlotList',
]

/**
 * How long to let the socket settle before resyncing at all.
 *
 * A flaky link flaps CLOSED→OPEN→CLOSED→OPEN in well under a second, and each OPEN would
 * otherwise start its own full fan-out — against a backend that is, by the evidence of the flap,
 * not ready. Waiting also costs nothing perceptible: this path only runs when the socket has
 * *just* come back, and the frames the server pushes per connection (programmer, park, universes,
 * channel buffer) arrive independently of it.
 */
export const RESYNC_DEBOUNCE_MS = 250

/** Tags per wave, and the gap between waves. */
export const RESYNC_WAVE_SIZE = 8
export const RESYNC_WAVE_INTERVAL_MS = 150

/**
 * The resync split into waves, priority tags first, then the rest in declaration order.
 *
 * Invalidating all 46 tags in one tick makes every subscribed query refetch in the same frame.
 * That is bounded — only *mounted* queries refetch, and the admin-only families pass
 * `skip: !isAdmin` — but the moment it happens is the worst one for it: a reconnect usually means
 * the backend has just restarted, and lighting7 serves REST from a single pooled SQLite
 * connection (`maximumPoolSize = 1`), so a dozen simultaneous GETs serialise behind each other
 * while the show is still warming up. Spreading them costs a few hundred milliseconds on a path
 * nobody is watching, and keeps the burst at a handful of requests at a time.
 *
 * Concatenated, the waves are exactly `RECONNECT_RESYNC_TAGS` — pinned in `./status.test.ts`, so
 * a new tag cannot fall out of the resync by landing in no wave.
 */
export const RECONNECT_RESYNC_WAVES: readonly (readonly RestTagType[])[] = (() => {
  const priority = RESYNC_PRIORITY_TAGS.filter((tag) => RECONNECT_RESYNC_TAGS.includes(tag))
  const ordered = [...priority, ...RECONNECT_RESYNC_TAGS.filter((tag) => !priority.includes(tag))]
  const waves: RestTagType[][] = []
  for (let i = 0; i < ordered.length; i += RESYNC_WAVE_SIZE) {
    waves.push(ordered.slice(i, i + RESYNC_WAVE_SIZE))
  }
  return waves
})()

let debounceTimer: ReturnType<typeof setTimeout> | null = null
let waveTimer: ReturnType<typeof setTimeout> | null = null

/** Abandons a resync that is pending or part-way through its waves. */
function cancelResync() {
  if (debounceTimer !== null) {
    clearTimeout(debounceTimer)
    debounceTimer = null
  }
  if (waveTimer !== null) {
    clearTimeout(waveTimer)
    waveTimer = null
  }
}

function runResyncWaves() {
  let index = 0
  const step = () => {
    waveTimer = null
    store.dispatch(restApi.util.invalidateTags([...RECONNECT_RESYNC_WAVES[index]]))
    index += 1
    if (index < RECONNECT_RESYNC_WAVES.length) {
      waveTimer = setTimeout(step, RESYNC_WAVE_INTERVAL_MS)
    }
  }
  step()
}

let previousStatus: Status | null = null
lightingApi.status.subscribe((status) => {
  if (status === Status.OPEN && previousStatus !== Status.OPEN) {
    cancelResync()
    debounceTimer = setTimeout(() => {
      debounceTimer = null
      runResyncWaves()
    }, RESYNC_DEBOUNCE_MS)
  } else if (status !== Status.OPEN) {
    // The socket went away again — mid-flap, or mid-wave. Refetching into a backend that has just
    // dropped us only produces failed queries to retry on the *next* open, so stop here and let
    // that open start a clean resync.
    cancelResync()
  }
  previousStatus = status
})

export const statusApi = restApi.injectEndpoints({
  endpoints: (build) => {
    return {
      status: build.query<Status, void>({
        queryFn: () => {
          const value = lightingApi.status.get()
          return { data: value }
        },
        async onCacheEntryAdded(_, { updateCachedData, cacheEntryRemoved }) {
          const subscription = lightingApi.status.subscribe((value) => {
            updateCachedData(() => {
              return value
            })
          })
          await cacheEntryRemoved
          subscription.unsubscribe()
        },
      }),
      reconnect: build.mutation<void, void>({
        queryFn: () => {
          lightingApi.status.reconnect()

          return { data: undefined }
        },
      })
    }
  },
  overrideExisting: false,
})

export const { useStatusQuery, useReconnectMutation } = statusApi

/**
 * Whether the desk is reachable right now.
 *
 * For controls that promise an *immediate* change to the rig — blackout, grand master, Blind,
 * park, the programmer's value editors. A WebSocket write made while the socket is down goes
 * nowhere (`sendGesture` says so, but only after the fact), and because programmer state is
 * server-driven the control would otherwise sit there looking live and doing nothing. Disabling
 * is the honest answer, and it is the one thing a replay queue must not be used for: flushing a
 * minute-old blackout on reconnect moves the rig behind the operator's back.
 *
 * REST-backed editing is deliberately *not* gated on this — those surfaces have their own error
 * toasts, and REST can be perfectly healthy while the socket is mid-backoff.
 *
 * `selectFromResult` narrows the subscription to the boolean, so the many controls reading it
 * re-render on connect and disconnect only, not on every `Status` transition through CONNECTING.
 */
export function useIsDeskConnected(): boolean {
  const { connected } = useStatusQuery(undefined, {
    selectFromResult: ({ data }) => ({ connected: data === Status.OPEN }),
  })
  return connected
}
