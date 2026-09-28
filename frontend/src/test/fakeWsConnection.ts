import {
  InternalApiConnection,
  InternalEventHandler,
  InternalEventType,
  parseWsFrame,
} from "../api/internalApi"

/**
 * A stand-in for the live WebSocket connection: it captures the single handler a bridge
 * registers in `src/api/*Api.ts`, so a test can fire synthetic events at it directly and
 * read back whatever the bridge sent.
 *
 * `fire` and `frame` run the body through `parseWsFrame`, exactly as the real connection
 * does — it parses each frame once and hands every bridge the result, so a fake that
 * passed the raw event alone would exercise a path production never takes.
 */
export function fakeWsConnection() {
  let handler: InternalEventHandler | null = null
  let open = true
  const sent: Record<string, unknown>[] = []

  const conn: InternalApiConnection = {
    baseUrl: "/api/",
    readyState: () => (open ? 1 : 3), // WebSocket.OPEN / CLOSED
    send: (payload) => {
      // Mirrors the real connection: a frame written to a socket that isn't OPEN goes
      // nowhere, and the caller is told so. `setOpen(false)` is how a test reaches the
      // dropped-gesture path without standing up a real WebSocket.
      if (!open) return false
      sent.push(JSON.parse(payload as string))
      return true
    },
    reconnect: () => {},
    subscribe: (fn) => {
      handler = fn
      return {
        unsubscribe: () => {
          handler = null
        },
      }
    },
  }

  return {
    conn,
    /** Every frame the bridge sent, already parsed. */
    sent,
    /** Take the socket down (or bring it back), so writes are dropped as they are live. */
    setOpen: (value: boolean) => {
      open = value
    },
    /** Deliver an arbitrary connection event — an open, a close with a code, a raw frame. */
    fire: (evType: InternalEventType, ev: Event) => handler?.(evType, ev, parseWsFrame(ev)),
    /** Deliver one inbound message frame, given its body. */
    frame: (body: unknown) => {
      const ev = new MessageEvent("message", { data: JSON.stringify(body) })
      handler?.(InternalEventType.message, ev, parseWsFrame(ev))
    },
  }
}
