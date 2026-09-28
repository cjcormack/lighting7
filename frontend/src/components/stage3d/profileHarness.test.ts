/**
 * @vitest-environment jsdom
 */
import { describe, it, expect, beforeEach, afterEach } from "vitest"
import { buildHarness, isHarnessActive } from "./profileHarness"

describe("buildHarness", () => {
  it("returns exactly 50 patches, 16 regions, 8 riggings", () => {
    const data = buildHarness(10, 8, 6)
    expect(data.patches).toHaveLength(50)
    expect(data.regions).toHaveLength(16)
    expect(data.riggings).toHaveLength(8)
  })

  it("synthetic type advertises acceptsBeamAngle", () => {
    const data = buildHarness(10, 8, 6)
    expect(data.syntheticType.acceptsBeamAngle).toBe(true)
  })

  it("is deterministic — two calls with the same inputs produce structurally equal output", () => {
    const a = buildHarness(10, 8, 6)
    const b = buildHarness(10, 8, 6)
    expect(a.patches).toEqual(b.patches)
    expect(a.regions).toEqual(b.regions)
    expect(a.riggings).toEqual(b.riggings)
    expect(a.syntheticFixture).toEqual(b.syntheticFixture)
    expect(a.syntheticType).toEqual(b.syntheticType)
  })

  it("synthetic fixture references the synthetic type key", () => {
    const data = buildHarness(10, 8, 6)
    expect(data.syntheticFixture.typeKey).toBe(data.syntheticType.typeKey)
  })
})

describe("isHarnessActive", () => {
  const originalSearch = window.location.search

  function setSearch(query: string) {
    // jsdom permits assigning location.search via history.replaceState.
    window.history.replaceState({}, "", `/${query}`)
  }

  beforeEach(() => {
    setSearch("")
  })

  afterEach(() => {
    window.history.replaceState({}, "", `/${originalSearch}`)
  })

  it("returns true when ?profileHarness=1 is present", () => {
    setSearch("?profileHarness=1")
    expect(isHarnessActive()).toBe(true)
  })

  it("returns false when the flag is absent", () => {
    setSearch("?foo=bar")
    expect(isHarnessActive()).toBe(false)
  })

  it("returns false when the flag has a different value", () => {
    setSearch("?profileHarness=0")
    expect(isHarnessActive()).toBe(false)
    setSearch("?profileHarness=true")
    expect(isHarnessActive()).toBe(false)
  })
})
