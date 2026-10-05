import { createContext, useContext, useEffect, useMemo, useRef } from 'react'
import { useFrame, useThree } from '@react-three/fiber'
import {
  type Color,
  ConeGeometry,
  InstancedBufferAttribute,
  InstancedMesh,
  MathUtils,
  Matrix4,
  ShaderMaterial,
  type Vector2,
  Vector3,
  type Vector4,
} from 'three'
import type { StageRegionDto } from '../../api/stageRegionApi'
import { toThree } from '../../lib/stageCoords'
import { NO_RAYCAST } from './raycast'
import { getGoboTexture } from './goboAtlas'
import { makeVolumeMaterial } from './beamShaders'
import { HAZE_LEVEL, VOLUMETRIC_STEPS } from './washConfig'
import {
  MAX_BEAM_REGIONS,
  beamCapacity,
  beamInstanceIndex,
  lightRowIndex,
  lightsFor,
  lobesFor,
  type EmitterLayout,
} from './emitterLayout'
import { LightTable, type LightRow } from './scene/lightTable'
import { beamReach, type BeamHit, type Collider } from './scene/beamReach'
import { packLanding } from './scene/landing'
import { useSurfaceLighting } from './scene/SurfaceLighting'
import type { HazeQuality } from './scene/hazeGovernor'

export { BEAM_LENGTH, MAX_BEAM_REGIONS, MAX_PRISM_LOBES } from './emitterLayout'

export interface RegionGeometry {
  uuid: string
  widthM: number
  depthM: number
  heightM: number
  yawRad: number
  // OBB centre, half a thickness below the deck — feeds the beam shaders' uRegion* uniforms, which
  // run the ray-OBB shadow tests on the volumes.
  obbCenter: Vector3
  obbHalfX: number
  obbHalfY: number
  obbHalfZ: number
  /** Whole-box centre + bounding-sphere radius — feeds the per-frame shadow-mask cull. */
  boundingCenter: Vector3
  boundingRadius: number
}

export function computeRegionGeometry(regions: StageRegionDto[]): RegionGeometry[] {
  return regions.map((r) => {
    const w = r.widthM ?? 1
    const d = r.depthM ?? 1
    const h = r.heightM ?? 1
    // `centerZ` is the top surface; the box hangs below it (see worldCornersFor).
    const cz = r.centerZ ?? 0
    const obbCenter = toThree(r.centerX ?? 0, r.centerY ?? 0, cz - h / 2)
    return {
      uuid: r.uuid,
      widthM: w,
      depthM: d,
      heightM: h,
      yawRad: MathUtils.degToRad(r.yawDeg ?? 0),
      obbCenter,
      obbHalfX: w / 2,
      obbHalfY: h / 2,
      obbHalfZ: d / 2,
      boundingCenter: obbCenter,
      boundingRadius: Math.hypot(w / 2, h / 2, d / 2),
    }
  })
}

/**
 * Where the beams stop in the air: the lowest floor, the furthest upstage wall and the outermost
 * side walls the view draws, in lighting metres. The beam volumes clip to them, so a wide beam's
 * flank does not poke through the floor under a hung head, nor a long throw's out of the building.
 * Without a modelled room they are the stage's own floor and back wall, and no sides.
 */
export interface BeamClip {
  floorZ: number
  wallY: number
  /** The outermost side walls, lighting X; ∓[NO_SIDE_X] without a room. */
  minX: number
  maxX: number
}

/**
 * Where the air shows a beam, as a vertical half-plane in lighting metres: haze where
 * `nx·X + ny·Y ≥ d`, `(nx, ny)` a unit normal pointing upstage. Null draws haze everywhere.
 */
export interface HazePlane {
  nx: number
  ny: number
  d: number
}

/**
 * One beam in the air, as a director writes it (a scratch object, reused per frame): the hull's
 * instance matrix — from the **apex**, its y scale the apex → aperture distance plus the drawn
 * length — and what the march reads.
 */
export interface BeamWrite {
  matrix: Matrix4
  apex: Vector3
  dir: Vector3
  /** The head's world X axis: the gobo's and the beam mask's cross-section frame. */
  right: Vector3
  color: Color
  opacity: number
  /** Cos of the half-field (along `right`, for a segment). */
  cosHalf: number
  /** Edge hardness 0..1 — 1 minus the softness. */
  edge: number
  /** The gobo layers, both patterns and the turned one's angle (`goboLayers.ts`'s `packGobos`); 0 = open. */
  gobos: number
  /** Metres from the aperture, or negative ("always sharp") with no focus channel. */
  focusDist: number
  /** Apex → aperture. */
  near: number
  /** Depth of field: the blur per unit of relative focus error (`beamMask.ts`'s `focusBlur`). */
  dof: number
  /** Open fraction of the field. */
  iris: number
  /** 0 a round aperture; > 0 a segment's depth over its width; < 0 an oval's narrow over wide. */
  aspect: number
  /** The four blades, `beamMask.ts`'s `packBlades` — (top, bottom) and (left, right); 0, 0 for none. */
  bladesA: number
  bladesB: number
  /** Bitmask of the regions this beam can reach, from the CPU cone-vs-sphere cull. */
  shadowMask: number
  /** Where the axis landed; null in open air. The march stops behind it and [edgeLand] both. */
  land: SurfaceHit | null
  /** The second face a beam split across an edge lands on (`scene/landing.ts`); null for none. */
  edgeLand: SurfaceHit | null
}

// Per-fixture emitter writes, called from FixtureModel's per-frame loop.
// A beam write targets a (slot, lobe) and a light write a (slot, light), allocated to the fixture
// by the layout: a single-cell body's lobe 0 is its beam and lobes 1+ its prism images, parked
// otherwise; a multi-cell body has a lobe per cell and a light per run of cells. Each writer
// records which buffer *group* it touched; the controller's own useFrame flips needsUpdate on the
// dirty groups once at the end of the frame, so the caller never handles a buffer flag itself.
//
// Every beam in the air is the raymarched volume since stage-view plan session 6 — the cone shell
// that drew an open beam went. Where a beam *lands* is not drawn here: each light is one row of the
// light table, and every surface lights itself from the table (`scene/surfaceShader.ts`).
export interface EmittersHandle {
  /** Fixture slots the layout knows. A write for any other slot is dropped. */
  slotCount: number
  regionCount: number
  /**
   * Beam lobes this slot was given — 0 (no beam), 1, `MAX_PRISM_LOBES` for a prism fixture, or
   * one per cell. A director draws no more lobes than this; a write past it is dropped rather
   * than landing in the next slot's block.
   */
  lobesFor(slot: number): number
  /** Light-table rows this slot was given. Writes past it are dropped. */
  lightsFor(slot: number): number

  /**
   * Where a beam from [origin] along the unit [dir] first meets a surface of the view, within
   * [maxT] — the **axial reach** (`scene/beamReach.ts`). Writes [out] and answers true on a hit.
   */
  reach(origin: Vector3, dir: Vector3, maxT: number, out: BeamHit): boolean

  /** Place and shape a (slot, lobe)'s beam in the air. */
  writeBeam(slot: number, lobe: number, beam: BeamWrite): void

  /** A (slot, light)'s row of the light table: where it lands on the view's surfaces. */
  writeLight(slot: number, light: number, row: LightRow): void
  /** Take one light off the table — dark this frame. */
  clearLight(slot: number, light: number): void

  /** Park every lobe ≥ fromLobe of a slot — the "prism swung out" transition. */
  hideLobes(slot: number, fromLobe: number): void
  /** Take every light ≥ fromLight of a slot off the table. */
  hideLights(slot: number, fromLight: number): void
  // Zero-scale all of a slot's beams and take its lights off the table.
  // Called when a fixture is functionally off.
  hideSlot(slot: number): void
}

/** Where a beam's axis landed, in three.js space: the point and the face's normal towards the light. */
export interface SurfaceHit {
  px: number
  py: number
  pz: number
  nx: number
  ny: number
  nz: number
}

// — dirty groups ————————————————————————————————————————————————————
//
// One bit per buffer group, set by the writer that touches the group and cleared by the
// controller's flush at the end of the frame. Flipping `needsUpdate` is indeed cheap; what
// isn't is what it schedules — three re-uploads the *whole* flagged attribute buffer, so a
// group nothing wrote costs a full bufferSubData for zero changed bytes.
//
// Groups follow the writers, not the meshes, so each `EmittersHandle` method sets exactly one
// bit — add a writer, give it a bit, and add its buffers to the table below. The light table is
// not a group: it is one texture, packed and flagged by the flush whenever a light moved.
const DIRTY_BEAM_MATRIX = 1 << 0
const DIRTY_BEAM_ATTRS = 1 << 1

/** Anything with a `needsUpdate` flag — an InstancedBufferAttribute or a mesh's instanceMatrix. */
interface Uploadable {
  needsUpdate: boolean
}

export interface DirtyGroup {
  bit: number
  buffers: Uploadable[]
}

/**
 * The bit → buffers table, built once per `BuiltEmitters` (the buffers are fixed for its
 * lifetime) so the per-frame flush allocates nothing.
 */
export function dirtyGroups(b: BuiltEmitters): DirtyGroup[] {
  return [
    { bit: DIRTY_BEAM_MATRIX, buffers: [b.volumeMesh.instanceMatrix] },
    {
      bit: DIRTY_BEAM_ATTRS,
      buffers: [
        b.volumeOrigin,
        b.volumeDir,
        b.volumeRight,
        b.volumeColor,
        b.volumeFx,
        b.volumeShape,
        b.volumeGate,
        b.volumeLand,
      ],
    },
  ]
}

/**
 * Upload the frame's writes: flip `needsUpdate` on the groups the writers touched, then clear.
 *
 * Separate from the `useFrame` that calls it so `StageEmitters.test.ts` can drive a full
 * write → flush cycle without a canvas. The invariant it pins is one-directional: every buffer a
 * writer *mutated* must end up flagged. Flagging a group whose bytes happen not to have changed
 * costs an upload, not a wrong picture.
 */
export function flushDirty(b: BuiltEmitters, groups: ReadonlyArray<DirtyGroup>): void {
  const dirty = b.dirty
  if (dirty === 0) return
  for (const group of groups) {
    if ((dirty & group.bit) === 0) continue
    for (const buffer of group.buffers) buffer.needsUpdate = true
  }
  b.dirty = 0
}

const EmittersContext = createContext<EmittersHandle | null>(null)

export function useEmitters(): EmittersHandle | null {
  return useContext(EmittersContext)
}

export interface StageDims {
  width: number
  height: number
  depth: number
}

interface StageEmittersProps {
  /** Per-slot instance blocks, from `emitterNeedsFor` over the drawn rig, in slot order. */
  layout: EmitterLayout
  regionGeometry: ReadonlyArray<RegionGeometry>
  /** Every surface a beam stops at (`scene/beamReach.ts`), rebuilt when the scene changes. */
  colliders: readonly Collider[]
  /** The floor and back wall the beams clip to. */
  clip: BeamClip
  /** How much of the light table the surfaces take (`scene/sceneView.ts`). */
  lightBudget: number
  /** Whether the air shows the beams — the View menu's Haze, unless it is Off. */
  haze: boolean
  /** How far the air shows them (`hazeClipFor`); null everywhere. */
  hazeClip: HazePlane | null
  /** The haze governor's tier: how much the volumes may march (`scene/hazeGovernor.ts`). */
  hazeQuality: HazeQuality
  /**
   * An element to stamp `data-lights="<packed>/<lit>"` on as the table is packed — how many lights
   * the surfaces took of how many were lit. Read by a test or a measurement, never by the UI.
   */
  statsRef?: React.RefObject<HTMLElement | null>
  children: React.ReactNode
}

/**
 * The frame at which the flush runs: after every director (priority 0) and before the render
 * (`STAGE_RENDER_PRIORITY`), whatever order the two mounted in. Tied with the render it would run
 * after it whenever this component remounted after it — a frame's lights a frame late.
 */
export const EMITTER_FLUSH_PRIORITY = 0.5

// Stage-level controller owning the InstancedMesh of raymarched beam volumes and the light table the
// surfaces read. Allocates per-instance attribute buffers sized by emitterLayout, then exposes an
// imperative write handle so each FixtureModel can populate its slot without taking on the mesh
// state itself.
export function StageEmitters({
  layout,
  regionGeometry,
  colliders,
  clip,
  lightBudget,
  haze,
  hazeClip,
  hazeQuality,
  statsRef,
  children,
}: StageEmittersProps) {
  const regionCount = Math.min(regionGeometry.length, MAX_BEAM_REGIONS)

  // Module-level shared texture — deterministic, expensive to bake, so a
  // remount of the Stage view must not rebuild it (and must not dispose it).
  const goboTexture = getGoboTexture()
  const volumeMaterial = useMemo(() => makeVolumeMaterial(goboTexture), [goboTexture])
  useEffect(() => () => volumeMaterial.dispose(), [volumeMaterial])

  const invalidate = useThree((s) => s.invalidate)

  // March depth trades against fill: a high pixel ratio multiplies the shaded area (2.25× at the
  // canvas's 1.5 cap), so drop a third of the samples there — and the haze governor takes more off
  // before the frame rate gives (`scene/hazeGovernor.ts`). Haze off shows no beam in the air at
  // all; the surfaces still light — and the mesh is not drawn, rather than drawn and marched to
  // nothing: the shader scales by `uHaze`, so at zero it costs its whole march for no pixel.
  const gl = useThree((s) => s.gl)
  useEffect(() => {
    const dpr = gl.getPixelRatio()
    const base = dpr > 1 ? Math.max(6, VOLUMETRIC_STEPS - 4) : VOLUMETRIC_STEPS
    volumeMaterial.uniforms.uVolSteps.value = Math.max(2, Math.round(base * hazeQuality.stepScale))
    volumeMaterial.uniforms.uHaze.value = haze ? HAZE_LEVEL : 0
    invalidate()
  }, [gl, volumeMaterial, haze, hazeQuality, invalidate])

  // Shared region OBB uniforms — synced whenever the region layout changes. They drive the shadow
  // tests, so a region drag is a uniform write and rebuilds no buffer. The floor and wall planes
  // ride along: they clip the beams in the air.
  const materials = useMemo(() => [volumeMaterial], [volumeMaterial])
  useEffect(() => {
    writeRegionUniforms(materials, regionGeometry, regionCount, clip.wallY)
    for (const mat of materials) {
      mat.uniforms.uFloorY.value = clip.floorZ
      ;(mat.uniforms.uSideX.value as Vector2).set(clip.minX, clip.maxX)
    }
    // A uniform write is not a prop change, so the `demand` frameloop has to be asked.
    invalidate()
  }, [materials, regionGeometry, regionCount, clip.wallY, clip.floorZ, clip.minX, clip.maxX, invalidate])

  useEffect(() => {
    writeHazeClip(materials, hazeClip)
    invalidate()
  }, [materials, hazeClip, invalidate])

  // Pre-allocate buffers + InstancedMesh objects sized by the layout. Rebuilds
  // when the rig's needs change — a patch edit. A region moved is a uniform write (above).
  // Keyed on the layout's signature, not its identity: the layout is rebuilt
  // with every render of the rig, and equal signatures address identically.
  const layoutKey = layout.signature
  const built = useMemo(
    () => buildEmitters(layout, regionCount, volumeMaterial),
    // `layout` is read only through its signature's content: two layouts with one signature
    // produce identical buffers, so rebuilding on a fresh-but-equal layout would throw away
    // every slot's written state for nothing.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [layoutKey, regionCount, volumeMaterial],
  )

  // Haze off draws no beam mesh (see the march-depth effect above): the shader scales by `uHaze`.
  useEffect(() => {
    built.volumeMesh.visible = haze
    invalidate()
  }, [built, haze, invalidate])

  useEffect(
    () => () => {
      built.volumeMesh.dispose()
      built.volumeMesh.geometry.dispose()
    },
    [built],
  )

  // The colliders are read per frame through a ref, so a scene change swaps them without
  // rebuilding the handle — which would throw away every slot's written state.
  const collidersRef = useRef(colliders)
  collidersRef.current = colliders
  const handle = useMemo<EmittersHandle>(() => makeHandle(built, () => collidersRef.current), [built])

  const groups = useMemo(() => dirtyGroups(built), [built])

  // The surfaces' light texture: packed from the table whenever a light moved or the budget did.
  const lighting = useSurfaceLighting()
  const budgetRef = useRef(lightBudget)
  useEffect(() => {
    if (budgetRef.current === lightBudget) return
    budgetRef.current = lightBudget
    built.lights.dirty = true
    invalidate()
  }, [lightBudget, built, invalidate])
  // The table leaves with the rig it was sized for: a surface must not keep last rig's lights.
  useEffect(
    () => () => {
      lighting.uniforms.uLightCount.value = 0
    },
    [built, lighting],
  )

  // Flush the frame's writes once, after all the FixtureModel useFrames have run — so 50
  // FixtureModels don't each flip the same flags, and a group none of them wrote is not
  // re-uploaded at all.
  // The counts last packed, stamped on whatever element `statsRef` names each frame it lacks them:
  // a pack happens only when a light moves, and the element can be mounted after the one pack a
  // rig that stays dark ever gets.
  const statsText = useRef('0/0')
  useFrame(() => {
    flushDirty(built, groups)
    if (built.lights.dirty) {
      const packed = built.lights.pack(budgetRef.current, lighting.data)
      lighting.uniforms.uLightCount.value = packed
      lighting.uniforms.uLights.value.needsUpdate = true
      statsText.current = `${packed}/${built.lights.litCount()}`
    }
    const el = statsRef?.current
    if (el != null && el.dataset.lights !== statsText.current) el.dataset.lights = statsText.current
  }, EMITTER_FLUSH_PRIORITY)

  return (
    <>
      <primitive object={built.volumeMesh} raycast={NO_RAYCAST} />
      <EmittersContext.Provider value={handle}>{children}</EmittersContext.Provider>
    </>
  )
}

export interface BuiltEmitters {
  layout: EmitterLayout
  regionCount: number

  /** Bitfield of the DIRTY_* groups written since the last flush. Mutable, and mutated from the
   *  per-frame write path — the one piece of state the handle owns rather than the meshes. */
  dirty: number

  volumeMesh: InstancedMesh

  volumeOrigin: InstancedBufferAttribute
  volumeDir: InstancedBufferAttribute
  volumeRight: InstancedBufferAttribute
  /** (r, g, b, opacity) per beam. */
  volumeColor: InstancedBufferAttribute
  volumeFx: InstancedBufferAttribute
  /** (apex → aperture, iris, aspect, unused) per beam. */
  volumeShape: InstancedBufferAttribute
  /** (cos of the half-field, shadow mask, the two packed blade words — `beamMask.ts`'s
   *  `packBlades`) per beam. */
  volumeGate: InstancedBufferAttribute
  /** Where the beam lands, two planes packed (`scene/landing.ts`'s `packLanding`). */
  volumeLand: InstancedBufferAttribute

  /** One row per light, in the layout's slot order. */
  lights: LightTable
}

export function buildEmitters(
  layout: EmitterLayout,
  regionCount: number,
  volumeMaterial: ShaderMaterial,
): BuiltEmitters {
  const beamCap = beamCapacity(layout)
  const lobeCount = layout.totalLobes

  // Closed + coarse: the volume hull is only a conservative fragment
  // generator (back faces), the analytic intersection in the shader is the
  // real boundary.
  const volumeGeo = new ConeGeometry(1, 1, 24, 1, false)

  const volumeOrigin = vec3InstAttr(beamCap)
  const volumeDir = vec3InstAttr(beamCap)
  const volumeRight = vec3InstAttr(beamCap)
  const volumeColor = vec4InstAttr(beamCap)
  const volumeFx = vec4InstAttr(beamCap)
  const volumeShape = vec4InstAttr(beamCap)
  const volumeGate = vec4InstAttr(beamCap)
  const volumeLand = vec4InstAttr(beamCap)
  // Eight attributes here plus three's position, normal and uv and the instance matrix's four make
  // fifteen of WebGL's guaranteed sixteen — `beamShaders.ts` says why they are packed: a program
  // with a seventeenth does not link (ANGLE: "Too many attributes"), and every beam goes dark.
  volumeGeo.setAttribute('aBeamOrigin', volumeOrigin)
  volumeGeo.setAttribute('aBeamDir', volumeDir)
  volumeGeo.setAttribute('aBeamRight', volumeRight)
  volumeGeo.setAttribute('aColor', volumeColor)
  volumeGeo.setAttribute('aBeamFx', volumeFx)
  volumeGeo.setAttribute('aBeamShape', volumeShape)
  volumeGeo.setAttribute('aBeamGate', volumeGate)
  volumeGeo.setAttribute('aBeamLand', volumeLand)

  const volumeMesh = new InstancedMesh(volumeGeo, volumeMaterial, beamCap)
  volumeMesh.frustumCulled = false
  volumeMesh.count = lobeCount

  // Start every beam instance parked — the directors only write lobes they
  // use, and an unwritten instance would otherwise draw at identity scale.
  for (let i = 0; i < beamCap; i++) volumeMesh.setMatrixAt(i, ZERO_MATRIX)
  volumeMesh.instanceMatrix.needsUpdate = true

  return {
    layout,
    regionCount,
    // The build-time writes above flag their own buffers directly; the frame loop starts clean.
    dirty: 0,
    volumeMesh,
    volumeOrigin,
    volumeDir,
    volumeRight,
    volumeColor,
    volumeFx,
    volumeShape,
    volumeGate,
    volumeLand,
    lights: new LightTable(layout.totalLights),
  }
}

function vec3InstAttr(count: number): InstancedBufferAttribute {
  return new InstancedBufferAttribute(new Float32Array(count * 3), 3)
}

function vec4InstAttr(count: number): InstancedBufferAttribute {
  return new InstancedBufferAttribute(new Float32Array(count * 4), 4)
}

const ZERO_MATRIX = new Matrix4().makeScale(0, 0, 0)

/**
 * Write the region layout into the beam materials' region uniforms, and the upstage wall the beams
 * clip to ([wallY], lighting metres upstage).
 *
 * The yaw pair is `(cos yaw, sin yaw)`, the region's own rotation: `rayObbT` rotates a world ray
 * *into* the box by −yaw — the same turn as `StageRegionMeshes`' `rotation={[0, yaw, 0]}`, undone.
 * The pair was `(cos −yaw, sin −yaw)` once, which had the shadow test turning a yawed region the
 * wrong way: harmless at 0° and 90°, where a box is its own mirror image, and shadowing the wrong
 * footprint at any other angle.
 */
export function writeRegionUniforms(
  materials: ReadonlyArray<ShaderMaterial>,
  regionGeometry: ReadonlyArray<RegionGeometry>,
  regionCount: number,
  wallY: number,
): void {
  for (const mat of materials) {
    const u = mat.uniforms
    const centers = u.uRegionCenter.value as Vector3[]
    const halves = u.uRegionHalf.value as Vector3[]
    const yawCs = u.uRegionYawCs.value as Array<{ set(x: number, y: number): void }>
    for (let i = 0; i < regionCount; i++) {
      const r = regionGeometry[i]
      centers[i].copy(r.obbCenter)
      halves[i].set(r.obbHalfX, r.obbHalfY, r.obbHalfZ)
      yawCs[i].set(Math.cos(r.yawRad), Math.sin(r.yawRad))
    }
    u.uNumRegions.value = regionCount
    u.uWallZ.value = -wallY
  }
}

/**
 * Write how far the haze reaches into the beam materials: [plane] in lighting metres, as three's
 * `dot(p, n) + w ≥ 0` — lighting (X, Y) is three (x, −z). Null clips nothing.
 */
export function writeHazeClip(materials: ReadonlyArray<ShaderMaterial>, plane: HazePlane | null): void {
  for (const mat of materials) {
    const v = mat.uniforms.uHazeClip.value as Vector4
    if (plane == null) v.set(0, 0, 0, 1)
    else v.set(plane.nx, 0, -plane.ny, -plane.d)
  }
}

const SCRATCH_LAND = [0, 0, 0, 0]

export function makeHandle(b: BuiltEmitters, colliders: () => readonly Collider[] = () => []): EmittersHandle {
  const layout = b.layout
  const lights = b.lights

  // Index of a (slot, lobe) on the beam mesh, or -1 for one this slot was not given — which a
  // writer then drops. Every writer goes through this, so a director that asks for more lobes
  // than its slot holds (a prism on a fixture whose layout predates it) parks nothing and
  // writes nothing, rather than drawing into the next fixture's block. The arithmetic itself is
  // emitterLayout's, the one tested copy.
  function beamIndex(slot: number, lobe: number): number {
    return lobe >= 0 && lobe < lobesFor(layout, slot) ? beamInstanceIndex(layout, slot, lobe) : -1
  }
  /** A (slot, light)'s row of the light table, or -1 for one this slot was not given. */
  function lightRow(slot: number, light: number): number {
    return light >= 0 && light < lightsFor(layout, slot) ? lightRowIndex(layout, slot, light) : -1
  }

  // Named closures rather than `this`-calls so the handle survives
  // destructuring (the tests stub methods individually).
  function hideLobes(slot: number, fromLobe: number): void {
    const count = lobesFor(layout, slot)
    if (fromLobe >= count) return
    b.dirty |= DIRTY_BEAM_MATRIX
    for (let lobe = Math.max(0, fromLobe); lobe < count; lobe++) {
      b.volumeMesh.setMatrixAt(beamInstanceIndex(layout, slot, lobe), ZERO_MATRIX)
    }
  }
  function hideLights(slot: number, fromLight: number): void {
    const count = lightsFor(layout, slot)
    for (let light = Math.max(0, fromLight); light < count; light++) {
      lights.clear(lightRowIndex(layout, slot, light))
    }
  }

  return {
    slotCount: layout.slotCount,
    regionCount: b.regionCount,
    lobesFor: (slot) => lobesFor(layout, slot),
    lightsFor: (slot) => lightsFor(layout, slot),

    reach(origin, dir, maxT, out) {
      return beamReach(origin.x, origin.y, origin.z, dir.x, dir.y, dir.z, colliders(), maxT, out)
    },

    writeBeam(slot, lobe, w) {
      const i = beamIndex(slot, lobe)
      if (i < 0) return
      b.dirty |= DIRTY_BEAM_MATRIX | DIRTY_BEAM_ATTRS
      b.volumeMesh.setMatrixAt(i, w.matrix)
      b.volumeOrigin.setXYZ(i, w.apex.x, w.apex.y, w.apex.z)
      b.volumeDir.setXYZ(i, w.dir.x, w.dir.y, w.dir.z)
      b.volumeRight.setXYZ(i, w.right.x, w.right.y, w.right.z)
      b.volumeColor.setXYZW(i, w.color.r, w.color.g, w.color.b, w.opacity)
      b.volumeFx.setXYZW(i, w.edge, w.gobos, 0, w.focusDist)
      b.volumeShape.setXYZW(i, w.near, w.iris, w.aspect, w.dof)
      b.volumeGate.setXYZW(i, w.cosHalf, w.shadowMask, w.bladesA, w.bladesB)
      packLanding(w.land, w.edgeLand, SCRATCH_LAND, 0)
      b.volumeLand.setXYZW(i, SCRATCH_LAND[0], SCRATCH_LAND[1], SCRATCH_LAND[2], SCRATCH_LAND[3])
    },

    writeLight(slot, light, row) {
      const i = lightRow(slot, light)
      if (i < 0) return
      lights.set(i, row)
    },
    clearLight(slot, light) {
      lights.clear(lightRow(slot, light))
    },

    hideLobes,
    hideLights,
    hideSlot(slot) {
      hideLobes(slot, 0)
      hideLights(slot, 0)
    },
  }
}
