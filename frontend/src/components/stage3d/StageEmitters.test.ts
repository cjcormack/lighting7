// @vitest-environment jsdom
//
// jsdom only because this module imports @react-three/fiber at top level. Nothing here renders,
// and no WebGL context is ever created — the meshes and buffers are plain JS objects.
import { packBlade } from './beamMask'
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
  writeHazeClip,
  writeRegionUniforms,
  type BeamWrite,
  type BuiltEmitters,
  type EmittersHandle,
} from './StageEmitters'
import { makeVolumeMaterial } from './beamShaders'
import { getGoboTexture } from './goboAtlas'
import { packGobos } from './goboLayers'
import { makeLightRow, type LightRow } from './scene/lightTable'
import { LAND_NONE, LAND_UP, REACH_EPS_M } from './scene/landing'
import { MAX_PRISM_LOBES, buildEmitterLayout, type EmitterLayout } from './emitterLayout'

// Two slots with a prism each, so every writer below has room to write.
const LAYOUT = buildEmitterLayout([
  { lobes: MAX_PRISM_LOBES, lights: MAX_PRISM_LOBES },
  { lobes: MAX_PRISM_LOBES, lights: MAX_PRISM_LOBES },
])
const REGIONS = computeRegionGeometry([
  { uuid: 'r1', centerX: 1, centerY: 0, centerZ: 0, widthM: 2, depthM: 2, heightM: 1, yawDeg: 0 },
  { uuid: 'r2', centerX: -1, centerY: 0, centerZ: 0, widthM: 2, depthM: 2, heightM: 1, yawDeg: 0 },
] as Parameters<typeof computeRegionGeometry>[0])

function build(layout: EmitterLayout = LAYOUT): BuiltEmitters {
  // Bare materials: buildEmitters only hands them to the meshes, and nothing here draws.
  return buildEmitters(layout, REGIONS.length, new ShaderMaterial())
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

/** A beam whose every field is non-zero, so a write that stores it reaches every buffer. */
function beamWrite(): BeamWrite {
  return {
    matrix: MATRIX,
    apex: ORIGIN,
    dir: DIR,
    right: RIGHT,
    color: COLOUR,
    opacity: 0.4,
    cosHalf: 0.97,
    edge: 0.7,
    gobos: packGobos(3, 9, 0, 1.2),
    focusDist: 6,
    near: 0.5,
    dof: 3,
    iris: 0.6,
    aspect: 0.3,
    bladesA: packBlade(0.2, 5) * 4096 + packBlade(0.1, 0),
    bladesB: packBlade(0.3, -4) * 4096,
    shadowMask: 0b11,
    land: { px: 1.5, py: 0.25, pz: -2, nx: 0, ny: 1, nz: 0, skin: REACH_EPS_M },
    edgeLand: { px: 1.5, py: 0, pz: -2.5, nx: 0, ny: 0, nz: 1, skin: REACH_EPS_M },
  }
}

function lightRow(level = 1, hit: LightRow['hit'] = null): LightRow {
  return {
    ...makeLightRow(),
    ax: ORIGIN.x,
    ay: ORIGIN.y,
    az: ORIGIN.z,
    cosBound: 0.97,
    r: COLOUR.r * level,
    g: COLOUR.g * level,
    b: COLOUR.b * level,
    hit,
  }
}

// One entry per EmittersHandle write method that touches a buffer.
const WRITERS: Array<{ name: string; write: (h: EmittersHandle) => void }> = [
  { name: 'writeBeam', write: (h) => h.writeBeam(1, 0, beamWrite()) },
]

describe('emitter dirty groups', () => {
  const built = build()

  it.each(WRITERS)('flags every buffer $name writes to', ({ write }) => {
    const { mutated, flagged } = writeAndFlush(built, write)
    // The writer has to reach *something*, or the case proves nothing.
    expect(mutated.length).toBeGreaterThan(0)
    expect(mutated.filter((name) => !flagged.includes(name))).toEqual([])
  })

  it('reaches every beam buffer with one write — nothing is left for a second writer', () => {
    const b = build()
    const { mutated } = writeAndFlush(b, (h) => h.writeBeam(0, 0, beamWrite()))
    expect(mutated.sort()).toEqual(allBuffers(b).map((x) => x.name).sort())
  })

  it('packs both landing planes, the first alone where there is no edge, and nothing in open air', () => {
    const b = build()
    const h = makeHandle(b)
    h.writeBeam(0, 0, beamWrite())
    h.writeBeam(0, 1, { ...beamWrite(), edgeLand: null })
    h.writeBeam(0, 2, { ...beamWrite(), land: null, edgeLand: null })
    const packed = Array.from(b.volumeLand.array.slice(0, 12))
    expect(packed.slice(0, 2)).toEqual([LAND_UP, 0.25])
    expect(packed[2]).toBeCloseTo(Math.PI / 2, 6)
    expect(packed[3]).toBe(-2.5)
    expect(packed.slice(4, 8)).toEqual([LAND_UP, 0.25, LAND_NONE, 1])
    expect(packed.slice(8, 12)).toEqual([LAND_NONE, -1, LAND_NONE, -1])
  })

  it("carries the depth of field in the haze's shape attribute, beside near, iris and aspect", () => {
    const b = build()
    makeHandle(b).writeBeam(0, 0, beamWrite())
    expect(Array.from(b.volumeShape.array.slice(0, 4))).toEqual([0.5, Math.fround(0.6), Math.fround(0.3), 3])
  })

  it("carries both gobo layers in the haze's fx attribute, packed exactly, between the edge and the focus", () => {
    const b = build()
    makeHandle(b).writeBeam(0, 0, beamWrite())
    expect(Array.from(b.volumeFx.array.slice(0, 4))).toEqual([Math.fround(0.7), packGobos(3, 9, 0, 1.2), 0, 6])
  })

  it('flags the matrices hideLobes parks', () => {
    // Light a slot first, so parking it is a real change rather than a write of the zeros
    // already there.
    const h = makeHandle(built)
    h.writeBeam(0, 0, beamWrite())
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
    handle.writeBeam(0, 0, beamWrite())
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
  // A par (one lobe, one light) beside a prism mover and a 12-cell bar.
  const layout = buildEmitterLayout([
    { lobes: 1, lights: 1 },
    { lobes: MAX_PRISM_LOBES, lights: MAX_PRISM_LOBES },
    { lobes: 12, lights: 4 },
  ])

  it('sizes the mesh and the light table by the rig, not by slots × the worst case', () => {
    const b = build(layout)
    expect(b.volumeMesh.count).toBe(13 + MAX_PRISM_LOBES)
    // A light a lobe for the par and the mover, four for the bar's twelve cells.
    expect(b.lights.capacity).toBe(5 + MAX_PRISM_LOBES)
  })

  it("keeps the haze program inside WebGL's sixteen guaranteed vertex attributes", () => {
    // three's ShaderMaterial prefix declares position, normal and uv whatever the shader reads, and
    // an instanced mesh's matrix takes four slots. Seventeen does not link on ANGLE ("Too many
    // attributes") and every beam in the air goes dark — session 7's review found exactly that.
    const b = build(layout)
    const geometryAttrs = Object.values(b.volumeMesh.geometry.attributes)
    const slots = geometryAttrs.reduce((n, a) => n + Math.ceil(a.itemSize / 4), 0) + 4
    expect(slots).toBeLessThanOrEqual(16)
    // And the haze shader itself declares no attribute the geometry does not carry.
    const declared = [...makeVolumeMaterial(getGoboTexture()).vertexShader.matchAll(/attribute\s+\w+\s+(\w+);/g)].map((m) => m[1])
    expect(declared.length).toBeGreaterThan(0)
    for (const name of declared) expect(Object.keys(b.volumeMesh.geometry.attributes)).toContain(name)
  })

  it("drops a write past a slot's block rather than landing in the next slot", () => {
    const b = build(layout)
    const { mutated } = writeAndFlush(b, (h) => {
      // Lobe 1 of the par would be lobe 0 of the mover.
      h.writeBeam(0, 1, beamWrite())
      h.writeLight(0, 1, lightRow())
      // The bar's fifth light would be the next slot's; a slot that isn't.
      h.writeLight(2, 4, lightRow())
      h.writeBeam(7, 0, beamWrite())
      h.writeLight(7, 0, lightRow())
    })
    expect(mutated).toEqual([])
    expect(b.dirty).toBe(0)
    expect(b.lights.litCount()).toBe(0)
  })

  it("reports each slot's block to the directors", () => {
    const h = makeHandle(build(layout))
    expect(h.lobesFor(0)).toBe(1)
    expect(h.lobesFor(1)).toBe(MAX_PRISM_LOBES)
    expect(h.lobesFor(2)).toBe(12)
    expect(h.lightsFor(2)).toBe(4)
    expect(h.lobesFor(3)).toBe(0)
  })
})

describe('the light table rows', () => {
  const layout = buildEmitterLayout([
    { lobes: 1, lights: 1 },
    { lobes: MAX_PRISM_LOBES, lights: MAX_PRISM_LOBES },
    { lobes: 8, lights: 4 },
  ])

  it("addresses each slot's lights in its own block, and takes a dark slot off the table", () => {
    const b = build(layout)
    const h = makeHandle(b)
    h.writeLight(1, 2, lightRow())
    h.writeLight(2, 3, lightRow(0.5))
    // Light 2 of slot 1 is row 1 + 2; light 3 of slot 2 is after the mover's six.
    expect(b.lights.weight[3]).toBeGreaterThan(0)
    expect(b.lights.weight[1 + MAX_PRISM_LOBES + 3]).toBeGreaterThan(0)
    expect(b.lights.litCount()).toBe(2)
    h.hideSlot(1)
    h.clearLight(2, 3)
    expect(b.lights.litCount()).toBe(0)
  })

  it('writes a surface hit as the plane the surfaces stop lighting behind', () => {
    const b = build(layout)
    makeHandle(b).writeLight(0, 0, lightRow(1, { px: 1.5, py: 0, pz: -2, nx: 0, ny: 1, nz: 0, skin: REACH_EPS_M }))
    const row = b.lights.staged.subarray(0, 24)
    expect(Array.from(row.subarray(12, 16))).toEqual([LAND_UP, 0, LAND_NONE, 1])
  })

  it('answers the axial reach from the colliders it is handed', () => {
    const b = build(layout)
    const floor = [{ cx: 0, cy: -0.01, cz: 0, hx: 10, hy: 0.01, hz: 10, cos: 1, sin: 0, skin: 0.1, capSkin: 0.4 }]
    const h = makeHandle(b, () => floor)
    const hit = { t: 0, nx: 0, ny: 0, nz: 0, skin: 0 }
    expect(h.reach(ORIGIN, DIR, 40, hit)).toBe(true)
    expect(hit.t).toBeCloseTo(4, 9)
    expect(hit.ny).toBe(1)
    expect(hit.skin).toBe(0.4)
    expect(makeHandle(b).reach(ORIGIN, DIR, 40, hit)).toBe(false)
  })
})

describe('region uniforms', () => {
  it('carry each region\'s own turn, as the region mesh draws it', () => {
    const regions = computeRegionGeometry([
      { uuid: 'r', centerX: 2, centerY: 3, centerZ: 0.5, widthM: 4, depthM: 1, heightM: 0.5, yawDeg: 30 },
    ] as Parameters<typeof computeRegionGeometry>[0])
    const mat = makeVolumeMaterial(getGoboTexture())
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

  it('clip the haze to a plane given in lighting metres, and nowhere without one', () => {
    const mat = makeVolumeMaterial(getGoboTexture())
    const clip = mat.uniforms.uHazeClip.value as { x: number; y: number; z: number; w: number }
    // The default is no plane: 0·p + 1 ≥ 0 everywhere.
    expect([clip.x, clip.y, clip.z, clip.w]).toEqual([0, 0, 0, 1])
    // Upstage of lighting Y = 0.3: three's z = −Y, so the haze is where −z − 0.3 ≥ 0.
    writeHazeClip([mat], { nx: 0, ny: 1, d: 0.3 })
    expect([clip.x, clip.y, clip.z, clip.w]).toEqual([0, 0, -1, -0.3])
    const at = (x: number, y: number, z: number) => clip.x * x + clip.y * y + clip.z * z + clip.w
    expect(at(0, 2, -1)).toBeGreaterThan(0) // 1 m upstage, in the haze
    expect(at(0, 2, 3)).toBeLessThan(0) // 3 m into the house, clear
    writeHazeClip([mat], null)
    expect([clip.x, clip.y, clip.z, clip.w]).toEqual([0, 0, 0, 1])
  })

  it('centre a region half its thickness below its deck', () => {
    // `centerZ` is the top surface: a 0.95 m deck at 0 is centred at -0.475, below the stage.
    const [r] = computeRegionGeometry([
      { uuid: 'r', centerX: 0, centerY: 0, centerZ: 0, widthM: 2, depthM: 2, heightM: 0.95, yawDeg: 0 },
    ] as Parameters<typeof computeRegionGeometry>[0])
    expect(r.obbCenter.y).toBeCloseTo(-0.475, 12)
  })
})
