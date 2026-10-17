import type { EffectDirection, UpdateFxRequest } from '@/api/fxApi'
import type { ActiveEffect, EffectLibraryEntry, EffectParameterDef } from '@/store/fixtureFx'
import { beatsToSeconds, isOnSeconds, secondsToBeats } from '@/lib/effectTiming'
import { isMirroredOrder } from '@/lib/spreadShape'
import { CENTRE_PARAMS, hasCentre, defaultParameters, withCentreMode } from './centreMode'

export {
  CENTRE_BYTE,
  centreModeOf,
  defaultParameters,
  hasCentre,
  isAroundSpelling,
  storedCentreMode,
  withCentreMode,
  type CentreMode,
} from './centreMode'

/**
 * The live effect editor's rules, pure (fixture-fx-sheets plan D11, D12, D12a; §3.3).
 *
 * `FxEditor` and `FxPicker` only draw: which question an effect asks, what a stored blend reads back
 * as, which parameters are the effect's *shape*, which are a position on its axis and what one write
 * sends are all answered here, where a test can reach them without a store.
 */

/** D12a: a level effect replaces the level underneath, or moves within it. */
export type LevelMode = 'replace' | 'within'

/** The level effects — the dimmer category, D12a's *Replace it | Within it*. */
export function isLevelEffect(entry: Pick<EffectLibraryEntry, 'category'>): boolean {
  return entry.category === 'dimmer'
}

/** Replace is Override, Within is Multiply (call 2); anything else is neither. */
export function levelModeOf(blendMode: string): LevelMode | null {
  const blend = blendMode.toUpperCase()
  if (blend === 'OVERRIDE') return 'replace'
  if (blend === 'MULTIPLY') return 'within'
  return null
}

export function blendForLevelMode(mode: LevelMode): string {
  return mode === 'within' ? 'MULTIPLY' : 'OVERRIDE'
}

/**
 * How an effect starts from the picker: its defaults, and — for a movement effect — *Around*
 * (D12, "movement starts Around"). A level effect starts Replace (call 2), everything else Override.
 */
export function startingSpec(
  entry: Pick<EffectLibraryEntry, 'category' | 'parameters'>,
): { blendMode: string; parameters: Record<string, string> } {
  const spec = { blendMode: 'OVERRIDE', parameters: defaultParameters(entry) }
  return hasCentre(entry) ? withCentreMode(spec, 'around') : spec
}

/**
 * What a parameter is to the editor, which decides where it is drawn and in what unit.
 *
 * - `centre` — a movement effect's centre: hidden in Around, an absolute position in Absolute.
 * - `size` — a radius or a range on an axis: a distance, in coarse DMX steps.
 * - `axis` — any other position byte (a sweep's ends): a position on its axis, centre-relative
 *   (`-128..127`, venue-visit plan D3), as the centre is.
 * - `level` — a byte anywhere else: a percent, as the level row reads.
 * - `shape` — a curve, a switch, or a ratio beside levels: behind the Shape disclosure.
 * - `main` — everything else, drawn with the levels.
 *
 * The ratio rule is the board's: a Pulse's attack and hold are its shape (it has levels to be the
 * effect), while a Colour Cycle's fade and a Rainbow's saturation *are* the effect — it has no
 * level to put first.
 */
export type ParamRole = 'centre' | 'size' | 'axis' | 'level' | 'shape' | 'main'

export function paramRole(entry: Pick<EffectLibraryEntry, 'category' | 'parameters'>, param: EffectParameterDef): ParamRole {
  const type = param.type.toLowerCase()
  if (type === 'easingcurve' || type === 'boolean') return 'shape'
  if (type === 'double' || type === 'float') {
    return entry.parameters.some((p) => p.type.toLowerCase() === 'ubyte') ? 'shape' : 'main'
  }
  if (type !== 'ubyte') return 'main'
  if (entry.category === 'position' && paramAxis(param.name) != null) {
    if ((CENTRE_PARAMS as readonly string[]).includes(param.name)) return 'centre'
    if (/(Radius|Range)$/.test(param.name)) return 'size'
    return 'axis'
  }
  return 'level'
}

/**
 * Which axis a position parameter moves, from its name — `pan` / `tilt` as a camelCase word
 * (`panCenter`, `startPan`, `pan`), never a substring, so a script's `spanWidth` or `expansion` is
 * not read as a pan axis and drawn on the position scale.
 */
export function paramAxis(name: string): 'pan' | 'tilt' | null {
  if (/(^pan|Pan)(?![a-z])/.test(name)) return 'pan'
  if (/(^tilt|Tilt)(?![a-z])/.test(name)) return 'tilt'
  return null
}

/** A ratio parameter (a 0–1 double) is drawn as a percent. */
export function isRatioParam(param: EffectParameterDef): boolean {
  const type = param.type.toLowerCase()
  return (type === 'double' || type === 'float') && Number(param.defaultValue) <= 1
}

export function clampByte(n: number): number {
  return Math.max(0, Math.min(255, Math.round(n)))
}

// ─── The draft and its write ───────────────────────────────────────────────────────────────────

/** Everything the editor edits on a running effect. */
export interface FxDraft {
  parameters: Record<string, string>
  beatDivision: number
  blendMode: string
  phaseOffset: number
  stepTiming: boolean
  distributionStrategy: string
  elementMode: string
  elementFilter: string
  /** Null → master 1, as the instance stores it. */
  speedMasterUuid: string | null
  /** Null → unscaled. */
  rateSpeedMasterUuid: string | null
  /**
   * D7's *Seconds* (venue-visit plan S15): the effect follows no master. A beat effect's
   * [beatDivision] is then seconds and both masters are null; a wall-clock effect's division is
   * seconds either way, and its Seconds is a null [rateSpeedMasterUuid].
   */
  seconds: boolean
  /** Size (effects plan D2), 0–1 — how much of the effect reaches the stage over what is underneath. */
  size: number
  /** Direction (D5): which way each head runs its cycle. */
  direction: EffectDirection
  /** Runs (D9): stop after this many cycles; null runs forever. */
  nShot: number | null
  /**
   * The spread's shape (effects plan D4): Spread 0–2, Wings and Blocks at least 1, Groups 0 (off) or
   * more. The desk shapes the heads from them (P2); the editor's dials only preview it
   * (`lib/spreadShape.ts`).
   */
  spread: number
  wings: number
  blocks: number
  groups: number
}

/**
 * The draft an editor opens on — and so the snapshot Revert puts back. The DTO omits a
 * distribution, element mode or filter it has nothing to say about, so the absences read as the
 * defaults they stand for: `LINEAR`, `PER_FIXTURE`, `ALL`.
 */
export function draftOf(effect: ActiveEffect): FxDraft {
  return {
    parameters: { ...effect.parameters },
    beatDivision: effect.beatDivision,
    blendMode: effect.blendMode,
    phaseOffset: effect.phaseOffset,
    stepTiming: effect.stepTiming ?? false,
    distributionStrategy: effect.distributionStrategy ?? 'LINEAR',
    elementMode: effect.elementMode ?? 'PER_FIXTURE',
    elementFilter: effect.elementFilter ?? 'ALL',
    speedMasterUuid: effect.speedMasterUuid ?? null,
    rateSpeedMasterUuid: effect.rateSpeedMasterUuid ?? null,
    // The desk reports D7's reading; a desk before S15 does not, and then a wall-clock effect with
    // no rate master is the only Seconds there is.
    seconds: isOnSeconds(effect, effect.timingSource),
    // Absent on the wire at their defaults (the desk's socket encodes no default), and from a desk
    // before effects plan session 1.
    size: effect.size ?? 1,
    direction: effect.direction ?? 'FORWARD',
    nShot: effect.nShot ?? null,
    // The same for the spread's shape (effects plan session 2).
    spread: effect.spread ?? 1,
    wings: effect.wings ?? 1,
    blocks: effect.blocks ?? 1,
    groups: effect.groups ?? 0,
  }
}

/** Which of the optional fields this effect's target can take — the editor shows only those. */
export interface FxScope {
  /** A group, or a fixture with heads: distribution and step timing. */
  heads: boolean
  /** A group with multi-element members. */
  elementMode: boolean
  /** A fixture with heads, or a group with multi-element members. */
  elementFilter: boolean
  /**
   * Step timing, where it differs from [heads]: a template's effect offers its distribution (the
   * layer supplies the heads) but not step timing, which is a question about one head's elements.
   */
  stepTiming?: boolean
  /**
   * How many heads the effect runs over, for the Spread section's dials — a live effect's own (they
   * draw up to sixteen, then *+n*). Absent on an effect palette, which has no target: the dials draw
   * eight notional heads (effects plan §4.1).
   */
  headCount?: number
}

/** The fields the DTO may leave out, and so the editor sends only once the operator has set them. */
export type OptionalField =
  | 'stepTiming'
  | 'distributionStrategy'
  | 'elementMode'
  | 'elementFilter'
  | 'speedMasterUuid'
  | 'rateSpeedMasterUuid'
  | 'seconds'
  | 'size'
  | 'direction'
  | 'nShot'
  | 'spread'
  | 'wings'
  | 'blocks'
  | 'groups'

export const OPTIONAL_FIELDS: readonly OptionalField[] = [
  'stepTiming',
  'distributionStrategy',
  'elementMode',
  'elementFilter',
  'speedMasterUuid',
  'rateSpeedMasterUuid',
  'seconds',
  'size',
  'direction',
  'nShot',
  'spread',
  'wings',
  'blocks',
  'groups',
]

/**
 * One `updateFx` frame for the draft — every write carries the parameters, speed, blend and phase,
 * so a floored push that drops an intermediate move can never drop a *different* field the operator
 * set in the same window. `effectType` is never sent: the editor edits one type, and a type swap is
 * the picker's.
 *
 * The optional fields go only once [touched]: the DTO leaves a distribution, element mode or filter
 * out where it does not apply (`EffectDto`'s `showDistribution`), so the draft's default for an
 * absent one is a guess, and sending it unasked would write the guess over whatever the instance
 * holds. Once touched, a field rides every later write — Revert's included, which is how Revert
 * puts it back. A null speed master is spelled [nullMasterAs] — Revert passes master 1's uuid, which
 * the desk treats as the null default, because the frame's absent field means "keep"; a null rate
 * master is left absent, since *unscaled* is said by `seconds` (below).
 *
 * The timing (venue-visit plan D7) rides once touched too: `seconds: true` puts the effect on
 * Seconds and sends **no** master beside it — the desk refuses both — which is also how Revert puts
 * a wall-clock effect back to *unscaled* (`FU-FX-RATE-MASTER-CLEAR`, closed by S15); `seconds:
 * false` sends the master beside it.
 *
 * Size, direction and runs (effects plan D2, D5, D9) ride once touched too, which keeps a frame to
 * a desk that predates them the shape it always was; a null count is said as `runForever`, since
 * the frame's absent `nShot` means "keep".
 *
 * The spread's shape (effects plan D4) rides once touched too, each field on its own; groups 0 is how
 * the frame turns them off, so a cleared Groups needs no flag of its own.
 */
export function updateRequestOf(
  draft: FxDraft,
  touched: ReadonlySet<OptionalField> = new Set(),
  nullMasterAs: string | null = null,
): UpdateFxRequest {
  const speedMasterUuid = draft.speedMasterUuid ?? nullMasterAs
  const onSeconds = touched.has('seconds') && draft.seconds
  return {
    parameters: { ...draft.parameters },
    beatDivision: draft.beatDivision,
    blendMode: draft.blendMode,
    phaseOffset: draft.phaseOffset,
    ...(touched.has('stepTiming') ? { stepTiming: draft.stepTiming } : {}),
    ...(touched.has('distributionStrategy') ? { distributionStrategy: draft.distributionStrategy } : {}),
    ...(touched.has('elementMode') ? { elementMode: draft.elementMode } : {}),
    ...(touched.has('elementFilter') ? { elementFilter: draft.elementFilter } : {}),
    ...(touched.has('seconds') ? { seconds: draft.seconds } : {}),
    ...(touched.has('size') ? { size: draft.size } : {}),
    ...(touched.has('direction') ? { direction: draft.direction } : {}),
    ...(touched.has('nShot') ? (draft.nShot == null ? { runForever: true } : { nShot: draft.nShot }) : {}),
    ...(touched.has('spread') ? { spread: draft.spread } : {}),
    ...(touched.has('wings') ? { wings: draft.wings } : {}),
    ...(touched.has('blocks') ? { blocks: draft.blocks } : {}),
    ...(touched.has('groups') ? { groups: draft.groups } : {}),
    ...(!onSeconds && touched.has('speedMasterUuid') && speedMasterUuid != null ? { speedMasterUuid } : {}),
    ...(!onSeconds && touched.has('rateSpeedMasterUuid') && draft.rateSpeedMasterUuid != null
      ? { rateSpeedMasterUuid: draft.rateSpeedMasterUuid }
      : {}),
  }
}

/**
 * The draft patch for the timing chip's choice (venue-visit plan D7): [choice] null is *Seconds*,
 * else a master's uuid. Each patch names the timing **and** the master fields, so the editor marks
 * all of them set and Revert can put every one of them back.
 *
 * Moving a **beat** effect between Seconds and a master keeps its speed — the division converted at
 * the master's tempo ([bpmOf], the live bank's; 4 beats at 120 BPM is 2 s). A wall-clock effect's
 * number is seconds either way, and its master is its rate master.
 */
export function timingChoicePatch(
  draft: Pick<FxDraft, 'beatDivision' | 'seconds' | 'speedMasterUuid' | 'rateSpeedMasterUuid'>,
  wallClock: boolean,
  choice: string | null,
  bpmOf: (uuid: string | null) => number,
): Partial<FxDraft> {
  if (choice == null) {
    return {
      seconds: true,
      speedMasterUuid: null,
      rateSpeedMasterUuid: null,
      ...(!wallClock && !draft.seconds ? { beatDivision: beatsToSeconds(draft.beatDivision, bpmOf(draft.speedMasterUuid)) } : {}),
    }
  }
  if (wallClock) return { seconds: false, rateSpeedMasterUuid: choice }
  return {
    seconds: false,
    speedMasterUuid: choice,
    ...(draft.seconds ? { beatDivision: secondsToBeats(draft.beatDivision, bpmOf(choice)) } : {}),
  }
}

/**
 * The draft patch for choosing an Order (effects plan D4): the Order, and — onto a mirrored one
 * (Centre out, Edges in, Split, two wings already) — Wings back to 1 in the same write, since the
 * desk refuses wings under a mirrored Order and the editor hides the field there.
 */
export function orderPatch(draft: Pick<FxDraft, 'wings'>, order: string): Partial<FxDraft> {
  return isMirroredOrder(order) && draft.wings > 1 ? { distributionStrategy: order, wings: 1 } : { distributionStrategy: order }
}

/** The descriptors [spreadHeadCount] reads: a fixture's own properties, by name and type. */
type CarrierProperty = { name: string; type: string }

/**
 * Whether a fixture carries the effect's property **itself** rather than only on its heads — the
 * desk's `FxTarget.fixtureHasProperty`: a slider or a setting by name, a colour by the colour trait
 * (`WithColour`, a `colour` or `whiteMix` descriptor), a position by `WithPosition`.
 */
export function carriesEffectProperty(properties: readonly CarrierProperty[] | null | undefined, propertyName: string): boolean {
  return (properties ?? []).some(
    (p) =>
      p.name === propertyName ||
      (propertyName === 'rgbColour' && (p.type === 'colour' || p.type === 'whiteMix')) ||
      (propertyName === 'position' && p.type === 'position'),
  )
}

/**
 * How many heads a live effect's distribution runs over, for the Spread section's dials — the desk's
 * `FxEngine.buildExpansion`, counted. The first fixture stands for a group, as on the desk: where it
 * carries the effect's property itself ([carriesEffectProperty]) the effect runs once per fixture —
 * a group's members, unfiltered, or the one fixture — whatever heads it has (a pixel bar's master
 * dimmer). Otherwise it runs over the heads: one fixture's, narrowed by the head filter; a group's
 * under `FLAT` as one strip, the filter across the whole strip on its global index; under
 * `PER_FIXTURE` each member's own, filtered within it — the most any member keeps. A preview's
 * count, never sent.
 */
export function spreadHeadCount(
  fixtures: readonly { properties?: readonly CarrierProperty[] | null; elements?: readonly unknown[] | null }[],
  isGroup: boolean,
  propertyName: string,
  elementMode: string,
  elementFilter: string,
): number {
  const perFixture = isGroup ? fixtures.length : 1
  const first = fixtures[0]
  if (!first || carriesEffectProperty(first.properties, propertyName)) return perFixture
  const elementCounts = fixtures.map((f) => f.elements?.length ?? 0)
  if (!elementCounts.some((n) => n > 0)) return perFixture
  if (isGroup && elementMode === 'FLAT') {
    return filteredElementCount(
      elementCounts.reduce((sum, n) => sum + n, 0),
      elementFilter,
    )
  }
  return Math.max(...elementCounts.map((n) => filteredElementCount(n, elementFilter)))
}

/** One unit of a selection, as [selectionHeadCount] walks it. */
export interface SelectionUnit {
  key: string
  properties?: readonly CarrierProperty[] | null
  elements?: readonly { key: string; properties?: readonly CarrierProperty[] | null }[] | null
}

/**
 * [spreadHeadCount] for an effect over a selection (effects plan D3) — the desk's
 * `buildSelectionExpansion`, counted. Each unit, in selection order, is one member where it carries
 * the property itself, else its heads that carry it; a head reached twice counts once, at its first
 * place, and a unit left with nothing is skipped. With no unit its heads, the members are the count;
 * otherwise a member is a run of one head beside each fixture's run of its heads — one strip under
 * `FLAT`, filtered across it, else the most any run keeps. A preview's count, never sent.
 */
export function selectionHeadCount(
  units: readonly SelectionUnit[],
  propertyName: string,
  elementMode: string,
  elementFilter: string,
): number {
  const seen = new Set<string>()
  const runs: number[] = []
  let anyHeads = false
  for (const unit of units) {
    if (carriesEffectProperty(unit.properties, propertyName)) {
      if (!seen.has(unit.key)) {
        seen.add(unit.key)
        runs.push(1)
      }
      continue
    }
    const heads = (unit.elements ?? []).filter((e) => carriesEffectProperty(e.properties, propertyName) && !seen.has(e.key))
    for (const head of heads) seen.add(head.key)
    if (heads.length > 0) {
      runs.push(heads.length)
      anyHeads = true
    }
  }
  if (!anyHeads) return runs.length
  if (elementMode === 'FLAT') return filteredElementCount(runs.reduce((sum, n) => sum + n, 0), elementFilter)
  return Math.max(...runs.map((n) => filteredElementCount(n, elementFilter)))
}

/** How many of [n] elements the head filter keeps — the desk's `ElementFilter.includes`, counted. */
export function filteredElementCount(n: number, filter: string): number {
  switch (filter) {
    case 'ODD':
      return Math.ceil(n / 2)
    case 'EVEN':
      return Math.floor(n / 2)
    case 'FIRST_HALF':
      return Math.floor((n + 1) / 2)
    case 'SECOND_HALF':
      return n - Math.floor((n + 1) / 2)
    default:
      return n
  }
}

/** Whether head [index] of [total] is in a head filter — the desk's `ElementFilter.includes`, clause for clause. */
export function elementFilterIncludes(filter: string, index: number, total: number): boolean {
  const half = Math.floor((total + 1) / 2)
  switch (filter) {
    case 'ODD':
      return index % 2 === 0
    case 'EVEN':
      return index % 2 === 1
    case 'FIRST_HALF':
      return index < half
    case 'SECOND_HALF':
      return index >= half
    default:
      return true
  }
}

/** `1 run`, `3 runs` — the editor's Advanced summary and the pad face's word for an N-shot (D9). */
export function runsWord(nShot: number): string {
  return `${nShot} run${nShot === 1 ? '' : 's'}`
}

/** Two writes are the same write — `useLivePush`'s dedupe over a whole request. */
export function sameRequest(a: UpdateFxRequest, b: UpdateFxRequest): boolean {
  return JSON.stringify(a) === JSON.stringify(b)
}

// ─── The picker's families ─────────────────────────────────────────────────────────────────────

export type PickerFamily = 'INTENSITY' | 'COLOUR' | 'POSITION' | 'BEAM' | 'CONTROLS'

export const PICKER_FAMILIES: readonly PickerFamily[] = ['INTENSITY', 'COLOUR', 'POSITION', 'BEAM', 'CONTROLS']

export const PICKER_FAMILY_LABELS: Record<PickerFamily, string> = {
  INTENSITY: 'Intensity',
  COLOUR: 'Colour',
  POSITION: 'Position',
  BEAM: 'Beam',
  CONTROLS: 'Controls',
}

/**
 * The picker's segment an effect sits under, from its library category. A composite (Lightning
 * Strike) is applied through its dimmer half, so it is Intensity; a category the others do not name —
 * `controls`, or a script's own — is Controls, so nothing the library holds goes unlisted.
 */
export function pickerFamilyOf(category: string): PickerFamily {
  switch (category.toLowerCase()) {
    case 'dimmer':
    case 'composite':
      return 'INTENSITY'
    case 'colour':
    case 'color':
      return 'COLOUR'
    case 'position':
      return 'POSITION'
    case 'beam':
      return 'BEAM'
    default:
      return 'CONTROLS'
  }
}

/** The fixture sheet's row family as the picker's segment. */
export function pickerFamilyForSheet(family: string | null | undefined): PickerFamily | null {
  switch (family) {
    case 'INTENSITY':
    case 'COLOUR':
    case 'POSITION':
    case 'BEAM':
    case 'CONTROLS':
      return family
    default:
      return null
  }
}

// ─── A template's effect (D16) ─────────────────────────────────────────────────────────────────

/** The fields of a template's effect the editor's draft speaks — `TemplateEffect`'s, structurally. */
export interface TemplateEffectFields {
  parameters: Record<string, string>
  beatDivision: number
  blendMode: string
  distribution: string
  phaseOffset?: number
  speedMasterUuid?: string | null
  rateSpeedMasterUuid?: string | null
  /** D7's flag as stored — see `isOnSeconds`. */
  seconds?: boolean
  /** Size, direction and runs as stored (effects plan D2, D5, D9); absent at their defaults. */
  size?: number
  direction?: EffectDirection
  nShot?: number | null
  /** The spread's shape as stored (effects plan D4); absent at its defaults. */
  spread?: number
  wings?: number
  blocks?: number
  groups?: number
  /** The palette's resolved timing source, where the desk has said it. */
  timingSource?: string | null
}

/**
 * A template's effect as the editor's draft (fixture-fx-sheets plan D16): the same `FxDraft` a live
 * instance opens on, so `TemplateEditor` asks the *Centre* question through the same rules. The
 * fields a template has no answer for — step timing, element mode, the head filter — read as their
 * defaults and are never written back ([withTemplateDraft]).
 */
export function templateDraftOf(effect: TemplateEffectFields, timingSource?: string | null): FxDraft {
  return {
    parameters: { ...effect.parameters },
    beatDivision: effect.beatDivision,
    blendMode: effect.blendMode,
    phaseOffset: effect.phaseOffset ?? 0,
    stepTiming: false,
    distributionStrategy: effect.distribution,
    elementMode: 'PER_FIXTURE',
    elementFilter: 'ALL',
    speedMasterUuid: effect.speedMasterUuid ?? null,
    rateSpeedMasterUuid: effect.rateSpeedMasterUuid ?? null,
    seconds: isOnSeconds(effect, timingSource ?? effect.timingSource),
    size: effect.size ?? 1,
    direction: effect.direction ?? 'FORWARD',
    nShot: effect.nShot ?? null,
    spread: effect.spread ?? 1,
    wings: effect.wings ?? 1,
    blocks: effect.blocks ?? 1,
    groups: effect.groups ?? 0,
  }
}

/**
 * [effect] with the draft's fields written back — only the ones a template stores. A draft opened
 * on a running instance whose DTO said nothing about its distribution (a single fixture: the desk
 * leaves it out where it does not apply) holds `draftOf`'s `LINEAR` guess, so [distribution] false
 * keeps the template's own rather than writing the guess over it.
 */
export function withTemplateDraft<E extends TemplateEffectFields>(
  effect: E,
  draft: FxDraft,
  { distribution = true }: { distribution?: boolean } = {},
): E {
  return {
    ...effect,
    parameters: { ...draft.parameters },
    beatDivision: draft.beatDivision,
    blendMode: draft.blendMode,
    phaseOffset: draft.phaseOffset,
    distribution: distribution ? draft.distributionStrategy : effect.distribution,
    // Seconds follows no master (D7), so a draft on Seconds stores none beside the flag.
    speedMasterUuid: draft.seconds ? null : draft.speedMasterUuid,
    rateSpeedMasterUuid: draft.seconds ? null : draft.rateSpeedMasterUuid,
    // Written where it says something — a palette that never used Seconds stays as it was stored.
    ...(draft.seconds || effect.seconds != null ? { seconds: draft.seconds } : {}),
    // The same rule for size, direction and runs: said where they are not the default, or where the
    // palette already said them — a palette that never used them saves as it was stored.
    ...(draft.size !== 1 || effect.size != null ? { size: draft.size } : {}),
    ...(draft.direction !== 'FORWARD' || effect.direction != null ? { direction: draft.direction } : {}),
    ...(draft.nShot != null || effect.nShot != null ? { nShot: draft.nShot } : {}),
    // And the spread's shape (effects plan D4), each field on the same rule.
    ...(draft.spread !== 1 || effect.spread != null ? { spread: draft.spread } : {}),
    ...(draft.wings !== 1 || effect.wings != null ? { wings: draft.wings } : {}),
    ...(draft.blocks !== 1 || effect.blocks != null ? { blocks: draft.blocks } : {}),
    ...(draft.groups !== 0 || effect.groups != null ? { groups: draft.groups } : {}),
  }
}

// ─── A cue's stored effect (show-cue-editing plan §4.1, D17) ──────────────────────────────────

/**
 * The fields of a cue's ad-hoc effect the draft speaks — `CueAdHocEffect`'s, structurally: a
 * template's, plus the three a target answers (step timing, element mode, the head filter).
 */
export interface CueEffectFields extends TemplateEffectFields {
  phaseOffset: number
  stepTiming?: boolean | null
  elementMode?: string | null
  elementFilter?: string | null
}

/**
 * A cue's stored effect as the editor's draft: [templateDraftOf]'s reading — Seconds, size,
 * direction, runs and the spread's shape as stored — with the target's three fields read too, each
 * absent at its default.
 */
export function cueEffectDraftOf(effect: CueEffectFields, timingSource?: string | null): FxDraft {
  return {
    ...templateDraftOf(effect, timingSource),
    stepTiming: effect.stepTiming ?? false,
    elementMode: effect.elementMode ?? 'PER_FIXTURE',
    elementFilter: effect.elementFilter ?? 'ALL',
  }
}

/**
 * [effect] with the draft written back, every field a cue stores: [withTemplateDraft]'s, and the
 * target's three on its rule — said where the draft moved off the default or the effect already
 * said it, so an effect that never named an element mode saves as it was stored. Everything else on
 * the effect (its target, its fire timing, its uuid) is kept.
 */
export function withCueEffectDraft<E extends CueEffectFields>(effect: E, draft: FxDraft): E {
  return {
    ...withTemplateDraft(effect, draft),
    ...(draft.stepTiming || effect.stepTiming != null ? { stepTiming: draft.stepTiming } : {}),
    ...(draft.elementMode !== 'PER_FIXTURE' || effect.elementMode != null ? { elementMode: draft.elementMode } : {}),
    ...(draft.elementFilter !== 'ALL' || effect.elementFilter != null ? { elementFilter: draft.elementFilter } : {}),
  }
}

/**
 * **A cue's effect is read-only in the tray** (Fx board, *Open*; fixture-fx-sheets session 5): it
 * names its cue and has no editor, pause or stop — its home is the cue, whose next GO would put
 * back anything changed here (W5 refuses a cue's instance for the same reason). Session 3 left it
 * editable, as `ActiveEffectSheet` had; a *pad's* instance is edited (D20), being the programmer's.
 * The open row's naming of the cue is the board's *opens it*: a button that opens the cue's **cue
 * editor** in place, through `OwnerEditor` — the Stage view's *Moves with* path, not a second one.
 */
export function isCueEffect(effect: Pick<ActiveEffect, 'programmerOwned' | 'cueId'>): boolean {
  return !effect.programmerOwned && effect.cueId != null
}

/**
 * Was this instance spawned by a **programmer** template layer — a pad's effect — and so has a
 * template to compare with, update and reset to (fixture-fx-sheets plan §3.3, D20)? A cue's
 * template-layer instance is not: W5 refuses it (`FX_NOT_FROM_PALETTE`), since the next GO
 * respawns it. A *detached* copy (*Apply as values* on an effect palette's chip) carries no source.
 */
export function isTemplateLayerInstance(effect: Pick<ActiveEffect, 'templateId' | 'programmerLayerId' | 'cueId'>): boolean {
  return effect.templateId != null && effect.programmerLayerId != null && effect.cueId == null
}

/** Two parameter strings say the same thing — `'128'` and `'128.0'` both. */
function sameParam(a: string | undefined, b: string | undefined): boolean {
  if (a === b) return true
  if (a == null || b == null) return false
  const x = Number(a)
  const y = Number(b)
  return a.trim() !== '' && b.trim() !== '' && Number.isFinite(x) && Number.isFinite(y) && x === y
}

/**
 * *Edited* (§3.3, D20): does a running instance — as the editor's draft, or read off the wire
 * through `draftOf` — differ from the template's effect it was spawned from? Every field a template
 * stores, each compared as the desk would read it: a null speed master is master 1
 * ([master1Uuid]), a parameter either side omits is the library's default ([defaults]), and a
 * number is a number however it is spelled. The distribution is compared only where the instance
 * has one to say ([distribution]) — a single fixture's DTO leaves it out, and `draftOf`'s `LINEAR`
 * for the absence would read every template on another distribution as *edited*.
 */
export function differsFromTemplate(
  draft: FxDraft,
  effect: TemplateEffectFields,
  master1Uuid: string | null,
  defaults: Record<string, string> = {},
  { distribution = true }: { distribution?: boolean } = {},
): boolean {
  if (Math.abs(draft.beatDivision - effect.beatDivision) > 1e-6) return true
  if (draft.blendMode.toUpperCase() !== effect.blendMode.toUpperCase()) return true
  if (Math.abs(draft.phaseOffset - (effect.phaseOffset ?? 0)) > 1e-6) return true
  if (distribution && draft.distributionStrategy !== effect.distribution) return true
  if ((draft.speedMasterUuid ?? master1Uuid) !== (effect.speedMasterUuid ?? master1Uuid)) return true
  if ((draft.rateSpeedMasterUuid ?? null) !== (effect.rateSpeedMasterUuid ?? null)) return true
  if (draft.seconds !== isOnSeconds(effect, effect.timingSource)) return true
  if (Math.abs(draft.size - (effect.size ?? 1)) > 1e-6) return true
  if (draft.direction !== (effect.direction ?? 'FORWARD')) return true
  if ((draft.nShot ?? null) !== (effect.nShot ?? null)) return true
  if (Math.abs(draft.spread - (effect.spread ?? 1)) > 1e-6) return true
  if (draft.wings !== (effect.wings ?? 1)) return true
  if (draft.blocks !== (effect.blocks ?? 1)) return true
  if (draft.groups !== (effect.groups ?? 0)) return true
  const names = new Set([...Object.keys(draft.parameters), ...Object.keys(effect.parameters)])
  for (const name of names) {
    if (!sameParam(draft.parameters[name] ?? defaults[name], effect.parameters[name] ?? defaults[name])) return true
  }
  return false
}
