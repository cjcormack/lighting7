import { describe, it, expect } from "vitest"
import {
  STAGE_PROJECTIONS,
  PROJECTION_IDS,
  isProjectionId,
  project,
  unproject,
  projectionExtent,
  visibleExtent,
  toPercent,
  padExtent,
  type LightingPoint,
  type StageProjection,
} from "./stageProjection"

const ALL: StageProjection[] = PROJECTION_IDS.map((id) => STAGE_PROJECTIONS[id])

const DIMS = { widthM: 10, depthM: 8, heightM: 6 }

describe("STAGE_PROJECTIONS", () => {
  it("gives each projection a distinct in-plane axis pair plus the remaining depth axis", () => {
    for (const proj of ALL) {
      expect(proj.h.axis).not.toBe(proj.v.axis)
      expect(proj.depth).not.toBe(proj.h.axis)
      expect(proj.depth).not.toBe(proj.v.axis)
    }
  })

  it("maps its own id", () => {
    for (const id of PROJECTION_IDS) expect(STAGE_PROJECTIONS[id].id).toBe(id)
  })

  it("draws screen-up as the positive lighting direction (v is screen-down)", () => {
    // Plan: further upstage draws *higher* on screen. Elevations: higher off the
    // deck draws higher on screen. Both mean a negative v sign.
    expect(STAGE_PROJECTIONS.plan.v.sign).toBe(-1)
    expect(STAGE_PROJECTIONS.front.v.sign).toBe(-1)
    expect(STAGE_PROJECTIONS.side.v.sign).toBe(-1)
  })

  it("keeps +x on screen-right in plan and front so the two views agree", () => {
    expect(STAGE_PROJECTIONS.plan.h).toEqual({ axis: "x", sign: 1 })
    expect(STAGE_PROJECTIONS.front.h).toEqual({ axis: "x", sign: 1 })
  })

  it("puts FOH at screen-left in the side elevation", () => {
    const side = STAGE_PROJECTIONS.side
    // Y=0 is the downstage/FOH edge, so it must project further left than upstage.
    expect(project({ x: 0, y: 0, z: 0 }, side).h).toBeLessThan(
      project({ x: 0, y: 5, z: 0 }, side).h,
    )
  })
})

describe("isProjectionId", () => {
  it("accepts the three ids and rejects everything else", () => {
    expect(isProjectionId("plan")).toBe(true)
    expect(isProjectionId("front")).toBe(true)
    expect(isProjectionId("side")).toBe(true)
    // '2d' is the legacy stored view-mode value that must be migrated, not accepted.
    expect(isProjectionId("2d")).toBe(false)
    expect(isProjectionId("3d")).toBe(false)
    expect(isProjectionId(null)).toBe(false)
    expect(isProjectionId(undefined)).toBe(false)
  })
})

describe("project / unproject", () => {
  const P: LightingPoint = { x: 1.5, y: 3.25, z: 4.75 }

  it("selects the descriptor's axes with its signs", () => {
    expect(project(P, STAGE_PROJECTIONS.plan)).toEqual({ h: 1.5, v: -3.25 })
    expect(project(P, STAGE_PROJECTIONS.front)).toEqual({ h: 1.5, v: -4.75 })
    expect(project(P, STAGE_PROJECTIONS.side)).toEqual({ h: 3.25, v: -4.75 })
  })

  it("round-trips project → unproject for every projection", () => {
    for (const proj of ALL) {
      const back = unproject(project(P, proj), proj, P)
      expect(back.x).toBeCloseTo(P.x, 12)
      expect(back.y).toBeCloseTo(P.y, 12)
      expect(back.z).toBeCloseTo(P.z, 12)
    }
  })

  it("round-trips unproject → project for every projection", () => {
    const s = { h: -2.5, v: 1.75 }
    for (const proj of ALL) {
      const back = project(unproject(s, proj, P), proj)
      expect(back.h).toBeCloseTo(s.h, 12)
      expect(back.v).toBeCloseTo(s.v, 12)
    }
  })

  it("preserves the out-of-plane axis and changes only the in-plane pair", () => {
    for (const proj of ALL) {
      const moved = unproject({ h: 9, v: 9 }, proj, P)
      expect(moved[proj.depth]).toBe(P[proj.depth])
      expect(moved[proj.h.axis]).not.toBe(P[proj.h.axis])
      expect(moved[proj.v.axis]).not.toBe(P[proj.v.axis])
    }
  })

  it("leaves Y untouched when dragging in the front elevation", () => {
    // The specific case that matters: an elevation drag must not shift the
    // fixture up or downstage.
    const moved = unproject({ h: -4, v: -2 }, STAGE_PROJECTIONS.front, P)
    expect(moved.y).toBe(P.y)
    expect(moved.x).toBe(-4)
    expect(moved.z).toBe(2)
  })

  it("does not mutate the point passed as `existing`", () => {
    const src: LightingPoint = { x: 1, y: 2, z: 3 }
    unproject({ h: 8, v: 8 }, STAGE_PROJECTIONS.plan, src)
    expect(src).toEqual({ x: 1, y: 2, z: 3 })
  })

  it("is linear, so it doubles as a delta projector", () => {
    const a: LightingPoint = { x: 1, y: 2, z: 3 }
    const d: LightingPoint = { x: 0.5, y: -1.5, z: 2 }
    const sum: LightingPoint = { x: a.x + d.x, y: a.y + d.y, z: a.z + d.z }
    for (const proj of ALL) {
      const pa = project(a, proj)
      const pd = project(d, proj)
      const ps = project(sum, proj)
      expect(pa.h + pd.h).toBeCloseTo(ps.h, 12)
      expect(pa.v + pd.v).toBeCloseTo(ps.v, 12)
    }
  })
})

describe("projectionExtent", () => {
  // Envelope is X ∈ [−w/2, w/2], Y ∈ [0, d], Z ∈ [0, h].
  it("spans the stage width and depth in plan, with the datum at v = 0", () => {
    expect(projectionExtent(STAGE_PROJECTIONS.plan, DIMS)).toEqual({
      hMin: -5,
      hMax: 5,
      vMin: -8,
      vMax: 0,
    })
  })

  it("spans width and height in front", () => {
    expect(projectionExtent(STAGE_PROJECTIONS.front, DIMS)).toEqual({
      hMin: -5,
      hMax: 5,
      vMin: -6,
      vMax: 0,
    })
  })

  it("spans depth and height in side", () => {
    expect(projectionExtent(STAGE_PROJECTIONS.side, DIMS)).toEqual({
      hMin: 0,
      hMax: 8,
      vMin: -6,
      vMax: 0,
    })
  })

  it("puts the datum (deck, or downstage edge) at v = 0 in every projection", () => {
    for (const proj of ALL) {
      expect(projectionExtent(proj, DIMS).vMax).toBe(0)
    }
  })

  it("contains the projection of every envelope corner", () => {
    const halfW = DIMS.widthM / 2
    for (const proj of ALL) {
      const e = projectionExtent(proj, DIMS)
      for (const x of [-halfW, halfW]) {
        for (const y of [0, DIMS.depthM]) {
          for (const z of [0, DIMS.heightM]) {
            const s = project({ x, y, z }, proj)
            expect(s.h).toBeGreaterThanOrEqual(e.hMin)
            expect(s.h).toBeLessThanOrEqual(e.hMax)
            expect(s.v).toBeGreaterThanOrEqual(e.vMin)
            expect(s.v).toBeLessThanOrEqual(e.vMax)
          }
        }
      }
    }
  })
})

describe("visibleExtent", () => {
  const VB = { hMin: -5, hMax: 5, vMin: -8, vMax: 0 } // 10 × 8

  it("returns the viewBox unchanged when the rect matches its aspect", () => {
    expect(visibleExtent(VB, 500, 400)).toEqual(VB)
  })

  it("widens (not heightens) when the rect is wider than the viewBox", () => {
    // 10x8 viewBox in a 1000x400 rect: scale is limited by height (50 px/m), so
    // 1000 px shows 20 m — 5 m of surplus split either side of centre.
    const vis = visibleExtent(VB, 1000, 400)
    expect(vis).toEqual({ hMin: -10, hMax: 10, vMin: -8, vMax: 0 })
  })

  it("heightens when the rect is taller than the viewBox", () => {
    // Scale limited by width (50 px/m); 800 px shows 16 m of v, 4 m surplus.
    const vis = visibleExtent(VB, 500, 800)
    expect(vis).toEqual({ hMin: -5, hMax: 5, vMin: -12, vMax: 4 })
  })

  it("keeps the viewBox centre as the visible centre (xMidYMid)", () => {
    const vis = visibleExtent(VB, 1234, 321)
    expect((vis.hMin + vis.hMax) / 2).toBeCloseTo((VB.hMin + VB.hMax) / 2, 12)
    expect((vis.vMin + vis.vMax) / 2).toBeCloseTo((VB.vMin + VB.vMax) / 2, 12)
  })

  it("always contains the viewBox — the grid must never fall short of the canvas", () => {
    for (const [w, h] of [
      [100, 100],
      [1600, 200],
      [200, 1600],
      [640, 480],
      [37, 991],
    ]) {
      const vis = visibleExtent(VB, w, h)
      expect(vis.hMin).toBeLessThanOrEqual(VB.hMin)
      expect(vis.hMax).toBeGreaterThanOrEqual(VB.hMax)
      expect(vis.vMin).toBeLessThanOrEqual(VB.vMin)
      expect(vis.vMax).toBeGreaterThanOrEqual(VB.vMax)
    }
  })

  it("falls back to the viewBox for degenerate inputs rather than emitting NaN", () => {
    expect(visibleExtent(VB, 0, 400)).toEqual(VB)
    expect(visibleExtent(VB, 500, 0)).toEqual(VB)
    expect(visibleExtent({ hMin: 0, hMax: 0, vMin: 0, vMax: 0 }, 500, 400)).toEqual({
      hMin: 0,
      hMax: 0,
      vMin: 0,
      vMax: 0,
    })
  })
})

describe("toPercent", () => {
  const PLAN = STAGE_PROJECTIONS.plan
  const extent = projectionExtent(PLAN, DIMS) // hMin -5, hMax 5, vMin -8, vMax 0

  it("reproduces the mapping the DOM overview panel used before this existed", () => {
    // Historic formula: xPct = ((x + w/2) / w) * 100, yPct = (1 - y / d) * 100.
    for (const [x, y] of [
      [0, 0],
      [2.5, 2],
      [-5, 8],
      [5, 0],
      [-1.25, 6.5],
    ]) {
      const { leftPct, topPct } = toPercent(project({ x, y, z: 0 }, PLAN), extent)
      expect(leftPct).toBeCloseTo(((x + DIMS.widthM / 2) / DIMS.widthM) * 100, 10)
      expect(topPct).toBeCloseTo((1 - y / DIMS.depthM) * 100, 10)
    }
  })

  it("puts stage centre-front at the horizontal midpoint and the bottom edge", () => {
    // Y=0 is the downstage edge, which draws at the bottom of a plan view.
    expect(toPercent(project({ x: 0, y: 0, z: 0 }, PLAN), extent)).toEqual({
      leftPct: 50,
      topPct: 100,
    })
  })

  it("puts the upstage-left corner at the origin of the box", () => {
    expect(toPercent(project({ x: -5, y: 8, z: 0 }, PLAN), extent)).toEqual({
      leftPct: 0,
      topPct: 0,
    })
  })

  it("does NOT clamp — a fixture rigged outside the envelope draws outside the box", () => {
    const { leftPct, topPct } = toPercent(project({ x: 7.5, y: -2, z: 0 }, PLAN), extent)
    expect(leftPct).toBeGreaterThan(100)
    expect(topPct).toBeGreaterThan(100)
  })

  it("centres rather than dividing by zero on a degenerate extent", () => {
    expect(toPercent({ h: 3, v: 3 }, { hMin: 0, hMax: 0, vMin: 0, vMax: 0 })).toEqual({
      leftPct: 50,
      topPct: 50,
    })
  })

  it("maps height to the vertical axis in the front elevation", () => {
    const front = STAGE_PROJECTIONS.front
    const e = projectionExtent(front, DIMS)
    // Deck at the bottom, top of the envelope at the top.
    expect(toPercent(project({ x: 0, y: 0, z: 0 }, front), e).topPct).toBe(100)
    expect(toPercent(project({ x: 0, y: 0, z: 6 }, front), e).topPct).toBe(0)
  })
})

describe("padExtent", () => {
  it("grows uniformly on all four sides", () => {
    expect(padExtent({ hMin: -1, hMax: 1, vMin: -2, vMax: 0 }, 0.5)).toEqual({
      hMin: -1.5,
      hMax: 1.5,
      vMin: -2.5,
      vMax: 0.5,
    })
  })
})
