// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import {
  installRecordingFetch,
  installRelativeUrlRequest,
  updatesWs,
} from "@/test/backendMock"

// lightingApi opens a real WebSocket at import time (jsdom has none). Mock it so the
// module-level bridge in updates.ts wires onto a fake we can fire.
vi.mock("@/api/lightingApi", async () => (await import("@/test/backendMock")).lightingApiMock())

import { updatesApi, type UpdateStatus } from "./updates"
import { restApi } from "./restApi"
import { store } from "./index"

const STATUS: UpdateStatus = {
  channel: "PACKAGED_WINDOWS",
  currentVersion: "1.1.0",
  phase: "UPDATE_AVAILABLE",
  availability: "UPDATE_AVAILABLE",
  latest: {
    tag: "v1.2.0",
    version: "1.2.0",
    htmlUrl: "https://example.invalid/r",
    assetSizeBytes: 1000,
  },
  autoCheckEnabled: true,
  downloadedBytes: 0,
  totalBytes: null,
  live: { showReady: true, activeEffectCount: 0 },
  throttled: false,
}

let fetchMock: ReturnType<typeof installRecordingFetch>

// The fetch argument is a `Request`, not a string — stringifying it directly yields
// "[object Request]", which silently matches nothing and makes every assertion here vacuous.
const statusRequests = () =>
  fetchMock.mock.calls.filter(([input]) => {
    const url = input instanceof Request ? input.url : String(input)
    return url.includes("update/status")
  }).length

// The subscription has to be *held*, not just awaited: an invalidated cache entry with no
// subscribers is simply dropped rather than refetched, which is the difference between this
// suite testing the bridge and it testing nothing.
let subscription: { unsubscribe: () => void } | null = null

async function primeCache() {
  const promise = store.dispatch(updatesApi.endpoints.updateStatus.initiate(undefined))
  subscription = promise
  await promise.unwrap()
}

const cached = () => updatesApi.endpoints.updateStatus.select(undefined)(store.getState()).data

beforeEach(() => {
  installRelativeUrlRequest()
  fetchMock = installRecordingFetch({ "update/status": STATUS })
  // `store` is a module singleton shared by every test in this file, so without this the second
  // test onwards starts with a warm cache and a live subscription from the first.
  store.dispatch(restApi.util.resetApiState())
})

afterEach(() => {
  subscription?.unsubscribe()
  subscription = null
  vi.restoreAllMocks()
})

describe("update WS bridge", () => {
  it("subscribes at module load", () => {
    expect(updatesWs.callback).not.toBeNull()
  })

  /**
   * The whole reason this frame carries a payload. A several-hundred-megabyte download emits
   * progress twice a second; if each tick invalidated the tag, that would be an HTTP round-trip
   * per tick — exactly the traffic the socket exists to avoid.
   */
  it("patches the cached status from a progress frame without refetching", async () => {
    await primeCache()
    const before = statusRequests()

    updatesWs.callback?.({
      phase: "DOWNLOADING",
      availability: "UPDATE_AVAILABLE",
      latestVersion: "v1.2.0",
      downloadedBytes: 512,
      totalBytes: 1000,
    })

    expect(cached()?.phase).toBe("DOWNLOADING")
    expect(cached()?.downloadedBytes).toBe(512)
    expect(cached()?.totalBytes).toBe(1000)
    expect(statusRequests()).toBe(before)
  })

  it("keeps patching without a refetch across many progress ticks", async () => {
    await primeCache()
    const before = statusRequests()

    for (let sent = 100; sent <= 900; sent += 100) {
      updatesWs.callback?.({
        phase: "DOWNLOADING",
        availability: "UPDATE_AVAILABLE",
        latestVersion: "v1.2.0",
        downloadedBytes: sent,
        totalBytes: 1000,
      })
    }

    expect(cached()?.downloadedBytes).toBe(900)
    expect(statusRequests()).toBe(before)
  })

  /**
   * Terminal phases carry fields the frame doesn't — release notes, `error`, `stagedVersion`,
   * `lastApplyOutcome` — so these do have to refetch.
   */
  it.each(["READY_TO_APPLY", "FAILED", "IDLE", "UPDATE_AVAILABLE", "APPLY_REQUESTED"] as const)(
    "refetches on the terminal phase %s",
    async (phase) => {
      await primeCache()
      const before = statusRequests()

      updatesWs.callback?.({
        phase,
        availability: "UPDATE_AVAILABLE",
        latestVersion: "v1.2.0",
        downloadedBytes: 1000,
        totalBytes: 1000,
      })
      await vi.waitFor(() => expect(statusRequests()).toBeGreaterThan(before))
    },
  )

  it("does not refetch on CHECKING, which carries nothing new", async () => {
    await primeCache()
    const before = statusRequests()

    updatesWs.callback?.({
      phase: "CHECKING",
      availability: "UPDATE_AVAILABLE",
      latestVersion: "v1.2.0",
      downloadedBytes: 0,
      totalBytes: null,
    })

    expect(cached()?.phase).toBe("CHECKING")
    expect(statusRequests()).toBe(before)
  })

  /** A frame with no total must not wipe a total the cache already knows. */
  it("leaves a known totalBytes alone when the frame omits it", async () => {
    await primeCache()
    updatesWs.callback?.({
      phase: "DOWNLOADING",
      availability: "UPDATE_AVAILABLE",
      latestVersion: "v1.2.0",
      downloadedBytes: 10,
      totalBytes: 1000,
    })
    expect(cached()?.totalBytes).toBe(1000)

    updatesWs.callback?.({
      phase: "DOWNLOADING",
      availability: "UPDATE_AVAILABLE",
      latestVersion: "v1.2.0",
      downloadedBytes: 20,
      totalBytes: null,
    })

    expect(cached()?.totalBytes).toBe(1000)
    expect(cached()?.downloadedBytes).toBe(20)
  })
})
