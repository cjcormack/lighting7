/**
 * @vitest-environment jsdom
 */
import { describe, it, expect, beforeEach, afterEach } from "vitest"
import { buildHarness, DRAPE_HARNESS_CHANNELS, FOCUS_HARNESS_HEADS, FOCUS_HARNESS_THROW_M, harnessMode, isHarnessActive } from "./profileHarness"
import { buildElement } from "./scene/builders"
import { resolveDeclaredFocusDistance, resolveFocusParam } from "./beamOptics"
import type { SliderPropertyDescriptor } from "../../store/fixtures"
import focusInverse from "../../../../src/test/resources/stage/focusInverse.fixture.json"

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

describe("the focus scene", () => {
  const data = buildHarness(10, 8, 6, "focus")

  it("hangs three Revolutions, each with a focus channel of its own, throwing 24 m at the back wall", () => {
    expect(data.patches.map((p) => p.key)).toEqual(FOCUS_HARNESS_HEADS.map((h) => h.key))
    for (const p of data.patches) {
      // The back wall is the stage's depth; a level, upstage beam from 24 m in front of it.
      expect(8 - (p.stageY ?? 0)).toBe(FOCUS_HARNESS_THROW_M)
      expect(p.basePitchDeg).toBe(-90)
      const fixture = data.fixtureFor?.get(p.key)
      expect(fixture?.properties[0]).toMatchObject({ category: "focus", focusNearM: 2, focusFarM: 40 })
    }
    const channels = data.patches.map((p) => (data.fixtureFor?.get(p.key)?.properties[0] as SliderPropertyDescriptor).channel.channelNo)
    expect(new Set(channels).size).toBe(3)
    // No depth of field of its own: the family's constant is what the scene tunes.
    expect(data.syntheticType.depthOfField).toBeUndefined()
    expect(data.syntheticType.body).toEqual({ archetype: "mover", head: "profile", lensDiameterM: 0.15 })
  })

  it("names the levels the desk solves for 21, 24 and 27 m — the shared vector's", () => {
    const revolution = focusInverse.cases.find((c) => c.name.startsWith("Source Four Revolution"))!
    for (const head of FOCUS_HARNESS_HEADS) {
      expect(revolution.points.find((p) => p.distanceM === head.focusM)?.level).toBe(head.dmx)
      const prop = data.fixtureFor?.get(head.key)?.properties[0] as SliderPropertyDescriptor
      // Within a DMX step's distance: the byte draws 21.1, 24.0 and 27.6 m.
      expect(Math.abs(resolveDeclaredFocusDistance(prop, resolveFocusParam(prop, head.dmx))! - head.focusM)).toBeLessThan(0.7)
    }
  })
})

describe("the drape scene", () => {
  const data = buildHarness(10, 8, 6, "drape")

  it("hangs one spot as ADV1's Robe hangs, over a black backcloth, a pair of tabs and a column", () => {
    expect(data.patches).toHaveLength(1)
    const [spot] = data.patches
    expect(data.riggings).toEqual([expect.objectContaining({ uuid: spot.riggingUuid, positionY: -1.45, positionZ: 3.15 })])
    expect(spot).toMatchObject({ stageX: 2, stageZ: -0.5, baseYawDeg: 180, basePitchDeg: 180 })
    const shapes = (data.elements ?? []).map((e) => buildElement(e).parts.map((p) => p.geometry.shape))
    expect(shapes).toEqual([["pleat"], ["pleat", "pleat"], ["cylinder"]])
    expect(data.elements?.[0]).toMatchObject({ finishColour: "#101012", params: { role: "BACKCLOTH" } })
  })

  it("puts pan, tilt and focus on channels of their own, at the Robe's travel", () => {
    const channels = data.syntheticFixture.properties.map((p) => (p as SliderPropertyDescriptor).channel.channelNo)
    expect(channels).toEqual([DRAPE_HARNESS_CHANNELS.pan, DRAPE_HARNESS_CHANNELS.tilt, DRAPE_HARNESS_CHANNELS.focus])
    expect(data.syntheticFixture.properties[0]).toMatchObject({ axis: "PAN", degMax: 530 })
    expect(data.syntheticFixture.properties[1]).toMatchObject({ axis: "TILT", degMax: 280 })
  })

  it("leaves the project's scene alone in every other mode", () => {
    expect(buildHarness(10, 8, 6, "load").elements).toBeUndefined()
    expect(buildHarness(10, 8, 6, "focus").elements).toBeUndefined()
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

  it("returns the focus scene for ?profileHarness=focus", () => {
    setSearch("?profileHarness=focus")
    expect(isHarnessActive()).toBe(true)
    expect(harnessMode()).toBe("focus")
    setSearch("?profileHarness=1")
    expect(harnessMode()).toBe("load")
  })

  it("returns the drape scene for ?profileHarness=drape", () => {
    setSearch("?profileHarness=drape")
    expect(harnessMode()).toBe("drape")
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
