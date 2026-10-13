import { toast } from 'sonner'
import { Subscription } from "./subscription"
import { InternalApiConnection } from "./internalApi"
import { sendGesture } from "./wsGesture"

/**
 * Whose park a channel's is: the project's own (`SHOW` — in a venue, the venue's draft), or the
 * pinned venue version's (`VENUE`), which a show on that venue inherits.
 */
export type ParkOwner = 'SHOW' | 'VENUE'

/** One parked channel's park, keyed by `universe:channel` in the maps below. */
export interface ParkEntry {
  value: number
  owner: ParkOwner
  /**
   * The venue's park under a show's own on the same channel (a re-park at another value), which
   * unparking the show's brings back. Absent when there is none.
   */
  venueValue?: number
}

/**
 * A venue park the show has released for its run. `venueValue` is what restoring it brings back;
 * absent when the pinned version no longer parks the channel (the release is dormant).
 */
export interface ReleasedPark {
  universe: number
  channel: number
  venueValue?: number
}

export interface ParkApi {
  getAll(): Map<string, number>
  /** Every parked channel's park, owner and all, by `universe:channel`. */
  getEntries(): Map<string, ParkEntry>
  /** The venue parks this show has released. */
  getReleased(): ReleasedPark[]
  isParked(universe: number, channelNo: number): boolean
  getParkedValue(universe: number, channelNo: number): number | undefined
  /** False when the socket was down, so the write went nowhere. */
  park(universe: number, channelNo: number, value: number): boolean
  /**
   * False when the socket was down, so the write went nowhere. On a show on a venue, unparking a
   * venue park releases it for this show, and unparking the show's park over a venue park brings
   * the venue's back.
   */
  unpark(universe: number, channelNo: number): boolean
  /** Take the venue's park back on a channel: drops the show's release, or its park over the venue's. */
  restore(universe: number, channelNo: number): boolean
  subscribe(fn: (parked: Map<string, number>) => void): Subscription
  subscribeEntries(fn: (entries: Map<string, ParkEntry>, released: ReleasedPark[]) => void): Subscription
  subscribeToChannel(key: string, fn: (value: number | undefined) => void): Subscription
}

type ParkStateInMessage = {
  type: "parkState"
  channels: {
    universe: number
    channel: number
    value: number
    /** Absent from a desk that predates venue parks: every park was the project's own. */
    owner?: ParkOwner
    venueValue?: number
  }[]
  /** Absent from a desk that predates venue parks. */
  released?: ReleasedPark[]
}

type ParkErrorInMessage = {
  type: "parkError"
  code: string
  message: string
}

/** One toast id for every refused park write, so a batch replaces rather than stacks. */
export const PARK_ERROR_TOAST_ID = 'park-error'

export function createParkApi(conn: InternalApiConnection): ParkApi {
  const parkedChannels = new Map<string, ParkEntry>()
  let released: ReleasedPark[] = []

  let nextSubscriptionId = 1
  const globalSubscriptions = new Map<number, (parked: Map<string, number>) => void>()
  const entrySubscriptions = new Map<number, (entries: Map<string, ParkEntry>, released: ReleasedPark[]) => void>()
  const perChannelSubscriptions = new Map<string, Map<number, (value: number | undefined) => void>>()

  const values = () => new Map([...parkedChannels].map(([key, entry]) => [key, entry.value]))

  const notifyAll = () => {
    const snapshot = values()
    globalSubscriptions.forEach((fn) => fn(new Map(snapshot)))
    entrySubscriptions.forEach((fn) => fn(new Map(parkedChannels), released))
  }

  const notifyChannel = (key: string, value: number | undefined) => {
    const subs = perChannelSubscriptions.get(key)
    if (subs) {
      subs.forEach((fn) => fn(value))
    }
  }

  const handleOnMessage = (message: ParkStateInMessage | ParkErrorInMessage | null) => {
    if (message == null) return
    if (message.type === "parkError") {
      // A refused park write — an operator's on a venue (`VENUE_ADMIN_ONLY`), or a park that would
      // fire a trigger or hold a fixture command. The park list did not move, so this is the only
      // word the operator gets.
      toast.error(message.message, { id: PARK_ERROR_TOAST_ID })
      return
    }
    if (message.type !== "parkState") return

    // Track which channels were previously parked for unpark notifications
    const previousKeys = new Set(parkedChannels.keys())

    parkedChannels.clear()
    message.channels.forEach((ch) => {
      const key = `${ch.universe}:${ch.channel}`
      parkedChannels.set(key, {
        value: ch.value,
        owner: ch.owner ?? 'SHOW',
        ...(ch.venueValue != null ? { venueValue: ch.venueValue } : {}),
      })
      previousKeys.delete(key)
      notifyChannel(key, ch.value)
    })
    released = message.released ?? []

    // Notify channels that were unparked
    previousKeys.forEach((key) => {
      notifyChannel(key, undefined)
    })

    notifyAll()
  }

  // No `parkState` request on open: the server pushes one per connection.
  conn.subscribe((evType, _ev, message) => {
    if (evType === "message") {
      handleOnMessage(message as ParkStateInMessage | ParkErrorInMessage | null)
    }
  })

  return {
    getAll() {
      return values()
    },

    getEntries() {
      return new Map(parkedChannels)
    },

    getReleased() {
      return released
    },

    isParked(universe: number, channelNo: number): boolean {
      return parkedChannels.has(`${universe}:${channelNo}`)
    },

    getParkedValue(universe: number, channelNo: number): number | undefined {
      return parkedChannels.get(`${universe}:${channelNo}`)?.value
    },

    park(universe: number, channelNo: number, value: number) {
      return sendGesture(conn, {
        type: "parkChannel",
        universe,
        channel: channelNo,
        value,
      })
    },

    unpark(universe: number, channelNo: number) {
      return sendGesture(conn, {
        type: "unparkChannel",
        universe,
        channel: channelNo,
      })
    },

    restore(universe: number, channelNo: number) {
      return sendGesture(conn, {
        type: "restoreChannelPark",
        universe,
        channel: channelNo,
      })
    },

    subscribe(fn: (parked: Map<string, number>) => void): Subscription {
      const id = nextSubscriptionId++
      globalSubscriptions.set(id, fn)
      return { unsubscribe: () => globalSubscriptions.delete(id) }
    },

    subscribeEntries(fn: (entries: Map<string, ParkEntry>, released: ReleasedPark[]) => void): Subscription {
      const id = nextSubscriptionId++
      entrySubscriptions.set(id, fn)
      return { unsubscribe: () => entrySubscriptions.delete(id) }
    },

    subscribeToChannel(key: string, fn: (value: number | undefined) => void): Subscription {
      const id = nextSubscriptionId++
      let channelMap = perChannelSubscriptions.get(key)
      if (!channelMap) {
        channelMap = new Map()
        perChannelSubscriptions.set(key, channelMap)
      }
      channelMap.set(id, fn)
      return { unsubscribe: () => channelMap.delete(id) }
    },
  }
}
