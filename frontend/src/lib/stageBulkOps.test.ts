import { describe, it, expect } from "vitest"
import type { FixturePatch } from "../api/patchApi"
import type { RiggingDto } from "../api/riggingApi"
import {
  alignTargets,
  arrayAlongRigging,
  distributeTargets,
  lowerToPlacement,
  mirrorTargets,
  nudgeTargets,
  dropOntoRigging,
  resolveBulkTargets,
  riggingUnderPoint,
  setDepthTargets,
  setMountTargets,
  unparentPreservingWorld,
  unplaceTargets,
} from "./stageBulkOps"
import { STAGE_PROJECTIONS } from "./stageProjection"
import { worldPositionLighting } from "./stageCoords"

const PLAN = STAGE_PROJECTIONS.plan
const FRONT = STAGE_PROJECTIONS.front

let nextId = 1
function patch(overrides: Partial<FixturePatch> = {}): FixturePatch {
  const id = nextId++
  return {
    id,
    key: `p${id}`,
    displayName: `P${id}`,
    fixtureTypeKey: "tk",
    startChannel: 1,
    channelCount: 1,
    manufacturer: null,
    model: null,
    modeName: null,
    universe: 1,
    subnet: 0,
    sortOrder: 0,
    groups: [],
    stageX: 0,
    stageY: 0,
    stageZ: 0,
    baseYawDeg: null,
    basePitchDeg: null,
    riggingUuid: null,
    beamAngleDeg: null,
    gelCode: null,
    kindOverride: null,
    stageHidden: false,
    ...overrides,
  }
}

function rig(overrides: Partial<RiggingDto> = {}): RiggingDto {
  return {
    id: 1,
    uuid: "rig-1",
    name: "Truss 1",
    kind: "TRUSS",
    positionX: 0,
    positionY: 0,
    positionZ: 5,
    yawDeg: 0,
    pitchDeg: 0,
    rollDeg: 0,
    lengthM: 10,
    sortOrder: 0,
    ...overrides,
  }
}

/** Recompose a change through the coordinate helpers to get its world position. */
function worldOf(change: { stageX?: number | null; stageY?: number | null; stageZ?: number | null }, r: RiggingDto | null) {
  return worldPositionLighting(
    patch({
      stageX: change.stageX ?? 0,
      stageY: change.stageY ?? 0,
      stageZ: change.stageZ ?? 0,
      riggingUuid: r?.uuid ?? null,
    }),
    r ? [r] : [],
  )!
}

describe("resolveBulkTargets", () => {
  it("pairs each patch with its world position", () => {
    const ts = resolveBulkTargets([patch({ stageX: 1, stageY: 2, stageZ: 3 })], [])
    expect(ts).toHaveLength(1)
    expect(ts[0].world).toEqual({ x: 1, y: 2, z: 3 })
    expect(ts[0].rig).toBeNull()
  })

  it("resolves the mounting frame for a mounted patch", () => {
    const r = rig()
    const ts = resolveBulkTargets([patch({ stageX: 1, stageY: 0, stageZ: 0, riggingUuid: r.uuid })], [r])
    expect(ts[0].rig).toBe(r)
    expect(ts[0].world).toEqual({ x: 1, y: 0, z: 5 })
  })

  it("drops patches with no resolvable position", () => {
    // These are exactly the fixtures the unplaced tray exists to place.
    const ts = resolveBulkTargets(
      [patch({ stageX: null, stageY: 1 }), patch({ stageX: 1, stageY: null }), patch({ stageX: 1, stageY: 1 })],
      [],
    )
    expect(ts).toHaveLength(1)
  })

  it("treats a dangling riggingUuid as free, matching worldPositionLighting", () => {
    // A rig deleted by another operator. The lift and the lower must agree, or
    // the op writes coordinates in a frame that no longer exists.
    const ts = resolveBulkTargets(
      [patch({ stageX: 2, stageY: 3, stageZ: 4, riggingUuid: "gone" })],
      [],
    )
    expect(ts[0].rig).toBeNull()
    expect(ts[0].world).toEqual({ x: 2, y: 3, z: 4 })
  })
})

describe("lowerToPlacement", () => {
  it("never emits riggingUuid, so an align can't re-parent anything", () => {
    const r = rig()
    const ts = resolveBulkTargets([patch({ stageX: 1, stageY: 0, stageZ: 0, riggingUuid: r.uuid })], [r])
    const change = lowerToPlacement(ts[0], { x: 3, y: 0, z: 5 })
    expect("riggingUuid" in change).toBe(false)
  })

  it("lowers into the target's own frame", () => {
    const r = rig({ positionX: 10, positionZ: 5 })
    const ts = resolveBulkTargets([patch({ stageX: 0, stageY: 0, stageZ: 0, riggingUuid: r.uuid })], [r])
    // World (12, 0, 5) is 2 m along the bar from the rig origin at x=10.
    const change = lowerToPlacement(ts[0], { x: 12, y: 0, z: 5 })
    expect(change.stageX).toBeCloseTo(2, 9)
    expect(change.stageY).toBeCloseTo(0, 9)
    expect(change.stageZ).toBeCloseTo(0, 9)
  })
})

describe("alignTargets", () => {
  const ts = () =>
    resolveBulkTargets(
      [
        patch({ stageX: 0, stageY: 1 }),
        patch({ stageX: 4, stageY: 5 }),
        patch({ stageX: 2, stageY: 9 }),
      ],
      [],
    )

  it("aligns to the minimum screen h (leftmost as drawn)", () => {
    const { changes } = alignTargets(ts(), PLAN, "min-h")
    expect(changes.map((c) => c.stageX)).toEqual([0, 0, 0])
    // The other axis is untouched.
    expect(changes.map((c) => c.stageY)).toEqual([1, 5, 9])
  })

  it("aligns to the maximum screen h", () => {
    expect(alignTargets(ts(), PLAN, "max-h").changes.map((c) => c.stageX)).toEqual([4, 4, 4])
  })

  it("centres on the bounding-box midpoint, not the mean", () => {
    // xs are 0, 4, 2 — mean is 2, bbox midpoint is also 2 here, so use a
    // clustered set where they differ.
    const clustered = resolveBulkTargets(
      [patch({ stageX: 0, stageY: 0 }), patch({ stageX: 0, stageY: 1 }), patch({ stageX: 9, stageY: 2 })],
      [],
    )
    // mean = 3, bbox midpoint = 4.5
    expect(alignTargets(clustered, PLAN, "centre-h").changes.map((c) => c.stageX)).toEqual([
      4.5, 4.5, 4.5,
    ])
  })

  it("aligns on the vertical screen axis in plan, which is upstage", () => {
    // v = -y, so min-v (topmost on screen) is the LARGEST y.
    const { changes } = alignTargets(ts(), PLAN, "min-v")
    expect(changes.map((c) => c.stageY)).toEqual([9, 9, 9])
    expect(changes.map((c) => c.stageX)).toEqual([0, 4, 2])
  })

  it("aligns height in the front elevation without disturbing depth", () => {
    const targets = resolveBulkTargets(
      [
        patch({ stageX: 0, stageY: 3, stageZ: 1 }),
        patch({ stageX: 1, stageY: 7, stageZ: 4 }),
      ],
      [],
    )
    // max-v is the LOWEST z (screen-down), min-v the highest.
    const { changes } = alignTargets(targets, FRONT, "min-v")
    expect(changes.map((c) => c.stageZ)).toEqual([4, 4])
    expect(changes.map((c) => c.stageY)).toEqual([3, 7])
  })

  it("returns nothing for fewer than two targets", () => {
    expect(alignTargets(ts().slice(0, 1), PLAN, "min-h").changes).toEqual([])
    expect(alignTargets([], PLAN, "min-h").changes).toEqual([])
  })

  it("skips rig-mounted fixtures rather than pulling them off the bar", () => {
    // Aligning a mounted fixture to an arbitrary world coordinate and lowering
    // that into the rig frame gives it a non-zero local Y/Z — physically, it has
    // left the truss and is floating beside it. arrayAlongRigging is the on-bar
    // equivalent, so these are excluded and reported.
    const r = rig({ positionX: 0, positionZ: 5, yawDeg: 90 })
    const targets = resolveBulkTargets(
      [
        patch({ stageX: 0, stageY: 0, stageZ: 0, riggingUuid: r.uuid }),
        patch({ stageX: 3, stageY: 0, stageZ: 0, riggingUuid: r.uuid }),
        patch({ stageX: 1, stageY: 2 }),
        patch({ stageX: 4, stageY: 5 }),
      ],
      [r],
    )
    const { changes, skipped, warnings } = alignTargets(targets, PLAN, "min-h")
    expect(changes).toHaveLength(2)
    expect(skipped).toHaveLength(2)
    expect(warnings[0]).toContain("2 of 4")
    expect(warnings[0]).toContain("space evenly along truss")
    // Every emitted change belongs to a free fixture, so stageX IS the world x.
    expect(changes.map((c) => c.stageX)).toEqual([1, 1])
  })

  it("does nothing when every target is mounted", () => {
    const r = rig()
    const targets = resolveBulkTargets(
      [
        patch({ stageX: 0, stageY: 0, stageZ: 0, riggingUuid: r.uuid }),
        patch({ stageX: 2, stageY: 0, stageZ: 0, riggingUuid: r.uuid }),
      ],
      [r],
    )
    const { changes, skipped } = alignTargets(targets, PLAN, "min-h")
    expect(changes).toEqual([])
    expect(skipped).toHaveLength(2)
  })
})

describe("distributeTargets", () => {
  it("pins the extremes and evenly spaces the rest", () => {
    const ts = resolveBulkTargets(
      [
        patch({ stageX: 0, stageY: 0 }),
        patch({ stageX: 1, stageY: 0 }),
        patch({ stageX: 2, stageY: 0 }),
        patch({ stageX: 9, stageY: 0 }),
      ],
      [],
    )
    const { changes } = distributeTargets(ts, PLAN, "h")
    // Only the two interior fixtures move; spacing is 3 across 0..9.
    expect(changes).toHaveLength(2)
    const xs = changes.map((c) => c.stageX).sort((a, b) => (a ?? 0) - (b ?? 0))
    expect(xs[0]).toBeCloseTo(3, 9)
    expect(xs[1]).toBeCloseTo(6, 9)
  })

  it("uses equal centre spacing regardless of input order", () => {
    const ts = resolveBulkTargets(
      [patch({ stageX: 9, stageY: 0 }), patch({ stageX: 0, stageY: 0 }), patch({ stageX: 8, stageY: 0 })],
      [],
    )
    const { changes } = distributeTargets(ts, PLAN, "h")
    expect(changes).toHaveLength(1)
    expect(changes[0].stageX).toBeCloseTo(4.5, 9)
  })

  it("is a no-op below three targets — there is nothing between the pinned ends", () => {
    const two = resolveBulkTargets([patch({ stageX: 0 }), patch({ stageX: 5 })], [])
    expect(distributeTargets(two, PLAN, "h").changes).toEqual([])
  })

  it("leaves the cross axis alone", () => {
    const ts = resolveBulkTargets(
      [
        patch({ stageX: 0, stageY: 1 }),
        patch({ stageX: 5, stageY: 7 }),
        patch({ stageX: 10, stageY: 3 }),
      ],
      [],
    )
    const { changes } = distributeTargets(ts, PLAN, "h")
    expect(changes[0].stageY).toBe(7)
  })
})

describe("arrayAlongRigging", () => {
  const r = rig({ lengthM: 10, positionX: 0, positionZ: 5 })

  it("centres the span on the bar and respects the inset", () => {
    const ts = resolveBulkTargets([patch(), patch(), patch()], [])
    const { changes, warnings } = arrayAlongRigging(ts, {
      rig: r,
      spacing: { mode: "even", insetM: 0.5 },
      dropM: 0,
    })
    // Usable span is 9 m from -4.5 to +4.5.
    expect(changes.map((c) => c.stageX)).toEqual([-4.5, 0, 4.5])
    expect(warnings).toEqual([])
  })

  it("places a single fixture at the bar's centre", () => {
    const ts = resolveBulkTargets([patch()], [])
    const { changes } = arrayAlongRigging(ts, { rig: r, spacing: { mode: "even" } })
    expect(changes[0].stageX).toBe(0)
  })

  it("re-parents every fixture onto the bar", () => {
    const ts = resolveBulkTargets([patch({ stageX: 99, stageY: 99 }), patch()], [])
    const { changes } = arrayAlongRigging(ts, { rig: r, spacing: { mode: "even" } })
    expect(changes.every((c) => c.riggingUuid === r.uuid)).toBe(true)
  })

  it("negates dropM, because local +Z is up despite the field being called drop", () => {
    // Getting this backwards puts the whole array ABOVE the truss, which is
    // invisible in a plan view.
    const ts = resolveBulkTargets([patch()], [])
    const { changes } = arrayAlongRigging(ts, { rig: r, spacing: { mode: "even" }, dropM: 0.4 })
    expect(changes[0].stageZ).toBe(-0.4)
    // And it really is below the bar in world terms.
    expect(worldOf(changes[0], r).z).toBeCloseTo(5 - 0.4, 9)
  })

  it("applies offsetYM as the out-from-bar offset", () => {
    const ts = resolveBulkTargets([patch()], [])
    const { changes } = arrayAlongRigging(ts, {
      rig: r,
      spacing: { mode: "even" },
      offsetYM: 0.6,
      dropM: 0,
    })
    expect(changes[0].stageY).toBe(0.6)
  })

  it("spaces at a fixed pitch centred on the bar", () => {
    const ts = resolveBulkTargets([patch(), patch(), patch(), patch()], [])
    const { changes } = arrayAlongRigging(ts, {
      rig: r,
      spacing: { mode: "pitch", pitchM: 2 },
    })
    expect(changes.map((c) => c.stageX)).toEqual([-3, -1, 1, 3])
  })

  it("warns rather than clamping when a pitch overruns the bar", () => {
    // Nothing else would catch this: the server's only bound is ±500 m, so an
    // off-the-end fixture is accepted and drawn floating in mid-air.
    const ts = resolveBulkTargets([patch(), patch(), patch()], [])
    const { changes, warnings } = arrayAlongRigging(ts, {
      rig: r,
      spacing: { mode: "pitch", pitchM: 8 },
    })
    expect(changes.map((c) => c.stageX)).toEqual([-8, 0, 8])
    expect(warnings).toHaveLength(1)
    expect(warnings[0]).toContain("Truss 1")
    expect(warnings[0]).toContain("2 of 3")
  })

  it("keeps every fixture on the bar for a yawed and rolled truss", () => {
    const posed = rig({ yawDeg: 37, rollDeg: 12, lengthM: 8, positionX: 1, positionY: 2, positionZ: 4 })
    const ts = resolveBulkTargets([patch(), patch(), patch()], [])
    const { changes } = arrayAlongRigging(ts, { rig: posed, spacing: { mode: "even" }, dropM: 0 })
    // stageY and stageZ are zero, so each fixture sits exactly on the bar axis.
    for (const c of changes) {
      expect(c.stageY).toBe(0)
      expect(c.stageZ).toBe(-0)
    }
    // And the spacing is uniform in world space.
    const worlds = changes.map((c) => worldOf(c, posed))
    const d1 = Math.hypot(worlds[1].x - worlds[0].x, worlds[1].y - worlds[0].y, worlds[1].z - worlds[0].z)
    const d2 = Math.hypot(worlds[2].x - worlds[1].x, worlds[2].y - worlds[1].y, worlds[2].z - worlds[1].z)
    expect(d1).toBeCloseTo(d2, 9)
  })

  it("falls back to the default bar length when lengthM is null", () => {
    const ts = resolveBulkTargets([patch(), patch()], [])
    const { changes } = arrayAlongRigging(ts, {
      rig: rig({ lengthM: null }),
      spacing: { mode: "even", insetM: 0 },
    })
    // Default is 3 m, so ends land at ±1.5.
    expect(changes.map((c) => c.stageX)).toEqual([-1.5, 1.5])
  })

  it("returns nothing for an empty selection", () => {
    expect(arrayAlongRigging([], { rig: r, spacing: { mode: "even" } })).toEqual({
      changes: [],
      warnings: [],
    })
  })
})

describe("mirrorTargets", () => {
  it("reflects free fixtures about the stage centre line", () => {
    const ts = resolveBulkTargets(
      [patch({ stageX: 2, stageY: 3 }), patch({ stageX: -4, stageY: 1 })],
      [],
    )
    const { changes, skipped } = mirrorTargets(ts, PLAN)
    expect(changes.map((c) => c.stageX)).toEqual([-2, 4])
    // The cross axis is untouched.
    expect(changes.map((c) => c.stageY)).toEqual([3, 1])
    expect(skipped).toEqual([])
  })

  it("reflects about an arbitrary line", () => {
    const ts = resolveBulkTargets([patch({ stageX: 2, stageY: 0 })], [])
    expect(mirrorTargets(ts, PLAN, { aboutM: 5 }).changes[0].stageX).toBe(8)
  })

  it("skips rig-mounted fixtures by default and says how many", () => {
    const r = rig()
    const ts = resolveBulkTargets(
      [
        patch({ stageX: 2, stageY: 0 }),
        patch({ stageX: 1, stageY: 0, stageZ: 0, riggingUuid: r.uuid }),
      ],
      [r],
    )
    const { changes, skipped, warnings } = mirrorTargets(ts, PLAN)
    expect(changes).toHaveLength(1)
    expect(skipped).toHaveLength(1)
    expect(warnings[0]).toContain("1 of 2")
  })

  it("cuts mounted fixtures free when asked, preserving the reflected world point", () => {
    const r = rig({ positionX: 4, positionZ: 5 })
    const ts = resolveBulkTargets(
      [patch({ stageX: 1, stageY: 0, stageZ: 0, riggingUuid: r.uuid })],
      [r],
    )
    const { changes, skipped } = mirrorTargets(ts, PLAN, { unparentMounted: true })
    expect(skipped).toEqual([])
    expect(changes[0].riggingUuid).toBeNull()
    // World x was 5; reflected about 0 it is -5, and as a free fixture stageX IS
    // the world x.
    expect(changes[0].stageX).toBeCloseTo(-5, 9)
    expect(changes[0].stageZ).toBeCloseTo(5, 9)
  })

  it("leaves baseYawDeg alone unless asked", () => {
    const ts = resolveBulkTargets([patch({ stageX: 1, stageY: 0, baseYawDeg: 30 })], [])
    expect("baseYawDeg" in mirrorTargets(ts, PLAN).changes[0]).toBe(false)
    expect(mirrorTargets(ts, PLAN, { alsoFlipYaw: true }).changes[0].baseYawDeg).toBe(-30)
  })

  it("does not invent a yaw for a fixture that has none", () => {
    const ts = resolveBulkTargets([patch({ stageX: 1, stageY: 0, baseYawDeg: null })], [])
    expect("baseYawDeg" in mirrorTargets(ts, PLAN, { alsoFlipYaw: true }).changes[0]).toBe(false)
  })
})

describe("setDepthTargets", () => {
  it("sets a common height in an elevation", () => {
    const ts = resolveBulkTargets(
      [patch({ stageX: 0, stageY: 0, stageZ: 1 }), patch({ stageX: 1, stageY: 2, stageZ: 4 })],
      [],
    )
    const { changes } = setDepthTargets(ts, FRONT, 3)
    expect(changes.map((c) => c.stageZ)).toEqual([3, 3])
    expect(changes.map((c) => c.stageY)).toEqual([0, 2])
  })

  it("skips rig-mounted fixtures, which can't be levelled independently of the bar", () => {
    // Forcing a common height on a fixture bolted to a rolled bar would lift it
    // off the bar. Levelling the truss's roll is the operation for that.
    const rolled = rig({ rollDeg: 25, positionZ: 5, lengthM: 10 })
    const targets = resolveBulkTargets(
      [
        patch({ stageX: -3, stageY: 0, stageZ: 0, riggingUuid: rolled.uuid }),
        patch({ stageX: 0, stageY: 0, stageZ: 2 }),
      ],
      [rolled],
    )
    const { changes, skipped, warnings } = setDepthTargets(targets, FRONT, 4)
    expect(changes).toHaveLength(1)
    expect(changes[0].stageZ).toBe(4)
    expect(skipped).toHaveLength(1)
    expect(warnings[0]).toContain("1 of 2")
  })

  it("sets a common depth in plan view, where v is upstage", () => {
    const ts = resolveBulkTargets(
      [patch({ stageX: 0, stageY: 1 }), patch({ stageX: 1, stageY: 8 })],
      [],
    )
    expect(setDepthTargets(ts, PLAN, 5).changes.map((c) => c.stageY)).toEqual([5, 5])
  })
})

describe("setMountTargets", () => {
  it("re-parents without moving the fixture in space", () => {
    // This is the fix for the Mounting dropdown, which swaps riggingUuid and
    // leaves stage* alone, teleporting the fixture into the new rotated frame.
    const to = rig({ uuid: "to", positionX: 5, positionZ: 4, yawDeg: 90 })
    const ts = resolveBulkTargets([patch({ stageX: 2, stageY: 1, stageZ: 3 })], [to])
    const before = ts[0].world
    const changes = setMountTargets(ts, to)
    expect(changes[0].riggingUuid).toBe("to")
    const after = worldOf(changes[0], to)
    expect(after.x).toBeCloseTo(before.x, 9)
    expect(after.y).toBeCloseTo(before.y, 9)
    expect(after.z).toBeCloseTo(before.z, 9)
  })

  it("un-parents without moving the fixture", () => {
    const from = rig({ uuid: "from", positionX: 3, positionZ: 6, yawDeg: 45, rollDeg: 10 })
    const ts = resolveBulkTargets(
      [patch({ stageX: 1.5, stageY: 0, stageZ: -0.3, riggingUuid: from.uuid })],
      [from],
    )
    const before = ts[0].world
    const changes = setMountTargets(ts, null)
    expect(changes[0].riggingUuid).toBeNull()
    // Free-space stage* IS the world position.
    expect(changes[0].stageX).toBeCloseTo(before.x, 9)
    expect(changes[0].stageY).toBeCloseTo(before.y, 9)
    expect(changes[0].stageZ).toBeCloseTo(before.z, 9)
  })

  it("moves between two rigs, preserving world position", () => {
    const from = rig({ uuid: "from", positionX: 0, positionZ: 5 })
    const to = rig({ uuid: "to", positionX: 8, positionZ: 3, yawDeg: 30 })
    const ts = resolveBulkTargets(
      [patch({ stageX: 2, stageY: 0, stageZ: 0, riggingUuid: from.uuid })],
      [from, to],
    )
    const before = ts[0].world
    const after = worldOf(setMountTargets(ts, to)[0], to)
    expect(after.x).toBeCloseTo(before.x, 9)
    expect(after.y).toBeCloseTo(before.y, 9)
    expect(after.z).toBeCloseTo(before.z, 9)
  })
})

describe("unplaceTargets", () => {
  it("clears placement so the fixture returns to the tray", () => {
    expect(unplaceTargets([7, 8])).toEqual([
      { patchId: 7, riggingUuid: null, stageX: null, stageY: null, stageZ: null },
      { patchId: 8, riggingUuid: null, stageX: null, stageY: null, stageZ: null },
    ])
  })
})

describe("nudgeTargets", () => {
  it("shifts along the projection's screen axes", () => {
    const ts = resolveBulkTargets([patch({ stageX: 1, stageY: 2, stageZ: 3 })], [])
    // In plan, v is -y, so a positive v delta moves DOWNstage.
    const changes = nudgeTargets(ts, PLAN, 0.5, 0.25)
    expect(changes[0].stageX).toBeCloseTo(1.5, 9)
    expect(changes[0].stageY).toBeCloseTo(1.75, 9)
    expect(changes[0].stageZ).toBeCloseTo(3, 9)
  })

  it("slides a mounted fixture along its bar, never off it", () => {
    // Unyawed bar: local +X is world +X, so a nudge along screen h is entirely
    // along the truss.
    const r = rig({ positionZ: 5, lengthM: 6 })
    const ts = resolveBulkTargets(
      [patch({ stageX: 0, stageY: 0, stageZ: -0.3, riggingUuid: r.uuid })],
      [r],
    )
    const changes = nudgeTargets(ts, PLAN, 1, 0)
    expect(changes[0].stageX).toBeCloseTo(1, 9)
    // The drop below the bar and the on-axis offset pass through untouched — this
    // is what "still bolted to the truss" means.
    expect(changes[0].stageY).toBe(0)
    expect(changes[0].stageZ).toBe(-0.3)
    expect(changes[0].riggingUuid).toBeUndefined()
  })

  it("refuses to push a mounted fixture sideways off its bar", () => {
    // A 90°-yawed bar runs along world Y, so a nudge along world X has no
    // component along the truss. Lowering the nudged world point into the rig
    // frame would instead give local Y = -1 — a fixture floating a metre beside
    // the bar it is bolted to, which nothing downstream would catch.
    const r = rig({ yawDeg: 90, positionZ: 5 })
    const ts = resolveBulkTargets(
      [patch({ stageX: 0, stageY: 0, stageZ: 0, riggingUuid: r.uuid })],
      [r],
    )
    const changes = nudgeTargets(ts, PLAN, 1, 0)
    expect(changes[0].stageX).toBeCloseTo(0, 9)
    expect(changes[0].stageY).toBe(0)
    expect(worldOf(changes[0], r).x).toBeCloseTo(0, 9)
  })

  it("clamps a slide at the end of the bar", () => {
    const r = rig({ positionZ: 5, lengthM: 4 })
    const ts = resolveBulkTargets(
      [patch({ stageX: 1.8, stageY: 0, stageZ: 0, riggingUuid: r.uuid })],
      [r],
    )
    expect(nudgeTargets(ts, PLAN, 5, 0)[0].stageX).toBeCloseTo(2, 9)
  })

  it("nudges height in an elevation", () => {
    const ts = resolveBulkTargets([patch({ stageX: 0, stageY: 0, stageZ: 2 })], [])
    // v is screen-down, so -0.5 raises it.
    expect(nudgeTargets(ts, FRONT, 0, -0.5)[0].stageZ).toBeCloseTo(2.5, 9)
  })
})

describe("riggingUnderPoint", () => {
  const bar = rig({ uuid: "bar", positionX: 0, positionY: 0, positionZ: 5, lengthM: 10 })

  it("finds a bar the point is near", () => {
    // Bar runs along world X at z=5 → plan h -5..5, v 0.
    expect(riggingUnderPoint({ h: 0, v: 0.05 }, [bar], PLAN, 0.2)?.uuid).toBe("bar")
  })

  it("returns null beyond the tolerance", () => {
    expect(riggingUnderPoint({ h: 0, v: 1 }, [bar], PLAN, 0.2)).toBeNull()
  })

  it("measures to the segment, not the infinite line", () => {
    // Well past the +X end, so the nearest point on the segment is the endpoint.
    expect(riggingUnderPoint({ h: 20, v: 0 }, [bar], PLAN, 0.2)).toBeNull()
  })

  it("prefers the higher bar when two are equally close", () => {
    const low = rig({ uuid: "low", positionZ: 3, lengthM: 10 })
    const high = rig({ uuid: "high", positionZ: 7, lengthM: 10 })
    // Both project onto the same plan line, so distance ties and height decides.
    expect(riggingUnderPoint({ h: 0, v: 0 }, [low, high], PLAN, 0.2)?.uuid).toBe("high")
    expect(riggingUnderPoint({ h: 0, v: 0 }, [high, low], PLAN, 0.2)?.uuid).toBe("high")
  })

  it("ignores a bar that is edge-on in this projection", () => {
    // A bar along world Y projects to a point in the front elevation, so there is
    // no along-the-bar direction to drop onto.
    const endOn = rig({ uuid: "end-on", yawDeg: 90, lengthM: 10 })
    expect(riggingUnderPoint({ h: 0, v: -5 }, [endOn], FRONT, 1)).toBeNull()
  })
})

describe("dropOntoRigging", () => {
  const bar = rig({ uuid: "bar", positionX: 0, positionY: 0, positionZ: 5, lengthM: 10 })

  it("bolts a free fixture onto the bar at the drop point", () => {
    const [t] = resolveBulkTargets([patch({ stageX: 0, stageY: 0, stageZ: 0 })], [])
    const { change, clampedX } = dropOntoRigging(t, { x: 3, y: 0, z: 5 }, bar)
    expect(change.riggingUuid).toBe("bar")
    expect(change.stageX).toBeCloseTo(3, 9)
    expect(clampedX).toBe(false)
  })

  it("forces stageY to zero — dropping on a bar means on the bar", () => {
    const [t] = resolveBulkTargets([patch({ stageX: 0, stageY: 0 })], [])
    // Drop 0.4 m off the bar's axis; that offset is pointer noise, not intent.
    const { change } = dropOntoRigging(t, { x: 1, y: 0.4, z: 5 }, bar)
    expect(change.stageY).toBe(0)
  })

  it("applies the default drop below the bar for a previously free fixture", () => {
    const [t] = resolveBulkTargets([patch({ stageX: 0, stageY: 0 })], [])
    const { change } = dropOntoRigging(t, { x: 0, y: 0, z: 5 }, bar)
    // Negative: local +Z is up, so a drop is negative. Getting this backwards puts
    // the fixture above the truss.
    expect(change.stageZ).toBe(-0.3)
  })

  it("keeps an already-mounted fixture's trim when re-hung on another bar", () => {
    const from = rig({ uuid: "from", positionZ: 6, lengthM: 8 })
    const [t] = resolveBulkTargets(
      [patch({ stageX: 1, stageY: 0, stageZ: -0.75, riggingUuid: from.uuid })],
      [from],
    )
    const { change } = dropOntoRigging(t, { x: 2, y: 0, z: 5 }, bar)
    expect(change.riggingUuid).toBe("bar")
    expect(change.stageZ).toBe(-0.75)
  })

  it("clamps to the bar and reports it, rather than persisting an overhang", () => {
    const [t] = resolveBulkTargets([patch({ stageX: 0, stageY: 0 })], [])
    const { change, clampedX } = dropOntoRigging(t, { x: 99, y: 0, z: 5 }, bar)
    expect(change.stageX).toBeCloseTo(5, 9)
    expect(clampedX).toBe(true)
  })

  it("computes offsets in the DESTINATION rig's frame, not the old one", () => {
    // The trap: patchPlacementFromWorld reads patch.riggingUuid, so using it here
    // would give offsets in `from`'s rotated frame stored against `to`'s uuid —
    // in range, accepted, and drawn plausibly wrong.
    const from = rig({ uuid: "from", positionX: 0, positionZ: 5, yawDeg: 0 })
    const to = rig({ uuid: "to", positionX: 4, positionZ: 5, yawDeg: 90, lengthM: 10 })
    const [t] = resolveBulkTargets(
      [patch({ stageX: 2, stageY: 0, stageZ: 0, riggingUuid: from.uuid })],
      [from, to],
    )
    const { change } = dropOntoRigging(t, { x: 4, y: 3, z: 5 }, to)
    // `to` is yawed 90°, so world +Y is its local +X: a drop 3 m upstage of its
    // origin is local X = 3.
    expect(change.stageX).toBeCloseTo(3, 9)
  })
})

describe("unparentPreservingWorld", () => {
  it("cuts a fixture free without moving it", () => {
    const r = rig({ positionX: 2, positionZ: 6, yawDeg: 45, rollDeg: 10, lengthM: 8 })
    const [t] = resolveBulkTargets(
      [patch({ stageX: 1.5, stageY: 0, stageZ: -0.3, riggingUuid: r.uuid })],
      [r],
    )
    const change = unparentPreservingWorld(t)
    expect(change.riggingUuid).toBeNull()
    // Free-space stage* IS the world position.
    expect(change.stageX).toBeCloseTo(t.world.x, 9)
    expect(change.stageY).toBeCloseTo(t.world.y, 9)
    expect(change.stageZ).toBeCloseTo(t.world.z, 9)
  })
})
