import type { SpreadUnit } from '@/lib/spreadShape'
import type { ChannelRef } from '../store/fixtures'
import type { FxDirection, Timing } from './fxApi'

// === Types ===

export interface GroupSummary {
  name: string
  memberCount: number
  capabilities: string[]
  symmetricMode: string
  defaultOrder: string
  /**
   * Every member's key in the group's order (`sort_order`, venue-visit plan S7): the order its
   * effects and a spread over it run in, which the Groups list draws member rows in. Absent from
   * an older desk.
   */
  memberKeys?: string[]
  /** The group's colour as the show's rig draws it (tables-colours plan D7); absent is none. */
  colour?: string | null
}

export interface GroupMember {
  fixtureKey: string
  fixtureName: string
  index: number
  normalizedPosition: number
  panOffset: number
  tiltOffset: number
  symmetricInvert: boolean
  tags: string[]
}

export interface GroupDetail extends GroupSummary {
  members: GroupMember[]
  /**
   * Each strategy's step per member, indexed by the member's `index`: 1 comes round first, and
   * members sharing an offset share a step (a pair under `CENTRE_OUT`). Computed by the desk's
   * `SpreadOrder`, so the group sheet shows the order without mirroring a formula. A
   * function of the group's size alone. Absent from an older desk.
   */
  orderSteps?: Partial<Record<SpreadOrder, number[]>>
}

export type BlendMode = 'OVERRIDE' | 'ADDITIVE' | 'MULTIPLY' | 'MAX' | 'MIN'

export type ElementMode = 'WHOLE' | 'FLAT' | 'PER_FIXTURE'

export type SpreadOrder =
  | 'NORMAL'
  | 'UNIFIED'
  | 'CENTRE_OUT'
  | 'CENTRE_IN'
  | 'REVERSE'
  | 'PING_PONG'
  | 'RANDOM'
  | 'POSITIONAL'

export interface ApplyFxRequest {
  /**
   * The effect's registry id, as `GET /fx/library` reports it — `string`, like every other
   * `fxType` in this client (`GroupActiveFx`, `ActiveFxState`, `addFixtureFx`).
   *
   * Three closed unions stood here (dimmer / colour / position, twenty lowercase literals). The
   * vocabulary they claimed to enumerate is data-driven — `fx/index.txt` plus each `.fx.kts`
   * `id:`, plus whatever an `FX_DEFINITION` script registers — so no union could name a
   * user-defined effect, and the built-in list had already outgrown these by eight. They only
   * typechecked because the one production call site cast `effect.name as FxType`, which
   * constrains nothing. Narrow it from the FX library query at runtime if narrowing is ever wanted.
   *
   * **Send the library's name verbatim.** `FxRegistry` normalises on lookup, which is what lets a
   * `SineWave` id answer a `sinewave` request; canonicalising here would only add a second
   * spelling to keep in step.
   */
  fxType: string
  attribute: string
  cycle: number
  blendMode: BlendMode
  order: SpreadOrder
  phaseOffset: number
  parameters: Record<string, string>
  elementMode?: ElementMode
  elementFilter?: string
  timing?: Timing
  /** Speed master to subscribe to, as the master's uuid (omitted → master 1). */
  speedMasterUuid?: string
  /** Wall-clock rate master, as the master's uuid (omitted → unscaled). */
  rateSpeedMasterUuid?: string
  /** On *Seconds* (D7): the division is seconds, no master. Never beside a master. */
  seconds?: boolean
  /** Amount (effects plan D2), 0–1; omitted → 1. */
  amount?: number
  /** Direction (D5); omitted → forward. */
  direction?: FxDirection
  /** Runs (D9), at least 1; omitted → forever. */
  runs?: number
  /** The spread's shape (effects plan D4) — `lib/spreadShape.ts`; each absent at its default. */
  spread?: number
  wings?: number
  segments?: number
  parts?: number
  /** What `spread` counts (fx-settings plan D6): `STEP`, the FX's own steps; absent is `CYCLE`. */
  spreadUnit?: SpreadUnit
  /**
   * Create the effect in the programmer's reserved priority band, so it composes *on top of*
   * programmer values instead of being suppressed by them, and Clear sweeps it with them.
   * Set by the busking pad; cue and script authoring leave it off.
   */
  programmerOwned?: boolean
}

export interface ApplyFxResponse {
  fxId: number
}

export interface GroupActiveFx {
  id: number
  fxType: string
  attribute: string
  cycle: number
  blendMode: BlendMode
  order: SpreadOrder
  isRunning: boolean
  phaseOffset: number
  currentPhase: number
  parameters: Record<string, string>
  elementMode: ElementMode | null
  elementFilter: string | null
  timing: Timing
  cueId: number | null
  /** Speed master this effect subscribes to (null → master 1). */
  speedMasterUuid?: string | null
  /** Wall-clock rate master (null → unscaled); only WALL_CLOCK effects read it. */
  rateSpeedMasterUuid?: string | null
  /** D7's reading: follows no master. */
  seconds?: boolean
  /** Amount (effects plan D2), 0–1; absent at 1. */
  amount?: number
  /** Direction (D5); absent when forward. */
  direction?: FxDirection
  /** Runs (D9); absent or null runs forever. */
  runs?: number | null
  /** The spread's shape (effects plan D4) — `lib/spreadShape.ts`; each absent at its default. */
  spread?: number
  wings?: number
  segments?: number
  parts?: number
  /** What `spread` counts (fx-settings plan D6): `STEP`, the FX's own steps; absent is `CYCLE`. */
  spreadUnit?: SpreadUnit
  /** A programmer layer's N-shot that has run its count, kept painting nothing until its layer goes. */
  spent?: boolean
}

// === Group Property Types ===

export type GroupAttributeDescriptor =
  | GroupSliderAttributeDescriptor
  | GroupColourAttributeDescriptor
  | GroupWhiteMixAttributeDescriptor
  | GroupPositionAttributeDescriptor
  | GroupSettingAttributeDescriptor

export interface GroupSliderAttributeDescriptor {
  type: 'slider'
  name: string
  displayName: string
  category: string
  min: number
  max: number
  memberChannels: ChannelRef[]
}

export interface MemberColourChannels {
  fixtureKey: string
  redChannel: ChannelRef
  greenChannel: ChannelRef
  blueChannel: ChannelRef
  whiteChannel?: ChannelRef
  amberChannel?: ChannelRef
  uvChannel?: ChannelRef
}

export interface GroupColourAttributeDescriptor {
  type: 'colour'
  name: string
  displayName: string
  category: 'colour'
  memberColourChannels: MemberColourChannels[]
}

export interface MemberWhiteMixChannels {
  fixtureKey: string
  warmChannel: ChannelRef
  coldChannel: ChannelRef
}

/** The "all heads" form of a warm/cold white pair (venue-visit plan D8) — the blinder's cells. */
export interface GroupWhiteMixAttributeDescriptor {
  type: 'whiteMix'
  name: string
  displayName: string
  category: 'colour'
  warmKelvin: number
  coldKelvin: number
  memberChannels: MemberWhiteMixChannels[]
}

export interface MemberPositionChannels {
  fixtureKey: string
  panChannel: ChannelRef
  tiltChannel: ChannelRef
  panMin: number
  panMax: number
  tiltMin: number
  tiltMax: number
}

export interface GroupPositionAttributeDescriptor {
  type: 'position'
  name: string
  displayName: string
  category: 'position'
  memberPositionChannels: MemberPositionChannels[]
}

export interface MemberSettingChannel {
  fixtureKey: string
  channel: ChannelRef
}

export interface SettingOption {
  name: string
  level: number
  displayName: string
  colourPreview?: string
}

export interface GroupSettingAttributeDescriptor {
  type: 'setting'
  name: string
  displayName: string
  category: string
  options: SettingOption[]
  memberChannels: MemberSettingChannel[]
}
