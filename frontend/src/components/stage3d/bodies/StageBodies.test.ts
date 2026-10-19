// @vitest-environment jsdom
//
// jsdom only because the module imports @react-three/fiber (and the store, through the archetype)
// at top level. Nothing renders and no WebGL context is created: meshes and buffers are plain objects.
import { describe, expect, it } from 'vitest'
import { Color, Matrix4, MeshBasicMaterial, ShaderMaterial } from 'three'
import { bodyInputFor, bodySpecFor, type BodySpec } from './archetype'
import {
  acquireBodyGeometry,
  bodyGeometry,
  bodyGeometryKey,
  cachedBodyGeometryCount,
  releaseBodyGeometry,
  releaseUnheldBodyGeometry,
} from './bodyGeometry'
import { applyHousingWorkLights, buildBodies, buildBodyLayout, flushBodies, makeBodiesHandle, makeBodyPose } from './StageBodies'
import { HOUSING_COLOR, HOUSING_FILL, TAG_HOUSING_ALBEDO, TAG_HOUSING_COLORS } from './palette'
import { litByFill } from '../scene/surfaceShader'
import { WORK_LIGHT_LEVELS } from '../scene/workLights'
import { RING_SEGMENTS } from '../../../lib/ringEffect'
import { FIXTURE_COLOURS } from '../../../lib/fixtureColours'

function spec(kind: 'PROFILE' | 'FRESNEL', lengthM: number | null = null): BodySpec {
  return bodySpecFor({
    ...bodyInputFor({ kindOverride: kind }, undefined, undefined, lengthM),
    acceptsBeamAngle: true,
  })
}

function build(specs: BodySpec[]) {
  const layout = buildBodyLayout(specs.map((s) => ({ spec: s, mount: 'hang' as const })))
  return buildBodies(layout, new MeshBasicMaterial(), new MeshBasicMaterial(), new ShaderMaterial())
}

/** Every buffer version, to see what a frame flagged for upload. */
function versions(b: ReturnType<typeof build>): number[] {
  return [...b.meshes.map((m) => m.instanceMatrix.version), b.billboardCentre.version]
}

describe('the instanced bodies', () => {
  it('shares one mesh per part between bodies drawn from one spec', () => {
    const b = build([spec('PROFILE'), spec('PROFILE'), spec('FRESNEL')])
    // Two geometries (profile, fresnel) × two levels × (yoke + head), then the lenses and hangers.
    const heads = b.meshes.filter((m) => m.count === 2)
    expect(heads.length).toBeGreaterThan(0)
    expect(b.addresses[0].parts.full.head?.mesh).toBe(b.addresses[1].parts.full.head?.mesh)
    expect(b.addresses[0].parts.full.head?.index).not.toBe(b.addresses[1].parts.full.head?.index)
    expect(b.addresses[2].parts.full.head?.mesh).not.toBe(b.addresses[0].parts.full.head?.mesh)
    expect(b.discLenses.count).toBe(3)
  })

  it('uploads nothing on a frame where no body moved', () => {
    const b = build([spec('PROFILE')])
    const handle = makeBodiesHandle(b)
    const pose = makeBodyPose()
    pose.head.makeTranslation(1, 3, -2)
    pose.yoke.makeTranslation(1, 3, -2)
    pose.lenses.push(new Matrix4().makeTranslation(1, 2.7, -2))
    handle.writePose(0, pose)
    flushBodies(b)
    const before = versions(b)
    handle.writePose(0, pose)
    flushBodies(b)
    expect(versions(b)).toEqual(before)
    // A real move is uploaded.
    pose.head.makeTranslation(1.5, 3, -2)
    handle.writePose(0, pose)
    flushBodies(b)
    expect(versions(b)).not.toEqual(before)
  })

  it('parks the level it leaves, and shows a billboard only at a distance', () => {
    const b = build([spec('PROFILE')])
    const handle = makeBodiesHandle(b)
    const pose = makeBodyPose()
    pose.head.makeTranslation(0, 3, 0)
    handle.writePose(0, pose)
    const full = b.addresses[0].parts.full.head!
    const simple = b.addresses[0].parts.simple.head!
    const at = (m: typeof full) => new Matrix4().fromArray(m.mesh.instanceMatrix.array, m.index * 16)
    expect(at(full).elements[13]).toBe(3)
    pose.shown = 'simple'
    handle.writePose(0, pose)
    expect(at(full).determinant()).toBe(0)
    expect(at(simple).elements[13]).toBe(3)
    pose.shown = 'billboard'
    pose.sizeM = 0.3
    handle.writePose(0, pose)
    expect(at(simple).determinant()).toBe(0)
    expect(b.billboardCentre.getW(0)).toBeCloseTo(0.3, 6)
  })

  it('paints a lens and tints a housing per instance', () => {
    const b = build([spec('PROFILE')])
    const handle = makeBodiesHandle(b)
    handle.setLens(0, 0, new Color(1, 0, 0))
    expect(b.discLenses.instanceColor!.getX(0)).toBe(1)
    const head = b.addresses[0].parts.full.head!
    const before = head.mesh.instanceColor!.getX(head.index)
    handle.setActive(0, true)
    expect(head.mesh.instanceColor!.getX(head.index)).toBeGreaterThan(before)
  })
})

describe('a lens ring', () => {
  const ringed = (): BodySpec => {
    const s = spec('PROFILE')
    return { ...s, ring: { x: 0, y: s.cells[0].y, z: 0, outerR: 0.1 }, key: `${s.key}:ring` }
  }

  it('instances its segments for the ringed bodies alone', () => {
    const b = build([spec('PROFILE'), ringed(), ringed()])
    expect(b.rings.count).toBe(2 * RING_SEGMENTS)
    expect(b.addresses[0].ringBase).toBeNull()
    expect(b.addresses[2].ringBase).toBe(RING_SEGMENTS)
  })

  it('draws the ring at full detail only, and paints it a segment at a time', () => {
    const b = build([ringed()])
    const handle = makeBodiesHandle(b)
    const pose = makeBodyPose()
    pose.ring = new Matrix4().makeTranslation(0, 3, 0)
    const at = (k: number) => new Matrix4().fromArray(b.rings.instanceMatrix.array, k * 16)
    handle.writePose(0, pose)
    expect(at(0).elements[13]).toBe(3)
    // Each segment is the next share of the turn.
    expect(at(1).elements).not.toEqual(at(0).elements)
    pose.shown = 'simple'
    handle.writePose(0, pose)
    expect(at(0).determinant()).toBe(0)
    handle.setRing(0, 5, new Color(0, 1, 0))
    expect(b.rings.instanceColor!.getY(5)).toBe(1)
    handle.hide(0)
    expect(at(5).determinant()).toBe(0)
  })
})

describe('the shared geometry', () => {
  it('disposes a body no canvas holds any more, so every length a strip is set to does not stay behind', () => {
    releaseUnheldBodyGeometry()
    const start = cachedBodyGeometryCount()
    const a = spec('PROFILE', 0.61)
    const b = spec('PROFILE', 0.62)
    const ka = bodyGeometryKey(a, 'hang', 'full')
    const kb = bodyGeometryKey(b, 'hang', 'full')
    acquireBodyGeometry(ka, bodyGeometry(a, 'hang', 'full'))
    acquireBodyGeometry(kb, bodyGeometry(b, 'hang', 'full'))
    expect(cachedBodyGeometryCount()).toBe(start + 2)
    // A new layout takes its hold before the old one lets go: a key both hold survives.
    acquireBodyGeometry(kb, bodyGeometry(b, 'hang', 'full'))
    releaseBodyGeometry(kb)
    releaseBodyGeometry(ka)
    expect(cachedBodyGeometryCount()).toBe(start + 1)
    releaseBodyGeometry(kb)
    expect(cachedBodyGeometryCount()).toBe(start)
    // A build that never committed held nothing, and the next sweep takes it.
    bodyGeometry(spec('PROFILE', 0.63), 'hang', 'full')
    expect(cachedBodyGeometryCount()).toBe(start + 1)
    releaseUnheldBodyGeometry()
    expect(cachedBodyGeometryCount()).toBe(start)
  })
})

describe('the housings under work lights (stage-view menu plan D6)', () => {
  const materials = () => ({
    housing: new ShaderMaterial({ uniforms: { uFill: { value: HOUSING_FILL } } }),
    billboard: new ShaderMaterial({
      uniforms: { uHousing: { value: new Color() }, uHousingActive: { value: new Color() }, uTag: { value: [] as Color[] } },
    }),
  })

  it('raises the housings’ own fill with the room, and the billboards follow through litByFill', () => {
    const { housing, billboard } = materials()
    applyHousingWorkLights(housing, billboard, WORK_LIGHT_LEVELS.on)
    expect(housing.uniforms.uFill.value).toBe(0.8)
    const on = billboard.uniforms.uHousing.value as Color
    expect(on.equals(litByFill(new Color(HOUSING_COLOR), 0.8, 0.5, WORK_LIGHT_LEVELS.on))).toBe(true)

    applyHousingWorkLights(housing, billboard, WORK_LIGHT_LEVELS.off)
    expect(housing.uniforms.uFill.value).toBe(HOUSING_FILL)
    const off = billboard.uniforms.uHousing.value as Color
    // Off is the billboard exactly as it was drawn before there was a switch.
    expect(off.equals(litByFill(new Color(HOUSING_COLOR), HOUSING_FILL))).toBe(true)
    expect(on.r).toBeGreaterThan(off.r)
  })

  it('keeps a selected housing’s lead at either level: it rides the same tint', () => {
    const { housing, billboard } = materials()
    for (const level of [WORK_LIGHT_LEVELS.off, WORK_LIGHT_LEVELS.on]) {
      applyHousingWorkLights(housing, billboard, level)
      const plain = billboard.uniforms.uHousing.value as Color
      const active = billboard.uniforms.uHousingActive.value as Color
      expect(active.b).toBeGreaterThan(plain.b)
    }
  })
})

describe('the housings by colour (tables-colours plan D12)', () => {
  const tagTint = (colour: (typeof FIXTURE_COLOURS)[number]) =>
    new Color(TAG_HOUSING_COLORS[colour]).multiplyScalar(TAG_HOUSING_ALBEDO)

  /** Every instance colour the handle writes, counted on the meshes' own `setColorAt`. */
  function countWrites(b: ReturnType<typeof build>) {
    let writes = 0
    for (const mesh of b.meshes) {
      const original = mesh.setColorAt.bind(mesh)
      mesh.setColorAt = (index, colour) => {
        writes++
        return original(index, colour)
      }
    }
    return () => writes
  }

  /** Every housing instance of one slot: its parts at both levels and its hanger. */
  function housingInstances(b: ReturnType<typeof build>, slot: number) {
    const a = b.addresses[slot]
    const parts = (['full', 'simple'] as const).flatMap((lod) =>
      (['base', 'yoke', 'head'] as const).flatMap((part) => (a.parts[lod][part] ? [a.parts[lod][part]!] : [])),
    )
    return [...parts, { mesh: b.hangers, index: slot }]
  }

  /** Equal within the instance buffer's float32. */
  const same = (a: Color, b: Color) => Math.abs(a.r - b.r) + Math.abs(a.g - b.g) + Math.abs(a.b - b.b) < 1e-6

  const colourAt = (p: { mesh: { instanceColor: { getX: (i: number) => number; getY: (i: number) => number; getZ: (i: number) => number } | null }; index: number }) =>
    new Color(p.mesh.instanceColor!.getX(p.index), p.mesh.instanceColor!.getY(p.index), p.mesh.instanceColor!.getZ(p.index))

  it('is off by default: a colour is held, and the housing stays plain until By colour', () => {
    const b = build([spec('PROFILE'), spec('PROFILE')])
    const handle = makeBodiesHandle(b)
    const writes = countWrites(b)
    handle.setTag(0, 'red')
    expect(writes()).toBe(0)
    expect(b.billboardTag.getX(0)).toBe(0)
    handle.setByColour(true)
    for (const p of housingInstances(b, 0)) expect(same(colourAt(p), tagTint('red'))).toBe(true)
    expect(b.billboardTag.getX(0)).toBe(FIXTURE_COLOURS.indexOf('red') + 1)
    // The untagged slot is not repainted.
    expect(writes()).toBe(housingInstances(b, 0).length)
  })

  it('writes a recolour once per instance, and nothing on an idle frame or a repeat', () => {
    const b = build([spec('PROFILE')])
    const handle = makeBodiesHandle(b)
    handle.setByColour(true)
    handle.setTag(0, 'red')
    flushBodies(b)
    const writes = countWrites(b)
    const pose = makeBodyPose()
    pose.head.makeTranslation(0, 3, 0)
    // A second of idle frames: every body writes its pose, and nothing writes a colour.
    for (let frame = 0; frame < 60; frame++) {
      handle.writePose(0, pose)
      flushBodies(b)
    }
    handle.setTag(0, 'red')
    handle.setActive(0, false)
    handle.setByColour(true)
    expect(writes()).toBe(0)
    handle.setTag(0, 'cyan')
    expect(writes()).toBe(housingInstances(b, 0).length)
    for (const p of housingInstances(b, 0)) expect(same(colourAt(p), tagTint('cyan'))).toBe(true)
  })

  it('still turns a selected housing the desk’s blue, and gives it its colour back', () => {
    const b = build([spec('PROFILE')])
    const handle = makeBodiesHandle(b)
    handle.setByColour(true)
    handle.setTag(0, 'red')
    const head = b.addresses[0].parts.full.head!
    handle.setActive(0, true)
    expect(same(colourAt(head), tagTint('red'))).toBe(false)
    expect(colourAt(head).b).toBeGreaterThan(colourAt(head).r)
    expect(b.billboardActive.getX(0)).toBe(1)
    expect(b.billboardTag.getX(0)).toBe(0)
    handle.setActive(0, false)
    expect(same(colourAt(head), tagTint('red'))).toBe(true)
    // Off again: plain.
    handle.setByColour(false)
    expect(same(colourAt(head), new Color(HOUSING_COLOR))).toBe(true)
    expect(b.billboardTag.getX(0)).toBe(0)
  })

  it('lights the billboard glyphs’ colours as the plain housing is lit, at either work-light level', () => {
    const housing = new ShaderMaterial({ uniforms: { uFill: { value: HOUSING_FILL } } })
    const billboard = new ShaderMaterial({
      uniforms: { uHousing: { value: new Color() }, uHousingActive: { value: new Color() }, uTag: { value: [] as Color[] } },
    })
    applyHousingWorkLights(housing, billboard, WORK_LIGHT_LEVELS.on)
    const tags = billboard.uniforms.uTag.value as Color[]
    expect(tags).toHaveLength(FIXTURE_COLOURS.length)
    expect(tags[0].equals(litByFill(tagTint('red'), 0.8, 0.5, WORK_LIGHT_LEVELS.on))).toBe(true)
  })
})
