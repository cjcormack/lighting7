import { createContext, useContext, useEffect, useMemo, useRef } from 'react'
import { useFrame, useThree } from '@react-three/fiber'
import {
  Color,
  ConeGeometry,
  InstancedBufferAttribute,
  InstancedMesh,
  MathUtils,
  Matrix4,
  ShaderMaterial,
  Vector3,
} from 'three'
import type { StageRegionDto } from '../../api/stageRegionApi'
import { toThree } from '../../lib/stageCoords'
import { NO_RAYCAST } from './raycast'
import { getGoboTexture } from './goboAtlas'
import { makeConeMaterial, makeVolumeMaterial } from './beamShaders'
import { HAZE_LEVEL, VOLUMETRIC_STEPS } from './washConfig'
import {
  MAX_BEAM_REGIONS,
  beamCapacity,
  beamInstanceIndex,
  lobesFor,
  washPixelIndex,
  washPixelsFor,
  type EmitterLayout,
} from './emitterLayout'
import { LightTable } from './scene/lightTable'
import { beamReach, type BeamHit, type Collider } from './scene/beamReach'
import { useSurfaceLighting } from './scene/SurfaceLighting'
import type { HazeQuality } from './scene/hazeGovernor'

export {
  BEAM_LENGTH,
  MAX_BEAM_REGIONS,
  MAX_PRISM_LOBES,
  MAX_WASH_PIXELS,
} from './emitterLayout'

export interface RegionGeometry {
  uuid: string
  widthM: number
  depthM: number
  heightM: number
  yawRad: number
  // OBB centre, half a thickness below the deck — feeds the beam shaders' uRegion* uniforms, which
  // run the ray-OBB shadow tests on the cones and the volumes.
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
 * Where the beams stop in the air: the lowest floor and the furthest upstage wall the view draws, in
 * lighting metres. The cone shell and the volume clip to them, so a wide cone's flank does not poke
 * through the floor under a hung head. Without a modelled room they are the stage's own floor and
 * back wall, as before.
 */
export interface BeamClip {
  floorZ: number
  wallY: number
}

// Per-fixture emitter writes, called from FixtureModel's per-frame loop.
// All writes target a (slot, lobe) allocated to the fixture by the controller:
// lobe 0 is the primary beam, lobes 1+ exist for prism images and stay parked
// otherwise. Each writer records which buffer *group* it touched; the
// controller's own useFrame flips needsUpdate on the dirty groups once at the
// end of the frame, so the caller never handles a buffer flag itself.
//
// Where a beam *lands* is no longer drawn here. The floor, wall and region cookie instances and the
// per-pixel wash pools are gone (stage-view plan session 3): each lobe and each washing pixel is one
// row of the light table, and every surface lights itself from the table (`scene/surfaceShader.ts`).
export interface EmittersHandle {
  /** Fixture slots the layout knows. A write for any other slot is dropped. */
  slotCount: number
  regionCount: number
  /**
   * Beam lobes this slot was given — 0 (no beam), 1, or `MAX_PRISM_LOBES` for a prism fixture.
   * A director draws no more lobes than this; a write past it is dropped rather than landing in
   * the next slot's block.
   */
  lobesFor(slot: number): number
  /** Wash pixels this slot was given — 0 unless it is a pixel strip. Writes past it are dropped. */
  washPixelsFor(slot: number): number

  /**
   * Where a beam from [origin] along the unit [dir] first meets a surface of the view, within
   * [maxT] — the **axial reach** (`scene/beamReach.ts`). Writes [out] and answers true on a hit.
   */
  reach(origin: Vector3, dir: Vector3, maxT: number, out: BeamHit): boolean

  /**
   * Place a (slot, lobe)'s mid-air beam. `volumetric` selects which mesh
   * draws it — the cheap silhouette shell (open gobo) or the raymarched
   * volume (gobo in the beam) — and parks the other, so switching is
   * stateless for the caller. The matrix's y scale is the beam's drawn length,
   * which the volume shader reads back as its march bound.
   */
  writeBeamMatrix(slot: number, lobe: number, matrix: Matrix4, volumetric: boolean): void
  writeConeAttrs(
    slot: number,
    lobe: number,
    origin: Vector3,
    dir: Vector3,
    color: Color,
    opacity: number,
    cosHalfAngle: number,
  ): void

  /**
   * Beam-shaping params for a (slot, lobe), applied to its cone and volume. `edge` 0..1 is focus
   * hardness. `goboSlot` 0 = open. `goboAngle` is in radians. `focusDist` is the focal-plane
   * distance in metres, or negative ("always sharp") when the fixture has no focus channel. `right`
   * is the head's world X axis, giving the gobo a stable cross-section frame.
   */
  writeBeamFx(
    slot: number,
    lobe: number,
    edge: number,
    goboSlot: number,
    goboAngle: number,
    focusDist: number,
    right: Vector3,
  ): void

  /**
   * Bitmask of region indices this (slot, lobe)'s beam can reach, from the CPU cone-vs-sphere cull.
   * The volume shader shadow-tests only masked regions.
   */
  writeShadowMask(slot: number, lobe: number, mask: number): void

  /**
   * A (slot, lobe)'s row of the light table: where it lands on the view's surfaces. [color] times
   * [level] is what it paints; [hit] is the axial reach (null for a beam that reaches nothing).
   */
  writeLight(
    slot: number,
    lobe: number,
    origin: Vector3,
    dir: Vector3,
    color: Color,
    level: number,
    cosHalfAngle: number,
    edge: number,
    focusDist: number,
    hit: SurfaceHit | null,
  ): void
  /** A washing pixel's row of the light table — a soft cone with no edge and no focus. */
  writeWashLight(
    slot: number,
    pixel: number,
    origin: Vector3,
    dir: Vector3,
    color: Color,
    level: number,
    cosHalfAngle: number,
    hit: SurfaceHit | null,
  ): void

  /** Park every lobe ≥ fromLobe of a slot — the "prism swung out" transition. */
  hideLobes(slot: number, fromLobe: number): void
  // Zero-scale all of a slot's matrices and take its lights off the table.
  // Called when a fixture is functionally off.
  hideSlot(slot: number): void
  /** Take one washing pixel off the table — dark this frame. */
  clearWashLight(slot: number, pixel: number): void
  /** Take all of a strip slot's washing pixels off the table. */
  hideWashSlot(slot: number): void
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
const DIRTY_CONE_ATTRS = 1 << 1
const DIRTY_BEAM_FX = 1 << 2
const DIRTY_SHADOW_MASK = 1 << 3

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
    {
      bit: DIRTY_BEAM_MATRIX,
      buffers: [b.coneMesh.instanceMatrix, b.volumeMesh.instanceMatrix],
    },
    {
      bit: DIRTY_CONE_ATTRS,
      buffers: [
        b.coneOrigin,
        b.coneColor,
        b.coneOpacity,
        b.volumeOrigin,
        b.volumeDir,
        b.volumeColor,
        b.volumeOpacity,
        b.volumeCosHalfAngle,
      ],
    },
    {
      bit: DIRTY_BEAM_FX,
      buffers: [b.coneFx, b.volumeFx, b.volumeRight],
    },
    {
      bit: DIRTY_SHADOW_MASK,
      buffers: [b.volumeMask],
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
  /** Whether the air shows the beams — the View menu's Haze. */
  haze: boolean
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
 * The frame at which the flush runs: after every director (priority 0) and before the composer
 * renders (priority 1), whatever order the two mounted in. At 1 it tied with the composer and ran
 * after it whenever this component remounted after `Bloom` — a frame's lights a frame late.
 */
export const EMITTER_FLUSH_PRIORITY = 0.5

// Stage-level controller owning the InstancedMesh objects (the cone shells and the raymarched
// volumes) and the light table the surfaces read. Allocates per-instance attribute buffers sized by
// emitterLayout, then exposes an imperative write handle so each FixtureModel can populate its slot
// without taking on the mesh state itself.
export function StageEmitters({
  layout,
  regionGeometry,
  colliders,
  clip,
  lightBudget,
  haze,
  hazeQuality,
  statsRef,
  children,
}: StageEmittersProps) {
  const regionCount = Math.min(regionGeometry.length, MAX_BEAM_REGIONS)

  const coneMaterial = useMemo(makeConeMaterial, [])
  // Module-level shared texture — deterministic, expensive to bake, so a
  // remount of the Stage view must not rebuild it (and must not dispose it).
  const goboTexture = getGoboTexture()
  const volumeMaterial = useMemo(() => makeVolumeMaterial(goboTexture), [goboTexture])
  useEffect(() => () => coneMaterial.dispose(), [coneMaterial])
  useEffect(() => () => volumeMaterial.dispose(), [volumeMaterial])

  const invalidate = useThree((s) => s.invalidate)

  // March depth trades against fill: a high pixel ratio multiplies the shaded area (2.25× at the
  // canvas's 1.5 cap), so drop a third of the samples there — and the haze governor takes more off
  // before the frame rate gives (`scene/hazeGovernor.ts`). With the volumes off, a gobo beam is
  // drawn as its shell. Haze off shows no beam in the air at all; the surfaces still light — and the
  // two meshes are not drawn, rather than drawn and marched to nothing: both shaders scale by
  // `uHaze`, so at zero they cost their whole march for no pixel.
  const gl = useThree((s) => s.gl)
  useEffect(() => {
    const dpr = gl.getPixelRatio()
    const base = dpr > 1 ? Math.max(6, VOLUMETRIC_STEPS - 4) : VOLUMETRIC_STEPS
    volumeMaterial.uniforms.uVolSteps.value = Math.max(2, Math.round(base * hazeQuality.stepScale))
    const level = haze ? HAZE_LEVEL : 0
    volumeMaterial.uniforms.uHaze.value = level
    coneMaterial.uniforms.uHaze.value = level
    invalidate()
  }, [gl, volumeMaterial, coneMaterial, haze, hazeQuality, invalidate])

  // Shared region OBB uniforms — sync into both materials whenever the region layout changes. They
  // drive the shadow tests, so a region drag is a uniform write and rebuilds no buffer. The floor
  // and wall planes ride along: they clip the beams in the air.
  const materials = useMemo(() => [coneMaterial, volumeMaterial], [coneMaterial, volumeMaterial])
  useEffect(() => {
    writeRegionUniforms(materials, regionGeometry, regionCount, clip.wallY)
    for (const mat of materials) mat.uniforms.uFloorY.value = clip.floorZ
    // A uniform write is not a prop change, so the `demand` frameloop has to be asked.
    invalidate()
  }, [materials, regionGeometry, regionCount, clip.wallY, clip.floorZ, invalidate])

  // Pre-allocate buffers + InstancedMesh objects sized by the layout. Rebuilds
  // when the rig's needs change — a patch edit. A region moved is a uniform write (above).
  // Keyed on the layout's signature, not its identity: the layout is rebuilt
  // with every render of the rig, and equal signatures address identically.
  const layoutKey = layout.signature
  const built = useMemo(
    () => buildEmitters(layout, regionCount, coneMaterial, volumeMaterial),
    // `layout` is read only through its signature's content: two layouts with one signature
    // produce identical buffers, so rebuilding on a fresh-but-equal layout would throw away
    // every slot's written state for nothing.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [layoutKey, regionCount, coneMaterial, volumeMaterial],
  )

  // Haze off draws neither mesh (see the march-depth effect above): both shaders scale by `uHaze`.
  useEffect(() => {
    built.coneMesh.visible = haze
    built.volumeMesh.visible = haze
    invalidate()
  }, [built, haze, invalidate])

  useEffect(
    () => () => {
      built.coneMesh.dispose()
      built.volumeMesh.dispose()
      built.coneMesh.geometry.dispose()
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
      <primitive object={built.coneMesh} raycast={NO_RAYCAST} />
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

  coneMesh: InstancedMesh
  volumeMesh: InstancedMesh

  coneOrigin: InstancedBufferAttribute
  coneFx: InstancedBufferAttribute
  coneColor: InstancedBufferAttribute
  coneOpacity: InstancedBufferAttribute

  volumeOrigin: InstancedBufferAttribute
  volumeDir: InstancedBufferAttribute
  volumeRight: InstancedBufferAttribute
  volumeColor: InstancedBufferAttribute
  volumeOpacity: InstancedBufferAttribute
  volumeCosHalfAngle: InstancedBufferAttribute
  volumeFx: InstancedBufferAttribute
  volumeMask: InstancedBufferAttribute

  /** One row per beam lobe, then one per washing pixel — the layout's own order. */
  lights: LightTable
}

export function buildEmitters(
  layout: EmitterLayout,
  regionCount: number,
  coneMaterial: ShaderMaterial,
  volumeMaterial: ShaderMaterial,
): BuiltEmitters {
  const beamCap = beamCapacity(layout)
  const lobeCount = layout.totalLobes

  const coneGeo = new ConeGeometry(1, 1, 48, 1, true)
  // Closed + coarse: the volume hull is only a conservative fragment
  // generator (back faces), the analytic intersection in the shader is the
  // real boundary.
  const volumeGeo = new ConeGeometry(1, 1, 24, 1, false)

  const coneOrigin = vec3InstAttr(beamCap)
  const coneColor = vec3InstAttr(beamCap)
  const coneOpacity = floatInstAttr(beamCap)
  const coneFx = vec4InstAttr(beamCap)
  coneGeo.setAttribute('aBeamOrigin', coneOrigin)
  coneGeo.setAttribute('aColor', coneColor)
  coneGeo.setAttribute('aOpacity', coneOpacity)
  coneGeo.setAttribute('aBeamFx', coneFx)

  const volumeOrigin = vec3InstAttr(beamCap)
  const volumeDir = vec3InstAttr(beamCap)
  const volumeRight = vec3InstAttr(beamCap)
  const volumeColor = vec3InstAttr(beamCap)
  const volumeOpacity = floatInstAttr(beamCap)
  const volumeCosHalfAngle = floatInstAttr(beamCap)
  const volumeFx = vec4InstAttr(beamCap)
  const volumeMask = floatInstAttr(beamCap)
  volumeGeo.setAttribute('aBeamOrigin', volumeOrigin)
  volumeGeo.setAttribute('aBeamDir', volumeDir)
  volumeGeo.setAttribute('aBeamRight', volumeRight)
  volumeGeo.setAttribute('aColor', volumeColor)
  volumeGeo.setAttribute('aOpacity', volumeOpacity)
  volumeGeo.setAttribute('aCosHalfAngle', volumeCosHalfAngle)
  volumeGeo.setAttribute('aBeamFx', volumeFx)
  volumeGeo.setAttribute('aShadowMask', volumeMask)

  const coneMesh = new InstancedMesh(coneGeo, coneMaterial, beamCap)
  coneMesh.frustumCulled = false
  coneMesh.count = lobeCount

  const volumeMesh = new InstancedMesh(volumeGeo, volumeMaterial, beamCap)
  volumeMesh.frustumCulled = false
  volumeMesh.count = lobeCount

  // Start every beam instance parked — the directors only write lobes they
  // use, and an unwritten instance would otherwise draw at identity scale.
  for (let i = 0; i < beamCap; i++) {
    coneMesh.setMatrixAt(i, ZERO_MATRIX)
    volumeMesh.setMatrixAt(i, ZERO_MATRIX)
  }
  coneMesh.instanceMatrix.needsUpdate = true
  volumeMesh.instanceMatrix.needsUpdate = true

  return {
    layout,
    regionCount,
    // The build-time writes above flag their own buffers directly; the frame loop starts clean.
    dirty: 0,
    coneMesh,
    volumeMesh,
    coneOrigin,
    coneFx,
    coneColor,
    coneOpacity,
    volumeOrigin,
    volumeDir,
    volumeRight,
    volumeColor,
    volumeOpacity,
    volumeCosHalfAngle,
    volumeFx,
    volumeMask,
    lights: new LightTable(layout.totalLobes + layout.totalWashPixels),
  }
}

function vec3InstAttr(count: number): InstancedBufferAttribute {
  return new InstancedBufferAttribute(new Float32Array(count * 3), 3)
}

function floatInstAttr(count: number): InstancedBufferAttribute {
  return new InstancedBufferAttribute(new Float32Array(count), 1)
}

// Beam-shaping params packed as one vec4 (edge, goboSlot, goboAngle, focusDist) rather than four
// scalars: the volume program is near the 16-attribute floor guaranteed by WebGL2, and one
// attribute means one needsUpdate flip.
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

export function makeHandle(b: BuiltEmitters, colliders: () => readonly Collider[] = () => []): EmittersHandle {
  const layout = b.layout
  const lights = b.lights

  // Index of a (slot, lobe) on the beam meshes, or -1 for one this slot was not given — which a
  // writer then drops. Every writer goes through this, so a director that asks for more lobes
  // than its slot holds (a prism on a fixture whose layout predates it) parks nothing and
  // writes nothing, rather than drawing into the next fixture's block. The arithmetic itself is
  // emitterLayout's, the one tested copy.
  function beamIndex(slot: number, lobe: number): number {
    return lobe >= 0 && lobe < lobesFor(layout, slot) ? beamInstanceIndex(layout, slot, lobe) : -1
  }
  /** A washing pixel's row of the light table: after every lobe's. */
  function washRow(slot: number, pixel: number): number {
    return pixel >= 0 && pixel < washPixelsFor(layout, slot)
      ? layout.totalLobes + washPixelIndex(layout, slot, pixel)
      : -1
  }

  // A named closure rather than a `this`-call so the handle survives
  // destructuring (the tests stub methods individually).
  function hideLobes(slot: number, fromLobe: number): void {
    const count = lobesFor(layout, slot)
    if (fromLobe >= count) return
    b.dirty |= DIRTY_BEAM_MATRIX
    for (let lobe = Math.max(0, fromLobe); lobe < count; lobe++) {
      const i = beamInstanceIndex(layout, slot, lobe)
      b.coneMesh.setMatrixAt(i, ZERO_MATRIX)
      b.volumeMesh.setMatrixAt(i, ZERO_MATRIX)
      lights.clear(i)
    }
  }

  function writeRow(
    row: number,
    origin: Vector3,
    dir: Vector3,
    color: Color,
    level: number,
    cosHalfAngle: number,
    edge: number,
    focusDist: number,
    hit: SurfaceHit | null,
  ): void {
    lights.set(
      row,
      origin.x,
      origin.y,
      origin.z,
      dir.x,
      dir.y,
      dir.z,
      cosHalfAngle,
      color.r * level,
      color.g * level,
      color.b * level,
      edge,
      focusDist,
      hit,
    )
  }

  return {
    slotCount: layout.slotCount,
    regionCount: b.regionCount,
    lobesFor: (slot) => lobesFor(layout, slot),
    washPixelsFor: (slot) => washPixelsFor(layout, slot),

    reach(origin, dir, maxT, out) {
      return beamReach(origin.x, origin.y, origin.z, dir.x, dir.y, dir.z, colliders(), maxT, out)
    },

    writeBeamMatrix(slot, lobe, matrix, volumetric) {
      const i = beamIndex(slot, lobe)
      if (i < 0) return
      b.dirty |= DIRTY_BEAM_MATRIX
      b.coneMesh.setMatrixAt(i, volumetric ? ZERO_MATRIX : matrix)
      b.volumeMesh.setMatrixAt(i, volumetric ? matrix : ZERO_MATRIX)
    },
    writeConeAttrs(slot, lobe, origin, dir, color, opacity, cosHalfAngle) {
      const i = beamIndex(slot, lobe)
      if (i < 0) return
      b.dirty |= DIRTY_CONE_ATTRS
      b.coneOrigin.setXYZ(i, origin.x, origin.y, origin.z)
      b.coneColor.setXYZ(i, color.r, color.g, color.b)
      b.coneOpacity.setX(i, opacity)
      b.volumeOrigin.setXYZ(i, origin.x, origin.y, origin.z)
      b.volumeDir.setXYZ(i, dir.x, dir.y, dir.z)
      b.volumeColor.setXYZ(i, color.r, color.g, color.b)
      b.volumeOpacity.setX(i, opacity)
      b.volumeCosHalfAngle.setX(i, cosHalfAngle)
    },

    writeBeamFx(slot, lobe, edge, goboSlot, goboAngle, focusDist, right) {
      const i = beamIndex(slot, lobe)
      if (i < 0) return
      b.dirty |= DIRTY_BEAM_FX
      // The cone shell reads only .x (edge) and .w (focus) — it has no interior to project into, so
      // the gobo payload matters on the volume, which shares this fx layout.
      b.coneFx.setXYZW(i, edge, goboSlot, goboAngle, focusDist)
      b.volumeFx.setXYZW(i, edge, goboSlot, goboAngle, focusDist)
      b.volumeRight.setXYZ(i, right.x, right.y, right.z)
    },

    writeShadowMask(slot, lobe, mask) {
      const i = beamIndex(slot, lobe)
      if (i < 0) return
      b.dirty |= DIRTY_SHADOW_MASK
      b.volumeMask.setX(i, mask)
    },

    writeLight(slot, lobe, origin, dir, color, level, cosHalfAngle, edge, focusDist, hit) {
      const i = beamIndex(slot, lobe)
      if (i < 0) return
      writeRow(i, origin, dir, color, level, cosHalfAngle, edge, focusDist, hit)
    },
    writeWashLight(slot, pixel, origin, dir, color, level, cosHalfAngle, hit) {
      const row = washRow(slot, pixel)
      if (row < 0) return
      writeRow(row, origin, dir, color, level, cosHalfAngle, 0, -1, hit)
    },

    hideLobes,
    hideSlot(slot) {
      hideLobes(slot, 0)
    },
    clearWashLight(slot, pixel) {
      lights.clear(washRow(slot, pixel))
    },
    hideWashSlot(slot) {
      const count = washPixelsFor(layout, slot)
      for (let p = 0; p < count; p++) lights.clear(washRow(slot, p))
    },
  }
}
