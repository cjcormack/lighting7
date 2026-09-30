// @vitest-environment jsdom
//
// jsdom only because this module imports @react-three/fiber at top level. Nothing here renders,
// and no WebGL context is ever created — the meshes and buffers are plain JS objects.
import { describe, expect, it } from 'vitest'
import {
  Color,
  InstancedBufferAttribute,
  InstancedMesh,
  Matrix4,
  ShaderMaterial,
  Vector3,
} from 'three'
import {
  buildEmitters,
  computeRegionGeometry,
  dirtyGroups,
  flushDirty,
  makeHandle,
  writeRegionUniforms,
  type BuiltEmitters,
  type EmittersHandle,
} from './StageEmitters'
import { makeConeMaterial } from './beamShaders'
import { MAX_PRISM_LOBES, buildEmitterLayout, type EmitterLayout } from './emitterLayout'

// Two slots with a prism and a wash block each, so every writer below has room to write.
const LAYOUT = buildEmitterLayout([
  { lobes: MAX_PRISM_LOBES, washPixels: 4 },
  { lobes: MAX_PRISM_LOBES, washPixels: 4 },
])
const REGIONS = computeRegionGeometry([
  { uuid: 'r1', centerX: 1, centerY: 0, centerZ: 0, widthM: 2, depthM: 2, heightM: 1, yawDeg: 0 },
  { uuid: 'r2', centerX: -1, centerY: 0, centerZ: 0, widthM: 2, depthM: 2, heightM: 1, yawDeg: 0 },
] as Parameters<typeof computeRegionGeometry>[0])

function build(layout: EmitterLayout = LAYOUT): BuiltEmitters {
  // Bare materials: buildEmitters only hands them to the meshes, and nothing here draws.
  const mat = () => new ShaderMaterial()
  return buildEmitters(layout, REGIONS.length, mat(), mat())
}

/**
 * Every GPU buffer the built emitters own, discovered from the object itself rather than from
 * `dirtyGroups()`.
 *
 * That independence is the whole point: if a writer mutated a buffer no group covers, comparing
 * against the group table would agree with itself and see nothing. Walking `BuiltEmitters` finds
 * every attribute and every mesh's `instanceMatrix`, table or no table.
 */
function allBuffers(b: BuiltEmitters): Array<{ name: string; attr: InstancedBufferAttribute }> {
  const out: Array<{ name: string; attr: InstancedBufferAttribute }> = []
  for (const [name, value] of Object.entries(b)) {
    if (value instanceof InstancedBufferAttribute) out.push({ name, attr: value })
    else if (value instanceof InstancedMesh) {
      out.push({ name: `${name}.instanceMatrix`, attr: value.instanceMatrix })
    }
  }
  return out
}

/**
 * Run one writer against a freshly-cleaned build and report which buffers its bytes actually
 * reached, and which ones the flush flagged for upload.
 */
function writeAndFlush(
  b: BuiltEmitters,
  write: (h: EmittersHandle) => void,
): { mutated: string[]; flagged: string[] } {
  const handle = makeHandle(b)
  const groups = dirtyGroups(b)
  const buffers = allBuffers(b)
  const before = buffers.map((x) => Float32Array.from(x.attr.array as Float32Array))
  // `needsUpdate` is write-only on a three BufferAttribute — the setter bumps `version` and
  // there is no getter — so an upload is observed as a version bump, which is the same thing
  // the renderer itself keys on.
  const versions = buffers.map((x) => x.attr.version)
  b.dirty = 0

  write(handle)
  flushDirty(b, groups)

  const mutated: string[] = []
  const flagged: string[] = []
  buffers.forEach((x, i) => {
    const now = x.attr.array as Float32Array
    if (now.some((v, j) => v !== before[i][j])) mutated.push(x.name)
    if (x.attr.version > versions[i]) flagged.push(x.name)
  })
  return { mutated, flagged }
}

const ORIGIN = new Vector3(1.5, 4, -2)
const DIR = new Vector3(0, -1, 0)
const RIGHT = new Vector3(1, 0, 0)
const COLOUR = new Color('#3fa9f5')
const MATRIX = new Matrix4().makeScale(2, 3, 4)

// One entry per EmittersHandle write method, with arguments chosen so every value it stores is
// non-zero and distinguishable from the zeroed initial buffers — otherwise a write that happens
// to store what was already there would look like no write at all.
const WRITERS: Array<{ name: string; write: (h: EmittersHandle) => void }> = [
  { name: 'writeBeamMatrix (shell)', write: (h) => h.writeBeamMatrix(1, 0, MATRIX, false) },
  { name: 'writeBeamMatrix (volumetric)', write: (h) => h.writeBeamMatrix(1, 0, MATRIX, true) },
  {
    name: 'writeConeAttrs',
    write: (h) => h.writeConeAttrs(1, 0, ORIGIN, DIR, COLOUR, 0.4, 0.97),
  },
  { name: 'writeBeamFx', write: (h) => h.writeBeamFx(1, 0, 0.7, 3, 1.2, 6, RIGHT) },
  { name: 'writeShadowMask', write: (h) => h.writeShadowMask(1, 0, 0b11) },
]

describe('emitter dirty groups', () => {
  const built = build()

  it.each(WRITERS)('flags every buffer $name writes to', ({ write }) => {
    const { mutated, flagged } = writeAndFlush(built, write)
    // The writer has to reach *something*, or the case proves nothing.
    expect(mutated.length).toBeGreaterThan(0)
    expect(mutated.filter((name) => !flagged.includes(name))).toEqual([])
  })

  it('flags the matrices and visibilities hideLobes parks', () => {
    // Light a slot first, so parking it is a real change rather than a write of the zeros
    // already there.
    const h = makeHandle(built)
    h.writeBeamMatrix(0, 0, MATRIX, false)
    flushDirty(built, dirtyGroups(built))

    const { mutated, flagged } = writeAndFlush(built, (handle) => handle.hideSlot(0))
    expect(mutated.length).toBeGreaterThan(0)
    expect(mutated.filter((name) => !flagged.includes(name))).toEqual([])
  })

  it('uploads nothing on a frame no writer touched — the point of the change', () => {
    const { mutated, flagged } = writeAndFlush(built, () => {})
    expect(mutated).toEqual([])
    expect(flagged).toEqual([])
  })

  it('clears the dirty field so a group is not re-uploaded on the next frame', () => {
    const handle = makeHandle(built)
    handle.writeConeAttrs(0, 0, ORIGIN, DIR, COLOUR, 0.5, 0.9)
    expect(built.dirty).not.toBe(0)
    flushDirty(built, dirtyGroups(built))
    expect(built.dirty).toBe(0)
  })

  it('covers every buffer of the build in exactly one group', () => {
    // A buffer in no group can never be uploaded after the first frame; a buffer in two is a
    // sign the groups have stopped following the writers.
    const grouped = dirtyGroups(built).flatMap((g) => g.buffers)
    for (const { name, attr } of allBuffers(built)) {
      const hits = grouped.filter((buffer) => buffer === attr).length
      expect(`${name}:${hits}`).toBe(`${name}:1`)
    }
  })
})

describe('emitter layout bounds', () => {
  // A par (one lobe, no wash) beside a prism mover.
  const layout = buildEmitterLayout([
    { lobes: 1, washPixels: 0 },
    { lobes: MAX_PRISM_LOBES, washPixels: 0 },
  ])

  it('sizes the meshes and the light table by the rig, not by slots × the worst case', () => {
    const b = build(layout)
    expect(b.coneMesh.count).toBe(1 + MAX_PRISM_LOBES)
    expect(b.volumeMesh.count).toBe(1 + MAX_PRISM_LOBES)
    // One row per lobe; no wash block, so no wash rows.
    expect(b.lights.capacity).toBe(1 + MAX_PRISM_LOBES)
    expect(build(LAYOUT).lights.capacity).toBe(2 * MAX_PRISM_LOBES + 2 * 4)
  })

  it('drops a write past a slot\'s block rather than landing in the next slot', () => {
    const b = build(layout)
    const { mutated } = writeAndFlush(b, (h) => {
      // Lobe 1 of the par would be lobe 0 of the mover.
      h.writeBeamMatrix(0, 1, MATRIX, false)
      h.writeConeAttrs(0, 1, ORIGIN, DIR, COLOUR, 0.4, 0.97)
      h.writeLight(0, 1, ORIGIN, DIR, COLOUR, 1, 0.97, 0, -1, null)
      // A pixel on a slot with no wash block, a slot that isn't.
      h.writeWashLight(0, 0, ORIGIN, DIR, COLOUR, 1, 0.9, null)
      h.writeBeamMatrix(7, 0, MATRIX, false)
      h.writeLight(7, 0, ORIGIN, DIR, COLOUR, 1, 0.97, 0, -1, null)
    })
    expect(mutated).toEqual([])
    expect(b.dirty).toBe(0)
    expect(b.lights.litCount()).toBe(0)
  })

  it('reports each slot\'s block to the directors', () => {
    const h = makeHandle(build(layout))
    expect(h.lobesFor(0)).toBe(1)
    expect(h.lobesFor(1)).toBe(MAX_PRISM_LOBES)
    expect(h.lobesFor(2)).toBe(0)
    expect(h.washPixelsFor(1)).toBe(0)
  })
})

describe('the light table rows', () => {
  const layout = buildEmitterLayout([
    { lobes: 1, washPixels: 0 },
    { lobes: MAX_PRISM_LOBES, washPixels: 0 },
    { lobes: 0, washPixels: 4 },
  ])

  it('puts every lobe first and every washing pixel after it, and takes a dark slot off the table', () => {
    const b = build(layout)
    const h = makeHandle(b)
    h.writeLight(1, 2, ORIGIN, DIR, COLOUR, 1, 0.97, 0.5, 6, null)
    h.writeWashLight(2, 3, ORIGIN, DIR, COLOUR, 0.5, 0.7, null)
    // Lobe 2 of slot 1 is row 1 + 2; pixel 3 of slot 2 is after all seven lobes.
    expect(b.lights.weight[3]).toBeGreaterThan(0)
    expect(b.lights.weight[1 + MAX_PRISM_LOBES + 3]).toBeGreaterThan(0)
    expect(b.lights.litCount()).toBe(2)
    h.hideSlot(1)
    h.clearWashLight(2, 3)
    expect(b.lights.litCount()).toBe(0)
  })

  it('writes a surface hit as the plane the surfaces stop lighting behind', () => {
    const b = build(layout)
    makeHandle(b).writeLight(0, 0, ORIGIN, DIR, COLOUR, 1, 0.97, 0, -1, {
      px: 1.5, py: 0, pz: -2, nx: 0, ny: 1, nz: 0,
    })
    const row = b.lights.staged.subarray(0, 16)
    expect(Array.from(row.subarray(12, 16))).toEqual([0, 1, 0, 0])
  })

  it('answers the axial reach from the colliders it is handed', () => {
    const b = build(layout)
    const floor = [{ cx: 0, cy: -0.01, cz: 0, hx: 10, hy: 0.01, hz: 10, cos: 1, sin: 0 }]
    const h = makeHandle(b, () => floor)
    const hit = { t: 0, nx: 0, ny: 0, nz: 0 }
    expect(h.reach(ORIGIN, DIR, 40, hit)).toBe(true)
    expect(hit.t).toBeCloseTo(4, 9)
    expect(hit.ny).toBe(1)
    expect(makeHandle(b).reach(ORIGIN, DIR, 40, hit)).toBe(false)
  })
})

describe('region uniforms', () => {
  it('carry each region\'s own turn, as the region mesh draws it', () => {
    const regions = computeRegionGeometry([
      { uuid: 'r', centerX: 2, centerY: 3, centerZ: 0.5, widthM: 4, depthM: 1, heightM: 0.5, yawDeg: 30 },
    ] as Parameters<typeof computeRegionGeometry>[0])
    const mat = makeConeMaterial()
    writeRegionUniforms([mat], regions, 1, 8)
    const yaw = (30 * Math.PI) / 180
    const cs = (mat.uniforms.uRegionYawCs.value as Array<{ x: number; y: number }>)[0]
    // (cos yaw, sin yaw): rayObbT turns a ray in by −yaw, undoing the region mesh's +yaw. It was
    // (cos −yaw, sin −yaw), which mirrored every yawed shadow box.
    expect(cs.x).toBeCloseTo(Math.cos(yaw), 12)
    expect(cs.y).toBeCloseTo(Math.sin(yaw), 12)
    expect(mat.uniforms.uNumRegions.value).toBe(1)
    // The wall the beams clip to, in three's −z: 8 m upstage.
    expect(mat.uniforms.uWallZ.value).toBe(-8)
  })

  it('centre a region half its thickness below its deck', () => {
    // `centerZ` is the top surface: a 0.95 m deck at 0 is centred at -0.475, below the stage.
    const [r] = computeRegionGeometry([
      { uuid: 'r', centerX: 0, centerY: 0, centerZ: 0, widthM: 2, depthM: 2, heightM: 0.95, yawDeg: 0 },
    ] as Parameters<typeof computeRegionGeometry>[0])
    expect(r.obbCenter.y).toBeCloseTo(-0.475, 12)
  })
})
