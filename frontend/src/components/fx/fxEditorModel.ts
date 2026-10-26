import type { FxDirection, Timing, UpdateFxRequest } from '@/api/fxApi'
import type { ActiveFx, FxLibraryEntry, FxParamUnit, FxParameterDef } from '@/store/fixtureFx'
import { beatsToSeconds, isOnSeconds, secondsToBeats } from '@/lib/fxTiming'
import { isMirroredOrder } from '@/lib/spreadShape'
import { CENTRE_PARAMS, hasCentre, defaultParameters, withCentreMode } from './centreMode'
import { STORED_ELEMENT_MODE } from './fxConstants'

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

/** The level effects — the intensity category, D12a's *Replace it | Within it*. */
export function isLevelFx(entry: Pick<FxLibraryEntry, 'family'>): boolean {
  return entry.family === 'intensity'
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
  entry: Pick<FxLibraryEntry, 'family' | 'parameters'>,
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

export function paramRole(entry: Pick<FxLibraryEntry, 'family' | 'parameters'>, param: FxParameterDef): ParamRole {
  const type = param.type.toLowerCase()
  if (type === 'easingcurve' || type === 'boolean') return 'shape'
  if (type === 'double' || type === 'float') {
    return entry.parameters.some((p) => p.type.toLowerCase() === 'ubyte') ? 'shape' : 'main'
  }
  if (type !== 'ubyte') return 'main'
  if (entry.family === 'position' && paramAxis(param.name) != null) {
    if ((CENTRE_PARAMS as readonly string[]).includes(param.name)) return 'centre'
    if (/(Radius|Range)$/.test(param.name)) return 'size'
    return 'axis'
  }
  return 'level'
}

/**
 * Which axis a position parameter moves, from its name — `pan` / `tilt` as a camelCase word
 * (`panCentre`, `startPan`, `pan`), never a substring, so a script's `spanWidth` or `expansion` is
 * not read as a pan axis and drawn on the position scale.
 */
export function paramAxis(name: string): 'pan' | 'tilt' | null {
  if (/(^pan|Pan)(?![a-z])/.test(name)) return 'pan'
  if (/(^tilt|Tilt)(?![a-z])/.test(name)) return 'tilt'
  return null
}

/** A ratio parameter (a 0–1 double) is drawn as a percent. */
export function isRatioParam(param: FxParameterDef): boolean {
  const type = param.type.toLowerCase()
  return (type === 'double' || type === 'float') && Number(param.defaultValue) <= 1
}

/** The board's words for the built-ins' parameters, for a parameter that declares no label. */
const PARAM_LABELS: Record<string, string> = {
  panCentre: 'Pan',
  tiltCentre: 'Tilt',
  panRadius: 'Pan size',
  tiltRadius: 'Tilt size',
  panRange: 'Pan range',
  tiltRange: 'Tilt range',
  min: 'Low',
  max: 'High',
  attackRatio: 'Attack',
  holdRatio: 'Hold',
  fadeRatio: 'Fade',
  dutyCycle: 'Duty',
  onRatio: 'On',
}

/** A parameter's guessed label: the board's word for it, else its name as words. */
export function paramLabel(name: string): string {
  return (
    PARAM_LABELS[name] ??
    name
      .replace(/([A-Z])/g, ' $1')
      .replace(/^./, (s) => s.toUpperCase())
      .trim()
  )
}

/** How the editor draws one parameter: its label, unit and range, and whether it folds under *More*. */
export interface ParamView {
  label: string
  unit: FxParamUnit | null
  min: number
  max: number
  step: number
  fold: 'main' | 'detail'
  /** A guessed range (no `min` or `max` declared): the field reads values past it, as an older row may hold. */
  open: boolean
}

/** Whether the desk declared anything about [param] beyond its name, type, default and description. */
export function declaresMetadata(param: FxParameterDef): boolean {
  return (
    param.label != null ||
    param.min != null ||
    param.max != null ||
    param.step != null ||
    param.unit != null ||
    param.role != null ||
    param.shownWhen != null ||
    param.pair != null ||
    (param.options != null && param.type.toLowerCase() === 'choice')
  )
}

/**
 * [param] as the editor draws it (fx-settings plan D1): what the desk declares, each field it leaves
 * out guessed from the name and type ([paramRole]) — all of them for a script FX's parameter that
 * declares nothing.
 */
export function paramView(entry: Pick<FxLibraryEntry, 'family' | 'parameters'>, param: FxParameterDef): ParamView {
  const guess = guessedView(entry, param)
  if (!declaresMetadata(param)) return guess
  const ranged = param.min != null || param.max != null
  return {
    label: param.label ?? guess.label,
    unit: param.unit ?? guess.unit,
    min: param.min ?? guess.min,
    max: param.max ?? guess.max,
    step: param.step ?? guess.step,
    fold: param.role ?? guess.fold,
    open: guess.open && !ranged,
  }
}

function guessedView(entry: Pick<FxLibraryEntry, 'family' | 'parameters'>, param: FxParameterDef): ParamView {
  const type = param.type.toLowerCase()
  const role = paramRole(entry, param)
  const fold = role === 'shape' ? 'detail' : 'main'
  const label = paramLabel(param.name)
  if (type === 'ubyte') {
    const unit: FxParamUnit | null =
      role === 'centre' || role === 'axis' ? 'position' : role === 'size' ? 'dmx' : role === 'level' ? '%' : null
    return { label, unit, min: 0, max: 255, step: 1, fold, open: false }
  }
  if (type === 'double' || type === 'float') {
    return isRatioParam(param)
      ? { label, unit: 'ratio', min: 0, max: 1, step: 0.01, fold, open: false }
      : { label, unit: null, min: 0, max: 10, step: 0.1, fold, open: true }
  }
  if (type === 'int') {
    // From the declared default and nothing else, so the range never moves under a drag.
    const max = Math.max(255, (Number(param.defaultValue) || 0) * 2)
    return { label, unit: /Ms$/.test(param.name) ? 'ms' : null, min: 0, max, step: 1, fold, open: true }
  }
  return { label, unit: null, min: 0, max: 0, step: 1, fold, open: false }
}

/** Whether [param] is drawn while the FX holds [parameters] — its `shownWhen`, against the library's defaults. */
export function isParamShown(
  entry: Pick<FxLibraryEntry, 'parameters'>,
  param: FxParameterDef,
  parameters: Record<string, string>,
): boolean {
  const when = param.shownWhen
  if (when == null) return true
  const other = entry.parameters.find((p) => p.name === when.param)
  if (other == null) return true
  const value = (parameters[other.name] || other.defaultValue).trim().toUpperCase()
  return when.values.some((v) => v.toUpperCase() === value)
}

export function clampByte(n: number): number {
  return Math.max(0, Math.min(255, Math.round(n)))
}

// ─── The draft and its write ───────────────────────────────────────────────────────────────────

/** Everything the editor edits on a running effect. */
export interface FxDraft {
  parameters: Record<string, string>
  cycle: number
  blendMode: string
  phaseOffset: number
  timing: Timing
  order: string
  elementMode: string
  elementFilter: string
  /** Null → master 1, as the instance stores it. */
  speedMasterUuid: string | null
  /** Null → unscaled. */
  rateSpeedMasterUuid: string | null
  /**
   * D7's *Seconds* (venue-visit plan S15): the effect follows no master. A beat effect's
   * [cycle] is then seconds and both masters are null; a wall-clock effect's division is
   * seconds either way, and its Seconds is a null [rateSpeedMasterUuid].
   */
  seconds: boolean
  /** Amount (effects plan D2), 0–1 — how much of the effect reaches the stage over what is underneath. */
  amount: number
  /** Direction (D5): which way each head runs its cycle. */
  direction: FxDirection
  /** Runs (D9): stop after this many cycles; null runs forever. */
  runs: number | null
  /**
   * The spread's shape (effects plan D4): Spread 0–2, Wings and Segments at least 1, Parts 0 (off) or
   * more. The desk shapes the heads from them (P2); the editor's dials only preview it
   * (`lib/spreadShape.ts`).
   */
  spread: number
  wings: number
  segments: number
  parts: number
}

/**
 * The draft an editor opens on — and so the snapshot Revert puts back. The DTO omits a
 * distribution, element mode or filter it has nothing to say about, so the absences read as the
 * defaults they stand for: `NORMAL`, `PER_FIXTURE`, `ALL`.
 */
export function draftOf(effect: ActiveFx): FxDraft {
  return {
    parameters: { ...effect.parameters },
    cycle: effect.cycle,
    blendMode: effect.blendMode,
    phaseOffset: effect.phaseOffset,
    timing: effect.timing ?? 'CYCLE',
    order: effect.order ?? 'NORMAL',
    elementMode: effect.elementMode ?? STORED_ELEMENT_MODE,
    elementFilter: effect.elementFilter ?? 'ALL',
    speedMasterUuid: effect.speedMasterUuid ?? null,
    rateSpeedMasterUuid: effect.rateSpeedMasterUuid ?? null,
    // The desk reports D7's reading; a desk before S15 does not, and then a wall-clock effect with
    // no rate master is the only Seconds there is.
    seconds: isOnSeconds(effect, effect.timingSource),
    // Absent on the wire at their defaults (the desk's socket encodes no default), and from a desk
    // before effects plan session 1.
    amount: effect.amount ?? 1,
    direction: effect.direction ?? 'FORWARD',
    runs: effect.runs ?? null,
    // The same for the spread's shape (effects plan session 2).
    spread: effect.spread ?? 1,
    wings: effect.wings ?? 1,
    segments: effect.segments ?? 1,
    parts: effect.parts ?? 0,
  }
}

/** Which of the optional fields this effect's target can take — the editor shows only those. */
export interface FxScope {
  /** A group, or a fixture with heads: Order and Timing. */
  heads: boolean
  /** A target with a fixture that has elements: *Over*. */
  elementMode: boolean
  /** A fixture with heads, or a group with multi-element members. */
  elementFilter: boolean
  /**
   * Timing, where it differs from [heads]: a palette's FX offers its Order (the layer supplies the
   * heads) but not Timing, which is a question about one head's elements.
   */
  timing?: boolean
  /**
   * How many heads the effect runs over, for the Spread section's dials — a live effect's own (they
   * draw up to sixteen, then *+n*). Absent on an effect palette, which has no target: the dials draw
   * eight notional heads (effects plan §4.1).
   */
  headCount?: number
}

/** The fields the DTO may leave out, and so the editor sends only once the operator has set them. */
export type OptionalField =
  | 'timing'
  | 'order'
  | 'elementMode'
  | 'elementFilter'
  | 'speedMasterUuid'
  | 'rateSpeedMasterUuid'
  | 'seconds'
  | 'amount'
  | 'direction'
  | 'runs'
  | 'spread'
  | 'wings'
  | 'segments'
  | 'parts'

export const OPTIONAL_FIELDS: readonly OptionalField[] = [
  'timing',
  'order',
  'elementMode',
  'elementFilter',
  'speedMasterUuid',
  'rateSpeedMasterUuid',
  'seconds',
  'amount',
  'direction',
  'runs',
  'spread',
  'wings',
  'segments',
  'parts',
]

/**
 * One `updateFx` frame for the draft — every write carries the parameters, speed, blend and phase,
 * so a floored push that drops an intermediate move can never drop a *different* field the operator
 * set in the same window. `fxType` is never sent: the editor edits one type, and a type swap is
 * the picker's.
 *
 * The optional fields go only once [touched]: the DTO leaves a distribution, element mode or filter
 * out where it does not apply (`FxDto`'s `showOrder`), so the draft's default for an
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
 * Amount, direction and runs (effects plan D2, D5, D9) ride once touched too, which keeps a frame to
 * a desk that predates them the shape it always was; a null count is said as `runForever`, since
 * the frame's absent `runs` means "keep".
 *
 * The spread's shape (effects plan D4) rides once touched too, each field on its own; parts 0 is how
 * the frame turns them off, so a cleared Parts needs no flag of its own.
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
    cycle: draft.cycle,
    blendMode: draft.blendMode,
    phaseOffset: draft.phaseOffset,
    ...(touched.has('timing') ? { timing: draft.timing } : {}),
    ...(touched.has('order') ? { order: draft.order } : {}),
    ...(touched.has('elementMode') ? { elementMode: draft.elementMode } : {}),
    ...(touched.has('elementFilter') ? { elementFilter: draft.elementFilter } : {}),
    ...(touched.has('seconds') ? { seconds: draft.seconds } : {}),
    ...(touched.has('amount') ? { amount: draft.amount } : {}),
    ...(touched.has('direction') ? { direction: draft.direction } : {}),
    ...(touched.has('runs') ? (draft.runs == null ? { runForever: true } : { runs: draft.runs }) : {}),
    ...(touched.has('spread') ? { spread: draft.spread } : {}),
    ...(touched.has('wings') ? { wings: draft.wings } : {}),
    ...(touched.has('segments') ? { segments: draft.segments } : {}),
    ...(touched.has('parts') ? { parts: draft.parts } : {}),
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
  draft: Pick<FxDraft, 'cycle' | 'seconds' | 'speedMasterUuid' | 'rateSpeedMasterUuid'>,
  wallClock: boolean,
  choice: string | null,
  bpmOf: (uuid: string | null) => number,
): Partial<FxDraft> {
  if (choice == null) {
    return {
      seconds: true,
      speedMasterUuid: null,
      rateSpeedMasterUuid: null,
      ...(!wallClock && !draft.seconds ? { cycle: beatsToSeconds(draft.cycle, bpmOf(draft.speedMasterUuid)) } : {}),
    }
  }
  if (wallClock) return { seconds: false, rateSpeedMasterUuid: choice }
  return {
    seconds: false,
    speedMasterUuid: choice,
    ...(draft.seconds ? { cycle: secondsToBeats(draft.cycle, bpmOf(choice)) } : {}),
  }
}

/**
 * The draft patch for choosing an Order (effects plan D4): the Order, and — onto a mirrored one
 * (Centre out, Centre in, two wings already) — Wings back to 1 in the same write, since the
 * desk refuses wings under a mirrored Order and the editor hides the field there.
 */
export function orderPatch(draft: Pick<FxDraft, 'wings'>, order: string): Partial<FxDraft> {
  return isMirroredOrder(order) && draft.wings > 1 ? { order: order, wings: 1 } : { order: order }
}

/** The descriptors [spreadHeadCount] reads: a fixture's own properties, by name and type. */
type CarrierAttribute = { name: string; type: string }

/**
 * Whether a fixture carries the effect's property **itself** rather than only on its heads — the
 * desk's `FxTarget.fixtureHasAttribute`: a slider or a setting by name, a colour by the colour trait
 * (`WithColour`, a `colour` or `whiteMix` descriptor), a position by `WithPosition`.
 */
export function carriesFxAttribute(attributes: readonly CarrierAttribute[] | null | undefined, attribute: string): boolean {
  return (attributes ?? []).some(
    (p) =>
      p.name === attribute ||
      (attribute === 'rgbColour' && (p.type === 'colour' || p.type === 'whiteMix')) ||
      (attribute === 'position' && p.type === 'position'),
  )
}

/**
 * How many heads a live effect's distribution runs over, for the Spread section's dials — the desk's
 * `FxEngine.buildExpansion`, counted. The first fixture stands for a group, as on the desk: where it
 * carries the effect's property itself ([carriesFxAttribute]) the effect runs once per fixture —
 * a group's members, unfiltered, or the one fixture — whatever heads it has (a pixel bar's master
 * dimmer). Otherwise it runs over the heads: under `WHOLE` one unit per fixture with heads; one
 * fixture's, narrowed by the head filter; a group's under `FLAT` as one strip, the filter across the
 * whole strip on its global index; under `PER_FIXTURE` each member's own, filtered within it — the
 * most any member keeps. A preview's count, never sent.
 */
export function spreadHeadCount(
  fixtures: readonly { attributes?: readonly CarrierAttribute[] | null; elements?: readonly unknown[] | null }[],
  isGroup: boolean,
  attribute: string,
  elementMode: string,
  elementFilter: string,
): number {
  const perFixture = isGroup ? fixtures.length : 1
  const first = fixtures[0]
  if (!first || carriesFxAttribute(first.attributes, attribute)) return perFixture
  const elementCounts = fixtures.map((f) => f.elements?.length ?? 0)
  if (!elementCounts.some((n) => n > 0)) return perFixture
  if (elementMode === 'WHOLE') return isGroup ? elementCounts.filter((n) => n > 0).length : 1
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
  attributes?: readonly CarrierAttribute[] | null
  elements?: readonly { key: string; attributes?: readonly CarrierAttribute[] | null }[] | null
}

/**
 * [spreadHeadCount] for an effect over a selection (effects plan D3) — the desk's
 * `buildSelectionExpansion`, counted. Each unit, in selection order, is one member where it carries
 * the property itself, else its heads that carry it; a head reached twice counts once, at its first
 * place, and a unit left with nothing is skipped. With no unit its heads, the members are the count;
 * otherwise a member is a run of one head beside each fixture's run of its heads — one unit each under
 * `WHOLE`, one strip under `FLAT`, filtered across it, else the most any run keeps. A preview's count,
 * never sent.
 */
export function selectionHeadCount(
  units: readonly SelectionUnit[],
  attribute: string,
  elementMode: string,
  elementFilter: string,
): number {
  const seen = new Set<string>()
  const runs: number[] = []
  let anyHeads = false
  for (const unit of units) {
    if (carriesFxAttribute(unit.attributes, attribute)) {
      if (!seen.has(unit.key)) {
        seen.add(unit.key)
        runs.push(1)
      }
      continue
    }
    const heads = (unit.elements ?? []).filter((e) => carriesFxAttribute(e.attributes, attribute) && !seen.has(e.key))
    for (const head of heads) seen.add(head.key)
    if (heads.length > 0) {
      runs.push(heads.length)
      anyHeads = true
    }
  }
  if (!anyHeads || elementMode === 'WHOLE') return runs.length
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
export function runsWord(runs: number): string {
  return `${runs} run${runs === 1 ? '' : 's'}`
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
 * The picker's segment an effect sits under, from the category it drives ([drivenCategoryOf]). A
 * category the others do not name — `controls`, or a script's own — is Controls, so nothing the
 * library holds goes unlisted.
 */
export function pickerFamilyOf(category: string): PickerFamily {
  switch (category.toLowerCase()) {
    case 'intensity':
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

/** The fields of a template's effect the editor's draft speaks — `PaletteFx`'s, structurally. */
export interface PaletteFxFields {
  parameters: Record<string, string>
  cycle: number
  blendMode: string
  order: string
  phaseOffset?: number
  speedMasterUuid?: string | null
  rateSpeedMasterUuid?: string | null
  /** D7's flag as stored — see `isOnSeconds`. */
  seconds?: boolean
  /** Amount, direction and runs as stored (effects plan D2, D5, D9); absent at their defaults. */
  amount?: number
  direction?: FxDirection
  runs?: number | null
  /** The spread's shape as stored (effects plan D4); absent at its defaults. */
  spread?: number
  wings?: number
  segments?: number
  parts?: number
  /** The palette's resolved timing source, where the desk has said it. */
  timingSource?: string | null
  /** *Over* as stored; absent is Each fixture. */
  elementMode?: string | null
}

/**
 * A template's effect as the editor's draft (fixture-fx-sheets plan D16): the same `FxDraft` a live
 * instance opens on, so `PaletteEditor` asks the *Centre* question through the same rules. The
 * fields a template has no answer for — Timing and the head filter — read as their defaults and are
 * never written back ([withPaletteDraft]); its *Over* is its own.
 */
export function paletteDraftOf(effect: PaletteFxFields, timingSource?: string | null): FxDraft {
  return {
    parameters: { ...effect.parameters },
    cycle: effect.cycle,
    blendMode: effect.blendMode,
    phaseOffset: effect.phaseOffset ?? 0,
    timing: 'CYCLE',
    order: effect.order,
    elementMode: effect.elementMode ?? STORED_ELEMENT_MODE,
    elementFilter: 'ALL',
    speedMasterUuid: effect.speedMasterUuid ?? null,
    rateSpeedMasterUuid: effect.rateSpeedMasterUuid ?? null,
    seconds: isOnSeconds(effect, timingSource ?? effect.timingSource),
    amount: effect.amount ?? 1,
    direction: effect.direction ?? 'FORWARD',
    runs: effect.runs ?? null,
    spread: effect.spread ?? 1,
    wings: effect.wings ?? 1,
    segments: effect.segments ?? 1,
    parts: effect.parts ?? 0,
  }
}

/**
 * [effect] with the draft's fields written back — only the ones a palette stores. A draft opened
 * on a running instance whose DTO said nothing about its distribution (a single fixture: the desk
 * leaves it out where it does not apply) holds `draftOf`'s `NORMAL` guess, so [order] false
 * keeps the palette's own rather than writing the guess over it; [elementMode] false does the same
 * for an element mode the instance did not report.
 */
export function withPaletteDraft<E extends PaletteFxFields>(
  effect: E,
  draft: FxDraft,
  { order = true, elementMode = true }: { order?: boolean; elementMode?: boolean } = {},
): E {
  return {
    ...effect,
    parameters: { ...draft.parameters },
    cycle: draft.cycle,
    blendMode: draft.blendMode,
    phaseOffset: draft.phaseOffset,
    order: order ? draft.order : effect.order,
    // Said where it is not the stored default, or where the palette already said one.
    ...(elementMode && (draft.elementMode !== STORED_ELEMENT_MODE || effect.elementMode != null)
      ? { elementMode: draft.elementMode }
      : {}),
    // Seconds follows no master (D7), so a draft on Seconds stores none beside the flag.
    speedMasterUuid: draft.seconds ? null : draft.speedMasterUuid,
    rateSpeedMasterUuid: draft.seconds ? null : draft.rateSpeedMasterUuid,
    // Written where it says something — a palette that never used Seconds stays as it was stored.
    ...(draft.seconds || effect.seconds != null ? { seconds: draft.seconds } : {}),
    // The same rule for amount, direction and runs: said where they are not the default, or where the
    // palette already said them — a palette that never used them saves as it was stored.
    ...(draft.amount !== 1 || effect.amount != null ? { amount: draft.amount } : {}),
    ...(draft.direction !== 'FORWARD' || effect.direction != null ? { direction: draft.direction } : {}),
    ...(draft.runs != null || effect.runs != null ? { runs: draft.runs } : {}),
    // And the spread's shape (effects plan D4), each field on the same rule.
    ...(draft.spread !== 1 || effect.spread != null ? { spread: draft.spread } : {}),
    ...(draft.wings !== 1 || effect.wings != null ? { wings: draft.wings } : {}),
    ...(draft.segments !== 1 || effect.segments != null ? { segments: draft.segments } : {}),
    ...(draft.parts !== 0 || effect.parts != null ? { parts: draft.parts } : {}),
  }
}

// ─── A cue's stored effect (show-cue-editing plan §4.1, D17) ──────────────────────────────────

/**
 * The fields of a cue's ad-hoc effect the draft speaks — `CueAdHocFx`'s, structurally: a
 * template's, plus the three a target answers (Timing, element mode, the head filter).
 */
export interface CueFxFields extends PaletteFxFields {
  phaseOffset: number
  timing?: Timing | null
  elementMode?: string | null
  elementFilter?: string | null
}

/**
 * A cue's stored effect as the editor's draft: [paletteDraftOf]'s reading — Seconds, amount,
 * direction, runs and the spread's shape as stored — with the target's three fields read too, each
 * absent at its default.
 */
export function cueFxDraftOf(effect: CueFxFields, timingSource?: string | null): FxDraft {
  return {
    ...paletteDraftOf(effect, timingSource),
    timing: effect.timing ?? 'CYCLE',
    elementMode: effect.elementMode ?? STORED_ELEMENT_MODE,
    elementFilter: effect.elementFilter ?? 'ALL',
  }
}

/**
 * [effect] with the draft written back, every field a cue stores: [withPaletteDraft]'s, and the
 * target's three on its rule — said where the draft moved off the default or the effect already
 * said it, so an effect that never named an element mode saves as it was stored. Everything else on
 * the effect (its target, its fire timing, its uuid) is kept.
 */
export function withCueFxDraft<E extends CueFxFields>(effect: E, draft: FxDraft): E {
  return {
    ...withPaletteDraft(effect, draft),
    ...(draft.timing !== 'CYCLE' || effect.timing != null ? { timing: draft.timing } : {}),
    ...(draft.elementMode !== STORED_ELEMENT_MODE || effect.elementMode != null ? { elementMode: draft.elementMode } : {}),
    ...(draft.elementFilter !== 'ALL' || effect.elementFilter != null ? { elementFilter: draft.elementFilter } : {}),
  }
}

/**
 * **A cue's effect is read-only in the tray** (Fx board, *Open*; fixture-fx-sheets session 5): it
 * names its cue and has no editor, pause or stop — its home is the cue, whose next GO would put
 * back anything changed here (W5 refuses a cue's instance for the same reason). A *pad's* instance
 * is edited (D20), being the programmer's.
 * The open row's naming of the cue is the board's *opens it*: a button that opens the cue's **cue
 * editor** in place, through `OwnerEditor` — the Stage view's *Moves with* path, not a second one.
 */
export function isCueFx(effect: Pick<ActiveFx, 'programmerOwned' | 'cueId'>): boolean {
  return !effect.programmerOwned && effect.cueId != null
}

/**
 * Was this instance spawned by a **programmer** template layer — a pad's effect — and so has a
 * template to compare with, update and reset to (fixture-fx-sheets plan §3.3, D20)? A cue's
 * template-layer instance is not: W5 refuses it (`FX_NOT_FROM_PALETTE`), since the next GO
 * respawns it. A *detached* copy (*Apply as values* on an effect palette's chip) carries no source.
 */
export function isPaletteLayerInstance(effect: Pick<ActiveFx, 'paletteId' | 'programmerLayerId' | 'cueId'>): boolean {
  return effect.paletteId != null && effect.programmerLayerId != null && effect.cueId == null
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
 * number is a number however it is spelled. The Order is compared only where the instance
 * has one to say ([order]) — a single fixture's DTO leaves it out, and `draftOf`'s `NORMAL`
 * for the absence would read every template on another distribution as *edited*.
 */
export function differsFromPalette(
  draft: FxDraft,
  effect: PaletteFxFields,
  master1Uuid: string | null,
  defaults: Record<string, string> = {},
  { order = true, elementMode = true }: { order?: boolean; elementMode?: boolean } = {},
): boolean {
  if (Math.abs(draft.cycle - effect.cycle) > 1e-6) return true
  if (draft.blendMode.toUpperCase() !== effect.blendMode.toUpperCase()) return true
  if (Math.abs(draft.phaseOffset - (effect.phaseOffset ?? 0)) > 1e-6) return true
  if (order && draft.order !== effect.order) return true
  if (elementMode && draft.elementMode !== (effect.elementMode ?? STORED_ELEMENT_MODE)) return true
  if ((draft.speedMasterUuid ?? master1Uuid) !== (effect.speedMasterUuid ?? master1Uuid)) return true
  if ((draft.rateSpeedMasterUuid ?? null) !== (effect.rateSpeedMasterUuid ?? null)) return true
  if (draft.seconds !== isOnSeconds(effect, effect.timingSource)) return true
  if (Math.abs(draft.amount - (effect.amount ?? 1)) > 1e-6) return true
  if (draft.direction !== (effect.direction ?? 'FORWARD')) return true
  if ((draft.runs ?? null) !== (effect.runs ?? null)) return true
  if (Math.abs(draft.spread - (effect.spread ?? 1)) > 1e-6) return true
  if (draft.wings !== (effect.wings ?? 1)) return true
  if (draft.segments !== (effect.segments ?? 1)) return true
  if (draft.parts !== (effect.parts ?? 0)) return true
  const names = new Set([...Object.keys(draft.parameters), ...Object.keys(effect.parameters)])
  for (const name of names) {
    if (!sameParam(draft.parameters[name] ?? defaults[name], effect.parameters[name] ?? defaults[name])) return true
  }
  return false
}
