// @vitest-environment jsdom
import { describe, expect, it, vi } from "vitest"
import { InternalEventType, UNAUTHENTICATED_CLOSE_CODE } from "./internalApi"
import { fakeWsConnection } from "../test/fakeWsConnection"
import { createAuthWsApi } from "./authWsApi"

function closeEvent(code: number) {
  return new CloseEvent("close", { code })
}

describe("createAuthWsApi", () => {
  it("fires the subscriber on a 4401 close", () => {
    const { conn, fire } = fakeWsConnection()
    const api = createAuthWsApi(conn)
    const spy = vi.fn()
    api.subscribeUnauthenticated(spy)

    fire(InternalEventType.close, closeEvent(UNAUTHENTICATED_CLOSE_CODE))

    expect(spy).toHaveBeenCalledTimes(1)
  })

  it("does NOT fire on an ordinary close", () => {
    const { conn, fire } = fakeWsConnection()
    const api = createAuthWsApi(conn)
    const spy = vi.fn()
    api.subscribeUnauthenticated(spy)

    // 1006 (abnormal) is what a backend restart looks like — that must keep
    // reconnecting, not drop the operator to a login screen.
    fire(InternalEventType.close, closeEvent(1006))
    fire(InternalEventType.close, closeEvent(1000))

    expect(spy).not.toHaveBeenCalled()
  })

  it("does NOT fire on non-close events", () => {
    const { conn, fire } = fakeWsConnection()
    const api = createAuthWsApi(conn)
    const spy = vi.fn()
    api.subscribeUnauthenticated(spy)

    fire(InternalEventType.open, new Event("open"))
    fire(InternalEventType.error, new Event("error"))
    fire(InternalEventType.message, new MessageEvent("message", { data: "{}" }))

    expect(spy).not.toHaveBeenCalled()
  })

  it("does NOT fire when the close event is not a CloseEvent (no code to read)", () => {
    const { conn, fire } = fakeWsConnection()
    const api = createAuthWsApi(conn)
    const spy = vi.fn()
    api.subscribeUnauthenticated(spy)

    fire(InternalEventType.close, new Event("close"))

    expect(spy).not.toHaveBeenCalled()
  })

  it("notifies every subscriber, and stops after unsubscribe", () => {
    const { conn, fire } = fakeWsConnection()
    const api = createAuthWsApi(conn)
    const a = vi.fn()
    const b = vi.fn()
    const subA = api.subscribeUnauthenticated(a)
    api.subscribeUnauthenticated(b)

    fire(InternalEventType.close, closeEvent(UNAUTHENTICATED_CLOSE_CODE))
    expect(a).toHaveBeenCalledTimes(1)
    expect(b).toHaveBeenCalledTimes(1)

    subA.unsubscribe()
    fire(InternalEventType.close, closeEvent(UNAUTHENTICATED_CLOSE_CODE))
    expect(a).toHaveBeenCalledTimes(1)
    expect(b).toHaveBeenCalledTimes(2)
  })

  // The two channels share one `conn.subscribe` handler but mean opposite things — one is "your
  // session is gone, show the login screen", the other is "you are still signed in but your
  // account was edited". Crossing them would either log people out on a rename or leave a
  // revoked session sitting in the app.
  describe("ownAccountChanged, independently of the 4401 channel", () => {
    it("fires on an 'ownAccountChanged' frame, but not the unauthenticated one", () => {
      const { conn, fire } = fakeWsConnection()
      const api = createAuthWsApi(conn)
      const own = vi.fn()
      const unauth = vi.fn()
      api.subscribeOwnAccountChanged(own)
      api.subscribeUnauthenticated(unauth)

      fire(
        InternalEventType.message,
        new MessageEvent("message", { data: JSON.stringify({ type: "ownAccountChanged" }) }),
      )

      expect(own).toHaveBeenCalledTimes(1)
      expect(unauth).not.toHaveBeenCalled()
    })

    it("catches up on re-open, but not on the page's first connect", () => {
      const { conn, fire } = fakeWsConnection()
      const own = vi.fn()
      createAuthWsApi(conn).subscribeOwnAccountChanged(own)

      // The first open is this page's own initial connect, and AuthGate is already fetching
      // `auth/status` at that moment — notifying would just race a duplicate onto the critical
      // path.
      fire(InternalEventType.open, new Event("open"))
      expect(own).not.toHaveBeenCalled()

      // A re-open is a dropped connection or the post-login re-handshake. Here the account may
      // have been renamed or re-roled while the socket was down, and nothing else would notice.
      fire(InternalEventType.open, new Event("open"))
      expect(own).toHaveBeenCalledTimes(1)

      fire(InternalEventType.open, new Event("open"))
      expect(own).toHaveBeenCalledTimes(2)
    })

    it("is NOT fired by a 4401 close", () => {
      const { conn, fire } = fakeWsConnection()
      const api = createAuthWsApi(conn)
      const own = vi.fn()
      const unauth = vi.fn()
      api.subscribeOwnAccountChanged(own)
      api.subscribeUnauthenticated(unauth)

      fire(InternalEventType.close, closeEvent(UNAUTHENTICATED_CLOSE_CODE))

      expect(unauth).toHaveBeenCalledTimes(1)
      expect(own).not.toHaveBeenCalled()
    })

    it("is NOT fired by the sibling machine-scoped frames or a null body", () => {
      const { conn, fire } = fakeWsConnection()
      const api = createAuthWsApi(conn)
      const own = vi.fn()
      api.subscribeOwnAccountChanged(own)

      // `userListChanged` is broadcast to every socket; if it reached this subscriber, every
      // client would refetch `auth/status` on any admin edit — the thundering herd the
      // targeted frame exists to avoid.
      fire(
        InternalEventType.message,
        new MessageEvent("message", { data: JSON.stringify({ type: "userListChanged" }) }),
      )
      fire(
        InternalEventType.message,
        new MessageEvent("message", { data: JSON.stringify({ type: "installChanged" }) }),
      )
      fire(InternalEventType.message, new MessageEvent("message", { data: "null" }))
      fire(InternalEventType.message, new Event("message"))

      expect(own).not.toHaveBeenCalled()
    })
  })
})
