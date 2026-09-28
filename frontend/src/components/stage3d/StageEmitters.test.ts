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
  type BuiltEmitters,
  type EmittersHandle,
} from './StageEmitters'

const STAGE = { width: 10, height: 6, depth: 8 }
const FIXTURES = 2
const REGIONS = computeRegionGeometry([
  { uuid: 'r1', centerX: 1, centerY: 0, centerZ: 0, widthM: 2, depthM: 2, heightM: 1, yawDeg: 0 },
  { uuid: 'r2', centerX: -1, centerY: 0, centerZ: 0, widthM: 2, depthM: 2, heightM: 1, yawDeg: 0 },
] as Parameters<typeof computeRegionGeometry>[0])

function build(): BuiltEmitters {
  // Bare materials: buildEmitters only hands them to the meshes, and nothing here draws.
  const mat = () => new ShaderMaterial()
  return buildEmitters(FIXTURES, REGIONS.length, REGIONS, STAGE, mat(), mat(), mat(), mat())
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
  { name: 'writeFloorMatrix', write: (h) => h.writeFloorMatrix(1, 0, true, 2, -3, 5) },
  {
    name: 'writeFloorAttrs',
    write: (h) => h.writeFloorAttrs(1, 0, ORIGIN, DIR, COLOUR, 0.5, 0.97),
  },
  { name: 'writeRegionVisibility', write: (h) => h.writeRegionVisibility(1, 0, 1, true) },
  {
    name: 'writeRegionAttrs',
    write: (h) => h.writeRegionAttrs(1, 0, ORIGIN, DIR, COLOUR, 0.6, 0.97),
  },
  { name: 'writeWallMatrix', write: (h) => h.writeWallMatrix(1, 0, true, 1, 2, 3, 4) },
  { name: 'writeWallAttrs', write: (h) => h.writeWallAttrs(1, 0, ORIGIN, DIR, COLOUR, 0.7, 0.97) },
  { name: 'writeWashFloorMatrix', write: (h) => h.writeWashFloorMatrix(1, 2, true, 2, -3, 5) },
  {
    name: 'writeWashFloorAttrs',
    write: (h) => h.writeWashFloorAttrs(1, 2, ORIGIN, DIR, COLOUR, 0.8, 0.9),
  },
  { name: 'writeWashRegionVisibility', write: (h) => h.writeWashRegionVisibility(1, 2, 1, true) },
  {
    name: 'writeWashRegionAttrs',
    write: (h) => h.writeWashRegionAttrs(1, 2, ORIGIN, DIR, COLOUR, 0.9, 0.9),
  },
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
    h.writeFloorMatrix(0, 0, true, 2, -3, 5)
    h.writeWallMatrix(0, 0, true, 1, 2, 3, 4)
    h.writeRegionVisibility(0, 0, 0, true)
    flushDirty(built, dirtyGroups(built))

    const { mutated, flagged } = writeAndFlush(built, (handle) => handle.hideSlot(0))
    expect(mutated.length).toBeGreaterThan(0)
    expect(mutated.filter((name) => !flagged.includes(name))).toEqual([])
  })

  it('flags the wash block hideWashSlot parks', () => {
    const h = makeHandle(built)
    h.writeWashFloorMatrix(0, 0, true, 2, -3, 5)
    h.writeWashRegionVisibility(0, 0, 0, true)
    flushDirty(built, dirtyGroups(built))

    const { mutated, flagged } = writeAndFlush(built, (handle) => handle.hideWashSlot(0))
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
    handle.writeWashFloorAttrs(0, 0, ORIGIN, DIR, COLOUR, 0.5, 0.9)
    expect(built.dirty).not.toBe(0)
    flushDirty(built, dirtyGroups(built))
    expect(built.dirty).toBe(0)
  })

  it('covers every buffer of the build in exactly one group', () => {
    // A buffer in no group can never be uploaded after the first frame; a buffer in two is a
    // sign the groups have stopped following the writers. The two exceptions are the region
    // cookie placements, baked at build time from the region layout and never written again —
    // a region move rebuilds the whole emitter set.
    const BAKED_ONCE = ['regionMesh.instanceMatrix', 'washRegionMesh.instanceMatrix']
    const grouped = dirtyGroups(built).flatMap((g) => g.buffers)
    for (const { name, attr } of allBuffers(built)) {
      const hits = grouped.filter((buffer) => buffer === attr).length
      expect(`${name}:${hits}`).toBe(`${name}:${BAKED_ONCE.includes(name) ? 0 : 1}`)
    }
  })
})
