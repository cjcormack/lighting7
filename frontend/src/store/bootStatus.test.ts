// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import type { BootStatus } from "@/api/bootStatusWsApi"
import {
  bootStatusWs,
  installBootStatusFetch,
  installRelativeUrlRequest,
} from "@/test/backendMock"

// lightingApi opens a real WebSocket at import time (jsdom has none). Mock it so
// the module-level WS bridge in bootStatus.ts wires onto a fake `subscribe`,
// letting us capture the callback (via bootStatusWs) and fire a synthetic
// notification.
vi.mock("@/api/lightingApi", async () => (await import("@/test/backendMock")).lightingApiMock())

// Importing bootStatus runs the module-level bridge (captured via the mock) and
// pulls in the real singleton store + restApi.
import { bootStatusApi } from "./bootStatus"
import { restApi } from "./restApi"
import { store } from "./index"

const STARTING: BootStatus = {
  phase: "STARTING",
  message: "Starting…",
  percent: 0,
  ready: false,
  error: null,
}

// Mutable so a "pushed" refetch can return a newer status than the first fetch.
let currentStatus: BootStatus
let fetchMock: ReturnType<typeof installBootStatusFetch>

beforeEach(() => {
  currentStatus = { ...STARTING }
  installRelativeUrlRequest()
  fetchMock = installBootStatusFetch(() => currentStatus)
})

afterEach(() => {
  store.dispatch(restApi.util.resetApiState())
  vi.unstubAllGlobals()
})

const selectStatus = () =>
  bootStatusApi.endpoints.bootStatus.select(undefined)(store.getState())

describe("bootStatus WS bridge", () => {
  it("registers a WS subscriber at import time", () => {
    expect(bootStatusWs.callback).toBeTypeOf("function")
  })

  it("refetches the subscribed status query on a WS notification", async () => {
    const sub = store.dispatch(bootStatusApi.endpoints.bootStatus.initiate())
    try {
      await vi.waitFor(() => expect(selectStatus().status).toBe("fulfilled"))
      expect(selectStatus().data?.phase).toBe("STARTING")
      const afterFirst = fetchMock.mock.calls.length
      expect(afterFirst).toBeGreaterThanOrEqual(1)

      // Simulate a pushed `bootProgressState` frame: the bridge dispatches
      // invalidateTags(['BootStatus']), which forces the live query to refetch.
      currentStatus = { ...currentStatus, phase: "FX_COMPILE", percent: 50 }
      bootStatusWs.callback!()

      await vi.waitFor(() => {
        expect(fetchMock.mock.calls.length).toBeGreaterThan(afterFirst)
        expect(selectStatus().data?.phase).toBe("FX_COMPILE")
      })
    } finally {
      sub.unsubscribe()
    }
  })
})
