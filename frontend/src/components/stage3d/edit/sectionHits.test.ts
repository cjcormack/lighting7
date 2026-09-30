import { describe, expect, it } from 'vitest'
import type { FixturePatch, PatchPlacement } from '../../../api/patchApi'
import type { RiggingDto } from '../../../api/riggingApi'
import type { StageRegionDto } from '../../../api/stageRegionApi'
import type { StageElementDto } from '../../../api/stageElementApi'
import type { DrawnPoint } from '../../../hooks/useProjectedPatches'
import { STAGE_PROJECTIONS } from '../../../lib/stageProjection'
import { orthoSection } from '../stageCameras'
import { elementOutline, hitAt, insideOutline, marqueeHits, projectRigging, regionOutline } from './sectionHits'
import { pixelToSection, sectionToPixel, visibleSection } from './sectionView'

const plan = STAGE_PROJECTIONS.plan
const front = STAGE_PROJECTIONS.front

function point(key: string, h: number, v: number, placement?: Partial<PatchPlacement>): DrawnPoint {
  return {
    patch: { key } as FixturePatch,
    world: { x: 0, y: 0, z: 0 },
    screen: { h, v },
    leftPct: 0,
    topPct: 0,
    ...(placement && { placement: placement as PatchPlacement }),
  }
}

const bar = { uuid: 'lx1', positionX: 0, positionY: 4, positionZ: 5, yawDeg: 0, pitchDeg: 0, rollDeg: 0, lengthM: 6 } as RiggingDto
const deck = { uuid: 'deck', centerX: 0, centerY: 2, centerZ: 0.5, widthM: 4, depthM: 2, heightM: 0.5, yawDeg: 0 } as StageRegionDto

function element(over: Partial<StageElementDto>): StageElementDto {
  return {
    id: 1,
    uuid: 'el',
    name: 'Flat 1',
    kind: 'FLAT',
    layer: 'SET',
    positionX: 0,
    positionY: 0,
    positionZ: 0,
    yawDeg: 0,
    widthM: 2,
    depthM: 0.2,
    heightM: 3,
    finishColour: null,
    finishPattern: null,
    emissive: false,
    params: {},
    hidden: false,
    sortOrder: 0,
    ...over,
  }
}

describe('hitAt — what a press on a section lands on', () => {
  // 1 cm a pixel: the fixture's 12 px hit circle is 12 cm, the bar's 7 px line 9 cm (half its thickness).
  const mPerPx = 0.01
  const scene = {
    projection: plan,
    points: [point('par-1', 0, -4)],
    riggings: [bar],
    regions: [deck],
    elements: [element({ uuid: 'flat', positionY: 6 })],
  }

  it('takes a fixture over the bar it hangs on, and the bar over the areas under it', () => {
    expect(hitAt(scene, { h: 0.05, v: -4 }, mPerPx)).toMatchObject({ kind: 'patch' })
    expect(hitAt(scene, { h: 2, v: -4.05 }, mPerPx)).toMatchObject({ kind: 'rigging', rig: { uuid: 'lx1' } })
    expect(hitAt(scene, { h: 1, v: -2 }, mPerPx)).toMatchObject({ kind: 'region', region: { uuid: 'deck' } })
    expect(hitAt(scene, { h: 0.5, v: -6 }, mPerPx)).toMatchObject({ kind: 'element', element: { uuid: 'flat' } })
    expect(hitAt(scene, { h: 4, v: -1 }, mPerPx)).toBeNull()
  })

  it('takes the smallest area under the pointer: a prop on a deck, not the deck', () => {
    const prop = element({ uuid: 'chair', kind: 'OBJECT', positionX: 1, positionY: 2, widthM: 0.5, depthM: 0.5 })
    expect(hitAt({ ...scene, elements: [prop] }, { h: 1, v: -2 }, mPerPx)).toMatchObject({ kind: 'element', element: { uuid: 'chair' } })
    // Beside the prop, the deck.
    expect(hitAt({ ...scene, elements: [prop] }, { h: -1, v: -2 }, mPerPx)).toMatchObject({ kind: 'region' })
    // A platform the size of the deck it is: the region, listed first, takes the tie.
    const platform = element({ uuid: 'plat', kind: 'PLATFORM', positionY: 2, positionZ: 0.5, widthM: 4, depthM: 2, heightM: 0.5 })
    expect(hitAt({ ...scene, elements: [platform] }, { h: 1, v: -2 }, mPerPx)).toMatchObject({ kind: 'region' })
  })

  it('scales the pixel radii with the zoom, so a press feels the same at every zoom', () => {
    expect(hitAt(scene, { h: 0.3, v: -4 }, 0.01)).toMatchObject({ kind: 'rigging' })
    // Zoomed out ten times, 30 cm is inside the fixture's 12 px.
    expect(hitAt(scene, { h: 0.3, v: -4 }, 0.1)).toMatchObject({ kind: 'patch' })
  })

  it('never takes a room — it is the whole hall — nor an element the view hides', () => {
    const hall = element({ uuid: 'hall', kind: 'ROOM', widthM: 30, depthM: 40, heightM: 10 })
    const hiddenFlat = element({ uuid: 'gone', hidden: true })
    expect(elementOutline(hall, plan)).toBeNull()
    expect(elementOutline(hiddenFlat, plan)).toBeNull()
    expect(hitAt({ ...scene, elements: [hall, hiddenFlat] }, { h: 5, v: -10 }, mPerPx)).toBeNull()
  })
})

describe('outlines', () => {
  it('a platform hangs down from its Z, which is its top, as a region does', () => {
    const platform = element({ kind: 'PLATFORM', positionZ: 1, widthM: 2, depthM: 2, heightM: 1 })
    const outline = elementOutline(platform, front)!
    const vs = outline.map((p) => p.v)
    // v is screen-down: −Z. The box runs from Z 0 to Z 1.
    expect(Math.min(...vs)).toBeCloseTo(-1, 9)
    expect(Math.max(...vs)).toBeCloseTo(0, 9)
  })

  it('a flown piece stands at its trim, not its Z', () => {
    const cloth = element({ kind: 'OBJECT', positionZ: 0, heightM: 1, params: { flies: true, states: { trimM: 5 } } })
    const vs = elementOutline(cloth, front)!.map((p) => p.v)
    expect(Math.min(...vs)).toBeCloseTo(-6, 9)
    expect(Math.max(...vs)).toBeCloseTo(-5, 9)
  })

  it('an object’s outline is the shape its builder draws: a disc stands its diameter tall, a cylinder is round', () => {
    const moon = element({ kind: 'OBJECT', widthM: 2, depthM: 0.1, heightM: 0.5, params: { shape: 'DISC' } })
    const vs = elementOutline(moon, front)!.map((p) => p.v)
    expect(Math.min(...vs)).toBeCloseTo(-2, 9)
    expect(Math.max(...vs)).toBeCloseTo(0, 9)
    const drum = element({ kind: 'OBJECT', positionY: 1, widthM: 1, depthM: 0.2, heightM: 1, params: { shape: 'CYLINDER' } })
    const planVs = elementOutline(drum, plan)!.map((p) => p.v)
    expect(Math.max(...planVs) - Math.min(...planVs)).toBeCloseTo(1, 9)
  })

  it('a seating block is the box round its seats', () => {
    const stalls = element({
      kind: 'SEATING',
      positionY: -4,
      widthM: 0,
      depthM: 0,
      heightM: 0,
      params: { rows: 3, seatsPerRow: 5, rowPitchM: 1, seatPitchM: 0.5 },
    })
    const outline = elementOutline(stalls, plan)!
    // Seats 1–5 across 2 m, rows A–C running 2 m back from the stage (local −Y), 30 cm round.
    expect(insideOutline({ h: 0, v: 5 }, outline)).toBe(true)
    expect(insideOutline({ h: 0, v: 6.2 }, outline)).toBe(true)
    expect(insideOutline({ h: 0, v: 3.5 }, outline)).toBe(false)
  })

  it('a yawed region is a polygon in plan and its bounding box in an elevation', () => {
    const turned = { ...deck, yawDeg: 45 }
    expect(regionOutline(turned, plan)).toHaveLength(4)
    expect(insideOutline({ h: 0, v: -2 }, regionOutline(turned, plan))).toBe(true)
    const vs = regionOutline(turned, front).map((p) => p.v)
    // Its deck (top) at 0.5, its floor at 0 — v is −Z.
    expect(Math.min(...vs)).toBeCloseTo(-0.5, 9)
    expect(Math.max(...vs)).toBeCloseTo(0, 9)
  })

  it('a bar pointing at the viewer is edge-on', () => {
    const upstage = { ...bar, yawDeg: 90 }
    expect(projectRigging(upstage, front).degenerate).toBe(true)
    expect(projectRigging(upstage, plan).degenerate).toBe(false)
  })
})

describe('marqueeHits', () => {
  it('takes the fixtures in the band; a lantern selects its fixture, once', () => {
    const points = [
      point('par-1', 0, 0),
      point('par-2', 5, 0),
      // par-1's pair across the stage: inside the band on its own.
      point('par-1', 1, 1, { uuid: 'p1-sr' }),
      point('par-3', 1.5, 0.5, { uuid: 'p3-sl' }),
    ]
    expect(marqueeHits(points, { h: -1, v: -1 }, { h: 2, v: 2 })).toEqual([
      { kind: 'patch', patchKey: 'par-1' },
      { kind: 'patch', patchKey: 'par-3' },
    ])
    // Dragged the other way, the same band.
    expect(marqueeHits(points, { h: 2, v: 2 }, { h: -1, v: -1 })).toHaveLength(2)
  })
})

describe('sectionView', () => {
  const view = { h: 1, v: -3, zoom: 50, width: 800, height: 600 }

  it('turns a pixel into a point on the section and back', () => {
    const p = pixelToSection(view, 500, 200)
    expect(p).toEqual({ h: 3, v: -5 })
    expect(sectionToPixel(view, p)).toEqual({ x: 500, y: 200 })
    expect(visibleSection(view)).toEqual({ hMin: -7, hMax: 9, vMin: -9, vMax: 3 })
  })

  // The layer's metres are the projection's h and v, so the section camera's screen axes must be
  // exactly them: right along +h and down along +v (`orthoSection`'s up is screen-up).
  it.each(['plan', 'front', 'side'] as const)('the %s camera looks along the projection’s axes', (id) => {
    const s = orthoSection(id, { min: { x: -5, y: 0, z: 0 }, max: { x: 5, y: 8, z: 6 } })
    const d = s.target.map((t, i) => t - s.position[i]!)
    const up = s.up
    // right = forward × up, in three.js space (x, y, z) = lighting (X, Z, −Y).
    const right = [d[1]! * up[2] - d[2]! * up[1], d[2]! * up[0] - d[0]! * up[2], d[0]! * up[1] - d[1]! * up[0]]
    const lighting = (v: readonly number[]) => ({ x: v[0]!, y: -v[2]!, z: v[1]! })
    const proj = STAGE_PROJECTIONS[id]
    const r = lighting(right)
    const down = lighting(up.map((u) => -u))
    expect(Math.sign(r[proj.h.axis])).toBe(proj.h.sign)
    expect(Math.sign(down[proj.v.axis])).toBe(proj.v.sign)
  })
})
