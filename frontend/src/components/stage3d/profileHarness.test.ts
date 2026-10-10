/**
 * @vitest-environment jsdom
 */
import { describe, it, expect, beforeEach, afterEach } from "vitest"
import { aimStatic, buildHarness, CLOTH_HARNESS_CHANNELS, clothScene, DRAPE_HARNESS_CHANNELS, FOCUS_HARNESS_HEADS, FOCUS_HARNESS_THROW_M, harnessMode, isHarnessActive, materialScene } from "./profileHarness"
import { buildElement } from "./scene/builders"
import { partTransmit } from "./scene/sceneParts"
import { SCRIM_THREAD_SHARE } from "./scene/scrimOpen"
import { MUSLIN_TRANSMITTANCE, surfaceDrawOf } from "./scene/seeThrough"
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

describe("the material scenes (stage-light plan session 2)", () => {
  /** A static lantern's beam at its base pose: yaw 0 at the house, +pitch down. */
  const beam = (yawDeg: number, pitchDeg: number) => {
    const y = (yawDeg * Math.PI) / 180
    const p = (pitchDeg * Math.PI) / 180
    return { x: Math.sin(y) * Math.cos(p), y: -Math.cos(y) * Math.cos(p), z: -Math.sin(p) }
  }

  it("points each fixed lantern at its pool", () => {
    for (const mode of ["rake", "floor", "cyc", "shadow"] as const) {
      for (const spot of materialScene(mode).spots) {
        const { baseYawDeg, basePitchDeg } = aimStatic(spot.from, spot.at)
        const d = beam(baseYawDeg, basePitchDeg)
        const len = Math.hypot(spot.at.x - spot.from.x, spot.at.y - spot.from.y, spot.at.z - spot.from.z)
        expect(d.x * len + spot.from.x).toBeCloseTo(spot.at.x, 9)
        expect(d.y * len + spot.from.y).toBeCloseTo(spot.at.y, 9)
        expect(d.z * len + spot.from.z).toBeCloseTo(spot.at.z, 9)
      }
    }
  })

  it("rakes one serge cloth square on, at 45° and at 75°", () => {
    const { spots, elements } = materialScene("rake")
    expect(elements.map((e) => [e.kind, e.finishColour])).toEqual([["DRAPE", "#101012"]])
    const off = spots.map((s) => Math.round((Math.atan2(s.at.x - s.from.x, s.at.y - s.from.y) * 180) / Math.PI))
    expect(off).toEqual([0, 45, 75])
  })

  it("lays three floors and hangs a cyc, serge and a red drape, each under its own light", () => {
    const data = buildHarness(10, 8, 6, "floor")
    expect(data.elements?.map((e) => e.kind)).toEqual(["PLATFORM", "PLATFORM", "PLATFORM"])
    expect(data.patches).toHaveLength(3)
    const cyc = buildHarness(10, 8, 6, "cyc")
    expect(cyc.elements?.map((e) => [e.finishColour, e.params.role])).toEqual([["#e8e6df", "CYC"], ["#101012", "LEG"], ["#3b1219", "LEG"]])
    // Nothing to write: the lanterns carry no channels and burn at full.
    expect(cyc.syntheticFixture.properties).toEqual([])
  })

  it("stands a flat in front of a wall under one profile, lit from the side (session 3)", () => {
    const data = buildHarness(10, 8, 6, "shadow")
    expect(data.elements?.map((e) => [e.kind, e.name, e.positionY])).toEqual([["FLAT", "Wall", 6], ["FLAT", "Flat", 4.5]])
    expect(data.patches).toHaveLength(1)
    const [spot] = materialScene("shadow").spots
    // From stage left, so the shadow falls stage right of the flat rather than straight behind it.
    expect(spot.from.x).toBeGreaterThan(spot.at.x)
  })
})

describe("the cloth scenes (scrim plan session 6)", () => {
  const FOLIAGE = "f".repeat(64)
  const DAY = "d".repeat(64)
  const NIGHT = "a".repeat(64)
  const images = { foliage: FOLIAGE, dayFront: DAY, nightBack: NIGHT }
  const transmits = (mode: "scrim" | "cutcloth" | "daynight") =>
    clothScene(mode, images).elements.flatMap((e) => buildElement(e).parts.map((p) => ({ element: e.name, part: p, transmit: partTransmit(p) })))

  it("each builds, every lantern dimmed by its side's channel, a fixture to a patch", () => {
    for (const mode of ["scrim", "cutcloth", "daynight"] as const) {
      const data = buildHarness(10, 8, 6, mode, images)
      expect(data.elements!.length).toBeGreaterThan(0)
      expect(data.patches.length).toBe(clothScene(mode).spots.length)
      for (const element of data.elements!) expect(buildElement(element).parts.length).toBeGreaterThan(0)
      const sides = new Set<number>()
      for (const patch of data.patches) {
        const fixture = data.fixtureFor?.get(patch.key)
        expect(fixture?.key).toBe(patch.key)
        expect(fixture?.properties[0]).toMatchObject({ category: "dimmer", channel: { universe: 1 } })
        const channelNo = (fixture?.properties[0] as SliderPropertyDescriptor).channel.channelNo
        expect(Object.values(CLOTH_HARNESS_CHANNELS)).toContain(channelNo)
        sides.add(channelNo)
        // Inside the default stage box, and in front of its back wall.
        expect(patch.stageY!).toBeLessThan(8)
      }
      // Something on each side of the cloth; the scrim scene's threads have two channels of their own.
      expect(sides.size).toBe(mode === "scrim" ? 4 : 2)
    }
  })

  it("=scrim hangs a sharkstooth and a bobbinet gauze side by side, each a net through the one open(θ)", () => {
    const nets = transmits("scrim").filter((t) => t.transmit?.kind === "angle")
    expect(nets.map((t) => t.element).sort()).toEqual(["Bobbinet gauze", "Sharkstooth gauze"])
    expect(nets.map((t) => (t.transmit as { r: number }).r).sort()).toEqual([SCRIM_THREAD_SHARE.BOBBINET, SCRIM_THREAD_SHARE.SHARKSTOOTH].sort())
    for (const net of nets) expect(surfaceDrawOf(net.part.light, net.part.finish.translucent)).toBe("scrim")
    // A grazing light and a light behind for each net's threads, at the angles the comment says.
    for (const spot of clothScene("scrim").spots.filter((s) => s.side === "graze" || s.side === "behind")) {
      const dx = spot.at.x - spot.from.x
      const dy = spot.at.y - spot.from.y
      const dz = spot.at.z - spot.from.z
      const offNormal = (Math.acos(Math.abs(dy) / Math.hypot(dx, dy, dz)) * 180) / Math.PI
      expect(offNormal).toBeCloseTo(spot.side === "graze" ? 83.3 : 53.97, 1)
      expect(spot.from.y > 3).toBe(spot.side === "behind")
    }
    // Behind each, something solid for the reveal to show.
    const solid = transmits("scrim").filter((t) => t.part.light === "solid").map((t) => t.element)
    expect(solid).toEqual(expect.arrayContaining(["Doorway 1", "Doorway 2", "Column 1", "Column 2", "Black backcloth"]))
  })

  it("=cutcloth paints a canvas border whose paint's alpha cuts it, back lights behind it", () => {
    const border = transmits("cutcloth").find((t) => t.element === "Foliage border")!
    expect(border.transmit).toMatchObject({ kind: "mask", image: FOLIAGE })
    expect(surfaceDrawOf(border.part.light, border.part.finish.translucent)).toBe("opaque")
    const { spots } = clothScene("cutcloth")
    for (const spot of spots.filter((s) => s.side === "back")) {
      // Upstage of the border, aimed down through it at the front of the stage.
      expect(spot.from.y).toBeGreaterThan(3)
      const t = (spot.from.y - 3) / (spot.from.y - spot.at.y)
      const z = spot.from.z + (spot.at.z - spot.from.z) * t
      expect(z).toBeGreaterThan(2.6)
      expect(z).toBeLessThan(5.6)
    }
    // Unpainted until its image lands: a plain canvas border, solid.
    const plain = clothScene("cutcloth").elements.find((e) => e.name === "Foliage border")!
    expect(buildElement(plain).parts[0].light).toBe("solid")
  })

  it("=daynight paints a muslin front and back, lit from either side", () => {
    const cloth = transmits("daynight").find((t) => t.element === "Day/night cloth")!
    expect(cloth.part.finish.paint).toEqual({ front: DAY, back: NIGHT })
    expect(cloth.part.finish.translucent).toBe(MUSLIN_TRANSMITTANCE)
    expect(surfaceDrawOf(cloth.part.light, cloth.part.finish.translucent)).toBe("translucent")
    const { spots } = clothScene("daynight")
    expect(spots.filter((s) => s.side === "front").every((s) => s.from.y < 3.6)).toBe(true)
    expect(spots.filter((s) => s.side === "back").every((s) => s.from.y > 3.6)).toBe(true)
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

  it("returns a material scene for ?profileHarness=rake, floor, cyc, shadow and gloss", () => {
    for (const mode of ["rake", "floor", "cyc", "shadow", "gloss"]) {
      setSearch(`?profileHarness=${mode}`)
      expect(harnessMode()).toBe(mode)
    }
  })

  it("returns a cloth scene for ?profileHarness=scrim, cutcloth and daynight", () => {
    for (const mode of ["scrim", "cutcloth", "daynight"]) {
      setSearch(`?profileHarness=${mode}`)
      expect(harnessMode()).toBe(mode)
    }
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
