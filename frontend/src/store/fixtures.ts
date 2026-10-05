import { restApi } from "./restApi"
import { useWithoutInfrastructure } from "@/lib/infrastructure"
import { lightingApi } from "../api/lightingApi"
import { store } from "./index"
import type { GroupColourPropertyDescriptor, GroupPropertyDescriptor } from "../api/groupsApi"
import type { Lantern } from "../lib/lanterns"
import type { Gel } from "../lib/gels"

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
      // The gel library (fixture optics plan D7): the desk's, fixed for the life of the desk
      // process like the lantern library, so read once and kept.
      gelList: build.query<Array<Gel>, void>({
        query: () => 'gels',
        keepUnusedDataFor: 3600,
      }),
    }
  },
  overrideExisting: false,
})

export const {
  useFixtureListQuery, useFixtureTypeListQuery, useLanternListQuery, useGelListQuery,
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
  /** The type's depth-of-field constant (`@FixtureType.depthOfField`, fixture-optics plan D9): how
   *  fast its focus goes soft off the focal plane. Null or absent uses its family's. */
  depthOfField?: number | null
  /** A fixed lens's full beam angle (`@FixtureType.fieldDeg`, fixture-optics plan D3) — the Fusion
   *  100's 10°. The beam angle is the zoom channel's, else the patch's, else this, else the family's
   *  (`resolveBeamDeg` in stage3d/bodies/archetype.ts). Null or absent declares none. */
  fieldDeg?: number | null
  /** How fast the type's mechanics move (`@FixtureType.travel`, fixture-optics plan D14) — drawn by
   *  the Stage view, which eases toward the DMX value at these rates; never output. Null or absent
   *  declares none, and the view snaps as it always did. */
  travel?: FixtureTravel | null
}

/**
 * A type's base speeds on the wire (backend `TravelInfo`). Each family is optional; one left null
 * snaps. `beamMs` / `colourMs` are the time a beam or colour channel takes across its whole DMX range.
 */
export interface FixtureTravel {
  panDegPerS?: number | null
  tiltDegPerS?: number | null
  beamMs?: number | null
  colourMs?: number | null
}

/** Which families one of a fixture's own timing channels stretches (backend `TimingRole`). */
export type TimingRole = 'POSITION' | 'BEAM' | 'COLOUR' | 'ALL'

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
  | CommandPropertyDescriptor

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
  // A wheel's function channel: its bands (INDEX, ROTATE_FWD, ROTATE_REV) say whether the
  // gobo_rotation channel is an angle or a speed. See resolveGoboRotation in stage3d/beamOptics.ts.
  | 'gobo_rotation_mode'
  | 'prism'
  | 'prism_rotation'
  | 'focus'
  | 'zoom'
  | 'iris'
  | 'frost'
  // A framing shutter: a blade's insertion and its angle, each naming its blade (`blade`). See
  // resolveDmxBlades in stage3d/beamOptics.ts.
  | 'shutter'
  | 'shutter_rotation'
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
  /** The coarse property (by `name`) this slider is the low byte of: the pair reads as one 16-bit
   *  value. A fine slider carries its coarse one's category, and the finders below skip it.
   *  Pan and tilt keep their own `pan_fine` / `tilt_fine` categories. */
  fineOf?: string
  /** Gobo rotation with a `gobo_rotation_mode` channel: the speed (RPM) at DMX max in a rotate band. */
  rpmMax?: number
  /** Gobo rotation with a `gobo_rotation_mode` channel: the angle (degrees) at DMX max when indexing. */
  indexDegMax?: number
  /** A `shutter` / `shutter_rotation` slider's blade, named for the edge of the light it cuts. A
   *  rotation's angle at DMX min / max is `degMin` / `degMax`. */
  blade?: ShutterBladeName
  /** A `shutter` slider: the blade's depth at DMX max (DMX min when `inverted`), as a fraction of
   *  the field's diameter — the lantern focus's unit, so 0.5 reaches the centre. DMX min is out. */
  depthMax?: number
  /** The DMX at which the slider's proportional band starts (`@FixtureProperty.activeMin`); absent
   *  is `min`. Below it the Stage view holds the band's start value. */
  activeMin?: number
  /** The DMX at which it ends — above it the channel is effects (the Robe's iris and frost pulse
   *  above 179), and the view holds the band's end value. Absent is `max`. */
  activeMax?: number
  /** A `strobe` slider's bands, in DMX order (`@FixtureProperty.strobe`, fixture-optics plan D12):
   *  what each range of the channel does to the light. Decoded by `lib/strobeBands.ts`. */
  strobeBands?: StrobeBand[]
  /** One of the fixture's own **timing channels** (`@FixtureProperty.timing`, fixture-optics plan
   *  D14) — the families it stretches. Null or absent on every other slider. Read by
   *  `lib/travel.ts`; the desk sends the channel as composed. */
  timing?: TimingRole | null
  /** A timing channel's move duration per DMX step, in seconds (the Revolution's 1 s). 0 is no
   *  timing: the move runs at the type's `travel`. */
  timingSecondsPerStep?: number | null
  /** The DMX from which a timing channel means "as fast as the fixture can", as 0 does — the
   *  Revolution's Focus Timing at 255. Null or absent for none. */
  timingFastFrom?: number | null
}

/** What a band of a strobe channel does to the light (backend `StrobeKind`). */
export type StrobeKind = 'CLOSED' | 'OPEN' | 'STROBE' | 'RANDOM' | 'PULSE'

/** One band of a strobe channel (backend `StrobeBandInfo`): DMX `from`..`to` inclusive does `kind`;
 *  a flashing band's rate runs `hzMin` at `from` to `hzMax` at `to`, the other way round where
 *  `inverted`. */
export type StrobeBand = {
  from: number
  to: number
  kind: StrobeKind
  hzMin?: number
  hzMax?: number
  inverted?: boolean
}

/** A framing shutter's blade, in the lantern focus's wire order (`BLADE_ORDER`). */
export type ShutterBladeName = 'TOP' | 'BOTTOM' | 'LEFT' | 'RIGHT'

/** The blades in the wire's order — top, bottom, left, right — which `packBlades` packs in. */
export const BLADE_ORDER: readonly ShutterBladeName[] = ['TOP', 'BOTTOM', 'LEFT', 'RIGHT']

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
  /** On a loadable setting (its `media` set), whether this option is a slot media can be loaded
   *  into — false for an open hole or an out position. Absent on every other setting. */
  loadable?: boolean
  /** On a **stepped zoom** (a `zoom` setting, fixture-optics plan D2): the full beam angle at this
   *  position — the Robe ColorSpot's 15°, 18° or 22°. */
  zoomDeg?: number
  /** On a colour setting: a band with **no single colour** — a scroll, a random or rainbow program,
   *  an auto change, a band handing colour to other channels. The Stage view animates it through the
   *  wheel's own previews (`lib/colourBands.ts`); every other colour option carries `colourPreview`. */
  noColour?: boolean
  /** On a setting-backed strobe channel: what this option's band does to the light (fixture-optics
   *  plan D12). Its band runs from its level to the next option's. */
  strobeKind?: StrobeKind
  /** On a flashing strobe option: its rate in Hz at the band's first value (last where
   *  `strobeInverted`). */
  hzMin?: number
  /** On a flashing strobe option: its rate in Hz at the band's last value (first where
   *  `strobeInverted`). */
  hzMax?: number
  /** On a flashing strobe option: the rate falls as the value rises. */
  strobeInverted?: boolean
}

/** What a loadable setting's slots take (`@FixtureProperty(media =)`, fixture optics plan D6). */
export type MediaSlot = 'GEL' | 'GOBO' | 'GOBO_OR_GEL'

export type SettingPropertyDescriptor = {
  type: 'setting'
  name: string
  displayName: string
  category: PropertyCategory
  channel: ChannelRef
  options: SettingOption[]
  compactDisplay?: CompactDisplayRole
  /** Set on a **loadable** setting: what its options can be loaded with. The options are the type's
   *  stock; a unit's fitted media overlays them (`lib/fittedMedia.ts`). Absent from an older desk. */
  media?: MediaSlot
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

/**
 * A **fixture command** (fixture optics plan session 7, D13): a reset, a lamp strike, a lamp off. Not
 * a control — nothing sets it, and every view that draws controls from a fixture's properties skips
 * it. It runs from the fixture panel's *Commands* menu behind a confirm
 * (`POST …/patches/{id}/commands/{name}`), and the desk holds its level for [holdMs] before giving
 * the channel back. Listed so the menu can name its commands and the DMX sheet its channel.
 */
export type CommandPropertyDescriptor = {
  type: 'command'
  /** The command's name (`resetScroller`) — what the route and `run_fixture_command` name. */
  name: string
  /** The menu item: `Reset scroller`. */
  displayName: string
  category: 'command'
  /** What it does, for the confirm. */
  description: string
  holdMs: number
  confirm: boolean
  channel: ChannelRef
  /** No property covers [channel]: the desk holds it idle between commands. */
  dedicated: boolean
  /** Channels the desk sets for the hold because the manual wants them so. */
  alongside?: { channel: ChannelRef; level: number; why: string }[]
  compactDisplay?: undefined
}

/** A fixture's commands, in the order the desk lists them; empty for every fixture with none. */
export function commandsOf(properties: readonly PropertyDescriptor[] | undefined): CommandPropertyDescriptor[] {
  return (properties ?? []).filter((p): p is CommandPropertyDescriptor => p.type === 'command')
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

/**
 * Every strobe channel that says what its bands do (fixture-optics plan D12): a `strobe` slider with
 * `strobeBands`, or a `strobe` setting whose options carry a `strobeKind`. A fixture may have two —
 * the LED Lightbar's strobe and random strobe — and the light is gated by both. A channel from an
 * older desk, declaring nothing, is left out: it draws open, as it always did.
 */
export function findStrobeProperties(
  properties: PropertyDescriptor[] | undefined,
): Array<SliderPropertyDescriptor | SettingPropertyDescriptor> {
  return (
    properties?.filter(
      (p): p is SliderPropertyDescriptor | SettingPropertyDescriptor =>
        p.category === 'strobe' &&
        !isFine(p) &&
        ((p.type === 'slider' && (p.strobeBands?.length ?? 0) > 0) ||
          (p.type === 'setting' && p.options.some((o) => o.strobeKind != null))),
    ) ?? []
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

/** A fine (low-byte) slider: never "the" property of its category — see `fineOf`. */
function isFine(p: PropertyDescriptor): boolean {
  return p.type === 'slider' && p.fineOf != null
}

/** A continuous beam slider by category (focus / zoom / iris / frost). */
function findSlider(
  properties: PropertyDescriptor[] | undefined,
  category: PropertyCategory,
): SliderPropertyDescriptor | undefined {
  return properties?.find(
    (p): p is SliderPropertyDescriptor => p.type === 'slider' && p.category === category && !isFine(p),
  )
}

/**
 * The fine slider declared `fineOf` the coarse property, if any: the low byte of a 16-bit pair, read
 * with `combineFinePair` in stage3d/beamOptics.ts.
 */
export function findFineProperty(
  properties: PropertyDescriptor[] | undefined,
  coarse: PropertyDescriptor | undefined,
): SliderPropertyDescriptor | undefined {
  if (!coarse) return undefined
  return properties?.find(
    (p): p is SliderPropertyDescriptor => p.type === 'slider' && p.fineOf === coarse.name,
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
      (p.type === 'slider' || p.type === 'setting') && p.category === category && !isFine(p),
  )
}

/**
 * Every gobo wheel, in **DMX channel order** — the layer order the Stage view draws them in
 * (fixture-optics plan session 4): the first wheel is layer A, the second layer B, and a beam
 * multiplies the two. The Robe ColorSpot 575 has two (its static wheel on channel 9, its rotating
 * wheel on 10), every other type one. Descriptor order is Kotlin reflection's — alphabetical in
 * practice, guaranteed nothing — so the channel decides, and a rig's layers never swap with a
 * build. Fine (low-byte) sliders are never a wheel.
 */
export function findGoboProperties(
  properties: PropertyDescriptor[] | undefined,
): Array<SliderPropertyDescriptor | SettingPropertyDescriptor> {
  const wheels =
    properties?.filter(
      (p): p is SliderPropertyDescriptor | SettingPropertyDescriptor =>
        (p.type === 'slider' || p.type === 'setting') && p.category === 'gobo' && !isFine(p),
    ) ?? []
  // Stable, so two wheels on one channel (never in the library) keep descriptor order.
  return wheels.sort((a, b) => a.channel.universe - b.channel.universe || a.channel.channelNo - b.channel.channelNo)
}

/**
 * Which of [wheels] (as [findGoboProperties] orders them) a gobo rotation channel turns: the wheel
 * it **follows** — the nearest at or below its channel — which is how every type in the library lays
 * them out (the MAC 250's wheel then its rotation; the Robe's static wheel, rotating wheel, then the
 * rotating wheel's index/speed; the Revolution's position, function, rotation). A rotation channel
 * before every wheel turns the first. -1 with no rotation channel or no wheel: nothing turns.
 */
export function goboRotationWheel(
  wheels: ReadonlyArray<SliderPropertyDescriptor | SettingPropertyDescriptor>,
  rotation: SliderPropertyDescriptor | SettingPropertyDescriptor | undefined,
): number {
  if (!rotation || wheels.length === 0) return -1
  const at = rotation.channel
  let best = -1
  for (let i = 0; i < wheels.length; i++) {
    const c = wheels[i].channel
    if (c.universe !== at.universe || c.channelNo > at.channelNo) continue
    if (best < 0 || c.channelNo >= wheels[best].channel.channelNo) best = i
  }
  return best < 0 ? 0 : best
}

export function findGoboRotationProperty(properties: PropertyDescriptor[] | undefined) {
  return findWheel(properties, 'gobo_rotation')
}

/** A wheel's function channel, which says whether its gobo rotation is an index or a speed. */
export function findGoboRotationModeProperty(properties: PropertyDescriptor[] | undefined) {
  return findWheel(properties, 'gobo_rotation_mode')
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

/**
 * The zoom channel, in either form (fixture-optics plan D2): a **slider** carrying `degMin`/`degMax`
 * as the full beam angle at DMX min/max, or a **stepped zoom** — a setting whose options carry
 * `zoomDeg` (the Robe ColorSpot 575's three angles). `resolveZoomDeg` reads both.
 */
export function findZoomProperty(properties: PropertyDescriptor[] | undefined) {
  return findWheel(properties, 'zoom')
}

/**
 * A fixture's framing shutters, by blade in [BLADE_ORDER]: each blade's insertion (`shutter`) and
 * rotation (`shutter_rotation`) slider, either absent. Undefined when the fixture drives no blade
 * from its channels — then a lantern's focus blades, if any, are the beam's. A blade's slider that
 * declares no scale (`depthMax`, or `degMin`/`degMax`) is left out, as the library's guard test
 * (`ShutterBladesTest`) refuses one.
 */
export interface ShutterProperties {
  depth: Array<SliderPropertyDescriptor | undefined>
  rotation: Array<SliderPropertyDescriptor | undefined>
}

export function findShutterProperties(
  properties: PropertyDescriptor[] | undefined,
): ShutterProperties | undefined {
  if (!properties) return undefined
  let found = false
  const depth: Array<SliderPropertyDescriptor | undefined> = [undefined, undefined, undefined, undefined]
  const rotation: Array<SliderPropertyDescriptor | undefined> = [undefined, undefined, undefined, undefined]
  for (const p of properties) {
    if (p.type !== 'slider' || isFine(p) || p.blade == null) continue
    const i = BLADE_ORDER.indexOf(p.blade)
    if (i < 0) continue
    if (p.category === 'shutter' && p.depthMax != null && depth[i] == null) {
      depth[i] = p
      found = true
    } else if (p.category === 'shutter_rotation' && p.degMin != null && p.degMax != null && rotation[i] == null) {
      rotation[i] = p
      found = true
    }
  }
  return found ? { depth, rotation } : undefined
}

export function findIrisProperty(properties: PropertyDescriptor[] | undefined) {
  return findSlider(properties, 'iris')
}

export function findFrostProperty(properties: PropertyDescriptor[] | undefined) {
  return findSlider(properties, 'frost')
}
