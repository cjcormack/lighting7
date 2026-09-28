// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import {
  authWs,
  failWith,
  installRecordingFetch,
  installRelativeUrlRequest,
} from "@/test/backendMock"

// lightingApi opens a real WebSocket at import time (jsdom has none). Mock it so the
// module-level 4401 bridge in auth.ts wires onto a fake `subscribeUnauthenticated`,
// letting us capture the callback (via authWs) and fire a synthetic rejection.
vi.mock("@/api/lightingApi", async () => (await import("@/test/backendMock")).lightingApiMock())

// Importing auth runs the module-level bridge and pulls in the real store + restApi.
import { authApi, type AuthStatus } from "./auth"
import { restApi } from "./restApi"
import { store } from "./index"

const SIGNED_IN: AuthStatus = {
  setupRequired: false,
  authenticated: true,
  user: { uuid: "u-1", username: "chris", displayName: "Chris", role: "ADMIN" },
}

let currentStatus: AuthStatus
let fetchMock: ReturnType<typeof installRecordingFetch>

beforeEach(() => {
  currentStatus = { ...SIGNED_IN }
  installRelativeUrlRequest()
})

afterEach(() => {
  store.dispatch(restApi.util.resetApiState())
  vi.unstubAllGlobals()
})

const statusFetchCount = () =>
  fetchMock.mock.calls.filter(([input]) =>
    (input instanceof Request ? input.url : String(input)).includes("auth/status"),
  ).length

const selectStatus = () => authApi.endpoints.authStatus.select(undefined)(store.getState())

// installRecordingFetch takes static bodies; auth/status has to answer with whatever
// `currentStatus` currently is, so build the route map fresh per test.
function install(routes: Record<string, unknown> = {}) {
  fetchMock = installRecordingFetch({ "auth/status": currentStatus, ...routes })
}

describe("auth 401 handling", () => {
  it("re-checks auth/status when any endpoint answers 401", async () => {
    install({ "auth/sessions": failWith(401, { error: "Not signed in" }) })
    const sub = store.dispatch(authApi.endpoints.authStatus.initiate())
    try {
      await vi.waitFor(() => expect(selectStatus().status).toBe("fulfilled"))
      const before = statusFetchCount()

      // The session died server-side; the next request to any route says so.
      await store.dispatch(authApi.endpoints.sessions.initiate())

      await vi.waitFor(() => expect(statusFetchCount()).toBeGreaterThan(before))
    } finally {
      sub.unsubscribe()
    }
  })

  it("picks up the signed-out status on that re-check, which is what flips the gate", async () => {
    install({ "auth/sessions": failWith(401, { error: "Not signed in" }) })
    const sub = store.dispatch(authApi.endpoints.authStatus.initiate())
    try {
      await vi.waitFor(() => expect(selectStatus().data?.authenticated).toBe(true))

      // The backend now reports the session as gone; re-mock before triggering.
      currentStatus = { setupRequired: false, authenticated: false }
      install({ "auth/sessions": failWith(401, { error: "Not signed in" }) })

      await store.dispatch(authApi.endpoints.sessions.initiate())

      await vi.waitFor(() => expect(selectStatus().data?.authenticated).toBe(false))
    } finally {
      sub.unsubscribe()
    }
  })

  it("does NOT re-check on a 401 from login — that's a wrong password, not a dead session", async () => {
    install({ "auth/login": failWith(401, { error: "Incorrect username or password" }) })
    const sub = store.dispatch(authApi.endpoints.authStatus.initiate())
    try {
      await vi.waitFor(() => expect(selectStatus().status).toBe("fulfilled"))
      const before = statusFetchCount()

      await store.dispatch(
        authApi.endpoints.login.initiate({ username: "chris", password: "wrong" }),
      )

      // Give any stray invalidation a chance to land before asserting it didn't.
      await new Promise((resolve) => setTimeout(resolve, 50))
      expect(statusFetchCount()).toBe(before)
    } finally {
      sub.unsubscribe()
    }
  })
})

describe("auth WS bridge", () => {
  it("registers a 4401 subscriber at import time", () => {
    expect(authWs.callback).toBeTypeOf("function")
  })

  it("re-checks auth/status when the socket is closed with 4401", async () => {
    install()
    const sub = store.dispatch(authApi.endpoints.authStatus.initiate())
    try {
      await vi.waitFor(() => expect(selectStatus().status).toBe("fulfilled"))
      const before = statusFetchCount()

      // Simulate the server rejecting our session on the socket.
      authWs.callback!()

      await vi.waitFor(() => expect(statusFetchCount()).toBeGreaterThan(before))
    } finally {
      sub.unsubscribe()
    }
  })
})
