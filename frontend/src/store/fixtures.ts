import { restApi } from "./restApi"
import { useWithoutInfrastructure } from "@/lib/infrastructure"
import { lightingApi } from "../api/lightingApi"
import { store } from "./index"
import type { GroupColourPropertyDescriptor, GroupPropertyDescriptor } from "../api/groupsApi"
import type { Lantern } from "../lib/lanterns"

// `GroupList` rides along because `GET /groups` reads the same runtime register the
// `fixturesChanged` frame announces: groups only ever change inside `Fixtures.register {}`
// (patch CRUD, patch-group edits, riggings, universe configs, a project switch), and that
// block fires `fixturesChanged()` as its last act. Without this tag a second client's group
// list stayed on whatever it fetched at connect — the freshness gap the deleted groups WS
// layer was supposed to close and never did.
lightingApi.fixtures.subscribe(function() {
  store.dispatch(restApi.util.invalidateTags(['Fixture', 'GroupList']))
})

export const fixturesApi = restApi.injectEndpoints({
  endpoints: (build) => {
    return {
      fixtureList: build.query<Array<Fixture>, void>({
        query: () => {
          return 'fixtures'
        },
        providesTags: ['Fixture'],
      }),
      // A single-fixture read stood here. Every surface works from `fixtureList` — it is one
      // request for the whole rig and the grid needs all of it anyway — so nothing fetched one.
      fixtureTypeList: build.query<Array<FixtureTypeInfo>, void>({
        query: () => {
          return 'fixture-types'
        },
        providesTags: ['Fixture'],
      }),
      // The lantern library (stage-view plan session 7): the desk's, not a show's, and fixed for
      // the life of the desk process — so it is read once and kept.
      lanternList: build.query<Array<Lantern>, void>({
        query: () => 'lanterns',
        keepUnusedDataFor: 3600,
      }),
    }
  },
  overrideExisting: false,
})

export const {
  useFixtureListQuery, useFixtureTypeListQuery, useLanternListQuery,
} = fixturesApi

export const FIXTURE_KINDS = [
  'MOVING_HEAD',
  'SCANNER',
  'PROFILE',
  'FRESNEL',
  'PAR',
  'WASH',
  'STRIP',
  'LASER',
  'BLINDER',
  'EFFECT',
  'GENERIC',
] as const

export type FixtureKind = (typeof FIXTURE_KINDS)[number]

/**
 * Human-readable labels for each kind, kept next to the type so adding a new
 * kind surfaces a TS error here (missing key) rather than at the UI consumer.
 */
export const FIXTURE_KIND_LABEL: Record<FixtureKind, string> = {
  MOVING_HEAD: 'Moving head',
  SCANNER: 'Scanner',
  PROFILE: 'Profile',
  FRESNEL: 'Fresnel',
  PAR: 'PAR',
  WASH: 'Wash',
  STRIP: 'Strip / bar',
  LASER: 'Laser',
  BLINDER: 'Blinder',
  EFFECT: 'Effect (fog, hazer, …)',
  GENERIC: 'Generic',
}

const FIXTURE_KIND_SET = new Set<string>(FIXTURE_KINDS)

export function isFixtureKind(value: unknown): value is FixtureKind {
  return typeof value === 'string' && FIXTURE_KIND_SET.has(value)
}

/**
 * Render-time kind selection: per-patch override wins, falling back to the
 * fixture type's declared kind, then GENERIC. Unknown strings on either input
 * (older backend, hand-edited JSON) fall through to GENERIC.
 */
export function resolveFixtureKind(
  override: string | null | undefined,
  typeKind: string | null | undefined,
): FixtureKind {
  if (isFixtureKind(override)) return override
  if (isFixtureKind(typeKind)) return typeKind
  return 'GENERIC'
}

/** How a fixture's beam is drawn on the stage view (mirrors backend BeamShape). */
export type BeamShape = 'NONE' | 'ROUND' | 'LINEAR'
/** Beam edge hardness (mirrors backend BeamEdge). */
export type BeamEdge = 'HARD' | 'SOFT'

export type FixtureTypeInfo = {
  typeKey: string
  manufacturer: string | null
  model: string | null
  modeName: string | null
  channelCount: number | null
  isRegistered: boolean
  capabilities: string[]
  properties: PropertyDescriptor[]
  elementGroupProperties: GroupPropertyDescriptor[] | null
  acceptsBeamAngle?: boolean
  acceptsGel?: boolean
  gelCompactDisplay?: CompactDisplayRole | null
  kind?: FixtureKind
  /** Physical bounding size in metres; `lengthM` is the long axis. Optional so
   *  older /types payloads still typecheck. */
  lengthM?: number | null
  widthM?: number | null
  heightM?: number | null
  /** The unit's length is set per install (a lightstrip cut to its run) rather than by the model:
   *  a patch may carry its own `lengthM`, and `lengthM` above is only the default drawn until it
   *  does. Every other type's length is fixed, and the desk refuses a stored one. Optional so an
   *  older desk reads as "fixed". */
  acceptsLength?: boolean
  beamShape?: BeamShape
  beamEdge?: BeamEdge
  /** The body the type declares (`@FixtureType.body`, stage-view plan session 7): an archetype, a
   *  mover's head, a lens diameter. Null or absent leaves it to the kind, as it always was. */
  body?: FixtureBodyInfo | null
  /** A patch of this type names a lantern from `GET /lanterns` and carries its focus — a generic
   *  dimmer. Optional so an older desk reads as "no". */
  acceptsLantern?: boolean
}

/** A declared body on the wire (backend `FixtureBodyInfo`). */
export interface FixtureBodyInfo {
  archetype: string
  head?: string | null
  lensDiameterM?: number | null
}

// Channel reference for property descriptors
export type ChannelRef = {
  universe: number
  channelNo: number
}

// Property descriptor types (discriminated union)
export type PropertyDescriptor =
  | SliderPropertyDescriptor
  | ColourPropertyDescriptor
  | PositionPropertyDescriptor
  | SettingPropertyDescriptor
  | TriggerPropertyDescriptor

export type PropertyCategory =
  | 'dimmer'
  | 'colour'
  | 'pan'
  | 'tilt'
  | 'pan_fine'
  | 'tilt_fine'
  | 'uv'
  | 'strobe'
  | 'amber'
  | 'white'
  | 'speed'
  // Beam-shaping roles (mirrors the backend PropertyCategory). Split out of
  // 'setting'/'other' so the 3D stage view can recognise them, the same way it
  // already keys off 'pan'/'tilt'. An older backend simply never sends these,
  // and every findXxx below returns undefined — the renderer then behaves
  // exactly as it did before, so no version check is needed anywhere.
  | 'gobo'
  | 'gobo_rotation'
  | 'prism'
  | 'prism_rotation'
  | 'focus'
  | 'zoom'
  | 'iris'
  | 'frost'
  | 'led_macro'
  | 'movement_macro'
  | 'setting'
  | 'other'

export type CompactDisplayRole = 'primary' | 'secondary'

export type SliderPropertyDescriptor = {
  type: 'slider'
  name: string
  displayName: string
  category: PropertyCategory
  channel: ChannelRef
  min: number
  max: number
  compactDisplay?: CompactDisplayRole
  /** Movement axis for moving-head sliders (omitted ⇒ none). */
  axis?: 'PAN' | 'TILT'
  /** Slider min in degrees (mapped to DMX min). Both deg fields must be set
   *  for the 3D view to convert raw values into degrees. */
  degMin?: number
  /** Slider max in degrees (mapped to DMX max). */
  degMax?: number
  /** Reverse the direction of the slider→degrees mapping; on a focus slider, DMX min is far focus. */
  inverted?: boolean
  /** Focus slider: the nearest focal distance (m from the aperture), at DMX min unless inverted.
   *  Both focus fields must be set for the 3D view to focus at a fixed distance. */
  focusNearM?: number
  /** Focus slider: the farthest focal distance (m from the aperture), at DMX max unless inverted. */
  focusFarM?: number
}

export type ColourPropertyDescriptor = {
  type: 'colour'
  name: string
  displayName: string
  category: 'colour'
  redChannel: ChannelRef
  greenChannel: ChannelRef
  blueChannel: ChannelRef
  whiteChannel?: ChannelRef
  amberChannel?: ChannelRef
  uvChannel?: ChannelRef
  compactDisplay?: CompactDisplayRole
}

export type PositionPropertyDescriptor = {
  type: 'position'
  name: string
  displayName: string
  category: 'position'
  panChannel: ChannelRef
  tiltChannel: ChannelRef
  panMin: number
  panMax: number
  tiltMin: number
  tiltMax: number
  compactDisplay?: CompactDisplayRole
}

export type SettingOption = {
  name: string
  level: number
  displayName: string
  colourPreview?: string
  /** Gobo pattern name at this wheel position (the backend GoboPattern
   *  vocabulary, lowercase). Absent on open/scroll positions and on wheels
   *  nobody has annotated — the stage view then falls back to the option's
   *  index (see resolveGoboSlot in stage3d/beamOptics.ts); an unknown name
   *  renders as open rather than as a wrong pattern. */
  gobo?: string
  /** Prism facet count at this wheel position; absent when the prism is out
   *  (or the wheel is unannotated — see resolvePrismFacets). */
  prismFacets?: number
}

export type SettingPropertyDescriptor = {
  type: 'setting'
  name: string
  displayName: string
  category: PropertyCategory
  channel: ChannelRef
  options: SettingOption[]
  compactDisplay?: CompactDisplayRole
}

/**
 * A **one-shot trigger** (stage-view plan session 9, D15): a confetti cannon's tube. Not a control —
 * nothing sets it, and every view that draws controls from a fixture's properties skips it. It is
 * fired from the cannon's panel (`CannonPanel`), a cue's Events or a MIDI `FireTrigger`, only while
 * the desk is armed; [armChannel] follows the desk's arm. Listed so the panel can name its tubes and
 * the DMX sheet its channels.
 */
export type TriggerPropertyDescriptor = {
  type: 'trigger'
  /** The trigger's name (`output1`) — what a fire, a cue event and a binding name. */
  name: string
  /** `Tube A`. */
  displayName: string
  category: 'trigger'
  /** `A`. */
  label: string
  channel: ChannelRef
  armChannel: ChannelRef
  /** The arm channel's name (`master`) — never a property. */
  armName: string
  compactDisplay?: undefined
}

/** A fixture's one-shot triggers, in the order the desk lists them; empty for every other fixture. */
export function triggersOf(properties: readonly PropertyDescriptor[] | undefined): TriggerPropertyDescriptor[] {
  return (properties ?? []).filter((p): p is TriggerPropertyDescriptor => p.type === 'trigger')
}

export type ElementDescriptor = {
  index: number
  key: string
  displayName: string
  properties: PropertyDescriptor[]
}

export type ModeInfo = {
  modeName: string
  channelCount: number
}

export type Fixture = {
  key: string
  name: string
  typeKey: string
  manufacturer?: string
  model?: string
  universe: number
  firstChannel: number
  channelCount: number
  channels: {
    channelNo: number
    description: string
  }[]
  properties: PropertyDescriptor[]
  elements?: ElementDescriptor[]
  elementGroupProperties?: GroupPropertyDescriptor[]
  mode?: ModeInfo
  capabilities: string[]
  groups: string[]
  /**
   * Ids of the Looks the backend reports as compatible with this fixture.
   *
   * **Capability-only**: "does this head have colour/position at all", never "was this Look
   * authored against that model", and no Look is excluded on any other ground — the "deferred
   * Looks only" this used to claim describes nothing now that every Look row is bound. It
   * answers for a Look's *deferred effects*, which are what the layer's targets supply.
   *
   * Known hole, and it is the backend's to close: a rows-only Look has an empty capability set,
   * so it is reported compatible with everything. Do not filter here — compatibility is one
   * answer, given server-side, or it drifts.
   */
  compatibleLookIds: number[]
  gelCode?: string | null
  /**
   * Patched as **infrastructure** — a dimmer channel on hard power, a relay, a hazer's fan — not
   * a lighting fixture. Hidden from every view but Patches and Channels: enumerate the rig through
   * `useVisibleFixtureListQuery` / `withoutInfrastructure`, never the raw list. The raw list still
   * carries it because a cue, group or binding that already names it must keep resolving.
   * Optional so an older backend (which never sends it) reads as "not infrastructure".
   */
  infrastructure?: boolean
}

/**
 * `useFixtureListQuery` with infrastructure fixtures left out — what a view that *lists or offers*
 * fixtures reads. A surface that only resolves a key it already holds (a cue row, an effect's
 * target, a group member's name) reads the raw list, so a reference to an infrastructure fixture
 * still resolves rather than reading as a missing one.
 */
export function useVisibleFixtureListQuery(options?: Parameters<typeof useFixtureListQuery>[1]) {
  const result = useFixtureListQuery(undefined, options)
  return useWithoutInfrastructure<typeof result, Fixture>(result, result.data)
}

/**
 * Find the property promoted to the compact card primary slot (top row).
 */
export function findCompactPrimary(properties: PropertyDescriptor[]): PropertyDescriptor | undefined {
  return properties.find((p) => p.compactDisplay === 'primary')
}

/**
 * Find the property promoted to the compact card secondary slot (bottom row).
 */
export function findCompactSecondary(properties: PropertyDescriptor[]): PropertyDescriptor | undefined {
  return properties.find((p) => p.compactDisplay === 'secondary')
}

/**
 * Result of finding a colour source from properties.
 * Either a direct colour property (type: 'colour') or a setting with category 'colour'.
 */
export type ColourSource =
  | { type: 'colour'; property: ColourPropertyDescriptor }
  | { type: 'setting'; property: SettingPropertyDescriptor }

/**
 * Find the colour source from an array of properties.
 * Prioritizes colour properties over colour settings.
 * If multiple colour settings exist, returns the first one.
 */
export function findColourSource(properties: PropertyDescriptor[]): ColourSource | undefined {
  // First, look for a direct colour property
  const colourProp = properties.find((p) => p.type === 'colour')
  if (colourProp) {
    return { type: 'colour', property: colourProp as ColourPropertyDescriptor }
  }

  // Fall back to the first setting with category 'colour'
  const colourSetting = properties.find(
    (p) => p.type === 'setting' && p.category === 'colour'
  )
  if (colourSetting) {
    return { type: 'setting', property: colourSetting as SettingPropertyDescriptor }
  }

  return undefined
}

/**
 * The aggregated per-element colour control of a multi-element fixture, if any.
 * Present only when the backend exposed ≥2 elements with a common colour
 * property (e.g. an RGBW pixel bar). Drives per-pixel stage rendering.
 */
export function findGroupColourSource(
  fixture: Fixture | undefined,
): GroupColourPropertyDescriptor | undefined {
  return fixture?.elementGroupProperties?.find(
    (p): p is GroupColourPropertyDescriptor => p.type === 'colour',
  )
}

/**
 * Find the dimmer slider on a fixture or element. Used widely for
 * brightness-derived UI (compact cards, stage markers, gel swatches).
 */
export function findDimmerProperty(
  properties: PropertyDescriptor[] | undefined,
): SliderPropertyDescriptor | undefined {
  return properties?.find(
    (p): p is SliderPropertyDescriptor => p.type === 'slider' && p.category === 'dimmer',
  )
}

/** Find the pan slider (axis === 'PAN') for moving-head head rotation. */
export function findPanProperty(
  properties: PropertyDescriptor[] | undefined,
): SliderPropertyDescriptor | undefined {
  return properties?.find(
    (p): p is SliderPropertyDescriptor => p.type === 'slider' && p.axis === 'PAN',
  )
}

/** Find the tilt slider (axis === 'TILT') for moving-head head rotation. */
export function findTiltProperty(
  properties: PropertyDescriptor[] | undefined,
): SliderPropertyDescriptor | undefined {
  return properties?.find(
    (p): p is SliderPropertyDescriptor => p.type === 'slider' && p.axis === 'TILT',
  )
}

/**
 * Find the optional 8-bit fine pan slider (category === 'pan_fine') for
 * 16-bit head positioning. Combined with `findPanProperty` to give 65536-step
 * resolution; absent on fixtures without sub-step pan precision.
 */
export function findPanFineProperty(
  properties: PropertyDescriptor[] | undefined,
): SliderPropertyDescriptor | undefined {
  return properties?.find(
    (p): p is SliderPropertyDescriptor => p.type === 'slider' && p.category === 'pan_fine',
  )
}

/** Fine tilt counterpart to {@link findPanFineProperty}. */
export function findTiltFineProperty(
  properties: PropertyDescriptor[] | undefined,
): SliderPropertyDescriptor | undefined {
  return properties?.find(
    (p): p is SliderPropertyDescriptor => p.type === 'slider' && p.category === 'tilt_fine',
  )
}

// — beam-shaping channels ————————————————————————————————————————————
//
// All of these return undefined against a backend that predates the categories,
// which is what makes the 3D view's new optics degrade silently to its old
// behaviour rather than needing a capability check.

/** A continuous beam slider by category (focus / zoom / iris / frost). */
function findSlider(
  properties: PropertyDescriptor[] | undefined,
  category: PropertyCategory,
): SliderPropertyDescriptor | undefined {
  return properties?.find(
    (p): p is SliderPropertyDescriptor => p.type === 'slider' && p.category === category,
  )
}

/**
 * A wheel-like channel by category. Matches either descriptor shape on purpose:
 * gobo rotation is a setting on the Equinox Fusion 100 and a plain slider on the
 * Martin MAC 250, Robe ColorSpot 575 and Varytec Easymove.
 */
export function findWheel(
  properties: PropertyDescriptor[] | undefined,
  category: PropertyCategory,
): SliderPropertyDescriptor | SettingPropertyDescriptor | undefined {
  return properties?.find(
    (p): p is SliderPropertyDescriptor | SettingPropertyDescriptor =>
      (p.type === 'slider' || p.type === 'setting') && p.category === category,
  )
}

/**
 * Every gobo wheel, in descriptor order. The Robe ColorSpot 575 exposes two
 * (static + rotating), and the backend emits descriptors in Kotlin reflection
 * order — alphabetical in practice, guaranteed nothing — so no single pick is
 * principled. The stage view renders the first wheel whose *current DMX value*
 * selects a pattern, so engaging either wheel shows its gobo regardless of
 * descriptor order.
 */
export function findGoboProperties(
  properties: PropertyDescriptor[] | undefined,
): Array<SliderPropertyDescriptor | SettingPropertyDescriptor> {
  return (
    properties?.filter(
      (p): p is SliderPropertyDescriptor | SettingPropertyDescriptor =>
        (p.type === 'slider' || p.type === 'setting') && p.category === 'gobo',
    ) ?? []
  )
}

export function findGoboRotationProperty(properties: PropertyDescriptor[] | undefined) {
  return findWheel(properties, 'gobo_rotation')
}

export function findPrismProperty(properties: PropertyDescriptor[] | undefined) {
  return findWheel(properties, 'prism')
}

export function findPrismRotationProperty(properties: PropertyDescriptor[] | undefined) {
  return findWheel(properties, 'prism_rotation')
}

export function findLedMacroProperty(properties: PropertyDescriptor[] | undefined) {
  return findWheel(properties, 'led_macro')
}

export function findMovementMacroProperty(properties: PropertyDescriptor[] | undefined) {
  return findWheel(properties, 'movement_macro')
}

export function findFocusProperty(properties: PropertyDescriptor[] | undefined) {
  return findSlider(properties, 'focus')
}

/** Zoom carries degMin/degMax as the full beam angle at DMX min/max. */
export function findZoomProperty(properties: PropertyDescriptor[] | undefined) {
  return findSlider(properties, 'zoom')
}

export function findIrisProperty(properties: PropertyDescriptor[] | undefined) {
  return findSlider(properties, 'iris')
}

export function findFrostProperty(properties: PropertyDescriptor[] | undefined) {
  return findSlider(properties, 'frost')
}
