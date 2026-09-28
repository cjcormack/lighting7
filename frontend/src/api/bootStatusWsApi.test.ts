import { describe, expect, it, vi } from "vitest"
import { InternalEventType } from "./internalApi"
import { fakeWsConnection } from "../test/fakeWsConnection"
import { BootStatus, createBootStatusWsApi } from "./bootStatusWsApi"

const SAMPLE_STATUS: BootStatus = {
  phase: "FX_COMPILE",
  message: "Compiling effects (12/28)…",
  percent: 45,
  ready: false,
  error: null,
}

function bootFrame(status: BootStatus = SAMPLE_STATUS) {
  return new MessageEvent("message", {
    data: JSON.stringify({ type: "bootProgressState", status }),
  })
}

describe("createBootStatusWsApi", () => {
  it("does NOT fire on an 'open' event — the reconnect resync owns that", () => {
    const { conn, fire } = fakeWsConnection()
    const api = createBootStatusWsApi(conn)
    const spy = vi.fn()
    api.subscribe(spy)

    fire(InternalEventType.open, new Event("open"))

    // Re-checking readiness after a backend restart is `store/status.ts`'s job now: it
    // invalidates `BootStatus` with every other tag on CLOSED→OPEN. A branch here would
    // fire the same refetch a second time.
    expect(spy).not.toHaveBeenCalled()
  })

  it("fires on a 'bootProgressState' message frame", () => {
    const { conn, fire } = fakeWsConnection()
    const api = createBootStatusWsApi(conn)
    const spy = vi.fn()
    api.subscribe(spy)

    fire(InternalEventType.message, bootFrame())

    expect(spy).toHaveBeenCalledTimes(1)
  })

  it("does NOT fire on a message frame of another type", () => {
    const { conn, fire } = fakeWsConnection()
    const api = createBootStatusWsApi(conn)
    const spy = vi.fn()
    api.subscribe(spy)

    fire(
      InternalEventType.message,
      new MessageEvent("message", {
        data: JSON.stringify({ type: "channelUpdate", value: 1 }),
      }),
    )

    expect(spy).not.toHaveBeenCalled()
  })

  it("does NOT fire when the message event is not a MessageEvent", () => {
    const { conn, fire } = fakeWsConnection()
    const api = createBootStatusWsApi(conn)
    const spy = vi.fn()
    api.subscribe(spy)

    // Same 'message' event type, but a plain Event — `parseWsFrame` returns null
    // for it, so the bridge sees no body.
    fire(InternalEventType.message, new Event("message"))

    expect(spy).not.toHaveBeenCalled()
  })

  it("does NOT fire when the parsed body is null", () => {
    const { conn, fire } = fakeWsConnection()
    const api = createBootStatusWsApi(conn)
    const spy = vi.fn()
    api.subscribe(spy)

    fire(InternalEventType.message, new MessageEvent("message", { data: "null" }))

    expect(spy).not.toHaveBeenCalled()
  })

  it("notifies every subscriber", () => {
    const { conn, fire } = fakeWsConnection()
    const api = createBootStatusWsApi(conn)
    const a = vi.fn()
    const b = vi.fn()
    api.subscribe(a)
    api.subscribe(b)

    fire(InternalEventType.message, bootFrame())

    expect(a).toHaveBeenCalledTimes(1)
    expect(b).toHaveBeenCalledTimes(1)
  })

  it("stops delivering to a subscriber after it unsubscribes", () => {
    const { conn, fire } = fakeWsConnection()
    const api = createBootStatusWsApi(conn)
    const spy = vi.fn()
    const sub = api.subscribe(spy)

    fire(InternalEventType.message, bootFrame())
    expect(spy).toHaveBeenCalledTimes(1)

    sub.unsubscribe()
    fire(InternalEventType.message, bootFrame())
    expect(spy).toHaveBeenCalledTimes(1) // no further deliveries
  })
})
