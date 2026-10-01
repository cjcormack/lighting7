import type { FixtureKind } from '../store/fixtures'

/**
 * The **lantern library** and a lantern's **focus** (stage-view plan session 7): the desk's
 * catalogue of conventionals (`GET /lanterns`, backend `fixture/lantern/LanternLibrary.kt`), and the
 * seven fields a generic dimmer's patch — and each of its extra placements — carries to say which
 * lantern it is and how it was focused. Pure, so the rules the form, the Focus card and the body
 * read agree, and are pinned by a node test.
 */

export type LanternFamily = 'PROFILE' | 'FRESNEL' | 'PC' | 'PAR' | 'FLOOD' | 'CYC' | 'DOWNLIGHT'

/** The archetypes a conventional is built as (a subset of `bodies/archetype.ts`'s `Archetype`). */
export type LanternArchetype = 'profile' | 'boxProfile' | 'fresnel' | 'par' | 'flood' | 'downlight'

export interface Lantern {
  id: string
  name: string
  maker: string
  family: LanternFamily
  archetype: LanternArchetype
  watts?: number | null
  beamDeg?: number | null
  /** The field angle — on a lantern with a [zoom], the one drawn until a focus sets its own. */
  fieldDeg: number
  zoom?: { minDeg: number; maxDeg: number } | null
  /** A PAR lamp's oval field: [wideDeg] is [fieldDeg], [narrowDeg] across it. */
  oval?: { wideDeg: number; narrowDeg: number } | null
  lensDiameterM: number
  frameSizeM?: number | null
  lengthM: number
  widthM: number
  heightM: number
  accessories?: { shutters?: boolean; barnDoors?: boolean; iris?: boolean; colourFrame?: boolean }
  /** The kinds a generic dimmer that names no lantern is drawn as this one for. */
  defaultFor?: FixtureKind[]
  discontinued?: boolean
  source?: string
}

/** The kind a patch hung with a lantern of each family is — mirrors `LanternFamily.kind`. */
export const LANTERN_FAMILY_KIND: Readonly<Record<LanternFamily, FixtureKind>> = {
  PROFILE: 'PROFILE',
  FRESNEL: 'FRESNEL',
  PC: 'FRESNEL',
  PAR: 'PAR',
  FLOOD: 'WASH',
  CYC: 'WASH',
  DOWNLIGHT: 'GENERIC',
}

export const LANTERN_FAMILY_LABEL: Readonly<Record<LanternFamily, string>> = {
  PROFILE: 'Profiles',
  FRESNEL: 'Fresnels',
  PC: 'PCs',
  PAR: 'PARs',
  FLOOD: 'Floods',
  CYC: 'Cyc floods',
  DOWNLIGHT: 'House',
}

/** The library, indexed: by id, and each kind's default. */
export interface LanternIndex {
  byId: ReadonlyMap<string, Lantern>
  defaults: ReadonlyMap<FixtureKind, Lantern>
  all: readonly Lantern[]
}

export const EMPTY_LANTERNS: LanternIndex = { byId: new Map(), defaults: new Map(), all: [] }

export function indexLanterns(list: readonly Lantern[] | undefined): LanternIndex {
  if (!list || list.length === 0) return EMPTY_LANTERNS
  const byId = new Map<string, Lantern>()
  const defaults = new Map<FixtureKind, Lantern>()
  for (const l of list) {
    byId.set(l.id, l)
    for (const k of l.defaultFor ?? []) if (!defaults.has(k)) defaults.set(k, l)
  }
  return { byId, defaults, all: list }
}

/**
 * The lantern a unit is drawn as: the one it names, else its kind's default — as the desk's
 * `LanternLibrary.effective` answers. An id the library does not hold (an archive from a newer
 * desk) is drawn as if it named none.
 */
export function effectiveLantern(
  index: LanternIndex,
  lanternType: string | null | undefined,
  kind: FixtureKind,
): Lantern | null {
  return (lanternType ? index.byId.get(lanternType) : undefined) ?? index.defaults.get(kind) ?? null
}

/** One blade, as the wire and the column carry it. */
export interface ShutterBlade {
  /** 0 out, 1 closed: the fraction of the field's diameter it covers, so 0.5 reaches the centre. */
  depth: number
  /** Its turn about the middle of its edge, ±[MAX_BLADE_ANGLE_DEG]. */
  angleDeg: number
}

/** Named for the edge of the light each cuts, not its place in the gate — the wire's order. */
export const BLADE_NAMES = ['top', 'bottom', 'left', 'right'] as const
export const BLADE_LABELS = ['Top', 'Bottom', 'Left', 'Right'] as const
export const MAX_BLADE_ANGLE_DEG = 30

export const OPEN_BLADES: readonly ShutterBlade[] = Object.freeze(
  BLADE_NAMES.map(() => Object.freeze({ depth: 0, angleDeg: 0 })),
)

/** The seven focus fields, on a patch or a placement. All optional: an older desk omits them. */
export interface LanternFocus {
  lanternType?: string | null
  zoomDeg?: number | null
  lampRotationDeg?: number | null
  shutters?: ShutterBlade[] | null
  gateRotationDeg?: number | null
  iris?: number | null
  focusSoftness?: number | null
}

export const FOCUS_KEYS = [
  'lanternType',
  'zoomDeg',
  'lampRotationDeg',
  'shutters',
  'gateRotationDeg',
  'iris',
  'focusSoftness',
] as const

/** Every focus field of [f], nulls for the absent — what a whole placement or a form sends. */
export function focusFields(f: LanternFocus | null | undefined): Required<LanternFocus> {
  return {
    lanternType: f?.lanternType ?? null,
    zoomDeg: f?.zoomDeg ?? null,
    lampRotationDeg: f?.lampRotationDeg ?? null,
    shutters: f?.shutters ?? null,
    gateRotationDeg: f?.gateRotationDeg ?? null,
    iris: f?.iris ?? null,
    focusSoftness: f?.focusSoftness ?? null,
  }
}

/** Which parts of the focus a lantern can take — what the Focus card and the form offer. */
export interface FocusFeatures {
  blades: 'shutters' | 'barnDoors' | null
  iris: boolean
  zoom: { minDeg: number; maxDeg: number } | null
  oval: boolean
  /** A focus knob: profiles, fresnels and PCs. A PAR, a flood and a downlight have none. */
  softness: boolean
}

export function focusFeatures(lantern: Lantern | null): FocusFeatures {
  if (!lantern) return { blades: null, iris: false, zoom: null, oval: false, softness: false }
  const a = lantern.accessories ?? {}
  return {
    blades: a.shutters ? 'shutters' : a.barnDoors ? 'barnDoors' : null,
    iris: a.iris === true,
    zoom: lantern.zoom ?? null,
    oval: lantern.oval != null,
    softness: lantern.family === 'PROFILE' || lantern.family === 'FRESNEL' || lantern.family === 'PC',
  }
}

/**
 * A zoom the lantern can take: [zoomDeg] clamped into its range, or null where it has none (or
 * none was set). The form sends this, so a lantern change never writes a zoom the desk refuses.
 */
export function zoomFor(lantern: Lantern | null, zoomDeg: number | null | undefined): number | null {
  if (zoomDeg == null || !Number.isFinite(zoomDeg) || !lantern?.zoom) return null
  return Math.min(lantern.zoom.maxDeg, Math.max(lantern.zoom.minDeg, zoomDeg))
}

/** The field the lantern is drawn at: its zoom where it has one and one is set, else its own. */
export function lanternFieldDeg(lantern: Lantern, zoomDeg: number | null | undefined): number {
  return zoomFor(lantern, zoomDeg) ?? lantern.fieldDeg
}

/**
 * The edge softness the focus knob sets, 0 hard to 1 feathered, within the family's range — a
 * fresnel at its sharpest is still softer than a profile at its softest. Null knob (or a lantern
 * without one) answers null, and the body keeps its family's own edge.
 */
const SOFT_RANGE: Partial<Record<LanternFamily, readonly [number, number]>> = {
  PROFILE: [0.04, 0.9],
  PC: [0.3, 0.85],
  FRESNEL: [0.6, 1],
}

export function softnessForFocus(lantern: Lantern | null, focusSoftness: number | null | undefined): number | null {
  if (lantern == null || focusSoftness == null || !Number.isFinite(focusSoftness)) return null
  const range = SOFT_RANGE[lantern.family]
  if (!range) return null
  const t = Math.min(1, Math.max(0, focusSoftness))
  return range[0] + (range[1] - range[0]) * t
}

/** The lanterns grouped by family, in the library's order — for a picker's option groups. */
export function lanternsByFamily(index: LanternIndex): Array<[LanternFamily, Lantern[]]> {
  const groups = new Map<LanternFamily, Lantern[]>()
  for (const l of index.all) {
    const list = groups.get(l.family) ?? []
    list.push(l)
    groups.set(l.family, list)
  }
  return [...groups.entries()]
}

/** A lantern's field, as a one-line summary: "19°", "zoom 25–50°", "oval 44×21°". */
export function lanternFieldLabel(l: Lantern): string {
  if (l.zoom) return `zoom ${fmt(l.zoom.minDeg)}–${fmt(l.zoom.maxDeg)}°`
  if (l.oval) return `oval ${fmt(l.oval.wideDeg)}×${fmt(l.oval.narrowDeg)}°`
  return `${fmt(l.fieldDeg)}°`
}

function fmt(v: number): string {
  return Number.isInteger(v) ? String(v) : v.toFixed(1)
}

/**
 * The focus in a phrase, for a summary line — *Top 28% · iris 60%*, or *open*. What the patch
 * editor's Lantern box and each placement show before the card is opened.
 */
export function focusSummary(lantern: Lantern | null, focus: LanternFocus): string {
  const features = focusFeatures(lantern)
  const parts: string[] = []
  if (features.blades && focus.shutters?.length === 4) {
    focus.shutters.forEach((b, i) => {
      if (b.depth > 0) parts.push(`${BLADE_LABELS[i]} ${Math.round(b.depth * 100)}%`)
    })
  }
  if (features.blades && focus.gateRotationDeg) parts.push(`turned ${fmt(focus.gateRotationDeg)}°`)
  if (features.iris && focus.iris != null && focus.iris < 1) parts.push(`iris ${Math.round(focus.iris * 100)}%`)
  if (features.softness && focus.focusSoftness != null) {
    parts.push(focus.focusSoftness < 0.2 ? 'sharp' : focus.focusSoftness > 0.7 ? 'soft' : 'medium focus')
  }
  if (features.zoom && focus.zoomDeg != null) parts.push(`${fmt(focus.zoomDeg)}°`)
  if (features.oval && focus.lampRotationDeg) parts.push(`lamp ${fmt(focus.lampRotationDeg)}°`)
  return parts.length === 0 ? 'open' : parts.join(' · ')
}

/**
 * A fixture's lanterns in a phrase, for the patch list: one name, *Name ×2* for a pair of one type,
 * or the distinct names joined — *Source Four 19° + Strand Cantata F*.
 */
export function lanternListLabel(names: readonly string[]): string {
  if (names.length === 0) return ''
  const distinct = [...new Set(names)]
  if (distinct.length === 1) return names.length > 1 ? `${distinct[0]} ×${names.length}` : distinct[0]
  return distinct.join(' + ')
}
