import {Subscription} from "./subscription";
import {InternalApiConnection, InternalEventType} from "./internalApi";

export enum Status {
    CONNECTING,
    OPEN,
    CLOSING,
    CLOSED,
}

export interface StatusApi {
    get(): Status
    /** `force` re-handshakes even a live socket — see InternalApiConnection.reconnect. */
    reconnect(force?: boolean): void
    subscribe(fn: ((status: Status) => void)): Subscription
}

export function createStatusApi(conn: InternalApiConnection): StatusApi {
    let nextStatusSubscriptionId = 1
    const statusUpdatesSubscriptions = new Map<number, (status: Status) => void>()

    const notifyStatusChange = (readyState: Status) => {
        statusUpdatesSubscriptions.forEach((fn) => {
            fn(readyState)
        })
    }

    conn.subscribe((evType) => {
        if (evType === InternalEventType.message) {
            return
        }
        notifyStatusChange(conn.readyState())
    })

    return {
        get: (): Status => {
            return conn.readyState()
        },
        reconnect: (force = false) => {
            conn.reconnect(force)
            notifyStatusChange(conn.readyState())
        },
        subscribe: (fn: (status: Status) => void): Subscription => {
            const thisId = nextStatusSubscriptionId
            nextStatusSubscriptionId++

            statusUpdatesSubscriptions.set(thisId, fn)

            return {
                unsubscribe: () => {
                    statusUpdatesSubscriptions.delete(thisId)
                },
            }
        },
    }
}
