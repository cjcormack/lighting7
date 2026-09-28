import { Subscription } from "./subscription"
import { InternalApiConnection } from "./internalApi"
import { sendGesture } from "./wsGesture"

export interface ParkApi {
  getAll(): Map<string, number>
  isParked(universe: number, channelNo: number): boolean
  getParkedValue(universe: number, channelNo: number): number | undefined
  /** False when the socket was down, so the write went nowhere. */
  park(universe: number, channelNo: number, value: number): boolean
  /** False when the socket was down, so the write went nowhere. */
  unpark(universe: number, channelNo: number): boolean
  subscribe(fn: (parked: Map<string, number>) => void): Subscription
  subscribeToChannel(key: string, fn: (value: number | undefined) => void): Subscription
}

type ParkStateInMessage = {
  type: "parkState"
  channels: {
    universe: number
    channel: number
    value: number
  }[]
}

export function createParkApi(conn: InternalApiConnection): ParkApi {
  const parkedChannels = new Map<string, number>()

  let nextSubscriptionId = 1
  const globalSubscriptions = new Map<number, (parked: Map<string, number>) => void>()
  const perChannelSubscriptions = new Map<string, Map<number, (value: number | undefined) => void>>()

  const notifyAll = () => {
    globalSubscriptions.forEach((fn) => fn(new Map(parkedChannels)))
  }

  const notifyChannel = (key: string, value: number | undefined) => {
    const subs = perChannelSubscriptions.get(key)
    if (subs) {
      subs.forEach((fn) => fn(value))
    }
  }

  const handleOnMessage = (message: ParkStateInMessage | null) => {
    if (message == null || message.type !== "parkState") {
      return
    }

    // Track which channels were previously parked for unpark notifications
    const previousKeys = new Set(parkedChannels.keys())

    parkedChannels.clear()
    message.channels.forEach((ch) => {
      const key = `${ch.universe}:${ch.channel}`
      parkedChannels.set(key, ch.value)
      previousKeys.delete(key)
      notifyChannel(key, ch.value)
    })

    // Notify channels that were unparked
    previousKeys.forEach((key) => {
      notifyChannel(key, undefined)
    })

    notifyAll()
  }

  // No `parkState` request on open: the server pushes one per connection.
  conn.subscribe((evType, _ev, message) => {
    if (evType === "message") {
      handleOnMessage(message as ParkStateInMessage | null)
    }
  })

  return {
    getAll() {
      return new Map(parkedChannels)
    },

    isParked(universe: number, channelNo: number): boolean {
      return parkedChannels.has(`${universe}:${channelNo}`)
    },

    getParkedValue(universe: number, channelNo: number): number | undefined {
      return parkedChannels.get(`${universe}:${channelNo}`)
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

    subscribe(fn: (parked: Map<string, number>) => void): Subscription {
      const id = nextSubscriptionId++
      globalSubscriptions.set(id, fn)
      return { unsubscribe: () => globalSubscriptions.delete(id) }
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
