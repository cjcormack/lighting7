import { describe, it, expect } from "vitest"
import {
  applyGuideSnap,
  buildGuideCandidates,
  snapWithGuides,
  type GuideSource,
} from "./stageSnapping"

const SOURCES: GuideSource[] = [
  { id: "a", points: [{ h: 0, v: 0 }] },
  { id: "b", points: [{ h: 2, v: -3 }] },
  { id: "c", points: [{ h: 5, v: -3 }] },
]

describe("buildGuideCandidates", () => {
  it("collects every occupied row and column, sorted", () => {
    const c = buildGuideCandidates(SOURCES, null)
    expect(c.h).toEqual([0, 2, 5])
    expect(c.v).toEqual([-3, 0])
  })

  it("excludes the dragged object so it can't snap to itself", () => {
    const c = buildGuideCandidates(SOURCES, "b")
    expect(c.h).toEqual([0, 5])
    expect(c.v).toEqual([-3, 0])
  })

  it("de-duplicates coincident values", () => {
    // b and c share v = -3; it must contribute one candidate, not two.
    expect(buildGuideCandidates(SOURCES, null).v).toEqual([-3, 0])
  })

  it("takes every point of a multi-point source, e.g. both ends of a truss", () => {
    const c = buildGuideCandidates(
      [{ id: "truss", points: [{ h: -6, v: -5 }, { h: 6, v: -4 }] }],
      null,
    )
    expect(c.h).toEqual([-6, 6])
    expect(c.v).toEqual([-5, -4])
  })

  it("returns empty candidates when everything is excluded", () => {
    expect(buildGuideCandidates([{ id: "only", points: [{ h: 1, v: 1 }] }], "only")).toEqual({
      h: [],
      v: [],
    })
  })
})

describe("applyGuideSnap", () => {
  const candidates = buildGuideCandidates(SOURCES, null)

  it("snaps onto a candidate inside tolerance", () => {
    const r = applyGuideSnap({ h: 2.05, v: -2.95 }, candidates, 0.1)
    expect(r.p).toEqual({ h: 2, v: -3 })
    expect(r.hitH).toBe(2)
    expect(r.hitV).toBe(-3)
  })

  it("leaves the point alone outside tolerance", () => {
    const r = applyGuideSnap({ h: 2.5, v: -2.5 }, candidates, 0.1)
    expect(r.p).toEqual({ h: 2.5, v: -2.5 })
    expect(r.hitH).toBeNull()
    expect(r.hitV).toBeNull()
  })

  it("decides the two axes independently", () => {
    // h aligns with 'c' at 5, v aligns with 'a' at 0 — different neighbours.
    const r = applyGuideSnap({ h: 4.98, v: 0.02 }, candidates, 0.1)
    expect(r.p).toEqual({ h: 5, v: 0 })
    expect(r.hitH).toBe(5)
    expect(r.hitV).toBe(0)
  })

  it("picks the nearer of two candidates", () => {
    const r = applyGuideSnap({ h: 1.6, v: 0 }, candidates, 2)
    expect(r.hitH).toBe(2)
  })

  it("treats a value exactly at tolerance as a hit", () => {
    // 0.5 and 2.5 are exactly representable, so this tests the boundary rather
    // than float noise: |2.1 - 2| is actually 0.10000000000000009.
    expect(applyGuideSnap({ h: 2.5, v: 99 }, candidates, 0.5).hitH).toBe(2)
  })

  it("does not snap just outside tolerance", () => {
    expect(applyGuideSnap({ h: 2.75, v: 99 }, candidates, 0.5).hitH).toBeNull()
  })
})

describe("snapWithGuides", () => {
  const candidates = buildGuideCandidates(SOURCES, null)
  const quarter = (v: number) => Math.round(v / 0.25) * 0.25

  it("prefers a guide over the grid on the axis it claims", () => {
    // 2.05 would grid-snap to 2.0 here too, so use a candidate that is NOT a grid
    // multiple to prove the guide won.
    const offGrid = buildGuideCandidates([{ id: "x", points: [{ h: 2.11, v: 0 }] }], null)
    const r = snapWithGuides({ h: 2.13, v: 9 }, offGrid, 0.1, quarter)
    expect(r.p.h).toBe(2.11)
    expect(r.hitH).toBe(2.11)
  })

  it("falls back to the grid on an axis no guide claimed", () => {
    const r = snapWithGuides({ h: 2.02, v: 7.06 }, candidates, 0.1, quarter)
    expect(r.p.h).toBe(2) // guide
    expect(r.hitH).toBe(2)
    expect(r.p.v).toBe(7) // grid
    expect(r.hitV).toBeNull()
  })

  it("grid-snaps both axes when there are no candidates", () => {
    const r = snapWithGuides({ h: 1.06, v: -2.04 }, null, 0.1, quarter)
    expect(r.p).toEqual({ h: 1, v: -2 })
    expect(r.hitH).toBeNull()
    expect(r.hitV).toBeNull()
  })

  it("leaves the point untouched when grid snapping is the identity", () => {
    const r = snapWithGuides({ h: 3.33, v: -1.11 }, { h: [], v: [] }, 0.1, (v) => v)
    expect(r.p).toEqual({ h: 3.33, v: -1.11 })
  })

  it("reaches an alignment the grid alone could never express", () => {
    // The whole point of the precedence: a neighbour sitting between grid
    // multiples is still exactly matchable.
    const odd = buildGuideCandidates([{ id: "n", points: [{ h: -1.87, v: 5.42 }] }], null)
    const r = snapWithGuides({ h: -1.9, v: 5.4 }, odd, 0.15, quarter)
    expect(r.p).toEqual({ h: -1.87, v: 5.42 })
  })
})
