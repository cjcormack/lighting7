import { createContext, useContext, useEffect, useLayoutEffect, useMemo, useRef } from 'react'
import { useFrame, useThree } from '@react-three/fiber'
import {
  BufferGeometry,
  Color,
  DoubleSide,
  InstancedBufferAttribute,
  InstancedMesh,
  Matrix4,
  MeshBasicMaterial,
  PlaneGeometry,
  ShaderMaterial,
  type Material,
} from 'three'
import { NO_RAYCAST } from '../raycast'
import { litByFill, makeSurfaceMaterial } from '../scene/surfaceShader'
import { useSurfaceLighting, useWorkLightLevels } from '../scene/SurfaceLighting'
import type { WorkLightLevels } from '../scene/workLights'
import { FINISH_LOBES } from '../scene/sceneParts'
import type { BodySpec } from './archetype'
import {
  acquireBodyGeometry,
  bodyGeometry,
  bodyGeometryKey,
  HANGER,
  LENS_DISC,
  LENS_SEGMENT,
  releaseBodyGeometry,
  releaseUnheldBodyGeometry,
  type BodyGeometry,
  type BodyLod,
} from './bodyGeometry'
import type { Mount } from './mount'
import { BODY_LENS_COLOR, HOUSING_ACTIVE_COLOR, HOUSING_ACTIVE_FILL, HOUSING_COLOR, HOUSING_FILL } from './palette'

/**
 * Every fixture body on one canvas, **instanced per archetype part** (stage-view plan session 6,
 * the design record's item 12): one `InstancedMesh` for each part (base, yoke, head) of each body
 * geometry at each level of detail, one for every disc lens and one for every segment lens in the
 * rig, one for the hangers and one for the far-off billboard glyphs. So draw calls scale with the
 * archetype parts the rig has, not with its fixtures, and a mover's yoke and head still move on
 * their own: each part is its own instance, written from the fixture's own matrices every frame.
 *
 * Sized by the rig ([BodyLayout], built in `Stage3D` beside the emitter layout, in the same slot
 * order) and rebuilt only when its signature — each slot's spec key and mount — changes, as the
 * emitters are. A fixture drags, pans and tilts without a rebuild.
 *
 * **The housings are lit like any surface**: the surface shader's receiver, tinted per instance, so
 * a beam that crosses a lantern lights it and the room's fill keeps the rig readable against the
 * dark. The selection tints the housing. **A lens is dark glass until its cell is lit** (`palette.ts`),
 * one instance per cell, painted from the cell's own colour and level by the colour sync.
 *
 * Nothing here takes the pointer: a fixture is pressed through the invisible hit proxy its
 * `FixtureModel` carries, so the gizmo, the hover and the click keep their old plumbing.
 */

export interface BodySlot {
  spec: BodySpec
  mount: Mount
}

export interface BodyLayout {
  slots: ReadonlyArray<BodySlot>
  /** Each slot's spec key and mount — what the instanced meshes are sized by. */
  signature: string
}

export function buildBodyLayout(slots: ReadonlyArray<BodySlot>): BodyLayout {
  return { slots, signature: slots.map((s) => `${s.spec.key}|${s.mount}`).join('\n') }
}

/** Which of its drawings a body is shown as this frame. */
export type BodyShown = BodyLod | 'billboard'

/**
 * Below this many pixels across, a body drops to its simple mesh; below the second, to a billboard
 * glyph — a dark dot with its lens colour, which is all a lantern is at that size.
 */
export const LOD_SIMPLE_BELOW_PX = 44
export const LOD_BILLBOARD_BELOW_PX = 9

/** A body's drawing for its size on screen. */
export function bodyShownFor(pixelsAcross: number): BodyShown {
  if (pixelsAcross < LOD_BILLBOARD_BELOW_PX) return 'billboard'
  if (pixelsAcross < LOD_SIMPLE_BELOW_PX) return 'simple'
  return 'full'
}

/** One frame of a body, as its `FixtureModel` writes it (a scratch object, reused per frame). */
export interface BodyPose {
  shown: BodyShown
  /** World matrices of the body's frames. `mount` is read only by a body with a base. */
  mount: Matrix4
  yoke: Matrix4
  head: Matrix4
  /** Each cell's lens, world, in cell order; entries past the spec's cell count are ignored. */
  lenses: Matrix4[]
  /** The hanger's world matrix, or null for none. */
  hanger: Matrix4 | null
  /** The billboard's centre (three.js) and size across, metres. */
  centreX: number
  centreY: number
  centreZ: number
  sizeM: number
}

export function makeBodyPose(): BodyPose {
  return {
    shown: 'full',
    mount: new Matrix4(),
    yoke: new Matrix4(),
    head: new Matrix4(),
    lenses: [],
    hanger: null,
    centreX: 0,
    centreY: 0,
    centreZ: 0,
    sizeM: 0,
  }
}

export interface BodiesHandle {
  /** Write one frame of a slot's body. Dropped for a slot the layout does not know. */
  writePose(slot: number, pose: BodyPose): void
  /** Paint a cell's lens (already the dark-glass-to-hue colour — `palette.lensColour`). */
  setLens(slot: number, cell: number, colour: Color): void
  /** Tint the slot's housing for the selection or a hover. */
  setActive(slot: number, active: boolean): void
  /** Park every part of a slot — its fixture unmounted. */
  hide(slot: number): void
}

const BodiesContext = createContext<BodiesHandle | null>(null)

export function useBodies(): BodiesHandle | null {
  return useContext(BodiesContext)
}

const ZERO = new Matrix4().makeScale(0, 0, 0)
const HOUSING = new Color(HOUSING_COLOR)
// The housing material's fill is HOUSING_FILL (the work lights' `housing` with them on); a selected
// housing's extra fill rides its tint, as a ratio, so it keeps its lead at either level.
const HOUSING_ACTIVE = new Color(HOUSING_ACTIVE_COLOR).multiplyScalar(HOUSING_ACTIVE_FILL / HOUSING_FILL)

const LENS_OFF = new Color(BODY_LENS_COLOR)

/**
 * The housings under [levels] (stage-view menu plan D6): the instanced housing material's own fill
 * and the billboard glyphs' two colours, which follow it through `litByFill`. With work lights on
 * the rig's fill rises with the room, or the matt black bodies vanish against a newly lit floor on
 * the Plan. A selected housing keeps its lead by riding the same tint. Uniform writes only: the
 * caller asks for the frame.
 */
export function applyHousingWorkLights(housing: ShaderMaterial, billboard: ShaderMaterial, levels: WorkLightLevels): void {
  housing.uniforms.uFill.value = levels.housing
  billboard.uniforms.uHousing.value = litByFill(HOUSING, levels.housing, 0.5, levels)
  billboard.uniforms.uHousingActive.value = litByFill(HOUSING_ACTIVE, levels.housing, 0.5, levels)
}

type PartName = 'base' | 'yoke' | 'head'
const PARTS: readonly PartName[] = ['base', 'yoke', 'head']
const LODS: readonly BodyLod[] = ['full', 'simple']

/** One slot's addresses in the built meshes. */
interface SlotAddress {
  /** Per level of detail and part: the mesh and this slot's index in it, or null for no such part. */
  parts: Record<BodyLod, Record<PartName, { mesh: InstancedMesh; index: number } | null>>
  lensMesh: InstancedMesh | null
  lensBase: number
  cells: number
}

export interface BuiltBodies {
  layout: BodyLayout
  /** The shared geometry this build draws, by key — held while it is mounted. */
  geometries: Array<{ key: string; geometry: BodyGeometry }>
  meshes: InstancedMesh[]
  addresses: SlotAddress[]
  hangers: InstancedMesh
  billboards: InstancedMesh
  billboardCentre: InstancedBufferAttribute
  billboardLens: InstancedBufferAttribute
  billboardActive: InstancedBufferAttribute
  discLenses: InstancedMesh
  segmentLenses: InstancedMesh
  /** Meshes written since the last flush. */
  dirty: Set<InstancedMesh>
  /** Billboard attributes written since the last flush. */
  billboardDirty: boolean
}

function instanced(geometry: BufferGeometry, material: Material, count: number): InstancedMesh {
  const mesh = new InstancedMesh(geometry, material, Math.max(1, count))
  mesh.count = count
  mesh.frustumCulled = false
  mesh.raycast = NO_RAYCAST
  for (let i = 0; i < Math.max(1, count); i++) mesh.setMatrixAt(i, ZERO)
  return mesh
}

const BILLBOARD_VERTEX = /* glsl */ `
  attribute vec4 aCentreSize;
  attribute vec3 aLens;
  attribute float aActive;
  varying vec2 vUv;
  varying vec3 vLens;
  varying float vActive;
  void main() {
    vUv = uv * 2.0 - 1.0;
    vLens = aLens;
    vActive = aActive;
    vec4 mv = viewMatrix * vec4(aCentreSize.xyz, 1.0);
    mv.xy += position.xy * aCentreSize.w;
    gl_Position = projectionMatrix * mv;
  }
`

const BILLBOARD_FRAGMENT = /* glsl */ `
  uniform vec3 uHousing;
  uniform vec3 uHousingActive;
  varying vec2 vUv;
  varying vec3 vLens;
  varying float vActive;
  void main() {
    float r = length(vUv);
    if (r > 1.0) discard;
    vec3 housing = mix(uHousing, uHousingActive, vActive);
    vec3 c = r < 0.58 ? vLens : housing;
    gl_FragColor = linearToOutputTexel(vec4(c, 1.0));
  }
`

export function buildBodies(
  layout: BodyLayout,
  housingMaterial: Material,
  lensMaterial: Material,
  billboardMaterial: ShaderMaterial,
): BuiltBodies {
  const meshes: InstancedMesh[] = []
  // One mesh per (geometry key, level, part), holding every slot drawn with it.
  const groups = new Map<string, { slots: number[] }>()
  layout.slots.forEach((s, i) => {
    const key = `${s.spec.key}|${s.mount}`
    let g = groups.get(key)
    if (!g) groups.set(key, (g = { slots: [] }))
    g.slots.push(i)
  })
  const addresses: SlotAddress[] = layout.slots.map((s) => ({
    parts: {
      full: { base: null, yoke: null, head: null },
      simple: { base: null, yoke: null, head: null },
    },
    lensMesh: null,
    lensBase: 0,
    cells: s.spec.cells.length,
  }))
  const geometries: Array<{ key: string; geometry: BodyGeometry }> = []
  for (const { slots } of groups.values()) {
    const first = layout.slots[slots[0]]
    for (const lod of LODS) {
      const geo = bodyGeometry(first.spec, first.mount, lod)
      geometries.push({ key: bodyGeometryKey(first.spec, first.mount, lod), geometry: geo })
      for (const part of PARTS) {
        const g = geo[part]
        if (!g) continue
        const mesh = instanced(g, housingMaterial, slots.length)
        for (let k = 0; k < slots.length; k++) mesh.setColorAt(k, HOUSING)
        meshes.push(mesh)
        slots.forEach((slot, k) => {
          addresses[slot].parts[lod][part] = { mesh, index: k }
        })
      }
    }
  }
  let discs = 0
  let segments = 0
  layout.slots.forEach((s, i) => {
    const cells = s.spec.cells
    if (cells.length === 0) return
    if (cells[0].shape === 'disc') {
      addresses[i].lensBase = discs
      discs += cells.length
    } else {
      addresses[i].lensBase = segments
      segments += cells.length
    }
  })
  const discLenses = instanced(LENS_DISC, lensMaterial, discs)
  const segmentLenses = instanced(LENS_SEGMENT, lensMaterial, segments)
  for (let i = 0; i < Math.max(1, discs); i++) discLenses.setColorAt(i, LENS_OFF)
  for (let i = 0; i < Math.max(1, segments); i++) segmentLenses.setColorAt(i, LENS_OFF)
  layout.slots.forEach((s, i) => {
    if (s.spec.cells.length > 0) addresses[i].lensMesh = s.spec.cells[0].shape === 'disc' ? discLenses : segmentLenses
  })
  const n = layout.slots.length
  const hangers = instanced(HANGER, housingMaterial, n)
  for (let i = 0; i < Math.max(1, n); i++) hangers.setColorAt(i, HOUSING)

  const quad = new PlaneGeometry(1, 1)
  const cap = Math.max(1, n)
  const billboardCentre = new InstancedBufferAttribute(new Float32Array(cap * 4), 4)
  const billboardLens = new InstancedBufferAttribute(new Float32Array(cap * 3), 3)
  const billboardActive = new InstancedBufferAttribute(new Float32Array(cap), 1)
  quad.setAttribute('aCentreSize', billboardCentre)
  quad.setAttribute('aLens', billboardLens)
  quad.setAttribute('aActive', billboardActive)
  const billboards = new InstancedMesh(quad, billboardMaterial, cap)
  billboards.count = n
  billboards.frustumCulled = false
  billboards.raycast = NO_RAYCAST
  for (let i = 0; i < cap; i++) billboardLens.setXYZ(i, LENS_OFF.r, LENS_OFF.g, LENS_OFF.b)

  return {
    layout,
    geometries,
    meshes: [...meshes, discLenses, segmentLenses, hangers],
    addresses,
    hangers,
    billboards,
    billboardCentre,
    billboardLens,
    billboardActive,
    discLenses,
    segmentLenses,
    dirty: new Set(),
    billboardDirty: false,
  }
}

/** Flag what the frame wrote for upload, once, after every body has written. */
export function flushBodies(b: BuiltBodies): void {
  for (const mesh of b.dirty) {
    mesh.instanceMatrix.needsUpdate = true
    if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true
  }
  b.dirty.clear()
  if (b.billboardDirty) {
    b.billboardCentre.needsUpdate = true
    b.billboardLens.needsUpdate = true
    b.billboardActive.needsUpdate = true
    b.billboardDirty = false
  }
}

export function makeBodiesHandle(b: BuiltBodies): BodiesHandle {
  const slotCount = b.layout.slots.length
  const known = (slot: number) => slot >= 0 && slot < slotCount

  // Every body writes its parts every frame it renders, so a write that stores what the buffer
  // already holds is dropped: a still rig flags nothing, and nothing is re-uploaded (the emitters'
  // dirty groups make the same point).
  function writeMatrix(mesh: InstancedMesh, index: number, m: Matrix4): void {
    const held = mesh.instanceMatrix.array
    const e = m.elements
    const o = index * 16
    let same = true
    for (let i = 0; i < 16; i++) {
      if (held[o + i] !== Math.fround(e[i])) {
        same = false
        break
      }
    }
    if (same) return
    mesh.setMatrixAt(index, m)
    b.dirty.add(mesh)
  }

  function writeBillboard(slot: number, x: number, y: number, z: number, size: number): void {
    const a = b.billboardCentre
    if (a.getX(slot) === Math.fround(x) && a.getY(slot) === Math.fround(y) && a.getZ(slot) === Math.fround(z) && a.getW(slot) === Math.fround(size)) return
    a.setXYZW(slot, x, y, z, size)
    b.billboardDirty = true
  }

  function parkParts(a: SlotAddress, keep: BodyLod | null): void {
    for (const lod of LODS) {
      if (lod === keep) continue
      for (const part of PARTS) {
        const p = a.parts[lod][part]
        if (p) writeMatrix(p.mesh, p.index, ZERO)
      }
    }
  }

  function hide(slot: number): void {
    if (!known(slot)) return
    const a = b.addresses[slot]
    parkParts(a, null)
    if (a.lensMesh) for (let c = 0; c < a.cells; c++) writeMatrix(a.lensMesh, a.lensBase + c, ZERO)
    writeMatrix(b.hangers, slot, ZERO)
    writeBillboard(slot, 0, 0, 0, 0)
  }

  return {
    writePose(slot, pose) {
      if (!known(slot)) return
      const a = b.addresses[slot]
      if (pose.shown === 'billboard') {
        parkParts(a, null)
        if (a.lensMesh) for (let c = 0; c < a.cells; c++) writeMatrix(a.lensMesh, a.lensBase + c, ZERO)
        writeMatrix(b.hangers, slot, ZERO)
        writeBillboard(slot, pose.centreX, pose.centreY, pose.centreZ, pose.sizeM)
        return
      }
      parkParts(a, pose.shown)
      const parts = a.parts[pose.shown]
      if (parts.base) writeMatrix(parts.base.mesh, parts.base.index, pose.mount)
      if (parts.yoke) writeMatrix(parts.yoke.mesh, parts.yoke.index, pose.yoke)
      if (parts.head) writeMatrix(parts.head.mesh, parts.head.index, pose.head)
      if (a.lensMesh) {
        for (let c = 0; c < a.cells; c++) {
          writeMatrix(a.lensMesh, a.lensBase + c, pose.lenses[c] ?? ZERO)
        }
      }
      writeMatrix(b.hangers, slot, pose.hanger ?? ZERO)
      writeBillboard(slot, 0, 0, 0, 0)
    },
    setLens(slot, cell, colour) {
      if (!known(slot)) return
      const a = b.addresses[slot]
      if (!a.lensMesh || cell < 0 || cell >= a.cells) return
      a.lensMesh.setColorAt(a.lensBase + cell, colour)
      b.dirty.add(a.lensMesh)
      // The glyph shows the first cell: a lantern at that size is one dot.
      if (cell === 0) {
        b.billboardLens.setXYZ(slot, colour.r, colour.g, colour.b)
        b.billboardDirty = true
      }
    },
    setActive(slot, active) {
      if (!known(slot)) return
      const a = b.addresses[slot]
      const tint = active ? HOUSING_ACTIVE : HOUSING
      for (const lod of LODS) {
        for (const part of PARTS) {
          const p = a.parts[lod][part]
          if (p) {
            p.mesh.setColorAt(p.index, tint)
            b.dirty.add(p.mesh)
          }
        }
      }
      b.hangers.setColorAt(slot, tint)
      b.dirty.add(b.hangers)
      b.billboardActive.setX(slot, active ? 1 : 0)
      b.billboardDirty = true
    },
    hide,
  }
}

/**
 * Just before the emitters' flush — after every director (0) and before the render
 * (`STAGE_RENDER_PRIORITY`) — so a body and its beam upload in one frame.
 */
export const BODIES_FLUSH_PRIORITY = 0.4

interface StageBodiesProps {
  layout: BodyLayout
  children: React.ReactNode
}

/** The canvas's bodies, and the handle every `FixtureModel` writes its own through. */
export function StageBodies({ layout, children }: StageBodiesProps) {
  const { uniforms } = useSurfaceLighting()
  const workLights = useWorkLightLevels()
  const invalidate = useThree((s) => s.invalidate)
  const housingMaterial = useMemo(
    () => makeSurfaceMaterial(uniforms, { colour: '#ffffff', pattern: 'PLAIN', emissive: false, lobes: FINISH_LOBES.LAMBERT }, { doubleSided: true, fill: HOUSING_FILL }),
    [uniforms],
  )
  const lensMaterial = useMemo(
    () => new MeshBasicMaterial({ color: '#ffffff', side: DoubleSide, toneMapped: false }),
    [],
  )
  const billboardMaterial = useMemo(
    () =>
      new ShaderMaterial({
        uniforms: {
          uHousing: { value: litByFill(HOUSING, HOUSING_FILL) },
          uHousingActive: { value: litByFill(HOUSING_ACTIVE, HOUSING_FILL) },
        },
        vertexShader: BILLBOARD_VERTEX,
        fragmentShader: BILLBOARD_FRAGMENT,
      }),
    [],
  )
  useEffect(
    () => () => {
      housingMaterial.dispose()
      lensMaterial.dispose()
      billboardMaterial.dispose()
    },
    [housingMaterial, lensMaterial, billboardMaterial],
  )
  // The work lights' housing fill: written onto the materials rather than rebuilding them, which
  // would rebuild the meshes and drop every slot's written state. A layout effect, so a canvas
  // mounted with work lights on draws its first frame with them.
  useLayoutEffect(() => {
    applyHousingWorkLights(housingMaterial, billboardMaterial, workLights)
    invalidate()
  }, [housingMaterial, billboardMaterial, workLights, invalidate])

  const layoutKey = layout.signature
  const built = useMemo(
    () => buildBodies(layout, housingMaterial, lensMaterial, billboardMaterial),
    // `layout` is read only through its signature's content, as the emitters' is: equal
    // signatures build identical meshes, and a rebuild would drop every slot's written state.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [layoutKey, housingMaterial, lensMaterial, billboardMaterial],
  )
  // The shared geometry this canvas holds. A new build takes its hold before the last one lets go —
  // React runs the old effect's cleanup before the new one's setup, and a key both share would
  // otherwise drop to no holders and be disposed under the meshes about to draw it.
  const heldRef = useRef<BuiltBodies['geometries']>([])
  useEffect(() => {
    for (const { key, geometry } of built.geometries) acquireBodyGeometry(key, geometry)
    for (const { key } of heldRef.current) releaseBodyGeometry(key)
    heldRef.current = built.geometries
    // And any a superseded build made and never committed.
    releaseUnheldBodyGeometry()
    invalidate()
    return () => {
      // The meshes' own instance buffers; the geometry is the shared cache's (above).
      for (const mesh of built.meshes) mesh.dispose()
      built.billboards.geometry.dispose()
      built.billboards.dispose()
    }
  }, [built, invalidate])
  useEffect(
    () => () => {
      for (const { key } of heldRef.current) releaseBodyGeometry(key)
      heldRef.current = []
    },
    [],
  )

  const handle = useMemo(() => makeBodiesHandle(built), [built])

  useFrame(() => flushBodies(built), BODIES_FLUSH_PRIORITY)

  return (
    <>
      {built.meshes.map((mesh) => (
        <primitive key={mesh.uuid} object={mesh} />
      ))}
      <primitive object={built.billboards} />
      <BodiesContext.Provider value={handle}>{children}</BodiesContext.Provider>
    </>
  )
}
