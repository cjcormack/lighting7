// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import {
  installRecordingFetch,
  installRelativeUrlRequest,
  ownAccountWs,
  usersWs,
} from "@/test/backendMock"

// lightingApi opens a real WebSocket at import time (jsdom has none). Mock it so the
// module-level bridges in users.ts and auth.ts wire onto fakes we can fire.
vi.mock("@/api/lightingApi", async () => (await import("@/test/backendMock")).lightingApiMock())

// Importing these runs their module-level bridges and pulls in the real store + restApi.
import { usersApi, type DeskUser } from "./users"
import { authApi, type AuthStatus } from "./auth"
import { restApi } from "./restApi"
import { store } from "./index"

const USERS: DeskUser[] = [
  {
    id: 1,
    uuid: "u-1",
    username: "chris",
    displayName: "Chris",
    role: "ADMIN",
    disabled: false,
    createdAt: new Date(1).toISOString(),
  },
]

const STATUS: AuthStatus = {
  setupRequired: false,
  authenticated: true,
  user: { uuid: "u-1", username: "chris", displayName: "Chris", role: "ADMIN" },
}

let fetchMock: ReturnType<typeof installRecordingFetch>

beforeEach(() => {
  installRelativeUrlRequest()
  fetchMock = installRecordingFetch({ users: USERS, "auth/status": STATUS })
})

afterEach(() => {
  store.dispatch(restApi.util.resetApiState())
  vi.unstubAllGlobals()
})

const countFetches = (fragment: string) =>
  fetchMock.mock.calls.filter(([input]) =>
    (input instanceof Request ? input.url : String(input)).includes(fragment),
  ).length

const selectUsers = () => usersApi.endpoints.users.select(undefined)(store.getState())

describe("user-list WS bridge", () => {
  it("refetches the user list when the socket reports a change on another client", async () => {
    const sub = store.dispatch(usersApi.endpoints.users.initiate())
    try {
      await vi.waitFor(() => expect(selectUsers().status).toBe("fulfilled"))
      const before = countFetches("users")

      // An admin renamed somebody in another browser. Nothing local mutated, so without the
      // bridge this cache would sit stale until the query happened to refetch.
      expect(usersWs.callback).not.toBeNull()
      usersWs.callback!()

      await vi.waitFor(() => expect(countFetches("users")).toBe(before + 1))
    } finally {
      sub.unsubscribe()
    }
  })

  // The whole reason the backend sends two separate frames: `userListChanged` goes to every
  // socket, so if it also invalidated `Auth`, one admin edit would make every connected client
  // re-read its own session. Only the targeted `ownAccountChanged` may do that.
  it("does NOT re-read auth/status — that is the targeted frame's job", async () => {
    const users = store.dispatch(usersApi.endpoints.users.initiate())
    const status = store.dispatch(authApi.endpoints.authStatus.initiate())
    try {
      await vi.waitFor(() => expect(selectUsers().status).toBe("fulfilled"))
      await vi.waitFor(() => expect(countFetches("auth/status")).toBe(1))
      const statusBefore = countFetches("auth/status")

      usersWs.callback!()
      await vi.waitFor(() => expect(countFetches("users")).toBeGreaterThan(1))

      expect(countFetches("auth/status")).toBe(statusBefore)
    } finally {
      users.unsubscribe()
      status.unsubscribe()
    }
  })

  it("re-reads auth/status when the targeted own-account frame arrives", async () => {
    const status = store.dispatch(authApi.endpoints.authStatus.initiate())
    try {
      await vi.waitFor(() => expect(countFetches("auth/status")).toBe(1))

      // An admin re-roled us. The session is still good — only the identity behind it changed.
      expect(ownAccountWs.callback).not.toBeNull()
      ownAccountWs.callback!()

      await vi.waitFor(() => expect(countFetches("auth/status")).toBe(2))
    } finally {
      status.unsubscribe()
    }
  })
})
