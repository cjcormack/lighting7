import { useEffect, useMemo, useRef, useState } from 'react'
import { useCursor } from '@react-three/drei'
import { useFrame, type RootState } from '@react-three/fiber'
import { StageLabel } from './StageLabel'
import {
  Color,
  Euler,
  Group,
  MathUtils,
  Matrix4,
  MeshBasicMaterial,
  Quaternion,
  Vector3,
} from 'three'
import type { FixturePatch } from '../../api/patchApi'
import type { RiggingDto } from '../../api/riggingApi'
import {
  findColourSource,
  findDimmerProperty,
  findStrobeProperties,
  type ChannelRef,
  type ColourPropertyDescriptor,
  type ElementDescriptor,
  type Fixture,
  type FixtureTypeInfo,
  type SettingPropertyDescriptor,
  type SliderPropertyDescriptor,
  findPanProperty,
  findTiltProperty,
  findPanFineProperty,
  findTiltFineProperty,
  findFocusProperty,
  findZoomProperty,
  findIrisProperty,
  findFrostProperty,
  findGoboProperties,
  findGoboRotationProperty,
  findGoboRotationModeProperty,
  goboRotationWheel,
  findFineProperty,
  findPrismProperty,
  findPrismRotationProperty,
  findLedMacroProperty,
  findMovementMacroProperty,
  findShutterProperties,
  type ShutterProperties,
} from '../../store/fixtures'
import {
  channelKey,
  getChannelValue,
  subscribeToChannels,
} from '../../hooks/usePropertyValues'
import { useChannelSource } from '../../hooks/useChannelSource'
import type { ChannelSource } from '../../api/channelSource'
import { colourFactor } from '../../hooks/useNormalizedIntensity'
import {
  computeNormalizedHue,
  computeNormalizedHueCss,
} from '../../lib/colourMath'
import { EMPTY_GELS, findGel, type GelIndex } from '../../lib/gels'
import { colourFilters, fittedProperties, filterColour } from '../../lib/fittedMedia'
import { isAnimatedAt, settingColourAt, sourceBandColour, sourceBandLevel } from '../../lib/colourBands'
import { strobeAnimates, strobeFactor } from '../../lib/strobeBands'
import { createColourTicker, STILL_COLOUR_TICKER, type ColourTicker } from './colourTicker'
import {
  colourTimed,
  findTimingChannels,
  isTravelling,
  makeTravelAxis,
  resetTravelAxis,
  sourceEpoch,
  stepTravel,
  timedSeconds,
  travelRates,
  NO_TIMING,
  type TimingChannels,
  type TravelAxis,
  type TravelRates,
} from '../../lib/travel'
import {
  beginTravelFrame,
  makeBeamTravel,
  travelBeam,
  travelPan,
  travelTilt,
  type BeamTravel,
  type TimingKeys,
} from './beamTravel'
import {
  DEFAULT_FIXTURE_COLOUR,
  PLACEHOLDER_FIXTURE_COLOUR,
  PLACEHOLDER_FIXTURE_INTENSITY,
} from '../fixtures/fixtureAppearance'
import { dmxToSignedDegrees, fromThree, worldPositionFor } from '../../lib/stageCoords'
import {
  combineFinePair,
  resolveGoboRotationMode,
  computeBeamGeom,
  evalLedMacro,
  evalMovementMacro,
  makeBeamGeom,
  makeBladeStates,
  makeGoboLayers,
  makeGoboRotation,
  resolveDeclaredFocusDistance,
  resolveDmxBlades,
  resolveEdgeHardness,
  resolveFocusDistance,
  resolveFocusParam,
  resolveGoboRotation,
  resolveGoboSlot,
  resolveIris,
  resolveMacroIndex,
  resolvePrismFacets,
  resolvePrismSpin,
  resolveZoomDeg,
  stepGoboLayers,
  type BeamGeom,
  type BladeState,
  type ByteDescriptor,
  type GoboRotation,
  type MacroColour,
  type MacroMovement,
} from './beamOptics'
import { computeLobeDirection, regionShadowMask } from './beamLobes'
import {
  BEAM_LENGTH,
  useEmitters,
  type BeamWrite,
  type EmittersHandle,
  type RegionGeometry,
  type SurfaceHit,
} from './StageEmitters'
import { MAX_THROW_M } from './emitterLayout'
import type { BeamHit } from './scene/beamReach'
import { makeLightRow, type LightRow } from './scene/lightTable'
import { useStageInvalidate } from './stageInvalidate'
import { forgetLanding, recordLanding } from './landedPoints'
import { bodySpecOf } from './emitterNeeds'
import { FOCUS_SPREAD_MAX, packBlades } from './beamMask'
import { MAX_GOBO_LAYERS } from './goboLayers'
import { EMPTY_LANTERNS, type LanternIndex } from '../../lib/lanterns'
import {
  apexDistanceM,
  lightRuns,
  MAX_LIGHTS_PER_FIXTURE,
  resolveBeamDeg,
  type BodySpec,
  type Cell,
} from './bodies/archetype'
import { bodyFrames } from './bodies/bodyGeometry'
import { hangerLengthM, mountFor, type Mount } from './bodies/mount'
import { lensColour } from './bodies/palette'
import { bodyShownFor, makeBodyPose, useBodies, type BodiesHandle } from './bodies/StageBodies'

// Above the fixture's own origin, in its unrotated placement group.
const FIXTURE_LABEL_OFFSET: [number, number, number] = [0, 0.18, 0]
const COLOR_TMP = new Color()
const LENS_TMP = new Color()
const SCRATCH_DIR = new Vector3()
const SCRATCH_APERTURE = new Vector3()
const SCRATCH_APEX = new Vector3()
const SCRATCH_RIGHT = new Vector3()
const SCRATCH_BX = new Vector3()
const SCRATCH_BY = new Vector3()
const SCRATCH_CENTRE = new Vector3()
const SCRATCH_ATTACH = new Vector3()
const SCRATCH_UP = new Vector3()
const SCRATCH_MACRO_COLOR = new Color()
const SCRATCH_RUN_COLOR = new Color()
const SCRATCH_MOVE_MACRO: MacroMovement = { panDeg: 0, tiltDeg: 0 }
const SCRATCH_LED_MACRO: MacroColour = { hueShift: 0, intensityScale: 1 }
const SCRATCH_HSL = { h: 0, s: 0, l: 0 }
// Saturation floor for a hue-cycling LED macro, so it still reads as a colour
// chase when the fixture's base colour is white.
const LED_MACRO_MIN_SATURATION = 0.8
const TAU = Math.PI * 2

// A prism shows N displaced copies of the *whole* beam image — gobo included —
// so each facet gets its own full lobe (volume + its light) from the
// slot's lobe block. Lobe centres sit this many beam half-angles off axis:
// just past 1 so they separate visibly while still overlapping, which is what
// a real 3-facet prism looks like.
const PRISM_SPLAY = 1.35
// A prism redistributes flux, it doesn't make any: each lobe carries 1/N of
// the beam, with a little back because the lobes overlap near the axis and
// additive blending under-reads the overlap region otherwise.
const PRISM_OVERLAP_GAIN = 1.1
const SCRATCH_PRISM_X = new Vector3()
const SCRATCH_PRISM_Y = new Vector3()
const SCRATCH_LOBE_DIR = new Vector3()

// Slack on the cone half-angle for the region shadow-mask cull, so a region
// joins a lobe's shadow tests before the shader's cosAngle test would need it
// — masks the boundary even on a wide spot at the edge of its reach.
const REGION_CULL_SLACK_RAD = MathUtils.degToRad(3)

// Beam strength in the air and on the surfaces, per unit of linear intensity: the prototype's
// colour × level in the haze, and colour × level × its typical lamp power on a surface.
const CONE_SCALE = 1
const POOL_SCALE = 40

// Below about one DMX step of intensity a beam is off — compared against pool values, so in their scale.
const LIGHT_OFF_OPACITY = 0.009 * POOL_SCALE

/**
 * The hull a beam's march is drawn inside is a closed cone scaled round the beam: this much wider
 * than the field so its coarse polygon never cuts into the analytic edge, and a segment's is the
 * ellipse through its rectangle's corners (√2 on each half-axis).
 */
const HULL_SLACK = 1.04
const RECT_HULL = Math.SQRT2 * HULL_SLACK

/** How far a lens face sits proud of its housing, so the two do not z-fight. */
const LENS_PROUD_M = 0.002

/** A hit proxy is never drawn: it is what the pointer presses, where the instanced parts are not. */
const HIT_PROXY_MATERIAL = new MeshBasicMaterial({ visible: false })

/**
 * The axial reach of a beam: cast, then turned into the light table's hit. Scratch objects, since
 * the directors call this per lobe per frame.
 */
const SCRATCH_BEAM_HIT: BeamHit = { t: 0, nx: 0, ny: 0, nz: 0, skin: 0 }
const SCRATCH_SURFACE_HIT: SurfaceHit = { px: 0, py: 0, pz: 0, nx: 0, ny: 0, nz: 0, skin: 0 }
const SCRATCH_RIM_HIT: BeamHit = { t: 0, nx: 0, ny: 0, nz: 0, skin: 0 }
const SCRATCH_EDGE_HIT: SurfaceHit = { px: 0, py: 0, pz: 0, nx: 0, ny: 0, nz: 0, skin: 0 }
const SCRATCH_RIM_DIR = new Vector3()
const SCRATCH_RIM_ORIGIN = new Vector3()

/**
 * The rim [edgeLanding] casts round: eight points on the field circle, or on a segment's rectangle
 * (its corners at 1, 1).
 */
const RIM_X = [1, Math.SQRT1_2, 0, -Math.SQRT1_2, -1, -Math.SQRT1_2, 0, Math.SQRT1_2]
const RIM_Y = [0, Math.SQRT1_2, 1, Math.SQRT1_2, 0, -Math.SQRT1_2, -1, -Math.SQRT1_2]
/** How far past a face a landing must sit to count as behind it: well under any set piece's size. */
const EDGE_EPS_M = 0.01
/** Halvings from a rim landing beyond back to the edge: within 1/32 of the field's radius. */
const EDGE_BISECTIONS = 5

/**
 * Where a beam from [origin] along [dir] lands: the surface hit (in the scratch object) and the
 * axial throw to it, however far within [MAX_THROW_M], so a follow spot on a balcony reaches the
 * stage; or the desk's stylised [BEAM_LENGTH] in open air. The cone is drawn past the hit to
 * [coneLandingDepth] and cut at the hit's plane, and at [edgeLanding]'s where it is split across an
 * edge. How much of it shows in the air is the window's
 * Haze setting: Stage clips it at the proscenium (`hazeClipFor`), Everywhere and the Positions plan
 * draw it whole. Cast from the **aperture**, not the apex behind it.
 */
export function landBeam(
  emitters: Pick<EmittersHandle, 'reach'>,
  origin: Vector3,
  dir: Vector3,
): { hit: SurfaceHit | null; length: number } {
  if (!emitters.reach(origin, dir, MAX_THROW_M, SCRATCH_BEAM_HIT)) return { hit: null, length: BEAM_LENGTH }
  const t = SCRATCH_BEAM_HIT.t
  SCRATCH_SURFACE_HIT.px = origin.x + dir.x * t
  SCRATCH_SURFACE_HIT.py = origin.y + dir.y * t
  SCRATCH_SURFACE_HIT.pz = origin.z + dir.z * t
  SCRATCH_SURFACE_HIT.nx = SCRATCH_BEAM_HIT.nx
  SCRATCH_SURFACE_HIT.ny = SCRATCH_BEAM_HIT.ny
  SCRATCH_SURFACE_HIT.nz = SCRATCH_BEAM_HIT.nz
  SCRATCH_SURFACE_HIT.skin = SCRATCH_BEAM_HIT.skin
  return { hit: SCRATCH_SURFACE_HIT, length: t }
}

/**
 * How far along [dir] from [apex] a cone of half-angle tangent [tanHalf] reaches before all of it
 * has crossed the plane of [hit] (its normal towards the light): the far rim of a grazing landing,
 * well past the axis's own hit. [cap] where the far rim never meets the plane.
 */
export function coneLandingDepth(apex: Vector3, dir: Vector3, tanHalf: number, hit: SurfaceHit, cap: number): number {
  const height = (apex.x - hit.px) * hit.nx + (apex.y - hit.py) * hit.ny + (apex.z - hit.pz) * hit.nz
  const nd = dir.x * hit.nx + dir.y * hit.ny + dir.z * hit.nz
  if (height <= 0 || nd >= 0) return cap
  // The steepest-away rim leans from the axis towards the plane's own direction across the beam.
  const across = Math.sqrt(Math.max(0, 1 - nd * nd))
  const closing = -nd - tanHalf * across
  return closing > 1e-6 ? Math.min(cap, height / closing) : cap
}

/** Where [castRim] landed: the point, the face's normal towards the light and its skin. */
const SCRATCH_RIM_LAND: SurfaceHit = { px: 0, py: 0, pz: 0, nx: 0, ny: 0, nz: 0, skin: 0 }

/**
 * Cast the ray [s] of the way from the axis to rim point [k], from the aperture, into
 * [SCRATCH_RIM_LAND]; false when it meets nothing.
 */
function castRim(
  emitters: Pick<EmittersHandle, 'reach'>,
  apex: Vector3,
  dir: Vector3,
  bx: Vector3,
  by: Vector3,
  tanX: number,
  tanY: number,
  rect: boolean,
  near: number,
  k: number,
  s: number,
): boolean {
  const corner = rect && RIM_X[k] !== 0 && RIM_Y[k] !== 0 ? Math.SQRT2 : 1
  SCRATCH_RIM_DIR.copy(dir)
    .addScaledVector(bx, tanX * RIM_X[k] * corner * s)
    .addScaledVector(by, tanY * RIM_Y[k] * corner * s)
  SCRATCH_RIM_ORIGIN.copy(apex).addScaledVector(SCRATCH_RIM_DIR, near)
  SCRATCH_RIM_DIR.normalize()
  if (!emitters.reach(SCRATCH_RIM_ORIGIN, SCRATCH_RIM_DIR, MAX_THROW_M, SCRATCH_RIM_HIT)) return false
  const t = SCRATCH_RIM_HIT.t
  SCRATCH_RIM_LAND.px = SCRATCH_RIM_ORIGIN.x + SCRATCH_RIM_DIR.x * t
  SCRATCH_RIM_LAND.py = SCRATCH_RIM_ORIGIN.y + SCRATCH_RIM_DIR.y * t
  SCRATCH_RIM_LAND.pz = SCRATCH_RIM_ORIGIN.z + SCRATCH_RIM_DIR.z * t
  SCRATCH_RIM_LAND.nx = SCRATCH_RIM_HIT.nx
  SCRATCH_RIM_LAND.ny = SCRATCH_RIM_HIT.ny
  SCRATCH_RIM_LAND.nz = SCRATCH_RIM_HIT.nz
  SCRATCH_RIM_LAND.skin = SCRATCH_RIM_HIT.skin
  return true
}

/** How far [p] sits in front of [face]'s plane; negative behind it. */
function heightAbove(face: SurfaceHit, p: SurfaceHit): number {
  return face.nx * (p.px - face.px) + face.ny * (p.py - face.py) + face.nz * (p.pz - face.pz)
}

/**
 * The second face a beam split across a convex edge lands on (`scene/landing.ts`), or null: a follow
 * spot aimed at the front of a stage has its axis on the riser, [first], and the half above carries
 * on to the deck. Casts the field's rim, from the aperture's — [tanX] along [bx], [tanY] along
 * [by], a segment's rectangle where [rect] — for landings behind [first], and keeps one whose face
 * [first] lies behind in turn: that pair bounds the solid between them. A rim that lands on
 * something beyond instead (the stalls floor past the lip of the stage) is followed back to the edge,
 * and the face just past it is tried. Of several, the landing nearest [first]'s plane wins, since
 * that is the face the edge belongs to rather than a rostrum further upstage. A flat in front of a
 * wall finds none, and still stops the whole beam: one more plane cannot draw its shadow.
 */
export function edgeLanding(
  emitters: Pick<EmittersHandle, 'reach'>,
  apex: Vector3,
  dir: Vector3,
  bx: Vector3,
  by: Vector3,
  tanX: number,
  tanY: number,
  rect: boolean,
  near: number,
  first: SurfaceHit,
): SurfaceHit | null {
  let nearest = Infinity
  let found = false
  for (let k = 0; k < RIM_X.length; k++) {
    if (!castRim(emitters, apex, dir, bx, by, tanX, tanY, rect, near, k, 1)) continue
    if (heightAbove(first, SCRATCH_RIM_LAND) >= -EDGE_EPS_M) continue
    if (heightAbove(SCRATCH_RIM_LAND, first) >= -EDGE_EPS_M) {
      let lo = 0
      let hi = 1
      for (let i = 0; i < EDGE_BISECTIONS; i++) {
        const mid = (lo + hi) / 2
        const escaped =
          castRim(emitters, apex, dir, bx, by, tanX, tanY, rect, near, k, mid) &&
          heightAbove(first, SCRATCH_RIM_LAND) < -EDGE_EPS_M
        if (escaped) hi = mid
        else lo = mid
      }
      if (!castRim(emitters, apex, dir, bx, by, tanX, tanY, rect, near, k, hi)) continue
      if (heightAbove(first, SCRATCH_RIM_LAND) >= -EDGE_EPS_M) continue
      if (heightAbove(SCRATCH_RIM_LAND, first) >= -EDGE_EPS_M) continue
    }
    const depth = -heightAbove(first, SCRATCH_RIM_LAND)
    if (depth >= nearest) continue
    nearest = depth
    found = true
    Object.assign(SCRATCH_EDGE_HIT, SCRATCH_RIM_LAND)
  }
  return found ? SCRATCH_EDGE_HIT : null
}

/**
 * The throw a focus channel racks over where its type declares no focus range, from [landBeam]'s
 * length: [BEAM_LENGTH], or the beam's own throw where it lands further away, so full focus is sharp
 * on what a long throw lands on.
 */
export function focusRangeM(landedLength: number): number {
  return Math.max(BEAM_LENGTH, landedLength)
}

/**
 * A beam's hull: the unit cone (apex at +y 0.5) laid from [apex] down [dir] for [length] (apex to
 * far end), its radius [radiusX] along the frame's `bx` and [radiusZ] along its `by`. Built from the
 * frame's own axes rather than a shortest-arc quaternion, so a segment's rectangle lines up with the
 * head; `bx × −dir = by` keeps it right-handed, so back faces stay back faces.
 */
export function composeBeamHull(
  apex: Vector3,
  dir: Vector3,
  bx: Vector3,
  by: Vector3,
  length: number,
  radiusX: number,
  radiusZ: number,
  out: Matrix4,
): Matrix4 {
  const cx = apex.x + (dir.x * length) / 2
  const cy = apex.y + (dir.y * length) / 2
  const cz = apex.z + (dir.z * length) / 2
  return out.set(
    bx.x * radiusX, -dir.x * length, by.x * radiusZ, cx,
    bx.y * radiusX, -dir.y * length, by.y * radiusZ, cy,
    bx.z * radiusX, -dir.z * length, by.z * radiusZ, cz,
    0, 0, 0, 1,
  )
}

/** A quarter-turn about X: a static body's barrel from hanging down to lying level. */
const STATIC_LEVEL = new Quaternion().setFromAxisAngle(new Vector3(1, 0, 0), -Math.PI / 2)

/**
 * A static lantern's head turn inside its yoke: `Rx(pitch) · Rz(roll)`, then the quarter-turn that
 * lays the barrel level — so pitch 0 throws **horizontally** towards the yaw's facing and +pitch
 * aims down, as `docs/fixtures-engineering.md` defines the columns and the MCP schema states them,
 * and as the rigs authored through it are entered. With the yoke's `Ry(yaw)` the whole turn is the
 * YXZ Euler of (pitch, yaw, roll) the body was always drawn with, then that quarter-turn, which is
 * about the body's own X: the long axis — `longAxisLighting` — is untouched, and roll still stands a
 * strip on end. Until session 6 the quarter-turn was missing and every static lantern was drawn 90°
 * of pitch off: pitch 0 straight down, and +pitch tilting it upstage at yaw 0. A mover's head is the
 * identity here: pan and tilt drive it, and its base orientation is its mount's.
 */
export function staticHeadQuaternion(pitchRad: number, rollRad: number, isStatic: boolean, out = new Quaternion()): Quaternion {
  if (!isStatic) return out.identity()
  return out.setFromEuler(new Euler(pitchRad, 0, rollRad, 'XYZ')).multiply(STATIC_LEVEL)
}

/** A lens face's matrix in the head's frame: the unit disc or 2 × 2 square, turned to face the beam. */
export function lensLocalMatrix(cell: Cell, emitAxis: 1 | -1, out: Matrix4): Matrix4 {
  // The unit face lies in XY facing +Z; Rx(−e·90°) turns +Z onto e·Y and keeps X along the head's X.
  const q = new Quaternion().setFromEuler(new Euler(-emitAxis * (Math.PI / 2), 0, 0))
  return out.compose(
    new Vector3(cell.x, cell.y + emitAxis * LENS_PROUD_M, cell.z),
    q,
    new Vector3(Math.max(1e-4, cell.halfWidthM), Math.max(1e-4, cell.halfDepthM), 1),
  )
}

interface FixtureModelProps {
  patch: FixturePatch
  fixture: Fixture | undefined
  fixtureType: FixtureTypeInfo | undefined
  /** The lantern library a generic dimmer's body is chosen from — `Stage3D`'s, passed rather than
   *  read, since a capture canvas bridges only the channel source into its tree. */
  lanterns?: LanternIndex
  /** The gel library a fitted gel and a `gelCode` are read from — passed for the same reason. */
  gels?: GelIndex
  riggings: RiggingDto[]
  regionGeometry: ReadonlyArray<RegionGeometry>
  slot: number
  selected: boolean
  editMode?: boolean
  onClick?: (group: Group) => void
  /** Called when this fixture becomes the edit-mode selection target, so the
   *  parent can bind TransformControls to its group. Lets picker-based and
   *  click-based selection share the same gizmo wiring. */
  onEditFocus?: (group: Group) => void
  /** Report where this fixture's beam lands (`landedPoints.ts`) — the selected fixture on an
   *  on-screen canvas, for the Focus tab's *Focus here*. */
  reportLanding?: boolean
  /** Whether this unit's gobos land on surfaces as well as in the air (fixture-optics plan session
   *  4): every gobo light's by default, the selected heads' only under the View menu's *Gobos on
   *  surfaces → Selected heads* (`scene/sceneView.ts`). Off, the pool is drawn without them. */
  goboOnSurfaces?: boolean
  /**
   * Whether this canvas draws **travel time** (fixture-optics plan D14): pan, tilt, the beam and the
   * colour eased toward the DMX value at the type's speed (`lib/travel.ts`). Every live canvas does;
   * a `render_view` capture does not — a one-shot render has no history, so it lands on the DMX.
   */
  travel?: boolean
}

/**
 * One fixture on the stage: its body (`bodies/`), its beams and its lights.
 *
 * The body is a node rig of empty groups — the placement, the **mount**, the **yoke** that pans and
 * the **head** that tilts — whose world matrices are copied every frame into the canvas's instanced
 * parts (`StageBodies`); what the pointer presses is an invisible hit proxy on it. A mover's mount is
 * its base orientation (`basePitchDeg` 180 hangs it) and its yoke and head take pan and tilt. A
 * static lantern keeps the same rig: its yoke turns by its yaw about the vertical and its head by
 * its pitch (and roll) inside it, `Ry(yaw) · Rx(pitch) · Rz(roll)` and a quarter-turn that lays the
 * barrel level (`staticHeadQuaternion`: pitch 0 is horizontal, +pitch aims down, as documented) —
 * so `longAxisLighting` is unchanged and `FixtureAim`, which aims movers, is untouched — and its yoke
 * hangs from its bar, or stands on a ledge (`bodies/mount.ts`).
 *
 * Every **cell** (`bodies/archetype.ts`) is its own lens and beam, leaving its aperture with the
 * apex behind it; a body with several lands at most four lights, averaging runs of cells.
 */
export function FixtureModel({
  patch,
  fixture,
  fixtureType,
  lanterns = EMPTY_LANTERNS,
  gels = EMPTY_GELS,
  riggings,
  regionGeometry,
  slot,
  selected,
  editMode,
  onClick,
  onEditFocus,
  reportLanding = false,
  goboOnSurfaces = true,
  travel = true,
}: FixtureModelProps) {
  const [hovered, setHovered] = useState(false)
  useCursor(!!onClick && hovered)
  // Selected, or hovered while editing: in view mode a hover only changes the cursor.
  const active = selected || (!!editMode && hovered)
  const emitters = useEmitters()
  const bodies = useBodies()

  const spec = useMemo(
    () =>
      bodySpecOf(
        {
          kindOverride: patch.kindOverride,
          lengthM: patch.lengthM,
          lanternType: patch.lanternType,
          zoomDeg: patch.zoomDeg,
          lampRotationDeg: patch.lampRotationDeg,
          shutters: patch.shutters,
          gateRotationDeg: patch.gateRotationDeg,
          iris: patch.iris,
          focusSoftness: patch.focusSoftness,
        },
        fixture,
        fixtureType,
        lanterns,
      ),
    [
      patch.kindOverride,
      patch.lengthM,
      patch.lanternType,
      patch.zoomDeg,
      patch.lampRotationDeg,
      patch.shutters,
      patch.gateRotationDeg,
      patch.iris,
      patch.focusSoftness,
      fixture,
      fixtureType,
      lanterns,
    ],
  )
  const rigging = useMemo(
    () => (patch.riggingUuid ? riggings.find((r) => r.uuid === patch.riggingUuid) ?? null : null),
    [patch.riggingUuid, riggings],
  )
  const mount = mountFor(rigging)
  const geometry = useMemo(() => bodyFrames(spec, mount), [spec, mount])
  const cellCount = spec.cells.length
  const multiCell = cellCount > 1

  // The type's descriptors as **this unit** holds them (fixture optics plan session 3): every
  // loadable setting's options overlaid with the unit's fitted media — `patch.media`, which for an
  // extra placement is already its own layered over the patch's (`patchAtPlacement`). Everything
  // below reads these, so a scroller's colour and a wheel's gobo are the unit's own.
  const unitProps = useMemo(
    () => fittedProperties(fixture?.properties, patch.media, gels),
    [fixture?.properties, patch.media, gels],
  )
  const colourSource = useMemo(
    () => (unitProps ? findColourSource(unitProps) : undefined),
    [unitProps],
  )
  const dimmerProp = useMemo(
    () => findDimmerProperty(unitProps),
    [unitProps],
  )
  // The strobe channels whose bands say what they do (fixture-optics plan D12): a level factor
  // beside the dimmer's, applied by the colour syncs below exactly as the 2D dispatch applies it.
  const strobeProps = useMemo(() => findStrobeProperties(unitProps), [unitProps])
  // Beam-shaping channels. All undefined against a backend that predates the categories,
  // which is what makes the optics below degrade to the old look.
  const focusProp = useMemo(() => findFocusProperty(unitProps), [unitProps])
  const zoomProp = useMemo(() => findZoomProperty(unitProps), [unitProps])
  const irisProp = useMemo(() => findIrisProperty(unitProps), [unitProps])
  const frostProp = useMemo(() => findFrostProperty(unitProps), [unitProps])
  const goboProps = useMemo(() => findGoboProperties(unitProps), [unitProps])
  const goboRotProp = useMemo(
    () => findGoboRotationProperty(unitProps),
    [unitProps],
  )
  // A 16-bit index/rotation's low byte (`fineOf`), and the wheel's function channel, which says
  // whether the rotation is an angle or a speed. Both undefined on every type but the Revolution.
  const goboRotFineProp = useMemo(
    () => findFineProperty(unitProps, goboRotProp),
    [unitProps, goboRotProp],
  )
  const goboRotModeProp = useMemo(
    () => findGoboRotationModeProperty(unitProps),
    [unitProps],
  )
  // Which wheel the rotation channel turns: the one it follows (`goboRotationWheel`). The other —
  // the Robe's static wheel — holds still.
  const goboTurned = useMemo(
    () => goboRotationWheel(goboProps.slice(0, MAX_GOBO_LAYERS), goboRotProp),
    [goboProps, goboRotProp],
  )
  const prismProp = useMemo(() => findPrismProperty(unitProps), [unitProps])
  const prismRotProp = useMemo(
    () => findPrismRotationProperty(unitProps),
    [unitProps],
  )
  const ledMacroProp = useMemo(
    () => findLedMacroProperty(unitProps),
    [unitProps],
  )
  const moveMacroProp = useMemo(
    () => findMovementMacroProperty(unitProps),
    [unitProps],
  )

  const shutterProps = useMemo(() => findShutterProperties(unitProps), [unitProps])
  const panProp = useMemo(() => findPanProperty(unitProps), [unitProps])
  const tiltProp = useMemo(() => findTiltProperty(unitProps), [unitProps])
  const panFineProp = useMemo(() => findPanFineProperty(unitProps), [unitProps])
  const tiltFineProp = useMemo(() => findTiltFineProperty(unitProps), [unitProps])
  const gel =
    !colourSource && fixtureType?.acceptsGel && patch.gelCode ? findGel(gels, patch.gelCode) : null
  // A unit's other colour wheels (the Robe's second) and its other gel-taking loadable settings — a
  // media frame's wing, a module wheel's dichroic — filter the beam's colour while their current
  // slot holds one (`colourFilters`).
  const filterProps = useMemo(
    () => colourFilters(unitProps, colourSource),
    [unitProps, colourSource],
  )

  // Travel time (fixture-optics plan D14): the type's speeds and the unit's own timing channels.
  // Null when nothing travels — a type that declares no speed and has no timing channel, or a canvas
  // that does not ease (a capture) — so the director and the colour syncs read the DMX straight.
  const travelRatesForUnit = useMemo(() => travelRates(fixtureType?.travel), [fixtureType?.travel])
  const timingChannels = useMemo(() => findTimingChannels(unitProps), [unitProps])
  const eases = travel && (fixtureType?.travel != null || timingChannels !== NO_TIMING)
  const colourTravel = useMemo<ColourTravel | undefined>(
    () => (eases ? { rate: travelRatesForUnit.colour, timing: timingChannels.colour } : undefined),
    [eases, travelRatesForUnit, timingChannels],
  )

  const fixturePos = useMemo(() => {
    const v = worldPositionFor(patch, riggings)
    return [v.x, v.y, v.z] as const
    // worldPositionFor reads only these four patch fields, so listing them is
    // complete — and cheaper than recomputing on every new patch object in a
    // per-frame render path.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    patch.stageX,
    patch.stageY,
    patch.stageZ,
    patch.riggingUuid,
    riggings,
  ])
  // Where the hanger meets the bar: the rigging's own line at this fixture's offset along it.
  const barY = useMemo(() => {
    if (!rigging) return null
    return worldPositionFor({ ...patch, stageZ: 0 }, riggings).y
    // As fixturePos: worldPositionFor reads only the placement fields listed.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rigging, patch.stageX, patch.stageY, patch.riggingUuid, riggings])

  // Fallback beam angle: the patch's own, else the body's — a lantern's field at its zoom, the
  // type's fixed lens, else the family's. A zoom channel overrides it per frame inside the director;
  // `resolveBeamDeg` (`bodies/archetype.ts`) is the whole precedence, in one place.
  const baseBeamDeg = resolveBeamDeg(null, patch.beamAngleDeg, spec)
  const showCone = spec.emits && cellCount > 0 && !!emitters

  const groupRef = useRef<Group>(null)
  const mountRef = useRef<Group>(null)
  const yokeRef = useRef<Group>(null)
  const headRef = useRef<Group>(null)

  const isMover = spec.archetype === 'mover'
  const yawRad = MathUtils.degToRad(patch.baseYawDeg ?? 0)
  const pitchRad = MathUtils.degToRad(patch.basePitchDeg ?? 0)
  const rollRad = MathUtils.degToRad(patch.baseRollDeg ?? 0)
  // A mover's base orientation turns its whole body (YXZ, rigEuler's convention), and its yoke and
  // head take pan and tilt. A static lantern's mount stays upright and its yoke and head carry the
  // same turn split in two — yaw on the yoke, pitch then roll on the head — so the yoke hangs (or
  // stands) plumb whatever the lantern is focused at.
  const mountRotation = useMemo(
    () => (isMover ? new Euler(pitchRad, yawRad, rollRad, 'YXZ') : new Euler()),
    [isMover, pitchRad, yawRad, rollRad],
  )
  const yokeRotation = useMemo(() => new Euler(0, isMover ? 0 : yawRad, 0), [isMover, yawRad])
  const headQuaternion = useMemo(() => staticHeadQuaternion(pitchRad, rollRad, !isMover), [isMover, pitchRad, rollRad])
  const mountLift = geometry.standLiftM

  useEffect(() => {
    if (selected && editMode && onEditFocus && groupRef.current) {
      onEditFocus(groupRef.current)
    }
  }, [selected, editMode, onEditFocus])

  // Shared per-fixture colour state. The colour syncs write here (React-rate);
  // the beam director reads here (per-frame) and pushes to the emitter slot. Cell 0 of a
  // single-cell body is the fixture's colour; a body of several has one entry per cell.
  const colorStateRef = useRef<ColorState>({
    color: new Color(DEFAULT_FIXTURE_COLOUR),
    coneOpacity: 0,
    poolOpacity: 0,
  })
  const cellStateRef = useRef<CellState | null>(null)
  if (multiCell) {
    if (cellStateRef.current?.count !== cellCount) cellStateRef.current = makeCellState(cellCount)
  } else if (cellStateRef.current) {
    cellStateRef.current = null
  }

  // The canvas renders on demand, and an effect writing straight into the
  // emitter buffers changes no prop — so each of these asks for the frame
  // that uploads the write.
  const invalidate = useStageInvalidate()

  // Paint a cell's lens on the instanced parts: dark glass at 0, the hue at full (`lensColour`).
  const lensRef = useRef<LensPainter | null>(null)
  lensRef.current = bodies
    ? (cell, hue, level) => bodies.setLens(slot, cell, lensColour(LENS_TMP, hue, level))
    : null

  // Slot zeroing — emitter slots persist across renders. If a fixture loses
  // its beam (or showCone otherwise turns off), the per-frame writes stop;
  // hide the slot once so its last frame doesn't ghost on screen.
  useEffect(() => {
    if (!emitters || showCone) return
    emitters.hideSlot(slot)
    invalidate()
  }, [emitters, showCone, slot, invalidate])

  // Unmount cleanup — same reason. A slot belongs to whichever FixtureModel
  // owns it; vacate before re-allocation can give it to a different fixture.
  useEffect(() => {
    return () => {
      if (emitters) {
        emitters.hideSlot(slot)
        invalidate()
      }
    }
  }, [emitters, slot, invalidate])
  useEffect(() => {
    return () => {
      if (bodies) {
        bodies.hide(slot)
        invalidate()
      }
    }
  }, [bodies, slot, invalidate])

  // The selection tints the housing.
  useEffect(() => {
    if (!bodies) return
    bodies.setActive(slot, active)
    invalidate()
  }, [bodies, slot, active, invalidate])

  // An animated colour band (a scroll or random wheel band) or a flashing strobe moves with time
  // rather than DMX, so ColourSync registers with this while one is live; it re-applies the colour and
  // level and asks for the next frame — before the beam director runs, so the director reads this
  // frame's colour and level.
  const colourTicker = useMemo(() => createColourTicker(invalidate), [invalidate])
  useFrame((state) => colourTicker.frame(state.clock.elapsedTime))

  useBeamDirector({
    spec,
    reportKey: reportLanding ? patch.key : null,
    panProp,
    tiltProp,
    panFineProp,
    tiltFineProp,
    baseBeamDeg,
    focusProp,
    zoomProp,
    irisProp,
    frostProp,
    goboProp: goboProps[0],
    goboProp2: goboProps[1],
    goboTurned,
    goboOnSurfaces,
    goboRotProp,
    goboRotFineProp,
    goboRotModeProp,
    prismProp,
    prismRotProp,
    ledMacroProp,
    moveMacroProp,
    shutterProps,
    travelRates: eases ? travelRatesForUnit : null,
    timingChannels,
    groupRef,
    yokeRef,
    headRef,
    slot,
    emitters: showCone ? emitters : null,
    regionGeometry,
    colorStateRef,
    cellStateRef,
  })

  useBodyDirector({ spec, mount, barY, groupRef, mountRef, yokeRef, headRef, slot, bodies })

  const hit = geometry.hitBox
  const hitProxy = (
    <mesh position={hit.centre} material={HIT_PROXY_MATERIAL}>
      <boxGeometry args={hit.size} />
    </mesh>
  )

  return (
    <group
      ref={groupRef}
      position={fixturePos}
      onClick={onClick ? (e) => { e.stopPropagation(); onClick(e.eventObject as Group) } : undefined}
      onPointerOver={onClick ? (e) => { e.stopPropagation(); setHovered(true) } : undefined}
      onPointerOut={onClick ? () => setHovered(false) : undefined}
    >
      <group ref={mountRef} rotation={mountRotation} position={[0, mountLift, 0]}>
        <group ref={yokeRef} rotation={yokeRotation}>
          <group ref={headRef} position={[0, geometry.pivotY, 0]} quaternion={headQuaternion}>
            {hit.frame === 'head' && hitProxy}
          </group>
        </group>
        {hit.frame === 'mount' && hitProxy}
      </group>

      <StageLabel position={FIXTURE_LABEL_OFFSET} kind="fixture" emphasised={active}>
        {patch.displayName}
      </StageLabel>

      {multiCell ? (
        <CellColourSync
          elements={fixture?.elements}
          cells={spec.cells}
          dimmerProp={dimmerProp}
          strobes={strobeProps}
          fallbackHex={gel?.color ?? DEFAULT_FIXTURE_COLOUR}
          lensRef={lensRef}
          cellStateRef={cellStateRef}
          ticker={colourTicker}
        />
      ) : (
        <ColourSync
          hasFixture={!!fixture}
          colourSource={colourSource}
          gel={gel}
          filters={filterProps}
          dimmerProp={dimmerProp}
          strobes={strobeProps}
          lensRef={lensRef}
          colorStateRef={colorStateRef}
          ticker={colourTicker}
          travel={colourTravel}
        />
      )}
    </group>
  )
}

/** Paints a cell's lens: its full-brightness hue and its linear 0..1 level. */
export type LensPainter = (cell: number, hue: Color, level: number) => void

interface ColorState {
  color: Color
  coneOpacity: number
  poolOpacity: number
}

/** Each cell's colour (linear, full brightness) and its air and surface opacities. */
interface CellState {
  count: number
  colors: Float32Array
  cone: Float32Array
  pool: Float32Array
}

function makeCellState(count: number): CellState {
  return {
    count,
    colors: new Float32Array(count * 3),
    cone: new Float32Array(count),
    pool: new Float32Array(count),
  }
}

interface BodyDirectorOpts {
  spec: BodySpec
  mount: Mount
  barY: number | null
  groupRef: React.RefObject<Group | null>
  mountRef: React.RefObject<Group | null>
  yokeRef: React.RefObject<Group | null>
  headRef: React.RefObject<Group | null>
  slot: number
  bodies: BodiesHandle | null
}

/** Pixels a metre covers on the canvas at [distance] — the camera's own, perspective or section. */
export function pixelsPerMetre(
  camera: RootState['camera'],
  canvasHeightPx: number,
  distance: number,
): number {
  if ('isOrthographicCamera' in camera && camera.isOrthographicCamera) {
    const span = (camera.top - camera.bottom) / Math.max(1e-6, camera.zoom)
    return canvasHeightPx / Math.max(1e-6, span)
  }
  const fov = 'fov' in camera ? (camera.fov as number) : 50
  return canvasHeightPx / (2 * Math.max(1e-3, distance) * Math.tan(MathUtils.degToRad(fov) / 2))
}

// Per-frame: copy the body's frames onto its instanced parts, at the level of detail its size on
// screen calls for. Runs after the beam director (registered after it), so the yoke and head are
// already this frame's.
function useBodyDirector({ spec, mount, barY, groupRef, mountRef, yokeRef, headRef, slot, bodies }: BodyDirectorOpts) {
  const pose = useMemo(() => makeBodyPose(), [])
  const lensLocal = useMemo(
    () => spec.cells.map((cell) => lensLocalMatrix(cell, spec.emitAxis, new Matrix4())),
    [spec],
  )
  const geometry = useMemo(() => bodyFrames(spec, mount), [spec, mount])
  const sizeM = Math.max(spec.lengthM, spec.widthM, spec.heightM)
  useFrame((state) => {
    if (!bodies) return
    const group = groupRef.current
    const mountNode = mountRef.current
    const yoke = yokeRef.current
    const head = headRef.current
    if (!group || !mountNode || !yoke || !head) return
    group.updateMatrixWorld()
    SCRATCH_CENTRE.setFromMatrixPosition(head.matrixWorld)
    const distance = state.camera.position.distanceTo(SCRATCH_CENTRE)
    pose.shown = bodyShownFor(sizeM * pixelsPerMetre(state.camera, state.size.height, distance))
    pose.mount.copy(mountNode.matrixWorld)
    pose.yoke.copy(yoke.matrixWorld)
    pose.head.copy(head.matrixWorld)
    while (pose.lenses.length < lensLocal.length) pose.lenses.push(new Matrix4())
    for (let c = 0; c < lensLocal.length; c++) pose.lenses[c].multiplyMatrices(head.matrixWorld, lensLocal[c])
    pose.hanger = null
    const attach = geometry.hangerAttach
    if (attach && mount === 'hang' && barY != null) {
      const frame = attach.frame === 'yoke' ? yoke : mountNode
      // A mover hangs only while its base is up — basePitchDeg 180. One at 0 stands on its bar.
      const baseUp = attach.frame === 'yoke' || SCRATCH_UP.set(0, 1, 0).transformDirection(mountNode.matrixWorld).y < -0.5
      if (baseUp) {
        SCRATCH_ATTACH.set(0, attach.y, 0).applyMatrix4(frame.matrixWorld)
        const length = hangerLengthM(mount, SCRATCH_ATTACH.y, barY)
        if (length > 0) {
          pose.hanger = HANGER_MATRIX.makeScale(1, length, 1).setPosition(SCRATCH_ATTACH)
        }
      }
    }
    pose.centreX = SCRATCH_CENTRE.x
    pose.centreY = SCRATCH_CENTRE.y
    pose.centreZ = SCRATCH_CENTRE.z
    pose.sizeM = Math.max(0.12, sizeM * 0.6)
    bodies.writePose(slot, pose)
  })
}

const HANGER_MATRIX = new Matrix4()

interface BeamDirectorOpts {
  spec: BodySpec
  /** The patch key to report the beam's landing under (`landedPoints.ts`), or null for none. */
  reportKey: string | null
  panProp: SliderPropertyDescriptor | undefined
  tiltProp: SliderPropertyDescriptor | undefined
  panFineProp: SliderPropertyDescriptor | undefined
  tiltFineProp: SliderPropertyDescriptor | undefined
  baseBeamDeg: number
  focusProp: SliderPropertyDescriptor | undefined
  /** A zoom slider, or a stepped zoom's setting (`findZoomProperty`). */
  zoomProp: ByteDescriptor | undefined
  irisProp: SliderPropertyDescriptor | undefined
  frostProp: SliderPropertyDescriptor | undefined
  /** Gobo layer A: the first wheel in channel order (`findGoboProperties`). */
  goboProp: ByteDescriptor | undefined
  /** Gobo layer B: the second wheel where one exists (the Robe: static, then rotating), multiplied
   *  over layer A in the air and on every surface. */
  goboProp2: ByteDescriptor | undefined
  /** Which layer the rotation channel turns (`goboRotationWheel`): 0, 1, or −1 for none. */
  goboTurned: number
  /** Whether the light table carries this unit's gobos, so they land on surfaces too. */
  goboOnSurfaces: boolean
  goboRotProp: ByteDescriptor | undefined
  /** The rotation's fine channel (`fineOf`), folded into the coarse value. */
  goboRotFineProp: SliderPropertyDescriptor | undefined
  /** The wheel's function channel: index or rotate (`gobo_rotation_mode`). */
  goboRotModeProp: ByteDescriptor | undefined
  prismProp: ByteDescriptor | undefined
  prismRotProp: ByteDescriptor | undefined
  ledMacroProp: ByteDescriptor | undefined
  moveMacroProp: ByteDescriptor | undefined
  /** The framing shutters a DMX head drives from its channels; replaces a lantern's blades. */
  shutterProps: ShutterProperties | undefined
  /** The type's speeds as rates, or null where nothing travels and every channel is drawn as sent. */
  travelRates: TravelRates | null
  /** The unit's own timing channels, which stretch a move planned while they hold a value. */
  timingChannels: TimingChannels
  groupRef: React.RefObject<Group | null>
  yokeRef: React.RefObject<Group | null>
  headRef: React.RefObject<Group | null>
  slot: number
  emitters: EmittersHandle | null
  regionGeometry: ReadonlyArray<RegionGeometry>
  colorStateRef: React.RefObject<ColorState>
  cellStateRef: React.RefObject<CellState | null>
}

// 8-bit fine DMX channel divides one coarse step into 256 sub-steps.
const FINE_STEPS = 256

// Module-level, not a per-frame closure: useFrame runs once per fixture per
// frame and this path is deliberately allocation-free (see the SCRATCH_*
// constants above), so a fresh arrow function here would cost ~3k throwaway
// closures a second on the 50-fixture profile harness.
//
// Reads by key off the ChannelSource rather than off a snapshot map, which is why
// ChannelSource has `getByKey` and no `getAll`: a source that composes layers (Output +
// Programmer) would have to allocate a merged map per fixture per frame to answer `getAll`.
function readChannel(source: ChannelSource, key: string | null): number {
  return key ? source.getByKey(key) : 0
}

function combineFine(
  coarseProp: SliderPropertyDescriptor | undefined,
  coarseRaw: number,
  fineProp: SliderPropertyDescriptor | undefined,
  fineRaw: number,
): number {
  if (!coarseProp) return 0
  return fineProp ? coarseRaw + fineRaw / FINE_STEPS : coarseRaw
}

/** No blades: what a body of several cells draws. */
const NO_BLADES: readonly [number, number] = [0, 0]

/**
 * The lantern focus's blades a beam draws: a single-cell body's — unless the fixture drives its own
 * blades from DMX, when its channels' are the beam's and a lantern's are never mixed in
 * (fixture-optics plan D5). The library never patches a lantern on a type with framing shutters
 * (`ShutterBladesTest`); this is what keeps a stored focus from drawing over one if it did.
 */
export function lanternBladesFor(
  spec: Pick<BodySpec, 'cells' | 'blades'>,
  shutters: ShutterProperties | undefined,
): BodySpec['blades'] {
  return spec.cells.length === 1 && !shutters ? spec.blades : null
}

/** A director's scratch for its DMX blades: the raw levels, the blades, and their packed pair. */
export interface BladeScratch {
  depthLevels: number[]
  rotationLevels: number[]
  blades: BladeState[]
  /** The blades in the frame's slots once a mover's half-turn is applied. */
  framed: BladeState[]
  packed: [number, number]
}

export function makeBladeScratch(): BladeScratch {
  return {
    depthLevels: [0, 0, 0, 0],
    rotationLevels: [0, 0, 0, 0],
    blades: makeBladeStates(),
    framed: [],
    packed: [0, 0],
  }
}

/**
 * A mover's DMX blades sit in the frame's opposite slots — top in the bottom's, left in the right's:
 * the frame turned a half-turn about the beam, which keeps each blade's angle (a rotation, not a
 * mirror). The beam frame's `v` is the head's own −Z, which points *away* from a mover's base when
 * it tilts out positive — up for a standing head, down for a hung one. A profile mover hangs (the
 * Source Four Revolutions on the hall's balcony do, and aim tilts them out positive to reach the
 * stage), so its blades are named as a hung head tilted out shows them: the top blade is the side
 * toward its base. The frame stays the head's, so a head that turns over to reach a point turns its
 * cut over with it, as the metal does. Estimate (fixture-optics plan D15): which side of the head
 * each frame sits on is `FU-MANUAL-S4REV-OPTICS` step 4's to settle.
 */
const MOVER_BLADE_SLOTS = [1, 0, 3, 2] as const

interface ShutterKeys {
  depth: Array<string | null>
  rotation: Array<string | null>
}

function shutterChannelKeys(shutters: ShutterProperties): ShutterKeys {
  return {
    depth: shutters.depth.map((p) => (p ? channelKey(p.channel) : null)),
    rotation: shutters.rotation.map((p) => (p ? channelKey(p.channel) : null)),
  }
}

/** Each blade channel's raw DMX into [scratch], allocation-free. */
function readShutterLevels(source: ChannelSource, keys: ShutterKeys, scratch: BladeScratch): BladeScratch {
  for (let i = 0; i < 4; i++) {
    scratch.depthLevels[i] = readChannel(source, keys.depth[i])
    scratch.rotationLevels[i] = readChannel(source, keys.rotation[i])
  }
  return scratch
}

/** The beam channels' levels a frame draws from — eased, or the DMX where nothing travels. */
export interface BeamLevels {
  focus: number
  zoom: number
  iris: number
  frost: number
  gobo: number
  gobo2: number
  /** The rotation channel with its fine byte folded in (`combineFinePair`): eased only while its
   *  wheel indexes, where it is an angle — a speed or a band change is drawn as sent. */
  goboRot: number
}

export function makeBeamLevels(): BeamLevels {
  return { focus: 0, zoom: 0, iris: 0, frost: 0, gobo: 0, gobo2: 0, goboRot: 0 }
}

/** One beam channel as drawn: stepped toward the DMX where it travels, the DMX where it does not. */
function beamLevel(bt: BeamTravel | null, axis: TravelAxis | undefined, key: string | null, raw: number): number {
  return bt && axis && key ? travelBeam(bt, axis, raw) : raw
}

/** Every beam channel the director reads, into [out], allocation-free. Exported for the test. */
export function readBeamLevels(
  source: ChannelSource,
  keys: {
    focus: string | null
    zoom: string | null
    iris: string | null
    frost: string | null
    gobo: string | null
    gobo2: string | null
    goboRot: string | null
    goboRotFine: string | null
  },
  hasRotFine: boolean,
  indexing: boolean,
  bt: BeamTravel | null,
  out: BeamLevels,
): BeamLevels {
  out.focus = beamLevel(bt, bt?.focus, keys.focus, readChannel(source, keys.focus))
  out.zoom = beamLevel(bt, bt?.zoom, keys.zoom, readChannel(source, keys.zoom))
  out.iris = beamLevel(bt, bt?.iris, keys.iris, readChannel(source, keys.iris))
  out.frost = beamLevel(bt, bt?.frost, keys.frost, readChannel(source, keys.frost))
  out.gobo = beamLevel(bt, bt?.gobo, keys.gobo, readChannel(source, keys.gobo))
  out.gobo2 = beamLevel(bt, bt?.gobo2, keys.gobo2, readChannel(source, keys.gobo2))
  const rot = combineFinePair(readChannel(source, keys.goboRot), hasRotFine ? readChannel(source, keys.goboRotFine) : null)
  // An indexed gobo turns to its new angle; a spin speed — or a change of the function channel's
  // band — is drawn as sent, since easing a speed through its stop band would draw a stop the wheel
  // never made (the operator's call, 2026-10-05). Leaving index forgets the axis, so entering it
  // again lands on the angle rather than travelling from a speed's value.
  if (bt && indexing) {
    out.goboRot = beamLevel(bt, bt.goboRot, keys.goboRot, rot)
  } else {
    if (bt) resetTravelAxis(bt.goboRot)
    out.goboRot = rot
  }
  return out
}

/**
 * The packed blades a beam draws this frame: a DMX head's, from its channels' levels in [scratch],
 * where it has framing shutters — else [lanternPacked], the lantern's packed once per spec. One or
 * the other, never both. A [mover]'s are framed as a hung head shows them ([MOVER_BLADE_SLOTS]).
 */
export function beamBlades(
  lanternPacked: readonly [number, number],
  shutters: ShutterProperties | undefined,
  scratch: BladeScratch,
  mover = false,
): readonly [number, number] {
  if (!shutters) return lanternPacked
  const blades = resolveDmxBlades(shutters, scratch.depthLevels, scratch.rotationLevels, scratch.blades)
  if (!mover) return packBlades(blades, scratch.packed)
  for (let i = 0; i < 4; i++) scratch.framed[i] = blades[MOVER_BLADE_SLOTS[i]]
  return packBlades(scratch.framed, scratch.packed)
}

const SCRATCH_BEAM: BeamWrite = {
  matrix: new Matrix4(),
  apex: SCRATCH_APEX,
  dir: new Vector3(),
  right: SCRATCH_RIGHT,
  color: new Color(),
  opacity: 0,
  cosHalf: 1,
  edge: 0,
  gobos: 0,
  focusDist: -1,
  near: 0,
  dof: 0,
  iris: 1,
  aspect: 0,
  bladesA: 0,
  bladesB: 0,
  shadowMask: 0,
  land: null,
  edgeLand: null,
}
const SCRATCH_LIGHT: LightRow = makeLightRow()
const SCRATCH_REPORT_ORIGIN = new Vector3()
const SCRATCH_REPORT_DIR = new Vector3()
const SCRATCH_REPORT_POINT = new Vector3()
const SCRATCH_GOBO_ROTATION = makeGoboRotation()
/** A wheel that is not turned this frame: no index, no spin. Never written. */
const NO_GOBO_ROTATION: Readonly<GoboRotation> = makeGoboRotation()
const SCRATCH_GOBOS = makeGoboLayers()

/**
 * Record where the beam's own **axis** lands, from its first aperture — what *Focus here* focuses
 * on (`landedPoints.ts`). Cast apart from the lobes, since a prism splays every lobe off the axis,
 * and whether or not the head is lit. [head]'s world matrix must be this frame's.
 */
function reportAxisLanding(
  reporter: object,
  patchKey: string,
  spec: BodySpec,
  emitters: Pick<EmittersHandle, 'reach'>,
  head: Group,
): void {
  const first = spec.cells[0]
  const axis = SCRATCH_REPORT_DIR.set(0, spec.emitAxis, 0).transformDirection(head.matrixWorld)
  const hit = first ? landBeam(emitters, apertureWorld(first, head, SCRATCH_REPORT_ORIGIN), axis).hit : null
  recordLanding(reporter, patchKey, hit ? fromThree(SCRATCH_REPORT_POINT.set(hit.px, hit.py, hit.pz)) : null)
}

/** A cell's aperture in world space: its centre on the head's face. */
function apertureWorld(cell: Cell, head: Group, out: Vector3): Vector3 {
  return out.set(cell.x, cell.y, cell.z).applyMatrix4(head.matrixWorld)
}

// Per-frame: decode pan/tilt, articulate the model, then read the beams back off
// the model's own matrices and push the fixture's slot in the shared instanced
// emitters. Origin has to come from the THREE objects (not the React
// `fixturePos` prop) — TransformControls mutates the group position directly
// during drag and React state lags.
function useBeamDirector({
  spec,
  reportKey,
  panProp,
  tiltProp,
  panFineProp,
  tiltFineProp,
  baseBeamDeg,
  focusProp,
  zoomProp,
  irisProp,
  frostProp,
  goboProp,
  goboProp2,
  goboTurned,
  goboOnSurfaces,
  goboRotProp,
  goboRotFineProp,
  goboRotModeProp,
  prismProp,
  prismRotProp,
  ledMacroProp,
  moveMacroProp,
  shutterProps,
  travelRates,
  timingChannels,
  groupRef,
  yokeRef,
  headRef,
  slot,
  emitters,
  regionGeometry,
  colorStateRef,
  cellStateRef,
}: BeamDirectorOpts) {
  const source = useChannelSource()
  const sourceRef = useRef(source)
  sourceRef.current = source
  // Pan/tilt feed geometry that's recomputed every frame anyway (TransformControls
  // can move the group mid-drag), so read them straight from the live channel
  // store in the frame loop rather than via React subscriptions — same reasoning
  // as the colour sync below: the R3F reconciler can drop hook-driven updates,
  // the frame loop can't. Keys are pre-baked so the per-frame read allocates nothing.
  const beamKeys = useMemo(
    () => ({
      pan: panProp ? channelKey(panProp.channel) : null,
      tilt: tiltProp ? channelKey(tiltProp.channel) : null,
      panFine: panFineProp ? channelKey(panFineProp.channel) : null,
      tiltFine: tiltFineProp ? channelKey(tiltFineProp.channel) : null,
      focus: focusProp ? channelKey(focusProp.channel) : null,
      zoom: zoomProp ? channelKey(zoomProp.channel) : null,
      iris: irisProp ? channelKey(irisProp.channel) : null,
      frost: frostProp ? channelKey(frostProp.channel) : null,
      gobo: goboProp ? channelKey(goboProp.channel) : null,
      gobo2: goboProp2 ? channelKey(goboProp2.channel) : null,
      goboRot: goboRotProp ? channelKey(goboRotProp.channel) : null,
      goboRotFine: goboRotFineProp ? channelKey(goboRotFineProp.channel) : null,
      goboRotMode: goboRotModeProp ? channelKey(goboRotModeProp.channel) : null,
      prism: prismProp ? channelKey(prismProp.channel) : null,
      prismRot: prismRotProp ? channelKey(prismRotProp.channel) : null,
      ledMacro: ledMacroProp ? channelKey(ledMacroProp.channel) : null,
      moveMacro: moveMacroProp ? channelKey(moveMacroProp.channel) : null,
    }),
    [
      panProp,
      tiltProp,
      panFineProp,
      tiltFineProp,
      focusProp,
      zoomProp,
      irisProp,
      frostProp,
      goboProp,
      goboProp2,
      goboRotProp,
      goboRotFineProp,
      goboRotModeProp,
      prismProp,
      prismRotProp,
      ledMacroProp,
      moveMacroProp,
    ],
  )

  // Beam cone trig. Zoom makes the angle per-frame, so this is a mutable struct
  // refreshed behind a dirty check rather than a memo — a static fixture pays
  // one float compare a frame. Per-fixture, not module-level: several readers
  // touch it within the same frame.
  const geomRef = useRef<BeamGeom>(makeBeamGeom())
  // Gobo and prism rotation are integrated angles, so they persist across
  // frames. The prism angle spins the lobe *arrangement*; the gobo angle spins
  // the image inside each lobe — the two compose independently, as on the
  // real fixture. Only the turned wheel's gobo has an angle (`stepGoboLayers`).
  const goboAngleRef = useRef(0)
  // Read in the frame loop through a ref, so the View menu's switch takes effect on the next
  // frame without re-registering it; flipping it asks for that frame.
  const goboOnSurfacesRef = useRef(goboOnSurfaces)
  goboOnSurfacesRef.current = goboOnSurfaces
  const prismAngleRef = useRef(0)
  // Lobes written last frame, so a shrinking facet count parks the excess
  // exactly once instead of every frame.
  const litLobesRef = useRef(1)
  // The light runs of a body of several cells: fixed by its cell count.
  const runs = useMemo(
    () => (spec.cells.length > 1 ? lightRuns(spec.cells.length, MAX_LIGHTS_PER_FIXTURE) : []),
    [spec],
  )
  // The blades, packed (`beamMask.ts`): the director writes the two floats into the beam and its
  // light each frame, and the GPU unpacks them. A lantern's are packed once per spec; a DMX head's
  // are read off its channels and packed every frame, into this director's own scratch — and a
  // fixture with DMX blades never draws a lantern's (fixture-optics plan D5).
  const lanternBlades = useMemo(() => packBlades(lanternBladesFor(spec, shutterProps)), [spec, shutterProps])
  const shutterKeys = useMemo(() => (shutterProps ? shutterChannelKeys(shutterProps) : null), [shutterProps])
  const [bladeScratch] = useState(makeBladeScratch)
  // Travel time (`beamTravel.ts`): a displayed value per channel, stepped toward the DMX each frame;
  // and this frame's beam levels, as drawn — the eased ones, or the DMX where nothing travels.
  const [beamTravel] = useState(makeBeamTravel)
  const [levels] = useState(makeBeamLevels)
  const timingKeys = useMemo<TimingKeys>(
    () => ({
      position: timingChannels.position ? channelKey(timingChannels.position.channel) : null,
      beam: timingChannels.beam ? channelKey(timingChannels.beam.channel) : null,
    }),
    [timingChannels],
  )
  // The frame's turn about the beam — the gate's, and a PAR lamp's — in radians.
  const frameTurnRad = MathUtils.degToRad(spec.cells.length === 1 ? spec.frameTurnDeg : 0)

  // The canvas renders on demand. Everything this director reads per frame is
  // a channel, so a change on any of them asks for a frame — on the active
  // source, which is what makes a programmer-only or Next GO preview move the
  // heads too. (The colour channels ask through the colour syncs.)
  // Through [subscribeToChannels], so a batch that moves several of them asks once.
  const invalidate = useStageInvalidate()
  const beamChannels = useMemo(
    () =>
      [
        panProp,
        tiltProp,
        panFineProp,
        tiltFineProp,
        focusProp,
        zoomProp,
        irisProp,
        frostProp,
        goboProp,
        goboProp2,
        goboRotProp,
        goboRotFineProp,
        goboRotModeProp,
        prismProp,
        prismRotProp,
        ledMacroProp,
        moveMacroProp,
        ...(shutterProps ? [...shutterProps.depth, ...shutterProps.rotation] : []),
      ].flatMap((p) => (p ? [p.channel] : [])),
    [
      panProp,
      tiltProp,
      panFineProp,
      tiltFineProp,
      focusProp,
      zoomProp,
      irisProp,
      frostProp,
      goboProp,
      goboProp2,
      goboRotProp,
      goboRotFineProp,
      goboRotModeProp,
      prismProp,
      prismRotProp,
      ledMacroProp,
      moveMacroProp,
      shutterProps,
    ],
  )
  useEffect(() => {
    invalidate()
    return subscribeToChannels(beamChannels, invalidate, source)
  }, [beamChannels, source, invalidate])
  // The gobos-on-surfaces switch changes the light table, not a prop R3F applies.
  useEffect(() => {
    invalidate()
  }, [goboOnSurfaces, invalidate])
  // This director's identity in `landedPoints.ts`: another canvas may report the same fixture.
  const [reporter] = useState(() => ({}))
  // Asked to report (the fixture was just selected): draw a frame, so the landing is this frame's;
  // and forget it once deselected or gone, so a later read never answers with a point from before
  // the head was re-aimed.
  useEffect(() => {
    if (!reportKey) return
    invalidate()
    return () => forgetLanding(reporter, reportKey)
  }, [reportKey, reporter, invalidate])

  useFrame((state, delta) => {
    const elapsed = state.clock.elapsedTime
    // Through a ref, not the closed-over value: the frame callback outlives the render that
    // created it, and flipping the vis source must take effect on the next frame rather than
    // waiting for whatever re-registers useFrame.
    const channelSource = sourceRef.current
    const yoke = yokeRef.current
    const head = headRef.current
    // Travel (fixture-optics plan D14): null where nothing travels, and every level below is the DMX.
    const bt = travelRates ? beamTravel : null
    if (bt && travelRates) beginTravelFrame(bt, channelSource, beamKeys, elapsed, travelRates, timingChannels, timingKeys)

    // A macro, a spinning gobo or a turning prism moves with time rather than
    // with DMX, so while one runs this frame asks for the next — the one case
    // where the `demand` frameloop keeps rendering with no channel moving.
    let animating = false
    if (spec.archetype === 'mover') {
      const panRaw = readChannel(channelSource, beamKeys.pan)
      const tiltRaw = readChannel(channelSource, beamKeys.tilt)
      const panCombined = combineFine(panProp, panRaw, panFineProp, readChannel(channelSource, beamKeys.panFine))
      const tiltCombined = combineFine(tiltProp, tiltRaw, tiltFineProp, readChannel(channelSource, beamKeys.tiltFine))

      // Signed about each axis's own centre. No base angles here: baseYaw and
      // basePitch are the body's mount orientation and are carried by the mount
      // group, so folding them in again would apply them twice.
      let panDeg = panProp ? dmxToSignedDegrees(panCombined, panProp) ?? 0 : 0
      let tiltDeg = tiltProp ? dmxToSignedDegrees(tiltCombined, tiltProp) ?? 0 : 0
      // The head swings to the DMX at its own speed rather than snapping: in degrees, so a 540°
      // pan and a 270° tilt each move at the type's rate (or over the timing channel's time).
      if (bt) {
        panDeg = travelPan(bt, panDeg)
        tiltDeg = travelTilt(bt, tiltDeg)
      }

      // A movement macro is an offset on top of the live pan/tilt, so both the
      // head model and the beam pick it up — they read from the same two values.
      const moveMacro = resolveMacroIndex(moveMacroProp, readChannel(channelSource, beamKeys.moveMacro))
      if (moveMacro > 0) {
        animating = true
        evalMovementMacro(moveMacro, elapsed, SCRATCH_MOVE_MACRO)
        panDeg += SCRATCH_MOVE_MACRO.panDeg
        tiltDeg += SCRATCH_MOVE_MACRO.tiltDeg
      }

      if (yoke && head) {
        // Split drive: the yoke pans, carrying the arms, and the head tilts
        // between them. Composed this equals headQuaternionFor(pan, tilt), which
        // stageCoords.test asserts so the two drive paths can't drift.
        yoke.rotation.set(0, MathUtils.degToRad(panDeg), 0)
        head.rotation.set(MathUtils.degToRad(tiltDeg), 0, 0)
      }
    }

    // The beam channels as drawn this frame. Stepped here — before the dark and beamless returns
    // below — so a move made in the dark has landed by the time the beam comes up, as on the rig.
    const indexing =
      goboRotModeProp != null &&
      resolveGoboRotationMode(goboRotModeProp, readChannel(channelSource, beamKeys.goboRotMode)) === 'INDEX'
    readBeamLevels(channelSource, beamKeys, goboRotFineProp != null, indexing, bt, levels)
    if (shutterKeys) {
      readShutterLevels(channelSource, shutterKeys, bladeScratch)
      if (bt) {
        for (let i = 0; i < 4; i++) {
          if (shutterKeys.depth[i]) bladeScratch.depthLevels[i] = travelBeam(bt, bt.depth[i], bladeScratch.depthLevels[i])
          if (shutterKeys.rotation[i]) bladeScratch.rotationLevels[i] = travelBeam(bt, bt.rotation[i], bladeScratch.rotationLevels[i])
        }
      }
    }
    // A move in flight asks for the next frame, like a macro; a settled head asks for none.
    if (bt?.moving) animating = true

    if (!emitters) {
      // A movement macro still swings a beamless head.
      if (reportKey) recordLanding(reporter, reportKey, null)
      if (animating) invalidate()
      return
    }

    const colorState = colorStateRef.current
    const cells = cellStateRef.current
    const cellCount = spec.cells.length
    const multi = cellCount > 1 && cells != null

    // Cull on the *base* opacity, not the macro-scaled one, so a pulsing macro
    // doesn't vacate and re-take the emitter slot every cycle. hideSlot parks
    // every lobe and light, so a prism split can't ghost after a blackout.
    let lit = false
    if (multi) {
      for (let c = 0; c < cells.count; c++) if (cells.pool[c] >= LIGHT_OFF_OPACITY) lit = true
    } else {
      lit = colorState.poolOpacity >= LIGHT_OFF_OPACITY
    }
    if (!lit) {
      emitters.hideSlot(slot)
      // Dark, but still pointing somewhere: a head is focused before it is brought up as often as after.
      const group = groupRef.current
      if (reportKey && group && head) {
        group.updateMatrixWorld()
        reportAxisLanding(reporter, reportKey, spec, emitters, head)
      }
      if (animating) invalidate()
      return
    }

    // An LED macro modulates on top of the base colour (a single-cell body). Written to a scratch
    // colour, never back into colorState: that field belongs to ColourSync, and folding the macro
    // in would leave the fixture permanently tinted once the macro stops.
    const ledMacro = multi ? 0 : resolveMacroIndex(ledMacroProp, readChannel(channelSource, beamKeys.ledMacro))
    if (ledMacro > 0) animating = true
    let beamColor = colorState.color
    let poolOpacity = colorState.poolOpacity
    let coneOpacity = colorState.coneOpacity
    if (ledMacro > 0) {
      evalLedMacro(ledMacro, elapsed, SCRATCH_LED_MACRO)
      SCRATCH_MACRO_COLOR.copy(colorState.color)
      if (SCRATCH_LED_MACRO.hueShift !== 0) {
        // offsetHSL alone is a no-op on an unsaturated base — every hue maps to
        // the same grey — and white is the common case here (the default beam
        // colour is #fff8d5, and a colour wheel sits at OPEN_WHITE). So pull
        // saturation up to a floor first, or the cycle would do nothing at all
        // on exactly the fixtures most likely to be running it.
        SCRATCH_MACRO_COLOR.getHSL(SCRATCH_HSL)
        SCRATCH_MACRO_COLOR.setHSL(
          (SCRATCH_HSL.h + SCRATCH_LED_MACRO.hueShift) % 1,
          Math.max(SCRATCH_HSL.s, LED_MACRO_MIN_SATURATION),
          SCRATCH_HSL.l,
        )
      }
      beamColor = SCRATCH_MACRO_COLOR
      poolOpacity *= SCRATCH_LED_MACRO.intensityScale
      coneOpacity *= SCRATCH_LED_MACRO.intensityScale
    }

    // Zoom overrides the static field angle (`resolveBeamDeg`): a ZOOM slider's degMin/degMax, or a
    // stepped zoom's band's zoomDeg; a zoom that declares no angle answers null and the base stands.
    const zoomDeg = resolveZoomDeg(zoomProp, levels.zoom)
    const beamDeg = zoomDeg ?? baseBeamDeg
    const geom = geomRef.current
    if (beamDeg !== geom.beamDeg) {
      computeBeamGeom(beamDeg, BEAM_LENGTH, REGION_CULL_SLACK_RAD, geom)
    }
    const tanHalf = Math.tan(MathUtils.degToRad(beamDeg) / 2)

    // Focus maps to a focal-plane distance from the aperture: a fixed one over the type's declared
    // range, else per lobe over its own throw ([focusRangeM]). The shaders soften the edge by how
    // far a surface or a sample sits from it. Without a focus channel the edge is the family's
    // softness (`bodies/archetype.ts`), moved towards soft by a frost channel.
    const focusParam = resolveFocusParam(focusProp, levels.focus)
    const declaredFocusDist = resolveDeclaredFocusDistance(focusProp, focusParam)
    // With a focus channel the family's cap lifts, and the blur softens the edge off the plane
    // ([resolveEdgeHardness]); the blur's scale is the type's depth of field, else its family's.
    const edge = resolveEdgeHardness(spec.softness, frostProp, levels.frost, focusParam != null)
    const dof = spec.depthOfField
    // The blur feathers the edge past the field (`beamMask.ts`), so a focus channel's light reaches
    // that much wider: its cone bound, its haze hull and its region cull.
    const spread = focusParam != null ? 1 + FOCUS_SPREAD_MAX : 1
    // A DMX iris closes the beam, and so does a conventional's own iris (its focus data); the
    // tighter of the two wins.
    const iris = Math.min(spec.iris, resolveIris(irisProp, levels.iris))

    // Up to two gobo wheels in series (the Robe: static, then rotating), each a layer the beam is
    // multiplied by, in the air and on every surface (fixture-optics plan session 4). Only the wheel
    // the rotation channel turns moves; a wheel with a function channel indexes to an angle or spins
    // at a speed, every other spins as its rotation channel's bands say. A body of several cells
    // carries none.
    const slotA = multi ? 0 : resolveGoboSlot(goboProp, levels.gobo)
    const slotB = multi ? 0 : resolveGoboSlot(goboProp2, levels.gobo2)
    const turnedSlot = goboTurned === 0 ? slotA : goboTurned === 1 ? slotB : 0
    const rotation =
      turnedSlot > 0
        ? resolveGoboRotation(
            goboRotProp,
            levels.goboRot,
            goboRotModeProp,
            readChannel(channelSource, beamKeys.goboRotMode),
            SCRATCH_GOBO_ROTATION,
          )
        : NO_GOBO_ROTATION
    const gobos = stepGoboLayers(slotA, slotB, goboTurned, rotation, goboAngleRef.current, delta, SCRATCH_GOBOS)
    goboAngleRef.current = gobos.angle
    if (gobos.spinning) animating = true

    const group = groupRef.current
    if (!group || !head) {
      if (animating) invalidate()
      return
    }
    // One walk of this fixture's subtree, after the rotations above, so the head matrices read
    // below are this frame's.
    group.updateMatrixWorld()

    // Direction read straight off the model's matrix rather than recomputed in JS. This whole bug
    // family was the beam and the geometry disagreeing; reading one from the other makes that
    // unrepresentable, and it picks up the mount rotation and any rig pose above it for free.
    // transformDirection normalises, so a scaled body doesn't skew the beam.
    const dir = SCRATCH_DIR.set(0, spec.emitAxis, 0).transformDirection(head.matrixWorld)

    // The head's world X axis gives the beam its cross-section frame — the gobo's, the mask's, a
    // segment's width — and the prism lobes their splay basis.
    SCRATCH_RIGHT.set(1, 0, 0).transformDirection(head.matrixWorld)
    // A lantern's gate — and a PAR's lamp — turns the frame about the beam, so its blades and its
    // oval turn with it, in the air and on every surface alike (`beamMask.ts`).
    if (frameTurnRad !== 0) SCRATCH_RIGHT.applyAxisAngle(dir, frameTurnRad)

    // A prism shows N displaced copies of the whole beam — gobo, focus, volume
    // and all — so each engaged facet takes one lobe of the slot's block and
    // gets the complete write set below. Disengaged, lobe 0 is the beam. Single-cell bodies only.
    const prismFacets = multi ? 0 : resolvePrismFacets(prismProp, readChannel(channelSource, beamKeys.prism))
    if (prismFacets > 0) {
      const prismSpin = resolvePrismSpin(
        prismRotProp,
        readChannel(channelSource, beamKeys.prismRot),
        prismProp,
        readChannel(channelSource, beamKeys.prism),
      )
      if (prismSpin !== 0) {
        animating = true
        prismAngleRef.current =
          (prismAngleRef.current + prismSpin * TAU * Math.min(delta, 0.1)) % TAU
      }
      // Beam-local basis for the splay circle, same construction as the gobo's.
      SCRATCH_PRISM_X.copy(SCRATCH_RIGHT)
        .addScaledVector(dir, -SCRATCH_RIGHT.dot(dir))
        .normalize()
      SCRATCH_PRISM_Y.crossVectors(dir, SCRATCH_PRISM_X)
    } else {
      prismAngleRef.current = 0
    }

    if (reportKey) reportAxisLanding(reporter, reportKey, spec, emitters, head)

    // Never more lobes than the slot was given: a fixture's prism and cells are known when the
    // layout is built (`emitterNeedsForSpec`), so this only bites on a frame where the two disagree,
    // and then the extra lobes are simply not drawn.
    const lobes = Math.min(multi ? cellCount : prismFacets > 0 ? prismFacets : 1, emitters.lobesFor(slot))
    const splay = MathUtils.degToRad(beamDeg / 2) * PRISM_SPLAY

    const blades = multi
      ? NO_BLADES
      : shutterProps && shutterKeys
        ? beamBlades(lanternBlades, shutterProps, bladeScratch, spec.archetype === 'mover')
        : lanternBlades

    const beam = SCRATCH_BEAM
    beam.cosHalf = geom.cosHalfBeam
    beam.edge = edge
    beam.iris = iris
    beam.bladesA = blades[0]
    beam.bladesB = blades[1]
    beam.gobos = gobos.packed
    // The surfaces take the same layers unless the View menu limits them to the selected heads; the
    // haze always draws them.
    const surfaceGobos = goboOnSurfacesRef.current ? gobos.packed : 0

    for (let lobe = 0; lobe < lobes; lobe++) {
      const cell = spec.cells[multi ? lobe : 0]
      const lobeDir =
        prismFacets > 0
          ? computeLobeDirection(
              dir,
              SCRATCH_PRISM_X,
              SCRATCH_PRISM_Y,
              splay,
              prismAngleRef.current + (TAU * lobe) / prismFacets,
              SCRATCH_LOBE_DIR,
            )
          : dir

      let opacity: number
      if (multi) {
        opacity = cells.cone[lobe]
        beam.color.setRGB(cells.colors[lobe * 3], cells.colors[lobe * 3 + 1], cells.colors[lobe * 3 + 2])
        if (cells.pool[lobe] < LIGHT_OFF_OPACITY) {
          emitters.hideLobes(slot, lobe)
          // hideLobes parks from here to the end; the cells after this one are rewritten below.
        }
      } else {
        // A prism redistributes the beam's flux, it doesn't add any: each lobe carries 1/N (plus a
        // little overlap compensation) so swinging the prism in reads as a split, not a jump.
        opacity = prismFacets > 0 ? (coneOpacity / prismFacets) * PRISM_OVERLAP_GAIN : coneOpacity
        beam.color.copy(beamColor)
      }

      // The beam leaves the cell's aperture, at the aperture's own size; its apex sits the
      // aperture's radius over tan(half-field) behind it (`bodies/archetype.ts`).
      apertureWorld(cell, head, SCRATCH_APERTURE)
      // A segment's depth over its width; an oval PAR's narrow axis over its wide one, negative
      // (`beamMask.ts`); 0 for a round lens.
      const aspect =
        cell.shape === 'segment'
          ? cell.halfDepthM / Math.max(1e-4, cell.halfWidthM)
          : !multi && spec.ovalRatio != null
            ? -spec.ovalRatio
            : 0
      const near = apexDistanceM(cell.halfWidthM, beamDeg)
      SCRATCH_APEX.copy(SCRATCH_APERTURE).addScaledVector(lobeDir, -near)

      SCRATCH_BX.copy(SCRATCH_RIGHT).addScaledVector(lobeDir, -SCRATCH_RIGHT.dot(lobeDir)).normalize()
      SCRATCH_BY.crossVectors(lobeDir, SCRATCH_BX)
      // Where the lobe lands: the first surface on its axis, from the aperture, and the second face
      // of an edge it is split across. The volume is drawn until all of it has crossed those
      // planes and is cut behind them, and the light table carries the same planes for the surfaces.
      const landed = landBeam(emitters, SCRATCH_APERTURE, lobeDir)
      const edgeHit = landed.hit
        ? edgeLanding(emitters, SCRATCH_APEX, lobeDir, SCRATCH_BX, SCRATCH_BY, tanHalf, tanHalf * (aspect !== 0 ? Math.abs(aspect) : 1), aspect > 0, near, landed.hit)
        : null
      // A segment's frustum reaches past the field circle at its corners, and a focus channel's past
      // the field.
      const tanEdge = (aspect > 0 ? tanHalf * Math.hypot(1, aspect) : tanHalf) * spread
      const length = landed.hit
        ? Math.max(
            coneLandingDepth(SCRATCH_APEX, lobeDir, tanEdge, landed.hit, near + MAX_THROW_M),
            edgeHit ? coneLandingDepth(SCRATCH_APEX, lobeDir, tanEdge, edgeHit, near + MAX_THROW_M) : 0,
          )
        : near + landed.length
      const focusDist = declaredFocusDist ?? resolveFocusDistance(focusParam, focusRangeM(landed.length))
      beam.focusDist = focusDist
      beam.land = landed.hit
      beam.edgeLand = edgeHit
      const far = length * tanHalf * spread
      composeBeamHull(
        SCRATCH_APEX,
        lobeDir,
        SCRATCH_BX,
        SCRATCH_BY,
        length,
        far * (aspect > 0 ? RECT_HULL : HULL_SLACK),
        // An oval's hull is the oval's, turned with the lamp: the march still bounds it by the round
        // cone of its wide field, and the mask cuts it to the oval.
        far * (aspect > 0 ? RECT_HULL * aspect : aspect < 0 ? HULL_SLACK * -aspect : HULL_SLACK),
        beam.matrix,
      )
      beam.dir.copy(lobeDir)
      beam.opacity = opacity
      beam.near = near
      beam.dof = dof
      beam.aspect = aspect
      // A segment's frustum reaches past the field circle at its corners, so its cull cone is the one
      // through them — or a region lying in a corner would never be shadow-tested; and a focus
      // channel's past the field.
      if (aspect > 0 || spread > 1) {
        const cull = Math.atan(tanEdge) + REGION_CULL_SLACK_RAD
        beam.shadowMask = regionShadowMask(SCRATCH_APEX, lobeDir, length, Math.cos(cull), Math.sin(cull), regionGeometry)
      } else {
        beam.shadowMask = regionShadowMask(SCRATCH_APEX, lobeDir, length, geom.cosCull, geom.sinCull, regionGeometry)
      }
      if (!multi || cells.pool[lobe] >= LIGHT_OFF_OPACITY) emitters.writeBeam(slot, lobe, beam)

      if (!multi) {
        // A single cell: each lobe lands as its own light.
        const pool = prismFacets > 0 ? (poolOpacity / prismFacets) * PRISM_OVERLAP_GAIN : poolOpacity
        writeLightRow(SCRATCH_LIGHT, SCRATCH_APEX, lobeDir, SCRATCH_RIGHT, beamColor, pool, geom.cosHalfBeam, tanHalf, spread, edge, focusDist, dof, near, iris, aspect, landed.hit, edgeHit, blades[0], blades[1], surfaceGobos)
        emitters.writeLight(slot, lobe, SCRATCH_LIGHT)
      }
    }

    if (multi) {
      // Several cells land at most four lights, each averaging a run of cells: the run's
      // aperture is the cells' span, its colour × level their mean.
      for (let r = 0; r < runs.length; r++) {
        const [from, to] = runs[r]
        let red = 0
        let green = 0
        let blue = 0
        // Off on the same test as the run's beams: no cell in it at a level the beam cull keeps.
        let lit = false
        for (let c = from; c < to; c++) {
          const pool = cells.pool[c]
          if (pool >= LIGHT_OFF_OPACITY) lit = true
          red += cells.colors[c * 3] * pool
          green += cells.colors[c * 3 + 1] * pool
          blue += cells.colors[c * 3 + 2] * pool
        }
        const n = to - from
        // A fixture's light is shared across its cells, as its beam is: a run carries its cells'
        // share of the whole, so three cells lit land what one would.
        red /= cells.count
        green /= cells.count
        blue /= cells.count
        const level = Math.max(red, green, blue)
        if (!lit || level <= 0) {
          emitters.clearLight(slot, r)
          continue
        }
        SCRATCH_RUN_COLOR.setRGB(red / level, green / level, blue / level)
        const first = spec.cells[from]
        const last = spec.cells[to - 1]
        // The run's aperture: centred between its end cells, as wide as they span.
        SCRATCH_APERTURE.set((first.x + last.x) / 2, (first.y + last.y) / 2, (first.z + last.z) / 2).applyMatrix4(head.matrixWorld)
        const halfWidth = n === 1 ? first.halfWidthM : (Math.abs(last.x - first.x) + first.halfWidthM + last.halfWidthM) / 2
        const halfDepth = first.halfDepthM
        const aspect = n === 1 && first.shape === 'disc' ? 0 : halfDepth / Math.max(1e-4, halfWidth)
        const near = apexDistanceM(halfWidth, beamDeg)
        SCRATCH_APEX.copy(SCRATCH_APERTURE).addScaledVector(dir, -near)
        const landed = landBeam(emitters, SCRATCH_APERTURE, dir)
        SCRATCH_BX.copy(SCRATCH_RIGHT).addScaledVector(dir, -SCRATCH_RIGHT.dot(dir)).normalize()
        SCRATCH_BY.crossVectors(dir, SCRATCH_BX)
        const edgeHit = landed.hit
          ? edgeLanding(emitters, SCRATCH_APEX, dir, SCRATCH_BX, SCRATCH_BY, tanHalf, tanHalf * (aspect > 0 ? aspect : 1), aspect > 0, near, landed.hit)
          : null
        const focusDist = declaredFocusDist ?? resolveFocusDistance(focusParam, focusRangeM(landed.length))
        writeLightRow(SCRATCH_LIGHT, SCRATCH_APEX, dir, SCRATCH_RIGHT, SCRATCH_RUN_COLOR, level, geom.cosHalfBeam, tanHalf, spread, edge, focusDist, dof, near, iris, aspect, landed.hit, edgeHit, 0, 0, 0)
        emitters.writeLight(slot, r, SCRATCH_LIGHT)
      }
    } else if (lobes < litLobesRef.current) {
      // Park lobes the prism no longer lights, once, on the frame it shrinks.
      emitters.hideLobes(slot, lobes)
      emitters.hideLights(slot, lobes)
    }
    litLobesRef.current = lobes
    if (animating) invalidate()
  })
}

/**
 * Fill a light row. [cosHalf] is the field's; a segment's bound is the cone through its corners, and
 * a focus channel's reaches [spread] times as wide, past the field by the most its blur feathers.
 */
function writeLightRow(
  row: LightRow,
  apex: Vector3,
  dir: Vector3,
  right: Vector3,
  color: Color,
  level: number,
  cosHalf: number,
  tanHalf: number,
  spread: number,
  edge: number,
  focusDist: number,
  dof: number,
  near: number,
  iris: number,
  aspect: number,
  hit: SurfaceHit | null,
  edgeHit: SurfaceHit | null,
  bladesA: number,
  bladesB: number,
  gobos: number,
): void {
  row.ax = apex.x
  row.ay = apex.y
  row.az = apex.z
  row.dx = dir.x
  row.dy = dir.y
  row.dz = dir.z
  row.cosBound =
    aspect > 0 || spread > 1 ? Math.cos(Math.atan(tanHalf * spread * (aspect > 0 ? Math.hypot(1, aspect) : 1))) : cosHalf
  row.r = color.r * level
  row.g = color.g * level
  row.b = color.b * level
  row.edge = edge
  row.focusDist = focusDist
  row.dof = dof
  row.hit = hit
  row.edgeHit = edgeHit
  row.rx = right.x
  row.ry = right.y
  row.rz = right.z
  row.tanHalf = tanHalf
  row.near = near
  row.iris = iris
  row.aspect = aspect
  row.bladesA = bladesA
  row.bladesB = bladesB
  row.gobos = gobos
}

// — colour sync (event-driven via live channel subscriptions) —————————
//
// Colour is applied to the scene imperatively from a raw channel subscription,
// NOT through useSyncExternalStore → render → useEffect. Inside the R3F Canvas
// (a separate reconciler root) those store-driven re-renders flush on the loop's
// own cadence and drop beat-rate changes; the subscription callback fires
// synchronously from the channel store, outside React, so every change lands.
// The lens is painted here directly (through the body's instanced lens, `LensPainter`); the beam's
// colorStateRef is read each frame by useBeamDirector and pushed to the emitter buffers.

interface ColourSyncBaseProps {
  dimmerProp: SliderPropertyDescriptor | undefined
  /**
   * The strobe channels (`findStrobeProperties`): each band's level factor multiplies the dimmer's
   * (`lib/strobeBands.ts`) — a closed band dark, a strobe flashing under the three-flash rule — and an
   * arm on a flashing band registers with the ticker while it flashes, as an animated colour band
   * does. Absent or empty gates nothing.
   */
  strobes?: readonly StrobeProperty[]
  /** The unit's colour filters (`colourFilters`): settings whose current slot's colour multiplies
   *  the beam's — a second colour wheel, a media frame's gel, a dichroic in a wheel. Absent or empty
   *  filters nothing. */
  filters?: readonly SettingPropertyDescriptor[]
  /** Paints the body's lens; null outside a canvas (the tests) and before the bodies exist. */
  lensRef: React.RefObject<LensPainter | null>
  colorStateRef: React.RefObject<ColorState>
  /**
   * The scene's clock for an animated colour band (`colourTicker.ts`): an arm whose wheel — or one of
   * whose filters — sits on a band with no single colour registers with it while it does, and
   * re-applies at its time each frame. Absent, time stands at 0 and nothing animates.
   */
  ticker?: ColourTicker
  /**
   * Travel time for the colour family (fixture-optics plan D14): a wheel, a scroller or a filter is
   * drawn passing through the slots between, stepped toward its DMX at the type's speed or over the
   * unit's colour timing channel (`lib/travel.ts`), and the arm registers with [ticker] while one is in
   * flight. Absent, every colour channel is drawn as sent. Needs a driven ticker: on a still one a move
   * would never leave its first frame.
   */
  travel?: ColourTravel
}

/** A colour arm's travel: the colour family's rate (DMX steps a second) and the unit's colour timing channel. */
export interface ColourTravel {
  rate: number
  timing?: SliderPropertyDescriptor
}

/** One colour arm's displayed levels, one axis per channel it eases. */
interface ColourTravelState {
  axes: Map<string, TravelAxis>
  source: ChannelSource | null
  epoch: number
  channels: readonly ChannelRef[] | null
  /** Whether a channel read on this apply is drawn short of its target. */
  moving: boolean
}

function makeColourTravelState(): ColourTravelState {
  return { axes: new Map(), source: null, epoch: 0, channels: null, moving: false }
}

/** A setting channel's level as one apply draws it. */
type LevelOf = (p: SettingPropertyDescriptor) => number

/**
 * Begin one apply of a colour arm and answer its level reader: the DMX where nothing travels, else
 * each channel stepped toward its DMX ([stepTravel]). [nowS] is the frame's time when the ticker
 * runs the apply, null from a channel callback — which plans a move without starting its clock. The
 * axes land, never travel, after a source switch, a replacement of the source's values or a change
 * of channels (a repatch), exactly as the beam director's do (`beamTravel.ts`).
 */
function beginColourApply(
  st: ColourTravelState,
  travel: ColourTravel | undefined,
  source: ChannelSource,
  channels: readonly ChannelRef[],
  nowS: number | null,
): LevelOf {
  st.moving = false
  if (!travel) return (p) => getChannelValue(p.channel, source)
  const epoch = sourceEpoch(source)
  if (st.source !== source || st.epoch !== epoch || st.channels !== channels) {
    st.axes.clear()
    st.source = source
    st.epoch = epoch
    st.channels = channels
  }
  const timedS = timedSeconds(travel.timing, travel.timing ? getChannelValue(travel.timing.channel, source) : 0)
  return (p) => {
    const raw = getChannelValue(p.channel, source)
    const key = channelKey(p.channel)
    let axis = st.axes.get(key)
    if (!axis) {
      axis = makeTravelAxis()
      st.axes.set(key, axis)
    }
    const level = stepTravel(axis, raw, nowS, travel.rate, colourTimed(p.category) ? timedS : null)
    if (isTravelling(axis)) st.moving = true
    return level
  }
}

/** Exported for the unit test — the arms are the interesting part and the enclosing
 *  `FixtureModel` cannot be rendered outside an R3F canvas. */
export function ColourSync({
  hasFixture,
  colourSource,
  gel,
  ...refs
}: ColourSyncBaseProps & {
  /** False for a patch whose fixture record hasn't resolved (or never will). */
  hasFixture: boolean
  colourSource:
    | { type: 'colour'; property: ColourPropertyDescriptor }
    | { type: 'setting'; property: SettingPropertyDescriptor }
    | undefined
  gel: { color: string } | null
}) {
  // First, and above the gel arm, exactly as FixtureAppearanceSource orders it: a patch with no
  // fixture record has no channels to read, and falling through to the warm-white default painted
  // it as a fully-lit lamp — for an unmatched patch, and for every patch during the window before
  // the fixture list resolves — while the DOM markers correctly showed a placeholder.
  if (!hasFixture) {
    return <PlaceholderBeamSync {...refs} />
  }
  // A body of several cells never reaches here: `FixtureModel` draws it through `CellColourSync`,
  // one colour and level per cell. This dispatch is the fixture's one colour, as the 2D one is.
  if (colourSource?.type === 'colour') {
    return <ColourBeamSync colourProp={colourSource.property} {...refs} />
  }
  if (colourSource?.type === 'setting') {
    return <SettingColourBeamSync settingProp={colourSource.property} {...refs} />
  }
  return <FixedColourBeamSync hex={gel?.color ?? DEFAULT_FIXTURE_COLOUR} {...refs} />
}

interface ColourApplyRefs {
  lensRef: React.RefObject<LensPainter | null>
  colorStateRef: React.RefObject<ColorState>
}

const NO_FILTERS: readonly SettingPropertyDescriptor[] = []

type StrobeProperty = SliderPropertyDescriptor | SettingPropertyDescriptor
const NO_STROBES: readonly StrobeProperty[] = []

/** The strobe channels' level factor at [timeS], read from `source` — the 2D `StrobeGate` twin. */
function liveStrobeFactor(strobes: readonly StrobeProperty[], source: ChannelSource, timeS: number): number {
  if (strobes.length === 0) return 1
  return strobeFactor(strobes, (p) => getChannelValue(p.channel, source), timeS)
}

/** Whether any strobe channel sits on a band that moves with time, so the arm re-applies each frame. */
function strobesAnimate(strobes: readonly StrobeProperty[], source: ChannelSource): boolean {
  if (strobes.length === 0) return false
  return strobeAnimates(strobes, (p) => getChannelValue(p.channel, source))
}

/**
 * `hex` through each filter's current slot colour at [timeS] (`filterColour`, `settingColourAt`),
 * read from `source`: a filter on an animated band — the Robe's second wheel scrolling — passes the
 * beam through its own wheel's colours in turn.
 */
function filteredHex(
  hex: string,
  filters: readonly SettingPropertyDescriptor[],
  levels: readonly number[],
  timeS: number,
): string {
  if (filters.length === 0) return hex
  return filterColour(
    hex,
    filters.map((f, i) => settingColourAt(f.options, levels[i], timeS)),
  )
}

/** Whether any filter sits on an animated band, so the arm must re-apply every frame. */
function filtersAnimate(filters: readonly SettingPropertyDescriptor[], levels: readonly number[]): boolean {
  for (let i = 0; i < filters.length; i++) if (isAnimatedAt(filters[i].options, levels[i])) return true
  return false
}

const NO_LEVELS: readonly number[] = []

/** Each filter's level as this apply draws it. */
function filterLevels(filters: readonly SettingPropertyDescriptor[], levelOf: LevelOf): readonly number[] {
  return filters.length === 0 ? NO_LEVELS : filters.map(levelOf)
}

function applyColour(hex: string, intensity: number, refs: ColourApplyRefs) {
  COLOR_TMP.set(hex)
  // The lens is the lamp face (the colour indicator) and is never culled; `lensColour` puts the
  // level on its own curve. `hex` is already a full-brightness hue. At level 0 it is dark glass.
  refs.lensRef.current?.(0, COLOR_TMP, intensity)
  // Beam cone/pool strengths stay LINEAR: they double as the LIGHT_OFF_OPACITY
  // cull signal downstream, so curving them would resurrect near-off fixtures
  // into ghost beams.
  const state = refs.colorStateRef.current
  state.color.copy(COLOR_TMP)
  state.coneOpacity = CONE_SCALE * intensity
  state.poolOpacity = POOL_SCALE * intensity
}

// 0..1 dimmer factor from the given channel source; 1 when the fixture has no dimmer
// ("always on"). Mirrors useNormalizedIntensity but reads imperatively.
function liveDimmerFactor(
  dimmerProp: SliderPropertyDescriptor | undefined,
  source: ChannelSource,
): number {
  if (!dimmerProp) return 1
  return Math.max(0, Math.min(1, getChannelValue(dimmerProp.channel, source) / 255))
}

// Subscribe to `channels` on `source` and run `apply` on every change — plus once on mount
// and after each (rare) re-render, so descriptor/gel/dimmer-prop changes also take effect.
// `channels` must be referentially stable across renders or the subscription will thrash.
//
// `source` is passed in rather than read here because `apply` has to read the same one this
// subscribes to, and the caller builds `apply`. A change of source re-subscribes and re-applies,
// which is what repaints the scene on a vis-source flip.
//
// `apply` answers whether what it drew **animates** — a wheel or a filter on a band with no single
// colour (`lib/colourBands.ts`), or a strobe channel on a band that flashes (`lib/strobeBands.ts`).
// While it does, the arm is registered with the [ticker] and
// re-applied every frame, which asks for the next; the first apply that answers false unregisters
// it, so the canvas goes back to drawing only when something changes. The registration is made and
// dropped from inside `apply` because the answer is a channel fact, known only there.
function useLiveColour(
  channels: ChannelRef[],
  apply: (inFrame: boolean) => boolean,
  source: ChannelSource,
  ticker: ColourTicker = STILL_COLOUR_TICKER,
) {
  const unregisterRef = useRef<(() => void) | null>(null)
  // `inFrame`: run by the ticker inside a frame, so `ticker.now()` is this frame's time — the only
  // time a colour channel's travel may start a move's clock (`lib/travel.ts`).
  const run = (inFrame = false) => {
    const animated = apply(inFrame)
    if (animated && !unregisterRef.current) {
      unregisterRef.current = ticker.onFrame(() => applyRef.current(true))
    } else if (!animated && unregisterRef.current) {
      unregisterRef.current()
      unregisterRef.current = null
    }
  }
  const applyRef = useRef(run)
  applyRef.current = run
  // A ticker swapped (a remount of its owner) or an arm unmounted leaves nothing registered.
  useEffect(
    () => () => {
      unregisterRef.current?.()
      unregisterRef.current = null
    },
    [ticker],
  )
  // The writes `apply` makes are imperative — a material colour, the beam's colour state — so on
  // the canvas's `demand` frameloop each one has to ask for the frame that shows it.
  const invalidate = useStageInvalidate()
  // Re-apply after every render. These components no longer subscribe through
  // React, so renders only happen on config/selection changes — cheap to redo,
  // and it covers inputs (gel hex, dimmer prop) that aren't channel values.
  useEffect(() => {
    applyRef.current()
    invalidate()
  })
  // Live path: write straight to the scene from the channel callback, bypassing
  // React entirely so beat-rate changes can't be dropped by the reconciler.
  //
  // Through [subscribeToChannels] rather than registering each channel here: a colour beam's set
  // runs to seven channels, and a per-channel registration reapplies the *whole* colour — seven
  // reads, the dimmer and colour factors, the normalised hue — once per changed channel per
  // batch, for every fixture on the stage. The coalesced wake-up still lands in the same frame
  // (a microtask drains before paint), so the reconciler is no more involved than it was.
  useEffect(
    () =>
      subscribeToChannels(
        channels,
        () => {
          applyRef.current()
          invalidate()
        },
        source,
      ),
    [channels, source, invalidate],
  )
}

function ColourBeamSync({
  colourProp,
  dimmerProp,
  filters = NO_FILTERS,
  strobes = NO_STROBES,
  ticker = STILL_COLOUR_TICKER,
  travel,
  ...refs
}: ColourSyncBaseProps & { colourProp: ColourPropertyDescriptor }) {
  const source = useChannelSource()
  const [travelState] = useState(makeColourTravelState)
  const channels = useMemo(() => {
    const cs: ChannelRef[] = [
      colourProp.redChannel,
      colourProp.greenChannel,
      colourProp.blueChannel,
    ]
    if (colourProp.whiteChannel) cs.push(colourProp.whiteChannel)
    if (colourProp.amberChannel) cs.push(colourProp.amberChannel)
    if (colourProp.uvChannel) cs.push(colourProp.uvChannel)
    if (dimmerProp) cs.push(dimmerProp.channel)
    for (const f of filters) cs.push(f.channel)
    for (const p of strobes) cs.push(p.channel)
    return cs
  }, [colourProp, dimmerProp, filters, strobes])

  useLiveColour(
    channels,
    (inFrame) => {
      // An RGB mix is electronic and never travels; only the filters' glass does.
      const levelOf = beginColourApply(travelState, travel, source, channels, inFrame ? ticker.now() : null)
      const levels = filterLevels(filters, levelOf)
      const r = getChannelValue(colourProp.redChannel, source)
      const g = getChannelValue(colourProp.greenChannel, source)
      const b = getChannelValue(colourProp.blueChannel, source)
      const w = colourProp.whiteChannel
        ? getChannelValue(colourProp.whiteChannel, source)
        : undefined
      const a = colourProp.amberChannel
        ? getChannelValue(colourProp.amberChannel, source)
        : undefined
      const uv = colourProp.uvChannel ? getChannelValue(colourProp.uvChannel, source) : undefined
      // Effective intensity = dimmer × colour so a colour-only fixture at RGB 0
      // reads as dark rather than beaming at full. Hue is normalised to full so a
      // dimmerless fixture at r:20 shows dim orange (via the level) not near-black.
      const timeS = ticker.now()
      const intensity =
        liveDimmerFactor(dimmerProp, source) * colourFactor(r, g, b, w, a, uv) * liveStrobeFactor(strobes, source, timeS)
      applyColour(
        filteredHex(computeNormalizedHueCss(r, g, b, w, a, uv), filters, levels, timeS),
        intensity,
        refs,
      )
      return filtersAnimate(filters, levels) || strobesAnimate(strobes, source) || travelState.moving
    },
    source,
    ticker,
  )
  return null
}

function SettingColourBeamSync({
  settingProp,
  dimmerProp,
  filters = NO_FILTERS,
  strobes = NO_STROBES,
  ticker = STILL_COLOUR_TICKER,
  travel,
  ...refs
}: ColourSyncBaseProps & { settingProp: SettingPropertyDescriptor }) {
  const source = useChannelSource()
  const [travelState] = useState(makeColourTravelState)
  const channels = useMemo(() => {
    const cs: ChannelRef[] = [settingProp.channel]
    if (dimmerProp) cs.push(dimmerProp.channel)
    for (const f of filters) cs.push(f.channel)
    for (const p of strobes) cs.push(p.channel)
    return cs
  }, [settingProp, dimmerProp, filters, strobes])

  useLiveColour(
    channels,
    (inFrame) => {
      // The wheel or the scroller as drawn: travelling, it passes through the slots between — the
      // unit's own, since `settingProp` is the fitted one (`lib/fittedMedia.ts`).
      const levelOf = beginColourApply(travelState, travel, source, channels, inFrame ? ticker.now() : null)
      const level = levelOf(settingProp)
      const levels = filterLevels(filters, levelOf)
      const timeS = ticker.now()
      // The band's colour — its preview, or for a scroll or random band its wheel's own colours at
      // this time — else open white, never black for want of data; a blackout band is dark
      // (`lib/colourBands.ts`, the 2D dispatch's `SettingColourAppearance` twin).
      const colour = settingColourAt(settingProp.options, level, timeS)
      const intensity =
        liveDimmerFactor(dimmerProp, source) * sourceBandLevel(colour) * liveStrobeFactor(strobes, source, timeS)
      applyColour(filteredHex(sourceBandColour(colour), filters, levels, timeS), intensity, refs)
      return (
        isAnimatedAt(settingProp.options, level) ||
        filtersAnimate(filters, levels) ||
        strobesAnimate(strobes, source) ||
        travelState.moving
      )
    },
    source,
    ticker,
  )
  return null
}

function FixedColourBeamSync({
  hex,
  dimmerProp,
  filters = NO_FILTERS,
  strobes = NO_STROBES,
  ticker = STILL_COLOUR_TICKER,
  travel,
  ...refs
}: ColourSyncBaseProps & { hex: string }) {
  // No colour channels (gel / dimmer-only), so colourFactor is implicitly 1 —
  // intensity is the dimmer alone. A gel/setting fixture with no dimmer beams
  // full by design (no brightness signal to gate on).
  const source = useChannelSource()
  const [travelState] = useState(makeColourTravelState)
  const channels = useMemo(
    () => [
      ...(dimmerProp ? [dimmerProp.channel] : []),
      ...filters.map((f) => f.channel),
      ...strobes.map((p) => p.channel),
    ],
    [dimmerProp, filters, strobes],
  )
  useLiveColour(
    channels,
    (inFrame) => {
      const levelOf = beginColourApply(travelState, travel, source, channels, inFrame ? ticker.now() : null)
      const levels = filterLevels(filters, levelOf)
      const timeS = ticker.now()
      applyColour(
        filteredHex(hex, filters, levels, timeS),
        liveDimmerFactor(dimmerProp, source) * liveStrobeFactor(strobes, source, timeS),
        refs,
      )
      return filtersAnimate(filters, levels) || strobesAnimate(strobes, source) || travelState.moving
    },
    source,
    ticker,
  )
  return null
}

// Patch with no matching fixture. The one arm of this dispatch with no channels to watch, so it
// holds no subscription at all — but it keeps the fixed-hook-set-per-branch shape the others have
// (one hook, unconditionally) and re-applies after every render, because the lens material is
// painted through the bodies' handle, which may not exist on the first pass.
function PlaceholderBeamSync({ lensRef, colorStateRef }: ColourSyncBaseProps) {
  const refs = { lensRef, colorStateRef }
  useEffect(() => {
    applyColour(PLACEHOLDER_FIXTURE_COLOUR, PLACEHOLDER_FIXTURE_INTENSITY, refs)
  })
  return null
}

// — cells ————————————————————————————————————————————————————————————————
//
// A body of several cells (a batten, a blinder, a bar of heads — stage-view plan session 6): each
// cell takes its own element's colour and level — the element's colour × its own dimmer × the
// fixture's master — and paints its own lens. What an element's colour is, the fixture dispatch's
// own arms decide per element: an RGB(W/A/UV) colour, a colour-wheel setting's preview, or warm and
// cold white sliders mixed by level (the 2-cell blinder's cells), else the fixture's gel or default.
// A cell whose element carries none of those shares the fixture's colour. 3D only: the 2D dispatch
// answers one colour per fixture, which the aggregate arms above still mirror.

/** Warm and cold white, as a two-white cell mixes them. */
const WARM_WHITE = new Color('#ffb46b')
const COLD_WHITE = new Color('#e4ecff')
const NEUTRAL_WHITE = new Color('#ffffff')
const CELL_COLOR = new Color()

interface CellSource {
  colour: ColourPropertyDescriptor | undefined
  setting: SettingPropertyDescriptor | undefined
  dimmer: SliderPropertyDescriptor | undefined
  /** White or amber sliders: each with the colour it adds. */
  whites: Array<{ slider: SliderPropertyDescriptor; colour: Color }>
}

function whiteColourOf(p: SliderPropertyDescriptor): Color {
  if (p.category === 'amber') return new Color('#ffbf00')
  const words = `${p.name} ${p.displayName}`.toLowerCase()
  if (words.includes('warm')) return WARM_WHITE
  if (words.includes('cold') || words.includes('cool')) return COLD_WHITE
  return NEUTRAL_WHITE
}

function cellSourceOf(element: ElementDescriptor | undefined): CellSource {
  const props = element?.properties ?? []
  const cs = findColourSource(props)
  return {
    colour: cs?.type === 'colour' ? cs.property : undefined,
    setting: cs?.type === 'setting' ? cs.property : undefined,
    dimmer: findDimmerProperty(props),
    whites: props
      .filter((p): p is SliderPropertyDescriptor => p.type === 'slider' && (p.category === 'white' || p.category === 'amber'))
      .map((slider) => ({ slider, colour: whiteColourOf(slider) })),
  }
}

/**
 * One cell's hue (into [out], full brightness) and its linear level, from its element — exported
 * for the test, which pins that the Liteobar's three cells take their own elements' colours.
 */
export function resolveCellColour(
  src: CellSource,
  fallback: Color,
  source: ChannelSource,
  out: Color,
  timeS = 0,
): number {
  let level = 1
  if (src.colour) {
    const c = src.colour
    const r = getChannelValue(c.redChannel, source)
    const g = getChannelValue(c.greenChannel, source)
    const b = getChannelValue(c.blueChannel, source)
    const w = c.whiteChannel ? getChannelValue(c.whiteChannel, source) : undefined
    const a = c.amberChannel ? getChannelValue(c.amberChannel, source) : undefined
    const uv = c.uvChannel ? getChannelValue(c.uvChannel, source) : undefined
    const hue = computeNormalizedHue(r, g, b, w, a, uv)
    out.set(`rgb(${hue.r}, ${hue.g}, ${hue.b})`)
    level = colourFactor(r, g, b, w, a, uv)
  } else if (src.setting) {
    // The fixture dispatch's band rule, per cell (`lib/colourBands.ts`).
    const colour = settingColourAt(src.setting.options, getChannelValue(src.setting.channel, source), timeS)
    out.set(sourceBandColour(colour))
    level = sourceBandLevel(colour)
  } else if (src.whites.length > 0) {
    // Mixed by level: each white adds its colour in proportion, and the cell is as bright as its
    // brightest white.
    let total = 0
    let peak = 0
    out.setRGB(0, 0, 0)
    for (const { slider, colour } of src.whites) {
      const v = getChannelValue(slider.channel, source) / 255
      total += v
      if (v > peak) peak = v
      out.r += colour.r * v
      out.g += colour.g * v
      out.b += colour.b * v
    }
    if (total > 0) out.multiplyScalar(1 / total)
    else out.copy(NEUTRAL_WHITE)
    level = peak
  } else {
    out.copy(fallback)
  }
  if (src.dimmer) level *= Math.max(0, Math.min(1, getChannelValue(src.dimmer.channel, source) / 255))
  return Math.max(0, Math.min(1, level))
}

/** Exported for the unit test, as [ColourSync] is: it cannot be rendered inside `FixtureModel` there. */
export function CellColourSync({
  elements,
  cells,
  dimmerProp,
  strobes = NO_STROBES,
  fallbackHex,
  lensRef,
  cellStateRef,
  ticker = STILL_COLOUR_TICKER,
}: {
  elements: ElementDescriptor[] | undefined
  cells: Cell[]
  dimmerProp: SliderPropertyDescriptor | undefined
  /** The fixture's strobe channels: a master gate on every cell, as its dimmer is. */
  strobes?: readonly StrobeProperty[]
  fallbackHex: string
  lensRef: React.RefObject<LensPainter | null>
  cellStateRef: React.RefObject<CellState | null>
  ticker?: ColourTicker
}) {
  const source = useChannelSource()
  const sources = useMemo(
    () => cells.map((cell) => cellSourceOf(cell.element != null ? elements?.[cell.element] : undefined)),
    [cells, elements],
  )
  const fallback = useMemo(() => new Color(fallbackHex), [fallbackHex])
  const channels = useMemo(() => {
    const cs: ChannelRef[] = []
    for (const src of sources) {
      if (src.colour) {
        cs.push(src.colour.redChannel, src.colour.greenChannel, src.colour.blueChannel)
        if (src.colour.whiteChannel) cs.push(src.colour.whiteChannel)
        if (src.colour.amberChannel) cs.push(src.colour.amberChannel)
        if (src.colour.uvChannel) cs.push(src.colour.uvChannel)
      }
      if (src.setting) cs.push(src.setting.channel)
      if (src.dimmer) cs.push(src.dimmer.channel)
      for (const w of src.whites) cs.push(w.slider.channel)
    }
    if (dimmerProp) cs.push(dimmerProp.channel)
    for (const p of strobes) cs.push(p.channel)
    return cs
  }, [sources, dimmerProp, strobes])

  useLiveColour(
    channels,
    () => {
      const state = cellStateRef.current
      const timeS = ticker.now()
      const master = liveDimmerFactor(dimmerProp, source) * liveStrobeFactor(strobes, source, timeS)
      let animated = strobesAnimate(strobes, source)
      for (let i = 0; i < sources.length; i++) {
        const setting = sources[i].setting
        if (setting && isAnimatedAt(setting.options, getChannelValue(setting.channel, source))) animated = true
        const level = resolveCellColour(sources[i], fallback, source, CELL_COLOR, timeS) * master
        lensRef.current?.(i, CELL_COLOR, level)
        if (state && i < state.count) {
          state.colors[i * 3] = CELL_COLOR.r
          state.colors[i * 3 + 1] = CELL_COLOR.g
          state.colors[i * 3 + 2] = CELL_COLOR.b
          // One beam per cell, each 1/√count of the fixture's, as the prototype weighs them.
          state.cone[i] = (CONE_SCALE * level) / Math.sqrt(state.count)
          state.pool[i] = POOL_SCALE * level
        }
      }
      return animated
    },
    source,
    ticker,
  )
  return null
}
