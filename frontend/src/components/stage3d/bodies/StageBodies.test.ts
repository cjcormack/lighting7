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
import { HOUSING_COLOR, HOUSING_FILL } from './palette'
import { litByFill } from '../scene/surfaceShader'
import { WORK_LIGHT_LEVELS } from '../scene/workLights'

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
    billboard: new ShaderMaterial({ uniforms: { uHousing: { value: new Color() }, uHousingActive: { value: new Color() } } }),
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
