import type { FixturePatch } from '../../../api/patchApi'
import {
  effectiveLantern,
  EMPTY_LANTERNS,
  focusFeatures,
  LANTERN_FAMILY_KIND,
  lanternFieldDeg,
  softnessForFocus,
  type Lantern,
  type LanternFocus,
  type LanternIndex,
  type ShutterBlade,
} from '../../../lib/lanterns'
import {
  findColourSource,
  findDimmerProperty,
  findTiltProperty,
  resolveFixtureKind,
  type ElementDescriptor,
  type Fixture,
  type FixtureBodyInfo,
  type FixtureKind,
  type FixtureTypeInfo,
} from '../../../store/fixtures'
import { MAX_CELLS, MAX_LIGHTS_PER_FIXTURE } from '../emitterLayout'

export { MAX_CELLS, MAX_LIGHTS_PER_FIXTURE }

/**
 * What a fixture body is built from (stage-view plan session 6, the design record's
 * §"Fixture bodies and the lantern library" items 3, 8 and 9): one of the parametric
 * **archetypes**, sized by the type's own dimensions, with its **cells** — the apertures a beam
 * leaves. Pure, so the archetype a rig draws and the cells it lights are pinned by a node test,
 * and so `emitterNeedsFor` (which sizes the shared emitters) and `FixtureModel` (which draws) read
 * one statement of it.
 *
 * **Where the archetype comes from** (stage-view plan session 7), first match wins:
 *
 * 1. **The lantern.** A type that takes one (`acceptsLantern` — a generic dimmer) is drawn as the
 *    lantern it names from the library (`GET /lanterns`), else the library's default for its kind
 *    (a PROFILE a Source Four 19°, a FRESNEL a Cantata F, a PAR a Par 64 CP62, a GENERIC a house
 *    downlight). Its size, lens, field, zoom, oval and accessories are the lantern's, and its focus
 *    — zoom, the focus knob, blades, gate, iris, a PAR's lamp turn — is the patch's (or the
 *    placement's) own.
 * 2. **The type's declared body** (`@FixtureType.body`): its archetype, and a mover's head.
 * 3. **The words**, as before the library existed: a tilt axis, or kind `MOVING_HEAD` / `SCANNER`,
 *    is a **mover** — a Source Four Revolution is a `PROFILE` that tilts — whose head is a spot, a
 *    wash or a profile by the type key and model's words, else by the type's beam edge, and a mover
 *    with several coloured elements is a bar of heads; a Twin Shot is the **cannon**; otherwise by
 *    kind: `PROFILE` a profile barrel (a box profile where the type's name says Cantata or Prelude),
 *    `FRESNEL` a fresnel, `PAR` a can, `WASH` a flood, `STRIP` a batten (a **tape** where the type
 *    takes a per-install length), `BLINDER` a blinder, `LASER` and `EFFECT` an effect box,
 *    `GENERIC` a house downlight.
 *
 * A declared mover's head it does not name still comes from the words.
 */

export type Archetype =
  | 'profile'
  | 'boxProfile'
  | 'fresnel'
  | 'par'
  | 'flood'
  | 'downlight'
  | 'mover'
  | 'batten'
  | 'blinder'
  | 'effect'
  | 'cannon'
  | 'tape'

export type MoverHead = 'spot' | 'wash' | 'profile' | 'bar'

export type CellShape = 'disc' | 'segment'

/**
 * One aperture — a lens, or an LED cell's face — in the **head's** frame: the pivot at the origin,
 * the beam along the head's Y (towards [BodySpec.emitAxis]), the long axis of a bar along X.
 */
export interface Cell {
  x: number
  y: number
  z: number
  shape: CellShape
  /** A disc's radius; a segment's half-width along the head's X. */
  halfWidthM: number
  /** A segment's half-depth along the head's Z; a disc's radius. */
  halfDepthM: number
  /**
   * The element this cell takes its colour and level from — an index into `fixture.elements` —
   * or null for a cell that shares the fixture's own colour (every single-lens body).
   */
  element: number | null
}

export interface BodySpec {
  archetype: Archetype
  /** A mover's head; null for everything else. */
  head: MoverHead | null
  /**
   * Which way the head emits along its own Y: −1 for a static lantern, whose lens faces down its
   * barrel at rest, +1 for a mover, whose beam runs up the body axis at DMX mid-travel.
   */
  emitAxis: 1 | -1
  /** Whether the body stands in a yoke that pans and a head that tilts in it. */
  yoke: boolean
  /** The body's bounding size, metres: the type's own, else the archetype's. */
  lengthM: number
  widthM: number
  heightM: number
  cells: Cell[]
  /** Whether its cells throw beams and light at all. */
  emits: boolean
  /**
   * Field angle when neither a zoom channel nor the patch gives one: a lantern's, else the type's
   * fixed lens (`@FixtureType.fieldDeg`), else the family's. [resolveBeamDeg] is the whole order.
   */
  fieldDeg: number
  /** Edge softness from the family: profiles and spots hard (low), everything else soft. */
  softness: number
  /**
   * How fast the edge goes soft away from the focal plane, for a fixture with a focus channel: the
   * blur in field radii per unit of relative focus error (`beamMask.ts`'s `focusBlur`). The type's
   * declared `depthOfField`, else the family's ([DEPTH_OF_FIELD]).
   */
  depthOfField: number
  accessories: {
    /** Shutter handles on the gate — drawn, and the frame the blades cut in. */
    shutters: boolean
    barnDoors: boolean
    colourFrame: boolean
  }
  /** The lantern it is drawn as, for a type hung with one; null otherwise. */
  lantern: Lantern | null
  /**
   * An oval beam's narrow axis over its wide one, as the tangents of their half-angles — a PAR
   * lamp's (`Lantern.oval`); null for a round beam. The field angle is the wide axis's.
   */
  ovalRatio: number | null
  /**
   * How far the beam's frame is turned about its axis, degrees: the gate's turn where the lantern
   * has blades, plus the lamp's where it has an oval. The blades and the oval turn with it.
   */
  frameTurnDeg: number
  /** The four blades where the lantern has shutters or barn doors and one is in; null otherwise. */
  blades: ShutterBlade[] | null
  /** The iris's open fraction where the lantern has one, else 1. A DMX iris closes it further. */
  iris: number
  /**
   * Everything the geometry is built from, spelled out: two specs with one key share every
   * instanced part (`StageBodies`), and a body rebuilds only when it changes.
   */
  key: string
}


/**
 * Edge softness by family, 0 (a profile's hard gate edge) to 1 (a flood's feathered one) —
 * GDTF's split: profiles and spots hard; fresnels, PCs, PARs, floods, LED washes and battens soft.
 */
export const SOFTNESS: Readonly<Record<Archetype | `mover:${MoverHead}`, number>> = {
  profile: 0.1,
  boxProfile: 0.12,
  fresnel: 0.85,
  par: 0.7,
  flood: 0.95,
  downlight: 0.9,
  mover: 0.5,
  'mover:spot': 0.2,
  'mover:wash': 0.85,
  'mover:profile': 0.12,
  'mover:bar': 0.35,
  batten: 0.9,
  blinder: 0.9,
  effect: 0.6,
  cannon: 0.6,
  tape: 0.9,
}

/**
 * Depth of field by family (fixture-optics plan D9): the blur, in field radii, per unit of relative
 * focus error `|f − d| / f`, for a type that declares none. Only a fixture with a FOCUS channel is
 * drawn by it — every type in the library that has one is a profile or a spot — so the rest carry a
 * middling value that nothing reads today.
 *
 * Estimate (D15): judged, not measured — no datasheet gives a depth of field. The profile family's
 * 3 was tuned by eye in Chromium (software GL) on a Source Four Revolution (`mover:profile`, the
 * 2–40 m range) throwing 24 m at a back wall — the scene `profileHarness.ts`'s
 * `?profileHarness=focus` builds — with focus at DMX 243, 245, 246, 247 and 249 (21.1, 23.0, 24.0,
 * 25.1 and 27.6 m): sharp on the wall (blur 0), a little soft one DMX step either side (blur about
 * 0.13, an edge roll-off of 0.1 field radii) and clearly soft 3 m either side (blur about 0.4). At 2
 * a step either side was hard to tell from the wall, so the sharpest step was hard to find; at 4 a
 * single step already looked as soft as 3 m should, and 3 m off lost the edge altogether. Checked on
 * the rig by `FU-MANUAL-S4REV-OPTICS` step 8 and the plan's §9 desk check.
 */
export const DEPTH_OF_FIELD: Readonly<Record<Archetype | `mover:${MoverHead}`, number>> = {
  // Estimate: tuned against the Revolution at 24 m (above). A profile's long, narrow lens train.
  profile: 3,
  boxProfile: 3,
  'mover:profile': 3,
  // Estimate: a spot's smaller aperture holds a little more depth of focus than a profile's.
  'mover:spot': 2.5,
  mover: 2.5,
  // Estimate: no focus channel in the library; middling values that nothing reads today.
  'mover:wash': 2,
  'mover:bar': 2,
  fresnel: 2,
  par: 2,
  flood: 2,
  downlight: 2,
  batten: 2,
  blinder: 2,
  effect: 2,
  cannon: 2,
  tape: 2,
}

/** Field angle by family, for a fixture whose zoom channel, patch and type say nothing. */
export const FIELD_DEG: Readonly<Record<Archetype | `mover:${MoverHead}`, number>> = {
  profile: 26,
  boxProfile: 26,
  fresnel: 45,
  par: 32,
  flood: 90,
  downlight: 60,
  mover: 20,
  'mover:spot': 16,
  'mover:wash': 25,
  'mover:profile': 19,
  'mover:bar': 8,
  batten: 30,
  blinder: 60,
  effect: 30,
  cannon: 30,
  tape: 90,
}

/** Default size (L × W × H, metres) where the type sends none. L is the long axis. */
const DEFAULT_DIMS: Readonly<Record<Archetype, readonly [number, number, number]>> = {
  profile: [0.6, 0.26, 0.26],
  boxProfile: [0.5, 0.3, 0.3],
  fresnel: [0.38, 0.3, 0.3],
  par: [0.36, 0.23, 0.23],
  flood: [0.24, 0.3, 0.26],
  downlight: [0.16, 0.2, 0.2],
  mover: [0.3, 0.3, 0.45],
  batten: [1.0, 0.1, 0.09],
  blinder: [0.46, 0.24, 0.12],
  effect: [0.25, 0.2, 0.15],
  cannon: [0.4, 0.24, 0.14],
  tape: [1.0, 0.02, 0.012],
}

const BOX_PROFILE_WORDS = /cantata|prelude|harmony/i
const SPOT_WORDS = /spot|beam|mac-250|mac 250|profile/i
const WASH_WORDS = /wash/i

/** The facts the body is chosen from — every one of them something the desk sends. */
export interface BodyInput {
  kind: FixtureKind
  typeKey: string
  /** The type's manufacturer and model, joined; read for the words that pick a head. */
  typeName: string
  hasTilt: boolean
  /** How many of the fixture's elements carry a colour or a level of their own. */
  colourElements: number[]
  acceptsBeamAngle: boolean
  acceptsLength: boolean
  beamEdge: 'HARD' | 'SOFT' | undefined
  lengthM: number | null
  widthM: number | null
  heightM: number | null
  /** The lantern it is hung with (named, else its kind's default), for a type that takes one. */
  lantern?: Lantern | null
  /** The type's declared body; null or absent leaves it to the kind. */
  body?: FixtureBodyInfo | null
  /** The type's declared depth of field (`@FixtureType.depthOfField`); null or absent is the family's. */
  depthOfField?: number | null
  /** The type's fixed lens (`@FixtureType.fieldDeg`); null or absent is the family's. */
  fieldDeg?: number | null
  /** The lantern's focus — the patch's own, or a placement's. */
  focus?: LanternFocus | null
}

/** Whether an element has a colour or a level of its own, so it is drawn as a cell. */
export function elementHasColour(element: ElementDescriptor): boolean {
  const props = element.properties ?? []
  if (findColourSource(props)) return true
  if (findDimmerProperty(props)) return true
  return props.some((p) => p.type === 'slider' && (p.category === 'white' || p.category === 'amber'))
}

/** The indices of `fixture.elements` a body draws as cells. */
export function colourElementsOf(fixture: Fixture | undefined): number[] {
  const elements = fixture?.elements ?? []
  const out: number[] = []
  for (let i = 0; i < elements.length; i++) if (elementHasColour(elements[i])) out.push(i)
  return out
}

/**
 * Read a fixture as the facts a body is chosen from. The kind is the lantern's where it is hung
 * with one — derived, as the desk derives `kindOverride` from it — else the patch's override over
 * the type's (`RigBriefing.isMovingHead` reads it in the same order).
 */
export function bodyInputFor(
  patch: Pick<FixturePatch, 'kindOverride'> & LanternFocus,
  fixture: Fixture | undefined,
  fixtureType: FixtureTypeInfo | undefined,
  lengthM: number | null,
  lanterns: LanternIndex = EMPTY_LANTERNS,
): BodyInput {
  const ownKind = resolveFixtureKind(patch.kindOverride, fixtureType?.kind)
  const lantern = fixtureType?.acceptsLantern === true ? effectiveLantern(lanterns, patch.lanternType, ownKind) : null
  return {
    kind: lantern ? LANTERN_FAMILY_KIND[lantern.family] : ownKind,
    typeKey: fixtureType?.typeKey ?? fixture?.typeKey ?? '',
    typeName: [fixtureType?.manufacturer, fixtureType?.model].filter(Boolean).join(' '),
    hasTilt: findTiltProperty(fixture?.properties) != null,
    colourElements: colourElementsOf(fixture),
    acceptsBeamAngle: fixtureType?.acceptsBeamAngle === true,
    acceptsLength: fixtureType?.acceptsLength === true,
    beamEdge: fixtureType?.beamEdge,
    lengthM,
    widthM: fixtureType?.widthM ?? null,
    heightM: fixtureType?.heightM ?? null,
    lantern,
    body: fixtureType?.body ?? null,
    depthOfField: fixtureType?.depthOfField ?? null,
    fieldDeg: fixtureType?.fieldDeg ?? null,
    focus: patch,
  }
}

const ARCHETYPES: ReadonlySet<string> = new Set<Archetype>([
  'profile', 'boxProfile', 'fresnel', 'par', 'flood', 'downlight', 'mover', 'batten', 'blinder', 'effect', 'cannon', 'tape',
])
const MOVER_HEADS: ReadonlySet<string> = new Set<MoverHead>(['spot', 'wash', 'profile', 'bar'])

/** A mover's head from the words — the fallback when nothing declares one. */
function guessHead(input: BodyInput, words: string): MoverHead {
  if (input.colourElements.length >= 2) return 'bar'
  if (input.kind === 'PROFILE') return 'profile'
  if (WASH_WORDS.test(words)) return 'wash'
  if (SPOT_WORDS.test(words)) return 'spot'
  return input.beamEdge === 'HARD' ? 'spot' : 'wash'
}

/** The archetype and, for a mover, its head: the lantern's, else the type's body, else the words. */
export function archetypeFor(input: BodyInput): { archetype: Archetype; head: MoverHead | null } {
  const words = `${input.typeKey} ${input.typeName}`
  if (input.lantern) return { archetype: input.lantern.archetype, head: null }
  const declared = input.body?.archetype
  if (declared && ARCHETYPES.has(declared)) {
    const archetype = declared as Archetype
    if (archetype !== 'mover') return { archetype, head: null }
    const head = input.body?.head
    return { archetype, head: head && MOVER_HEADS.has(head) ? (head as MoverHead) : guessHead(input, words) }
  }
  if (/twin-shot|twin shot/i.test(words)) return { archetype: 'cannon', head: null }
  if (input.hasTilt || input.kind === 'MOVING_HEAD' || input.kind === 'SCANNER') {
    return { archetype: 'mover', head: guessHead(input, words) }
  }
  switch (input.kind) {
    case 'PROFILE':
      return { archetype: BOX_PROFILE_WORDS.test(words) ? 'boxProfile' : 'profile', head: null }
    case 'FRESNEL':
      return { archetype: 'fresnel', head: null }
    case 'PAR':
      return { archetype: 'par', head: null }
    case 'WASH':
      return { archetype: 'flood', head: null }
    case 'STRIP':
      return { archetype: input.acceptsLength ? 'tape' : 'batten', head: null }
    case 'BLINDER':
      return { archetype: 'blinder', head: null }
    case 'LASER':
    case 'EFFECT':
      return { archetype: 'effect', head: null }
    case 'GENERIC':
    default:
      return { archetype: 'downlight', head: null }
  }
}

/** Positive, finite, else the fallback. */
function dim(v: number | null, fallback: number): number {
  return v != null && Number.isFinite(v) && v > 0 ? v : fallback
}

function round4(v: number): number {
  return Math.round(v * 1e4) / 1e4
}

/**
 * Cells laid evenly along the head's X across `span`, on the face at `y`. One per coloured
 * element, or one sharing the fixture's colour when it has fewer than two.
 */
function cellRow(
  elements: number[],
  span: number,
  y: number,
  shape: CellShape,
  halfDepth: number,
  discRadius: number,
): Cell[] {
  const n = elements.length >= 2 ? Math.min(elements.length, MAX_CELLS) : 1
  const pitch = span / n
  const cells: Cell[] = []
  for (let i = 0; i < n; i++) {
    const x = -span / 2 + pitch * (i + 0.5)
    const hw = shape === 'segment' ? pitch * 0.44 : Math.min(discRadius, pitch * 0.42)
    cells.push({
      x,
      y,
      z: 0,
      shape,
      halfWidthM: hw,
      halfDepthM: shape === 'segment' ? halfDepth : hw,
      element: elements.length >= 2 ? elements[i] : null,
    })
  }
  return cells
}

function disc(y: number, r: number): Cell {
  return { x: 0, y, z: 0, shape: 'disc', halfWidthM: r, halfDepthM: r, element: null }
}

/**
 * The body a fixture is drawn as. The head frame's geometry (where the lens sits, how big it is)
 * is decided here, so the apertures a beam leaves are pinned by the same test as the archetype —
 * `bodyGeometry.ts` builds meshes round these numbers and never moves a lens.
 */
export function bodySpecFor(input: BodyInput): BodySpec {
  const { archetype, head } = archetypeFor(input)
  const lantern = input.lantern ?? null
  const [dl, dw, dh] = DEFAULT_DIMS[archetype]
  let L = dim(lantern?.lengthM ?? input.lengthM, dl)
  let W = dim(lantern?.widthM ?? input.widthM, dw)
  let H = dim(lantern?.heightM ?? input.heightM, dh)
  // A lantern's barrel is its length; its diameter the larger of its other two.
  const D = Math.max(W, H)
  // The lens the lantern (or the type's body) declares, where one does: its radius, else null.
  const declaredLens = lantern?.lensDiameterM ?? input.body?.lensDiameterM ?? null
  const lensR = (fallback: number) =>
    declaredLens != null && Number.isFinite(declaredLens) && declaredLens > 0 ? Math.min(declaredLens / 2, D * 0.49) : fallback
  let cells: Cell[] = []
  let emitAxis: 1 | -1 = -1
  let yoke = true
  const accessories = { shutters: false, barnDoors: false, colourFrame: false }

  switch (archetype) {
    case 'profile':
      cells = [disc(-L / 2, lensR(D * 0.33))]
      accessories.shutters = true
      accessories.colourFrame = true
      break
    case 'boxProfile':
      cells = [disc(-L / 2, lensR(D * 0.3))]
      accessories.shutters = true
      accessories.colourFrame = true
      break
    case 'fresnel':
      cells = [disc(-L / 2, lensR(D * 0.34))]
      accessories.barnDoors = true
      accessories.colourFrame = true
      break
    case 'par':
      cells = [disc(-L / 2, lensR(D * 0.42))]
      accessories.colourFrame = true
      break
    case 'flood':
      cells = [
        {
          x: 0,
          y: -L / 2,
          z: 0,
          shape: 'segment',
          halfWidthM: W * 0.43,
          halfDepthM: H * 0.36,
          element: null,
        },
      ]
      accessories.colourFrame = true
      break
    case 'downlight':
      cells = [disc(-L / 2, lensR(D * 0.4))]
      yoke = false
      break
    case 'mover': {
      emitAxis = 1
      // A mover's size is W across the yoke and H from its base to the top of its head. The lens's
      // place on the head's face (`front`) is also the desk's `MoverLens`, which measures a focus
      // from it — `focusInverse.fixture.json`'s `heads` pins both sides.
      const headDia = W * (head === 'wash' ? 0.78 : 0.6)
      const headLen = H * (head === 'wash' ? 0.34 : head === 'bar' ? 0.2 : 0.52)
      const front = headLen / 2
      if (head === 'bar') {
        // A bar of heads: a row across the yoke, all tilting together until
        // FU-STAGE-INDEPENDENT-HEADS gives each head a tilt of its own.
        cells = cellRow(input.colourElements, W * 0.9, front, 'disc', 0, headLen * 0.4)
      } else {
        const r = headDia * (head === 'wash' ? 0.42 : head === 'profile' ? 0.33 : 0.28)
        cells = [disc(front, declaredLens != null && declaredLens > 0 ? Math.min(declaredLens / 2, headDia * 0.49) : r)]
      }
      break
    }
    case 'batten':
      cells = cellRow(input.colourElements, L * 0.94, -H / 2, 'segment', W * 0.31, H * 0.4)
      break
    case 'blinder':
      cells = cellRow(input.colourElements, L * 0.9, -H / 2, 'disc', 0, W * 0.38)
      break
    case 'effect':
      cells = [disc(-H / 2, Math.min(W, L) * 0.18)]
      break
    case 'cannon':
      cells = []
      break
    case 'tape':
      // A strip of LED tape: its whole length is one face, and it glows rather than throws.
      L = dim(input.lengthM, dl)
      W = Math.min(W, 0.03)
      H = Math.min(H, 0.02)
      cells = [
        { x: 0, y: -H / 2, z: 0, shape: 'segment', halfWidthM: L / 2, halfDepthM: W * 0.4, element: null },
      ]
      yoke = false
      break
  }

  const family = archetype === 'mover' && head ? (`mover:${head}` as const) : archetype
  const emits =
    archetype === 'batten' || archetype === 'blinder'
      ? true
      : archetype === 'tape' || archetype === 'cannon'
        ? false
        : input.acceptsBeamAngle || lantern != null

  // A lantern's parts are its own: a PC is a fresnel body without the barn doors, a Par 16 has no
  // colour frame, a profile's iris and shutters are what it can take.
  const focus = input.focus ?? null
  const features = focusFeatures(lantern)
  if (lantern) {
    const a = lantern.accessories ?? {}
    accessories.shutters = a.shutters === true
    accessories.barnDoors = a.barnDoors === true
    accessories.colourFrame = a.colourFrame === true
  }
  const blades =
    features.blades && focus?.shutters?.length === 4 && focus.shutters.some((b) => (b?.depth ?? 0) > 0)
      ? focus.shutters.map((b) => ({ depth: b?.depth ?? 0, angleDeg: b?.angleDeg ?? 0 }))
      : null
  const oval = lantern?.oval ?? null
  const ovalRatio =
    oval && oval.wideDeg > 0 && oval.narrowDeg > 0
      ? Math.tan((Math.min(oval.narrowDeg, oval.wideDeg) * Math.PI) / 360) / Math.tan((oval.wideDeg * Math.PI) / 360)
      : null
  const frameTurnDeg =
    (features.blades ? finite(focus?.gateRotationDeg) : 0) + (ovalRatio != null ? finite(focus?.lampRotationDeg) : 0)
  const iris = features.iris && focus?.iris != null && Number.isFinite(focus.iris) ? Math.min(1, Math.max(0, focus.iris)) : 1
  const softness =
    softnessForFocus(lantern, focus?.focusSoftness) ?? (lantern?.family === 'PC' ? PC_SOFTNESS : SOFTNESS[family])

  const spec: Omit<BodySpec, 'key'> = {
    archetype,
    head,
    emitAxis,
    yoke,
    lengthM: L,
    widthM: W,
    heightM: H,
    cells,
    emits,
    fieldDeg: lantern
      ? lanternFieldDeg(lantern, focus?.zoomDeg)
      : positiveAngle(input.fieldDeg) ?? FIELD_DEG[family],
    softness,
    depthOfField:
      input.depthOfField != null && Number.isFinite(input.depthOfField) && input.depthOfField > 0
        ? input.depthOfField
        : DEPTH_OF_FIELD[family],
    accessories,
    lantern,
    ovalRatio,
    frameTurnDeg,
    blades,
    iris,
  }
  return { ...spec, key: specKey(spec) }
}

/** A usable full beam angle in degrees, or null. */
function positiveAngle(deg: number | null | undefined): number | null {
  return deg != null && Number.isFinite(deg) && deg > 0 && deg < 180 ? deg : null
}

/**
 * The beam angle a fixture draws this frame (fixture-optics plan D3), one precedence for every
 * fixture: its **zoom channel** (a slider's `degMin`/`degMax`, a stepped zoom's band's `zoomDeg` —
 * `resolveZoomDeg`), else the **patch's** `beamAngleDeg`, else the spec's [BodySpec.fieldDeg] — which
 * is a lantern's field, else the **type's** fixed lens (`@FixtureType.fieldDeg`), else the
 * **family's** default. The zoom wins over the patch because it is live: a patched angle is what a
 * head with no zoom channel is set to, and a zoom head's channel is where its angle is.
 */
export function resolveBeamDeg(
  zoomDeg: number | null,
  patchBeamAngleDeg: number | null | undefined,
  spec: Pick<BodySpec, 'fieldDeg'>,
): number {
  return zoomDeg ?? positiveAngle(patchBeamAngleDeg) ?? spec.fieldDeg
}

/** A PC's edge: between a profile's and a fresnel's (the prototype's `SOFT.PC`). */
const PC_SOFTNESS = 0.55

function finite(v: number | null | undefined): number {
  return v != null && Number.isFinite(v) ? v : 0
}

function specKey(spec: Omit<BodySpec, 'key'>): string {
  const cells = spec.cells
    .map((c) => `${c.shape[0]}${round4(c.x)},${round4(c.y)},${round4(c.halfWidthM)},${round4(c.halfDepthM)}`)
    .join(';')
  const acc = `${+spec.accessories.shutters}${+spec.accessories.barnDoors}${+spec.accessories.colourFrame}`
  return `${spec.archetype}:${spec.head ?? '-'}:${round4(spec.lengthM)}x${round4(spec.widthM)}x${round4(spec.heightM)}:${acc}:${cells}`
}

/**
 * How far behind an aperture a beam's apex sits: the aperture's radius (a segment's half-width)
 * over the tangent of the half-field. For a lantern that is where its lamp is — about 0.5 m behind
 * a 19° Source Four's 170 mm lens — and for a wide LED face it is far behind, so the beam leaves
 * as a column rather than from a point. Floored so a degenerate field cannot put it at infinity.
 */
export function apexDistanceM(apertureRadiusM: number, fieldDeg: number): number {
  const half = (Math.max(0.5, Math.min(170, fieldDeg)) * Math.PI) / 360
  return Math.max(0, apertureRadiusM) / Math.tan(half)
}

/**
 * The runs of cells that share one light on the surfaces: every cell while there are at most
 * [cap], else [cap] contiguous runs as even as the count allows — so a 12-pixel bar takes four of
 * the surface shader's lights, not twelve. `[start, end)` pairs, in cell order.
 */
export function lightRuns(cellCount: number, cap = 4): Array<[number, number]> {
  const n = Math.max(0, Math.floor(cellCount))
  const k = Math.min(n, Math.max(1, Math.floor(cap)))
  const runs: Array<[number, number]> = []
  for (let j = 0; j < k; j++) runs.push([Math.floor((j * n) / k), Math.floor(((j + 1) * n) / k)])
  return runs
}
