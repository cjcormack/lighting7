import {Subscription} from "./subscription";

// The backend accepts the `/api` upgrade and then closes with this code when the
// request carried no valid session cookie (lighting7 plugins/Sockets.kt). A browser
// cannot read a 401 on an upgrade response, but it can read a close code — hence the
// application-range code rather than an HTTP status.
export const UNAUTHENTICATED_CLOSE_CODE = 4401

/**
 * A bridge's handler for one connection event.
 *
 * `message` is the frame's already-parsed JSON body — parsed exactly once per frame
 * for all ~27 bridges, rather than once per bridge (the `channelState` firehose runs
 * at ~40 frames/s per universe, on the same main thread that paints the grid). It is
 * `null` for anything that is not a text `message` event, and for a frame whose body
 * is not valid JSON. Switch on its `type`; `ev` remains available for the lifecycle
 * branches that need the raw event (`CloseEvent.code`, for instance).
 */
export type InternalEventHandler = (
  evType: InternalEventType,
  ev: Event,
  message: unknown,
) => void

export interface InternalApiConnection {
  baseUrl: string
  readyState(): number;
  /**
   * Write a frame, and report whether it was written.
   *
   * A socket that is CONNECTING, CLOSING or CLOSED cannot take a frame, and the reconnect
   * backoff below reaches 30 s — so the drop window after a network blip is seconds to half a
   * minute. Callers carrying an operator gesture must not ignore the `false`: see `sendGesture`
   * in `./wsGesture.ts`, which is how every such call site says so out loud.
   *
   * Deliberately no queue. Flushing a minute-old blackout on reconnect moves the rig behind the
   * operator's back; the bridges' idempotent state re-requests are the correct catch-up.
   */
  send(data: string | ArrayBufferLike | Blob | ArrayBufferView): boolean,
  subscribe(fn: InternalEventHandler): Subscription
  /**
   * Re-open the socket. A no-op while it is already OPEN or CONNECTING, unless
   * `force` — which is what a fresh login needs, since the identity behind an
   * already-open socket has just changed.
   */
  reconnect(force?: boolean): void;
}

export enum InternalEventType {
  'close' = 'close',
  'error' = 'error',
  'message' = 'message',
  'open' = 'open',
}

/**
 * The single parse of an inbound frame. Exported so a bridge's test fake can mirror
 * production exactly rather than hand-rolling the same guards; nothing else should
 * call it, since the connection has already parsed the frame by the time a bridge
 * sees it. Returns null for a non-text event and for a body that is not valid JSON.
 */
export function parseWsFrame(ev: Event): unknown {
  if (!(ev instanceof MessageEvent) || typeof ev.data !== 'string') return null
  try {
    return JSON.parse(ev.data)
  } catch {
    console.error('WebSocket: dropping a frame that is not valid JSON', ev.data)
    return null
  }
}

export function createInternalApiConnection(baseUrl: string, wsUrl: string): InternalApiConnection {
  let nextEventSubscriptionId = 1
  const eventSubscriptions = new Map<number, InternalEventHandler>()
  let reconnectDelay = 1000
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null

  const notifyEvent = (ev: Event) => {
    const evType = ev.type as InternalEventType
    if (!evType) return
    const message = evType === InternalEventType.message ? parseWsFrame(ev) : null
    eventSubscriptions.forEach((fn) => {
      // One bridge that throws must not starve the ones registered after it of this
      // frame — and the two most latency-sensitive (programmer, speed masters) are
      // registered last. Report it; never swallow it silently.
      try {
        fn(evType, ev, message)
      } catch (err) {
        console.error(`WebSocket: a ${evType} subscriber threw`, err)
      }
    })
  }

  function scheduleReconnect() {
    if (reconnectTimer) return
    console.log(`WebSocket closed, reconnecting in ${reconnectDelay}ms...`)
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null
      reconnectDelay = Math.min(reconnectDelay * 2, 30_000)
      ws = connect()
    }, reconnectDelay)
  }

  function connect(): WebSocket {
    const newWs = new WebSocket(wsUrl)

    newWs.onopen = (ev) => {
      reconnectDelay = 1000
      notifyEvent(ev)
    }
    newWs.onerror = (ev) => {
      notifyEvent(ev)
    }
    newWs.onclose = (ev) => {
      notifyEvent(ev)
      // The server rejected our session. Reconnecting would loop against the same
      // dead cookie forever; instead authWsApi turns this into a cache invalidation
      // and the auth gate re-checks /auth/status and shows the login screen. Logging
      // back in reconnects explicitly (see the `login` endpoint in store/auth.ts).
      if (ev.code === UNAUTHENTICATED_CLOSE_CODE) return
      scheduleReconnect()
    }

    newWs.onmessage = (ev) => {
      notifyEvent(ev)
    }

    return newWs
  }

  let ws = connect()

  return {
    baseUrl: baseUrl,
    readyState(): number {
      return ws.readyState
    },
    send(data: string | ArrayBufferLike | Blob | ArrayBufferView): boolean {
      if (ws.readyState !== WebSocket.OPEN) return false
      ws.send(data)
      return true
    },
    reconnect(force = false) {
      if (reconnectTimer) {
        clearTimeout(reconnectTimer)
        reconnectTimer = null
      }
      const isLive = ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING
      if (!force && isLive) {
        return
      }
      reconnectDelay = 1000
      // A socket opened while the backend was still bootstrap-open carries no user.
      // After setup, login or logout it has to re-handshake so the server re-reads
      // the cookie, rather than leaving a socket streaming under a stale identity.
      //
      // Whatever state the outgoing socket is in, it must not schedule its own
      // reconnect: we are about to replace it, and its close handler would race a
      // third socket against the replacement, orphaning the replacement while it
      // stays open and delivers every frame twice. CLOSED needs nothing — its close
      // already fired, and the timer it armed was cleared above. Subscribers still
      // see the close, so readyState-watchers (statusApi) stay accurate.
      const replaced = ws
      if (replaced.readyState !== WebSocket.CLOSED) {
        replaced.onclose = (ev) => notifyEvent(ev)
        replaced.close()
      }
      ws = connect()
    },
    subscribe(fn: InternalEventHandler): Subscription {
      const thisId = nextEventSubscriptionId
      nextEventSubscriptionId++

      eventSubscriptions.set(thisId, fn)

      return {
        unsubscribe: () => {
          eventSubscriptions.delete(thisId)
        },
      }
    }
  }
}
