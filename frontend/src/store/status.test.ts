// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import { statusWs } from "@/test/backendMock"

// lightingApi opens a real WebSocket at import time (jsdom has none). Mock it so the
// module-level reconnect bridge in status.ts wires onto a fake `subscribe`, letting a test
// drive synthetic readyState transitions through `statusWs.fire`.
vi.mock("@/api/lightingApi", async () => (await import("@/test/backendMock")).lightingApiMock())

import {
  RECONNECT_RESYNC_TAGS,
  RECONNECT_RESYNC_WAVES,
  RESYNC_DEBOUNCE_MS,
  RESYNC_WAVE_INTERVAL_MS,
  RESYNC_WAVE_SIZE,
} from "./status"
import { REST_TAG_TYPES } from "./restApi"
import { Status } from "@/api/statusApi"
import { store } from "./index"

/** The `open` branches deleted when the resync became derived; each tag must still be covered. */
const TAGS_FORMERLY_COVERED_BY_A_BRIDGE = [
  "BootStatus",
  "CueSlotList",
  "CueStackList",
  "Fixture",
  "GroupList",
  "Install",
  "Look",
  "LookList",
  "PromptBook",
  "Rigging",
  "SpeedMaster",
  "SpeedMasterList",
  "StageRegion",
  // `Template` stood here too. Its only provider was the single-template read, deleted with the
  // rest of the caller-less endpoints; `TemplateList` is what the library actually subscribes to.
  "TemplateList",
  "User",
  "UserList",
] as const

/** Tags the sweep found with no reconnect path at all — the desk failures this item fixes. */
const TAGS_FORMERLY_UNCOVERED = [
  "ProgramState",
  "Patch",
  "UniverseConfig",
  "Cue",
  "ControlSurfaceType",
  "SurfaceBinding",
  "PerfMidi",
  "FxLibrary",
  "Update",
  "AuthSessions",
  "DeviceLogin",
] as const

/**
 * Tags whose bridge is a **payload-free** frame that only ever invalidates — so a reconnect, where
 * that frame may have been missed, has to reach them through the resync and nothing else can.
 */
const TAGS_BEHIND_A_PAYLOAD_FREE_BRIDGE = ["BuskRig"] as const

const excluded = () => REST_TAG_TYPES.filter((tag) => !RECONNECT_RESYNC_TAGS.includes(tag))

describe("reconnect resync coverage", () => {
  it("accounts for every declared tag — resynced, or deliberately excluded", () => {
    // The resync is derived from REST_TAG_TYPES, so a new tag is covered by construction.
    // What this pins is the other half: the exclusion set stays a short, argued list rather
    // than growing back into the hand-maintained partial list it replaced.
    expect(excluded()).toEqual(["Auth"])
    expect(RECONNECT_RESYNC_TAGS.length).toBe(REST_TAG_TYPES.length - 1)
  })

  it("covers the tags that used to rely on a per-bridge 'open' branch", () => {
    for (const tag of TAGS_FORMERLY_COVERED_BY_A_BRIDGE) {
      expect(RECONNECT_RESYNC_TAGS).toContain(tag)
    }
  })

  it("covers the tags that had no reconnect path at all", () => {
    for (const tag of TAGS_FORMERLY_UNCOVERED) {
      expect(RECONNECT_RESYNC_TAGS).toContain(tag)
    }
  })

  it("covers the tags behind a payload-free bridge", () => {
    for (const tag of TAGS_BEHIND_A_PAYLOAD_FREE_BRIDGE) {
      expect(REST_TAG_TYPES).toContain(tag)
      expect(RECONNECT_RESYNC_TAGS).toContain(tag)
    }
  })
})

describe("reconnect resync waves", () => {
  it("covers exactly the resync set, in order, with no tag lost or repeated", () => {
    // The load-bearing pin: waves are a *transport* detail, so a new tag must never be able to
    // fall out of the resync by landing in no wave.
    expect(RECONNECT_RESYNC_WAVES.flat()).toEqual(
      expect.arrayContaining([...RECONNECT_RESYNC_TAGS]),
    )
    expect(RECONNECT_RESYNC_WAVES.flat()).toHaveLength(RECONNECT_RESYNC_TAGS.length)
    expect(new Set(RECONNECT_RESYNC_WAVES.flat()).size).toBe(RECONNECT_RESYNC_TAGS.length)
  })

  it("puts the operator-visible caches in the first wave, and caps every wave", () => {
    expect(RECONNECT_RESYNC_WAVES[0]).toContain("ProgramState")
    expect(RECONNECT_RESYNC_WAVES[0]).toContain("Patch")
    for (const wave of RECONNECT_RESYNC_WAVES) {
      expect(wave.length).toBeLessThanOrEqual(RESYNC_WAVE_SIZE)
      expect(wave.length).toBeGreaterThan(0)
    }
  })
})

describe("reconnect resync dispatch", () => {
  beforeEach(() => {
    vi.useFakeTimers()
    // Leave the module's `previousStatus` at a non-OPEN value, and clear any timer a previous
    // test armed — the bridge is module state, shared across this file.
    statusWs.fire(Status.CLOSED)
    vi.runAllTimers()
  })

  afterEach(() => {
    vi.runAllTimers()
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  type DispatchSpy = { mock: { calls: unknown[][] } }

  const invalidations = (spy: DispatchSpy) =>
    spy.mock.calls
      .map(([action]) => action as { type?: string; payload?: unknown })
      .filter((action) => action?.type?.endsWith("/invalidateTags") === true)

  it("waits out the debounce, then invalidates one wave at a time", () => {
    const spy = vi.spyOn(store, "dispatch")

    statusWs.fire(Status.OPEN)
    vi.advanceTimersByTime(RESYNC_DEBOUNCE_MS - 1)
    expect(invalidations(spy)).toHaveLength(0)

    vi.advanceTimersByTime(1)
    expect(invalidations(spy)).toHaveLength(1)
    expect(invalidations(spy)[0].payload).toEqual([...RECONNECT_RESYNC_WAVES[0]])

    vi.advanceTimersByTime(RESYNC_WAVE_INTERVAL_MS)
    expect(invalidations(spy)).toHaveLength(2)
    expect(invalidations(spy)[1].payload).toEqual([...RECONNECT_RESYNC_WAVES[1]])

    vi.advanceTimersByTime(RESYNC_WAVE_INTERVAL_MS * RECONNECT_RESYNC_WAVES.length)
    const payloads = invalidations(spy).map((action) => action.payload)
    expect(payloads).toEqual(RECONNECT_RESYNC_WAVES.map((wave) => [...wave]))
  })

  it("does not re-invalidate while the socket stays open", () => {
    statusWs.fire(Status.OPEN)
    vi.runAllTimers()
    const spy = vi.spyOn(store, "dispatch")

    statusWs.fire(Status.OPEN)
    vi.runAllTimers()

    expect(invalidations(spy)).toHaveLength(0)
  })

  it("resyncs once, not twice, when the socket flaps inside the debounce window", () => {
    const spy = vi.spyOn(store, "dispatch")

    statusWs.fire(Status.OPEN)
    vi.advanceTimersByTime(RESYNC_DEBOUNCE_MS - 50)
    statusWs.fire(Status.CLOSED)
    statusWs.fire(Status.OPEN)
    vi.runAllTimers()

    expect(invalidations(spy)).toHaveLength(RECONNECT_RESYNC_WAVES.length)
  })

  it("abandons the remaining waves when the socket drops mid-resync", () => {
    const spy = vi.spyOn(store, "dispatch")

    statusWs.fire(Status.OPEN)
    vi.advanceTimersByTime(RESYNC_DEBOUNCE_MS + RESYNC_WAVE_INTERVAL_MS)
    expect(invalidations(spy)).toHaveLength(2)

    // Refetching into a backend that just dropped us only produces failures to retry later.
    statusWs.fire(Status.CLOSED)
    vi.runAllTimers()

    expect(invalidations(spy)).toHaveLength(2)
  })
})
