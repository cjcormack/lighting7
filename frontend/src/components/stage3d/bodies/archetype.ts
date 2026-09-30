import type { FixturePatch } from '../../../api/patchApi'
import {
  findColourSource,
  findDimmerProperty,
  findTiltProperty,
  resolveFixtureKind,
  type ElementDescriptor,
  type Fixture,
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
 * **Where the archetype comes from today.** Session 7 gives a generic dimmer a lantern from the
 * library and `@FixtureType` a `body` descriptor; until then the desk knows a fixture's kind (the
 * patch's `kindOverride` over the type's `kind`), its type key and model, whether it tilts, its
 * elements and its dimensions, and that is all this reads:
 *
 * - a tilt axis, or kind `MOVING_HEAD` / `SCANNER`, is a **mover** — a Source Four Revolution is a
 *   `PROFILE` that tilts, so it is a mover with a profile head. The head is a spot, a wash or a
 *   profile by the type key and model's words, else by the type's beam edge; a mover with several
 *   coloured elements is a bar of heads;
 * - a Twin Shot is the **cannon**;
 * - otherwise by kind: `PROFILE` a profile barrel (a box profile where the type's name says
 *   Cantata or Prelude — none does today), `FRESNEL` a fresnel, `PAR` a can, `WASH` a flood,
 *   `STRIP` a batten (a **tape** where the type takes a per-install length), `BLINDER` a blinder,
 *   `LASER` and `EFFECT` an effect box, `GENERIC` a house downlight.
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
  /** Field angle when neither the patch nor a zoom channel gives one. */
  fieldDeg: number
  /** Edge softness from the family: profiles and spots hard (low), everything else soft. */
  softness: number
  accessories: {
    /** Shutter handles on the gate — drawn, and the frame session 7's blades will cut in. */
    shutters: boolean
    barnDoors: boolean
    colourFrame: boolean
  }
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

/** Field angle by family, for a fixture whose patch and channels say nothing. */
const FIELD_DEG: Readonly<Record<Archetype | `mover:${MoverHead}`, number>> = {
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

/** The facts the body is chosen from — every one of them something the desk sends today. */
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

/** Read a fixture as the facts a body is chosen from. */
export function bodyInputFor(
  patch: Pick<FixturePatch, 'kindOverride'>,
  fixture: Fixture | undefined,
  fixtureType: FixtureTypeInfo | undefined,
  lengthM: number | null,
): BodyInput {
  return {
    kind: resolveFixtureKind(patch.kindOverride, fixtureType?.kind),
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
  }
}

/** The archetype and, for a mover, its head. */
export function archetypeFor(input: BodyInput): { archetype: Archetype; head: MoverHead | null } {
  const words = `${input.typeKey} ${input.typeName}`
  if (/twin-shot|twin shot/i.test(words)) return { archetype: 'cannon', head: null }
  if (input.hasTilt || input.kind === 'MOVING_HEAD' || input.kind === 'SCANNER') {
    let head: MoverHead
    if (input.colourElements.length >= 2) head = 'bar'
    else if (input.kind === 'PROFILE') head = 'profile'
    else if (WASH_WORDS.test(words)) head = 'wash'
    else if (SPOT_WORDS.test(words)) head = 'spot'
    else head = input.beamEdge === 'HARD' ? 'spot' : 'wash'
    return { archetype: 'mover', head }
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
  const [dl, dw, dh] = DEFAULT_DIMS[archetype]
  let L = dim(input.lengthM, dl)
  let W = dim(input.widthM, dw)
  let H = dim(input.heightM, dh)
  // A lantern's barrel is its length; its diameter the larger of its other two.
  const D = Math.max(W, H)
  let cells: Cell[] = []
  let emitAxis: 1 | -1 = -1
  let yoke = true
  const accessories = { shutters: false, barnDoors: false, colourFrame: false }

  switch (archetype) {
    case 'profile':
      cells = [disc(-L / 2, D * 0.33)]
      accessories.shutters = true
      accessories.colourFrame = true
      break
    case 'boxProfile':
      cells = [disc(-L / 2, D * 0.3)]
      accessories.shutters = true
      accessories.colourFrame = true
      break
    case 'fresnel':
      cells = [disc(-L / 2, D * 0.34)]
      accessories.barnDoors = true
      accessories.colourFrame = true
      break
    case 'par':
      cells = [disc(-L / 2, D * 0.42)]
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
      cells = [disc(-L / 2, D * 0.4)]
      yoke = false
      break
    case 'mover': {
      emitAxis = 1
      // A mover's size is W across the yoke and H from its base to the top of its head.
      const headDia = W * (head === 'wash' ? 0.78 : 0.6)
      const headLen = H * (head === 'wash' ? 0.34 : head === 'bar' ? 0.2 : 0.52)
      const front = headLen / 2
      if (head === 'bar') {
        // A bar of heads: a row across the yoke, all tilting together until session 7's
        // independent heads (FU-STAGE-INDEPENDENT-HEADS).
        cells = cellRow(input.colourElements, W * 0.9, front, 'disc', 0, headLen * 0.4)
      } else {
        const r = headDia * (head === 'wash' ? 0.42 : head === 'profile' ? 0.33 : 0.28)
        cells = [disc(front, r)]
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
        : input.acceptsBeamAngle

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
    fieldDeg: FIELD_DEG[family],
    softness: SOFTNESS[family],
    accessories,
  }
  return { ...spec, key: specKey(spec) }
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
