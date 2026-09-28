// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import { createInternalApiConnection, InternalEventType } from "./internalApi"

// A stand-in for the browser WebSocket that never opens a socket, so a test can
// push frames at the connection the way the server would.
class FakeWebSocket {
  static readonly CONNECTING = 0
  static readonly OPEN = 1
  static readonly CLOSING = 2
  static readonly CLOSED = 3
  static last: FakeWebSocket | null = null

  readyState = FakeWebSocket.OPEN
  onopen: ((ev: Event) => void) | null = null
  onerror: ((ev: Event) => void) | null = null
  onclose: ((ev: CloseEvent) => void) | null = null
  onmessage: ((ev: MessageEvent) => void) | null = null

  constructor(public readonly url: string) {
    FakeWebSocket.last = this
  }

  send() {}
  close() {
    this.readyState = FakeWebSocket.CLOSED
  }

  deliver(body: unknown) {
    this.onmessage?.(new MessageEvent("message", { data: JSON.stringify(body) }))
  }
}

function connect() {
  const conn = createInternalApiConnection("/api/", "ws://desk/api")
  const socket = FakeWebSocket.last
  if (!socket) throw new Error("no socket was constructed")
  return { conn, socket }
}

describe("createInternalApiConnection", () => {
  beforeEach(() => {
    FakeWebSocket.last = null
    vi.stubGlobal("WebSocket", FakeWebSocket)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it("parses each frame once and hands every bridge the same object", () => {
    const { conn, socket } = connect()
    const first = vi.fn()
    const second = vi.fn()
    conn.subscribe(first)
    conn.subscribe(second)

    socket.deliver({ type: "channelState", channels: [] })

    // Identity, not equality: two parses would produce two objects, which is the
    // 24-parses-per-frame cost this seam exists to avoid.
    const parsed = first.mock.calls[0][2]
    expect(parsed).toEqual({ type: "channelState", channels: [] })
    expect(second.mock.calls[0][2]).toBe(parsed)
  })

  it("hands bridges a null body for a frame that is not valid JSON", () => {
    vi.spyOn(console, "error").mockImplementation(() => {})
    const { conn, socket } = connect()
    const bridge = vi.fn()
    conn.subscribe(bridge)

    socket.onmessage?.(new MessageEvent("message", { data: "{not json" }))

    expect(bridge).toHaveBeenCalledWith(InternalEventType.message, expect.anything(), null)
  })

  it("hands bridges a null body on lifecycle events", () => {
    const { conn, socket } = connect()
    const bridge = vi.fn()
    conn.subscribe(bridge)

    socket.onopen?.(new Event("open"))

    expect(bridge).toHaveBeenCalledWith(InternalEventType.open, expect.anything(), null)
  })

  it("does not let a throwing bridge starve the ones registered after it", () => {
    const logged = vi.spyOn(console, "error").mockImplementation(() => {})
    const { conn, socket } = connect()
    const thrower = vi.fn(() => {
      throw new Error("bridge blew up")
    })
    const later = vi.fn()
    conn.subscribe(thrower)
    conn.subscribe(later)

    socket.deliver({ type: "cueListChanged" })

    expect(thrower).toHaveBeenCalledTimes(1)
    expect(later).toHaveBeenCalledTimes(1)
    // Hardening, not silence: a bridge that throws has to be visible.
    expect(logged).toHaveBeenCalled()
  })

  it("writes a frame and reports success while the socket is OPEN", () => {
    const { conn, socket } = connect()
    const written = vi.spyOn(socket, "send")

    expect(conn.send('{"type":"programmer.clearAll"}')).toBe(true)
    expect(written).toHaveBeenCalledWith('{"type":"programmer.clearAll"}')
  })

  it("reports a write that lands while the socket is down, rather than dropping it silently", () => {
    const { conn, socket } = connect()
    const written = vi.spyOn(socket, "send")
    socket.readyState = FakeWebSocket.CLOSED

    // The reconnect backoff reaches 30 s, so this window is seconds to half a minute long.
    // Callers carrying an operator gesture use the answer to say so — see `sendGesture`.
    expect(conn.send('{"type":"surfaceScaler.setBlackout","enabled":true}')).toBe(false)
    expect(written).not.toHaveBeenCalled()
  })
})
