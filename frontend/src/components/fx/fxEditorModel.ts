import type { UpdateFxRequest } from '@/api/fxApi'
import type { ActiveEffect, EffectLibraryEntry, EffectParameterDef } from '@/store/fixtureFx'
import type { Fixture, SliderPropertyDescriptor } from '@/store/fixtures'
import { findPanProperty, findTiltProperty } from '@/store/fixtures'
import { annotatesDegrees } from '@/lib/axisDegrees'
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
 * as, which parameters are the effect's *shape*, how a size is said in degrees and what one write
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
 * - `size` — a radius or a range on an axis: degrees where the head annotates, else bytes.
 * - `axis` — any other position byte (a sweep's ends): an absolute position on its axis.
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
 * not read as a pan axis and drawn in degrees.
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

// ─── Degrees ────────────────────────────────────────────────────────────────────────────────────

/** The annotated pan and tilt a position effect's degrees are read through. */
export interface PositionAxes {
  pan: SliderPropertyDescriptor & { degMin: number; degMax: number }
  tilt: SliderPropertyDescriptor & { degMin: number; degMax: number }
}

/**
 * The axes the editor speaks degrees through, or null for bytes (D14's rule, the position cell's):
 * every fixture the effect moves must annotate **both** pan and tilt, and the first one's ranges
 * are the field's. A target mixing an annotated mover and a silent head keeps bytes for all of
 * them rather than two units in one editor.
 */
export function positionAxesOf(fixtures: readonly Fixture[]): PositionAxes | null {
  let lead: PositionAxes | null = null
  for (const fixture of fixtures) {
    const props = [...(fixture.properties ?? []), ...(fixture.elementGroupProperties ?? [])] as Fixture['properties']
    const pan = findPanProperty(props)
    const tilt = findTiltProperty(props)
    if (pan == null && tilt == null) continue
    if (!annotatesDegrees(pan) || !annotatesDegrees(tilt)) return null
    lead ??= { pan, tilt }
  }
  return lead
}

/**
 * A size in bytes as degrees of travel — 28 of 255 is 59° on a 540° pan (Fx board, note 3). A size
 * is a distance, so it is the byte's share of the axis's span, never a position on it.
 */
export function sizeToDegrees(bytes: number, axis: SliderPropertyDescriptor & { degMin: number; degMax: number }): number {
  const span = axis.max - axis.min
  if (span <= 0) return 0
  return (bytes * Math.abs(axis.degMax - axis.degMin)) / span
}

/** The inverse, rounded to a whole byte and held to 0–255. */
export function degreesToSize(deg: number, axis: SliderPropertyDescriptor & { degMin: number; degMax: number }): number {
  const degSpan = Math.abs(axis.degMax - axis.degMin)
  if (degSpan === 0) return 0
  return clampByte(Math.round((deg * (axis.max - axis.min)) / degSpan))
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
}

/** The fields the DTO may leave out, and so the editor sends only once the operator has set them. */
export type OptionalField =
  | 'stepTiming'
  | 'distributionStrategy'
  | 'elementMode'
  | 'elementFilter'
  | 'speedMasterUuid'
  | 'rateSpeedMasterUuid'

export const OPTIONAL_FIELDS: readonly OptionalField[] = [
  'stepTiming',
  'distributionStrategy',
  'elementMode',
  'elementFilter',
  'speedMasterUuid',
  'rateSpeedMasterUuid',
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
 * master has no such spelling (unscaled is not a master) and is left absent.
 */
export function updateRequestOf(
  draft: FxDraft,
  touched: ReadonlySet<OptionalField> = new Set(),
  nullMasterAs: string | null = null,
): UpdateFxRequest {
  const speedMasterUuid = draft.speedMasterUuid ?? nullMasterAs
  return {
    parameters: { ...draft.parameters },
    beatDivision: draft.beatDivision,
    blendMode: draft.blendMode,
    phaseOffset: draft.phaseOffset,
    ...(touched.has('stepTiming') ? { stepTiming: draft.stepTiming } : {}),
    ...(touched.has('distributionStrategy') ? { distributionStrategy: draft.distributionStrategy } : {}),
    ...(touched.has('elementMode') ? { elementMode: draft.elementMode } : {}),
    ...(touched.has('elementFilter') ? { elementFilter: draft.elementFilter } : {}),
    ...(touched.has('speedMasterUuid') && speedMasterUuid != null ? { speedMasterUuid } : {}),
    ...(touched.has('rateSpeedMasterUuid') && draft.rateSpeedMasterUuid != null
      ? { rateSpeedMasterUuid: draft.rateSpeedMasterUuid }
      : {}),
  }
}

/** Two writes are the same write — `useLivePush`'s dedupe over a whole request. */
export function sameRequest(a: UpdateFxRequest, b: UpdateFxRequest): boolean {
  return JSON.stringify(a) === JSON.stringify(b)
}

// ─── The picker's families ─────────────────────────────────────────────────────────────────────

export type PickerFamily = 'INTENSITY' | 'COLOUR' | 'POSITION' | 'CONTROLS'

export const PICKER_FAMILIES: readonly PickerFamily[] = ['INTENSITY', 'COLOUR', 'POSITION', 'CONTROLS']

export const PICKER_FAMILY_LABELS: Record<PickerFamily, string> = {
  INTENSITY: 'Intensity',
  COLOUR: 'Colour',
  POSITION: 'Position',
  CONTROLS: 'Controls',
}

/**
 * The picker's segment an effect sits under, from its library category. A composite (Lightning
 * Strike) is applied through its dimmer half, so it is Intensity; a category the four do not name —
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
    default:
      return 'CONTROLS'
  }
}

/** The fixture sheet's row family as the picker's segment — Beam rows' effects are Controls'. */
export function pickerFamilyForSheet(family: string | null | undefined): PickerFamily | null {
  switch (family) {
    case 'INTENSITY':
    case 'COLOUR':
    case 'POSITION':
    case 'CONTROLS':
      return family
    case 'BEAM':
      return 'CONTROLS'
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
}

/**
 * A template's effect as the editor's draft (fixture-fx-sheets plan D16): the same `FxDraft` a live
 * instance opens on, so `TemplateEditor` asks the *Centre* question through the same rules. The
 * fields a template has no answer for — step timing, element mode, the head filter — read as their
 * defaults and are never written back ([withTemplateDraft]).
 */
export function templateDraftOf(effect: TemplateEffectFields): FxDraft {
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
    speedMasterUuid: draft.speedMasterUuid,
    rateSpeedMasterUuid: draft.rateSpeedMasterUuid,
  }
}

/**
 * Was this instance spawned by a **programmer** template layer — a pad's effect — and so has a
 * template to compare with, update and reset to (fixture-fx-sheets plan §3.3, D20)? A cue's
 * template-layer instance is not: W5 refuses it (`FX_NOT_FROM_TEMPLATE`), since the next GO
 * respawns it. A *detached* copy (a plain click on an effect template's chip) carries no source.
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
  const names = new Set([...Object.keys(draft.parameters), ...Object.keys(effect.parameters)])
  for (const name of names) {
    if (!sameParam(draft.parameters[name] ?? defaults[name], effect.parameters[name] ?? defaults[name])) return true
  }
  return false
}
