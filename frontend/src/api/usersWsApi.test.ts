import { describe, expect, it, vi } from "vitest"
import { InternalEventType } from "./internalApi"
import { fakeWsConnection } from "../test/fakeWsConnection"
import { createUsersWsApi } from "./usersWsApi"
import { createInstallWsApi } from "./installWsApi"

function frame(type: string) {
  return new MessageEvent("message", { data: JSON.stringify({ type }) })
}

describe("createUsersWsApi", () => {
  it("does NOT fire on an 'open' event — the reconnect resync owns that", () => {
    const { conn, fire } = fakeWsConnection()
    const spy = vi.fn()
    createUsersWsApi(conn).subscribe(spy)

    fire(InternalEventType.open, new Event("open"))

    // `store/status.ts` invalidates `UserList`/`User` with every other tag on CLOSED→OPEN.
    expect(spy).not.toHaveBeenCalled()
  })

  it("fires on a 'userListChanged' message frame", () => {
    const { conn, fire } = fakeWsConnection()
    const spy = vi.fn()
    createUsersWsApi(conn).subscribe(spy)

    fire(InternalEventType.message, frame("userListChanged"))

    expect(spy).toHaveBeenCalledTimes(1)
  })

  it("does NOT fire on the sibling machine-scoped frames", () => {
    const { conn, fire } = fakeWsConnection()
    const spy = vi.fn()
    createUsersWsApi(conn).subscribe(spy)

    // Both ride the same backend collector (plugins/MachineSocket.kt), so a discriminator
    // check that was too loose would cross-fire and refetch the user list on an install
    // rename — or worse, on the targeted own-account frame.
    fire(InternalEventType.message, frame("installChanged"))
    fire(InternalEventType.message, frame("ownAccountChanged"))

    expect(spy).not.toHaveBeenCalled()
  })

  it("does NOT fire when the message event is not a MessageEvent", () => {
    const { conn, fire } = fakeWsConnection()
    const spy = vi.fn()
    createUsersWsApi(conn).subscribe(spy)

    fire(InternalEventType.message, new Event("message"))

    expect(spy).not.toHaveBeenCalled()
  })

  it("does NOT fire when the parsed body is null", () => {
    const { conn, fire } = fakeWsConnection()
    const spy = vi.fn()
    createUsersWsApi(conn).subscribe(spy)

    fire(InternalEventType.message, new MessageEvent("message", { data: "null" }))

    expect(spy).not.toHaveBeenCalled()
  })

  it("notifies every subscriber, and stops after unsubscribe", () => {
    const { conn, fire } = fakeWsConnection()
    const api = createUsersWsApi(conn)
    const a = vi.fn()
    const b = vi.fn()
    const subA = api.subscribe(a)
    api.subscribe(b)

    fire(InternalEventType.message, frame("userListChanged"))
    expect(a).toHaveBeenCalledTimes(1)
    expect(b).toHaveBeenCalledTimes(1)

    subA.unsubscribe()
    fire(InternalEventType.message, frame("userListChanged"))
    expect(a).toHaveBeenCalledTimes(1) // no further deliveries
    expect(b).toHaveBeenCalledTimes(2)
  })
})

describe("createInstallWsApi", () => {
  it("fires on an 'installChanged' frame, but not on 'open'", () => {
    const { conn, fire } = fakeWsConnection()
    const spy = vi.fn()
    createInstallWsApi(conn).subscribe(spy)

    fire(InternalEventType.open, new Event("open"))
    fire(InternalEventType.message, frame("installChanged"))

    // Reconnect catch-up is `store/status.ts`'s, so only the pushed frame counts here.
    expect(spy).toHaveBeenCalledTimes(1)
  })

  it("does NOT fire on the sibling machine-scoped frames", () => {
    const { conn, fire } = fakeWsConnection()
    const spy = vi.fn()
    createInstallWsApi(conn).subscribe(spy)

    fire(InternalEventType.message, frame("userListChanged"))
    fire(InternalEventType.message, frame("ownAccountChanged"))

    expect(spy).not.toHaveBeenCalled()
  })

  it("does NOT fire on a non-MessageEvent or a null body", () => {
    const { conn, fire } = fakeWsConnection()
    const spy = vi.fn()
    createInstallWsApi(conn).subscribe(spy)

    fire(InternalEventType.message, new Event("message"))
    fire(InternalEventType.message, new MessageEvent("message", { data: "null" }))

    expect(spy).not.toHaveBeenCalled()
  })
})
