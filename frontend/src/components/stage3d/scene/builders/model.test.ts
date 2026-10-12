import { describe, expect, it } from 'vitest'
import type { StageElementDto } from '../../../../api/stageElementApi'
import modelBoundsFixture from '../../../../../../src/test/resources/stage/modelBounds.fixture.json'
import { buildElement } from '.'
import { modelBounds, modelParts, readModelPart, transformedBounds } from './model'
import { partBox } from '../beamReach'
import { outwardProfile, partGeometry } from '../StageSceneElements'
import { PAINT_FACE_ATTRIBUTE } from '../surfaceShader'
import type { PartGeometry, ScenePart } from '../sceneParts'

function model(parts: unknown[], fields: Partial<StageElementDto> = {}): StageElementDto {
  return {
    id: 1, uuid: 'm', name: 'Model', kind: 'OBJECT', layer: 'SET',
    positionX: 0, positionY: 0, positionZ: 0, yawDeg: 0, widthM: 1, depthM: 1, heightM: 1,
    finishColour: '#336699', finishPattern: null, emissive: false, params: { shape: 'MODEL', parts }, hidden: false, sortOrder: 0,
    ...fields,
  }
}

const HASH = 'a'.repeat(64)

/** The box round a built part's geometry, in the element's lighting frame. */
function partExtent(part: ScenePart) {
  const b = partBox(part.geometry, part.transform)
  return {
    minX: part.at.x + b.ox - b.hx, maxX: part.at.x + b.ox + b.hx,
    minY: part.at.y + b.oy - b.hy, maxY: part.at.y + b.oy + b.hy,
    minZ: part.at.z + b.oz - b.hz, maxZ: part.at.z + b.oz + b.hz,
  }
}

describe('a MODEL object (an OBJECT built from parts)', () => {
  it('answers the shared bounds vector the desk answers', () => {
    for (const c of modelBoundsFixture.cases) {
      const parts = c.parts.map((p, i) => readModelPart(p, i))
      expect(parts.every((p) => p != null), c.name).toBe(true)
      const b = modelBounds(parts.filter((p) => p != null))!
      for (const key of ['minX', 'minY', 'minZ', 'maxX', 'maxY', 'maxZ'] as const) {
        expect(b[key], `${c.name}: ${key}`).toBeCloseTo(c.bounds[key], 9)
      }
    }
  })

  it('fits its parts to the element’s box: the footprint centred on the origin, the lowest point on the base', () => {
    const element = model(
      [
        { shape: 'BOX', x: 5, y: 5, z: 10, widthM: 2, depthM: 1, heightM: 1 },
        { shape: 'CYLINDER', x: 7, y: 5, z: 11, widthM: 1, heightM: 3, yawDeg: 20, pitchDeg: 15 },
        { shape: 'LATHE', x: 4, y: 6, z: 9.5, profile: [[0.3, 0], [0.1, 1]] },
      ],
      { widthM: 3, depthM: 2, heightM: 4 },
    )
    const { parts } = buildElement(element)
    expect(parts).toHaveLength(3)
    // Every part's transformed box together is exactly the element's box.
    const all = parts.map(partExtent)
    expect(Math.min(...all.map((b) => b.minX))).toBeCloseTo(-1.5, 9)
    expect(Math.max(...all.map((b) => b.maxX))).toBeCloseTo(1.5, 9)
    expect(Math.min(...all.map((b) => b.minY))).toBeCloseTo(-1, 9)
    expect(Math.max(...all.map((b) => b.maxY))).toBeCloseTo(1, 9)
    expect(Math.min(...all.map((b) => b.minZ))).toBeCloseTo(0, 9)
    expect(Math.max(...all.map((b) => b.maxZ))).toBeCloseTo(4, 9)
  })

  it('draws a model whose box is its own size at its own size', () => {
    const element = model([{ shape: 'BOX', z: 0.5, widthM: 2, depthM: 1, heightM: 1 }], { widthM: 2, depthM: 1, heightM: 1 })
    const [box] = buildElement(element).parts
    expect(box.at).toEqual({ x: 0, y: 0, z: 0.5 })
    expect(box.transform).toEqual([1, 0, 0, 0, 1, 0, 0, 0, 1].map((v, i) => (i % 4 === 0 ? v : expect.closeTo(0, 12))))
  })

  it('finishes each part in its own colour, else the element’s, and carries its texture and its shadow', () => {
    const element = model([
      { shape: 'BOX', widthM: 1, depthM: 1, heightM: 1, colour: '#AA0000', pattern: 'boards', texture: { image: HASH.toUpperCase(), mode: 'TILE', tileM: 0.5 } },
      { shape: 'SPHERE', x: 2, widthM: 1, shadow: false, emissive: true },
    ])
    const [box, sphere] = buildElement(element).parts
    expect(box.finish).toMatchObject({ colour: '#aa0000', pattern: 'BOARDS', texture: { image: HASH, mode: 'TILE', tileM: 0.5 } })
    expect(box.light).toBe('solid')
    expect(sphere.finish).toMatchObject({ colour: '#336699', emissive: true })
    expect(sphere.finish.texture).toBeUndefined()
    expect(sphere.light).toBe('none')
  })

  it('leaves out a part it cannot read, and draws nothing for a model with none it can, or a flat one', () => {
    expect(modelParts(model([{ shape: 'TORUS', widthM: 1 }, { shape: 'BOX', widthM: 1, depthM: 1, heightM: 1 }]))).toHaveLength(1)
    expect(buildElement(model([{ shape: 'BOX', widthM: 1 }])).parts).toEqual([])
    expect(buildElement(model([{ shape: 'LATHE', profile: [[0, 0], [0, 1]] }])).parts).toEqual([])
    expect(buildElement({ ...model([]), params: { shape: 'MODEL' } }).parts).toEqual([])
  })

  it('bakes a part’s turn and scale into its vertices, and its collider holds them', () => {
    const element = model(
      [
        { shape: 'EXTRUDE', outline: [[0, 0], [2, 0], [1, 3]], depthM: 0.2, yawDeg: 35, rollDeg: 12 },
        { shape: 'BOX', x: 3, widthM: 1, depthM: 1, heightM: 1, pitchDeg: 40 },
      ],
      { widthM: 5, depthM: 1.5, heightM: 2 },
    )
    for (const part of buildElement(element).parts) {
      const g = partGeometry(part.geometry, undefined, { transform: part.transform })
      const b = partBox(part.geometry, part.transform)
      const pos = g.attributes.position
      for (let i = 0; i < pos.count; i++) {
        // three (x, y, z) is lighting (x, −z, y) turned back: lighting = (x, −z, y).
        const [x, y, z] = [pos.getX(i), -pos.getZ(i), pos.getY(i)]
        expect(Math.abs(x - b.ox)).toBeLessThanOrEqual(b.hx + 1e-5)
        expect(Math.abs(y - b.oy)).toBeLessThanOrEqual(b.hy + 1e-5)
        expect(Math.abs(z - b.oz)).toBeLessThanOrEqual(b.hz + 1e-5)
      }
      // A turned part never shadows itself: its skin reaches across its whole box.
      expect(b.skin).toBeCloseTo(Math.max(b.hx, b.hy, b.hz), 9)
      g.dispose()
    }
  })

  it('maps the box round a transform’s corners', () => {
    const box = { minX: -1, minY: -1, minZ: 0, maxX: 1, maxY: 1, maxZ: 2 }
    expect(transformedBounds(box, [2, 0, 0, 0, 1, 0, 0, 0, 0.5], 1, 0, 0)).toEqual({ minX: -1, minY: -1, minZ: 0, maxX: 3, maxY: 1, maxZ: 1 })
  })

  it('textures every face: an outline stretched over its extent, mirrored behind, or tiled in the model’s units', () => {
    const outline: PartGeometry = { shape: 'extrude', outline: [[0, 0], [2, 0], [2, 1], [0, 1]], holes: [], d: 0.1 }
    const stretched = partGeometry(outline, undefined, { texture: { image: HASH, mode: 'STRETCH', tileM: 1 } })
    const tiled = partGeometry(outline, undefined, { texture: { image: HASH, mode: 'TILE', tileM: 0.5 } })
    for (const g of [stretched, tiled]) {
      const faces = g.attributes[PAINT_FACE_ATTRIBUTE]
      for (let i = 0; i < faces.count; i++) expect(faces.getX(i)).toBe(1)
    }
    const su = stretched.attributes.uv
    const sp = stretched.attributes.position
    const sn = stretched.attributes.normal
    for (let i = 0; i < su.count; i++) {
      expect(su.getX(i)).toBeGreaterThanOrEqual(-1e-6)
      expect(su.getX(i)).toBeLessThanOrEqual(1 + 1e-6)
      // The downstage face reads left to right from the house; the upstage face from behind.
      if (sn.getZ(i) > 0.5) expect(su.getX(i)).toBeCloseTo(sp.getX(i) / 2, 6)
      if (sn.getZ(i) < -0.5) expect(su.getX(i)).toBeCloseTo(1 - sp.getX(i) / 2, 6)
    }
    const tu = tiled.attributes.uv
    const tp = tiled.attributes.position
    const tn = tiled.attributes.normal
    for (let i = 0; i < tu.count; i++) {
      if (tn.getZ(i) > 0.5) {
        expect(tu.getX(i)).toBeCloseTo(tp.getX(i) / 0.5, 6)
        expect(tu.getY(i)).toBeCloseTo(tp.getY(i) / 0.5, 6)
      }
    }
    stretched.dispose()
    tiled.dispose()
  })

  it('turns a lathe’s profile written top first so it faces out', () => {
    expect(outwardProfile([[0.2, 0], [0.4, 1]])).toEqual([[0.2, 0], [0.4, 1]])
    expect(outwardProfile([[0.4, 1], [0.2, 0]])).toEqual([[0.2, 0], [0.4, 1]])
  })
})
