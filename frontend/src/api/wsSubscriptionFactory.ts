import { InternalApiConnection, InternalEventType } from './internalApi'
import { Subscription } from './subscription'

export interface WsSubscribable<TEvent> {
  subscribe(fn: (event: TEvent) => void): Subscription
}

export interface WsSubscribableHandle<TEvent> {
  api: WsSubscribable<TEvent>
  notify: (event: TEvent) => void
  /** Whether anything is still listening — what [createKeyedWsSubscribable] prunes on. */
  isEmpty: () => boolean
}

export function createWsSubscribable<TEvent>(): WsSubscribableHandle<TEvent> {
  let nextSubscriptionId = 1
  const subscriptions = new Map<number, (event: TEvent) => void>()

  return {
    api: {
      subscribe(fn) {
        const thisId = nextSubscriptionId++
        subscriptions.set(thisId, fn)
        return {
          unsubscribe: () => {
            subscriptions.delete(thisId)
          },
        }
      },
    },
    notify(event) {
      subscriptions.forEach((fn) => fn(event))
    },
    isEmpty: () => subscriptions.size === 0,
  }
}

/**
 * A pool of per-key subscribables: one fan-out per key, created on its first subscriber and
 * dropped again when its last one leaves.
 *
 * Two bridges want this and had hand-rolled it separately — `speedMastersWsApi`'s per-master beat
 * stream and `channelSource`'s derived-source fan-outs — because a plain `createWsSubscribable`
 * wakes everyone. Both need the same two things: per-key granularity, so a beat frame pulses only
 * the indicators for *that* master and a channel change wakes only the readers of *that* channel;
 * and pruning, so the map is the set of keys something is watching rather than the set it has ever
 * watched. Left to grow, the beat map re-requests beats on every reconnect for every master ever
 * displayed, and the channel fan-outs keep an entry per channel a stage has ever drawn.
 *
 * The identity check on the way out is load-bearing rather than defensive. An unsubscribe that runs
 * twice — React calling a cleanup again, a caller holding the handle past teardown — would
 * otherwise find its own detached entry empty a second time and delete whichever entry has since
 * been pooled under the same key, silently stranding every live subscriber on it, with no error.
 */
export interface KeyedWsSubscribable<TEvent> {
  subscribe(key: string, fn: (event: TEvent) => void): Subscription
  /** Wake only the subscribers of `key`. A key nobody is watching is a no-op. */
  notify(key: string, event: TEvent): void
  /** The keys with at least one live subscriber, for a caller that has to re-ask upstream. */
  keys(): IterableIterator<string>
}

export function createKeyedWsSubscribable<TEvent>(): KeyedWsSubscribable<TEvent> {
  const byKey = new Map<string, WsSubscribableHandle<TEvent>>()

  return {
    subscribe(key, fn) {
      const entry = byKey.get(key) ?? createWsSubscribable<TEvent>()
      byKey.set(key, entry)
      const subscription = entry.api.subscribe(fn)
      return {
        unsubscribe: () => {
          subscription.unsubscribe()
          if (entry.isEmpty() && byKey.get(key) === entry) byKey.delete(key)
        },
      }
    },
    notify(key, event) {
      byKey.get(key)?.notify(event)
    },
    keys: () => byKey.keys(),
  }
}

/**
 * The commonest bridge in this directory: one payload-free inbound frame meaning
 * "that list changed", fanned out to whoever is subscribed. A dozen bridges are
 * nothing but this, so the shape lives here once — a thirteenth is a one-line call,
 * and the null/`type` guards have a single home rather than twelve.
 *
 * Payload-carrying frames, anything with an `open` branch, and anything switching on
 * more than one `type` keep their own hand-written subscribe: the moment a bridge
 * needs the body, this factory is the wrong tool.
 */
export function createChangeSignalApi(
  conn: InternalApiConnection,
  messageType: string,
): WsSubscribable<void> {
  const changed = createWsSubscribable<void>()

  conn.subscribe((evType, _ev, frame) => {
    if (evType !== InternalEventType.message) return
    const message = frame as { type?: string } | null
    if (message?.type === messageType) {
      changed.notify()
    }
  })

  return changed.api
}
