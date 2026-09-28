import {Subscription} from "./subscription";
import {InternalApiConnection} from "./internalApi";
import {sendGesture} from "./wsGesture";

export interface ChannelsApi {
    getAll(): Map<string, number>
    get(universe: number, channelNo: number): number
    /** False when the socket was down, so the write went nowhere. */
    update(universe: number, channelNo: number, value: number): boolean
    subscribe(fn: (updates: Map<string, number>) => void): Subscription
    subscribeToChannel(key: string, fn: (value: number) => void): Subscription
}

type ChannelStateInMessage = {
    type: 'channelState',
    channels: {
        universe: number,
        id: number,
        currentLevel: number,
    }[],
}

/**
 * Throttles map updates, merging incoming maps with pending updates. All entries from a single
 * call are guaranteed to fire together — immediately when the last emit was more than [waitMs]
 * ago, else on the trailing timer that closes that window.
 *
 * No timer is alive while idle. The previous `setInterval` form kept ticking until a tick
 * observed an empty batch, so every idle transition cost one no-op wake-up and the function read
 * as a self-cancelling debounce it wasn't. Remembering the last emit time instead means a timer
 * is armed only when there is something waiting for it.
 */
function debounceMapUpdates(
    func: (updates: Map<string, number>) => void,
    waitMs: number
): (updates: Map<string, number>) => void {
    let timeoutId: number | undefined = undefined
    let pending = new Map<string, number>()
    let lastEmitMs = Number.NEGATIVE_INFINITY

    function emit() {
        const batch = pending
        pending = new Map()
        lastEmitMs = Date.now()
        func(batch)
    }

    return (updates: Map<string, number>) => {
        updates.forEach((value, key) => {
            pending.set(key, value)
        })

        // A timer already owns this batch — it picks the merged map up when it fires.
        if (timeoutId !== undefined) return

        const remainingMs = waitMs - (Date.now() - lastEmitMs)
        if (remainingMs <= 0) {
            emit()
            return
        }
        timeoutId = window.setTimeout(() => {
            timeoutId = undefined
            emit()
        }, remainingMs)
    }
}

export function createChannelsApi(conn: InternalApiConnection): ChannelsApi {
    const currentValues = new Map<string, number>()

    let nextChannelSubscriptionId = 1
    const channelUpdatesSubscriptions = new Map<number, (updates: Map<string, number>) => void>()

    const perChannelUpdatesSubscriptions = new Map<string, Map<number, (value: number) => void>>

    const notifyChannelsChange = (updates: Map<string, number>) => {
        channelUpdatesSubscriptions.forEach((fn) => {
            fn(updates)
        })

        updates.forEach((value, key) => {
            const subscriptions = perChannelUpdatesSubscriptions.get(key)
            if (subscriptions) {
                subscriptions.forEach((fn) => {
                    fn(value)
                })
            }
        })
    }

    const updateBatch = debounceMapUpdates((updates: Map<string, number>) => {
        notifyChannelsChange(updates)
    }, 33)

    const handleOnMessage = (message: ChannelStateInMessage | null) => {
        if (message == null || message.type != 'channelState') {
            return
        }

        // Batch all channels from this message together
        const updates = new Map<string, number>()
        message.channels.forEach((update) => {
            const key = `${update.universe}:${update.id}`
            currentValues.set(key, update.currentLevel)
            updates.set(key, update.currentLevel)
        })
        updateBatch(updates)
    }

    // No `channelState` request on open: the server pushes the whole buffer per connection.
    conn.subscribe((evType, _ev, message) => {
        if (evType === 'message') {
            handleOnMessage(message as ChannelStateInMessage | null)
        }
    })

    return {
        getAll() {
            return currentValues
        },
        get(universe: number, channelNo: number): number {
            return currentValues.get(`${universe}:${channelNo}`) || 0
        },
        update(universe: number, channelNo: number, value: number) {
            return sendGesture(conn, {
                type: 'updateChannel',
                universe: universe,
                id: channelNo,
                level: value,
                fadeTime: 0
            })
        },
        subscribe(fn: (updates: Map<string, number>) => void): Subscription {
            const thisId = nextChannelSubscriptionId
            nextChannelSubscriptionId++

            channelUpdatesSubscriptions.set(thisId, fn)

            return {
                unsubscribe: () => {
                    channelUpdatesSubscriptions.delete(thisId)
                },
            }
        },
        subscribeToChannel(key: string, fn: (value: number) => void): Subscription {
            const thisId = nextChannelSubscriptionId
            nextChannelSubscriptionId++

            let channelMap = perChannelUpdatesSubscriptions.get(key)
            if (!channelMap) {
                channelMap = new Map<number, (value: number) => void>()
                perChannelUpdatesSubscriptions.set(key, channelMap)
            }

            channelMap.set(thisId, fn)

            return {
                unsubscribe: () => {
                    channelMap.delete(thisId)
                },
            }
        },
    }
}
