import type { EffectLibraryEntry } from '@/store/fixtureFx'
import type { CreateFxDefinitionRequest, FxDefinition } from '@/store/fxDefinitions'
import type { FxCalcEditorType } from '@/store/scripts'

/**
 * Where a library entry came from (library-sheets plan §3.2, §1's first bug).
 *
 * - **`builtIn`** — shipped with the desk. Read-only; Fork is how it changes.
 * - **`custom`** — an `fx_definitions` row: some definition's `effectId` equals the entry's id.
 * - **`script`** — registered by an `FX_DEFINITION` script. Its `sourceDefinitionId` is the
 *   **script's** id (`fxDefinitionScriptDef.kt`), not a definition's.
 *
 * Nothing on the library entry tells the last two apart — both are `USER` with a
 * `sourceDefinitionId` (`EffectTypeInfo`) — which is how the old page fetched
 * `fx/definitions/<scriptId>` for a script's effect and opened a 404 or an unrelated definition.
 * The definition list is the discriminator, with no wire change.
 */
export type FxSource = 'builtIn' | 'custom' | 'script'

export const FX_SOURCE_LABELS: Record<FxSource, string> = {
  builtIn: 'Built-in',
  custom: 'Custom',
  script: 'Script',
}

/** The definitions by `effectId` — the one lookup [fxSourceOf] needs. */
export function definitionsByEffectId(definitions: readonly FxDefinition[]): Map<string, FxDefinition> {
  return new Map(definitions.map((d) => [d.effectId, d]))
}

export function fxSourceOf(entry: EffectLibraryEntry, byEffectId: ReadonlyMap<string, FxDefinition>): FxSource {
  if (byEffectId.has(entry.name)) return 'custom'
  return entry.source === 'USER' ? 'script' : 'builtIn'
}

/**
 * The effects an `FX_DEFINITION` script registers — script-registered library entries whose
 * `sourceDefinitionId` is this script. The Scripts sheet's Used by reads it; it is the FX
 * Library's discriminator read from the other side, so the two pages cannot disagree about which
 * record owns an effect.
 */
export function effectsRegisteredBy(
  scriptId: number,
  library: readonly EffectLibraryEntry[],
  byEffectId: ReadonlyMap<string, FxDefinition>,
): EffectLibraryEntry[] {
  return library.filter((e) => e.sourceDefinitionId === scriptId && fxSourceOf(e, byEffectId) === 'script')
}

export const FX_CATEGORY_ORDER = ['dimmer', 'colour', 'position', 'controls', 'composite'] as const
export type FxCategory = (typeof FX_CATEGORY_ORDER)[number]

export const FX_CATEGORY_LABELS: Record<FxCategory, string> = {
  dimmer: 'Dimmer',
  colour: 'Colour',
  position: 'Position',
  controls: 'Controls',
  composite: 'Composite',
}

export function isFxCategory(value: string): value is FxCategory {
  return (FX_CATEGORY_ORDER as readonly string[]).includes(value)
}

export const OUTPUT_TYPE_LABELS: Record<string, string> = {
  SLIDER: 'Slider',
  COLOUR: 'Colour',
  POSITION: 'Position',
}

export const EFFECT_MODE_LABELS: Record<string, string> = {
  STANDARD: 'Standard',
  STATEFUL: 'Stateful',
  COMPOSITE: 'Composite',
}

export function effectModeToEditorType(effectMode?: string): FxCalcEditorType {
  switch (effectMode) {
    case 'STATEFUL':
      return 'FX_CALC_STATEFUL'
    case 'COMPOSITE':
      return 'FX_CALC_COMPOSITE'
    default:
      return 'FX_CALC'
  }
}

/** A registry id read as words — `SineWave` → `Sine Wave`. */
export function displayName(name: string): string {
  return name.replace(/([A-Z])/g, ' $1').trim()
}

/**
 * What a row is called: a custom definition's own name (its registry id is minted and may not read
 * as words — `SineWaveCustom2`), anything else its id as words.
 */
export function entryName(entry: EffectLibraryEntry, definition: FxDefinition | undefined): string {
  return definition?.name ?? displayName(entry.name)
}

/**
 * `FxRegistry.normalize`'s rule — lower case, no spaces or underscores. The registry indexes every
 * id that way for lookup, so two ids that normalise alike would resolve to one effect.
 */
function normaliseEffectId(id: string): string {
  return id.toLowerCase().replaceAll(' ', '').replaceAll('_', '')
}

/**
 * An `effectId` that is free across the whole library: [base], then `<base>2`, `<base>3`… — with
 * the ordinal it took. Checked against every library id **and** every definition's (a definition
 * whose script failed to compile is not registered, but still holds its id), under the registry's
 * own normalisation.
 *
 * It is the id that must be unique, not the name (D9): `FxRegistry.register` overwrites by id and
 * deleting a definition unregisters its id, so a definition sharing a built-in's id would replace
 * the built-in, and deleting it would remove the built-in until a restart.
 */
export function uniqueEffectId(base: string, takenIds: Iterable<string>): { effectId: string; ordinal: number } {
  const taken = new Set<string>()
  for (const id of takenIds) taken.add(normaliseEffectId(id))
  for (let n = 1; ; n++) {
    const effectId = `${base}${n === 1 ? '' : n}`
    if (!taken.has(normaliseEffectId(effectId))) return { effectId, ordinal: n }
  }
}

/** **A fork's `effectId`** (D9): `<sourceId>Custom`, then `<sourceId>Custom2`, and so on. */
export function mintForkEffectId(sourceId: string, takenIds: Iterable<string>): { effectId: string; ordinal: number } {
  return uniqueEffectId(`${sourceId}Custom`, takenIds)
}

/** Every id a new definition must not take — the library's and the definitions'. */
export function takenEffectIds(
  library: readonly EffectLibraryEntry[],
  definitions: readonly FxDefinition[],
): string[] {
  return [...library.map((e) => e.name), ...definitions.map((d) => d.effectId)]
}

/**
 * The `POST /fx/definitions` body that forks a built-in (D9): its script, category, output type,
 * mode, parameters, `compatibleProperties` and `timingSource`, named *<name> (Custom)*, under an id
 * [mintForkEffectId] mints. The name carries the id's ordinal past the first fork, so two forks of
 * one effect can be told apart in the sheet.
 *
 * **`defaultStepTiming` is false.** The library entry does not publish the built-in's own default,
 * so a fork cannot copy it — the editor says so rather than leaving it to be discovered.
 */
export function forkRequest(
  entry: EffectLibraryEntry,
  library: readonly EffectLibraryEntry[],
  definitions: readonly FxDefinition[],
): CreateFxDefinitionRequest {
  const { effectId, ordinal } = mintForkEffectId(entry.name, takenEffectIds(library, definitions))
  return {
    effectId,
    name: `${displayName(entry.name)} (Custom${ordinal === 1 ? '' : ` ${ordinal}`})`,
    category: entry.category,
    outputType: entry.outputType,
    effectMode: entry.effectMode,
    parameters: entry.parameters.map((p) => ({ ...p })),
    compatibleProperties: [...entry.compatibleProperties],
    script: entry.script ?? '',
    timingSource: entry.timingSource ?? 'BEAT',
    defaultStepTiming: false,
  }
}
