import type { FxLibraryEntry } from '@/store/fixtureFx'
import type { CreateFxDefinitionRequest, FxDefinition } from '@/store/fxDefinitions'
import type { FxCalcEditorType } from '@/store/scripts'

/**
 * Where a library entry came from (library-sheets plan §3.2, §1's first bug).
 *
 * - **`builtIn`** — shipped with the desk. Read-only; Fork is how it changes.
 * - **`custom`** — an `fx_definitions` row: some definition's `fxId` equals the entry's id.
 * - **`script`** — registered by an `FX_DEFINITION` script. Its `sourceDefinitionId` is the
 *   **script's** id (`fxDefinitionScriptDef.kt`), not a definition's.
 *
 * Nothing on the library entry tells the last two apart — both are `USER` with a
 * `sourceDefinitionId` (`FxTypeInfo`) — which is how the old page fetched
 * `fx/definitions/<scriptId>` for a script's effect and opened a 404 or an unrelated definition.
 * The definition list is the discriminator, with no wire change.
 */
export type FxSource = 'builtIn' | 'custom' | 'script'

export const FX_SOURCE_LABELS: Record<FxSource, string> = {
  builtIn: 'Built-in',
  custom: 'Custom',
  script: 'Script',
}

/** The definitions by `fxId` — the one lookup [fxSourceOf] needs. */
export function definitionsByFxId(definitions: readonly FxDefinition[]): Map<string, FxDefinition> {
  return new Map(definitions.map((d) => [d.fxId, d]))
}

export function fxSourceOf(entry: FxLibraryEntry, byFxId: ReadonlyMap<string, FxDefinition>): FxSource {
  if (byFxId.has(entry.name)) return 'custom'
  return entry.source === 'USER' ? 'script' : 'builtIn'
}

/**
 * The effects an `FX_DEFINITION` script registers — script-registered library entries whose
 * `sourceDefinitionId` is this script. The Scripts sheet's Used by reads it; it is the FX
 * Library's discriminator read from the other side, so the two pages cannot disagree about which
 * record owns an effect.
 */
export function fxRegisteredBy(
  scriptId: number,
  library: readonly FxLibraryEntry[],
  byFxId: ReadonlyMap<string, FxDefinition>,
): FxLibraryEntry[] {
  return library.filter((e) => e.sourceDefinitionId === scriptId && fxSourceOf(e, byFxId) === 'script')
}

export const FX_FAMILY_ORDER = ['intensity', 'colour', 'position', 'beam', 'steps', 'controls'] as const
export type FxFamily = (typeof FX_FAMILY_ORDER)[number]

export const FX_FAMILY_LABELS: Record<FxFamily, string> = {
  intensity: 'Intensity',
  colour: 'Colour',
  position: 'Position',
  beam: 'Beam',
  // The Steps FX's own: each is offered in the family of what it steps (effects plan §4.4).
  steps: 'Steps',
  controls: 'Controls',
}

export function isFxCategory(value: string): value is FxFamily {
  return (FX_FAMILY_ORDER as readonly string[]).includes(value)
}

export const OUTPUT_TYPE_LABELS: Record<string, string> = {
  SLIDER: 'Slider',
  COLOUR: 'Colour',
  POSITION: 'Position',
  STEPS: 'Steps',
}

export const FX_MODE_LABELS: Record<string, string> = {
  STANDARD: 'Standard',
  STATEFUL: 'Stateful',
}

export function fxModeToEditorType(fxMode?: string): FxCalcEditorType {
  switch (fxMode) {
    case 'STATEFUL':
      return 'FX_CALC_STATEFUL'
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
export function entryName(entry: FxLibraryEntry, definition: FxDefinition | undefined): string {
  return definition?.name ?? displayName(entry.name)
}

/**
 * `FxRegistry.normalize`'s rule — lower case, no spaces or underscores. The registry indexes every
 * id that way for lookup, so two ids that normalise alike would resolve to one effect.
 */
function normaliseFxId(id: string): string {
  return id.toLowerCase().replaceAll(' ', '').replaceAll('_', '')
}

/**
 * An `fxId` that is free across the whole library: [base], then `<base>2`, `<base>3`… — with
 * the ordinal it took. Checked against every library id **and** every definition's (a definition
 * whose script failed to compile is not registered, but still holds its id), under the registry's
 * own normalisation.
 *
 * It is the id that must be unique, not the name (D9): `FxRegistry.register` overwrites by id and
 * deleting a definition unregisters its id, so a definition sharing a built-in's id would replace
 * the built-in, and deleting it would remove the built-in until a restart.
 */
export function uniqueFxId(base: string, takenIds: Iterable<string>): { fxId: string; ordinal: number } {
  const taken = new Set<string>()
  for (const id of takenIds) taken.add(normaliseFxId(id))
  for (let n = 1; ; n++) {
    const fxId = `${base}${n === 1 ? '' : n}`
    if (!taken.has(normaliseFxId(fxId))) return { fxId, ordinal: n }
  }
}

/** **A fork's `fxId`** (D9): `<sourceId>Custom`, then `<sourceId>Custom2`, and so on. */
export function mintForkFxId(sourceId: string, takenIds: Iterable<string>): { fxId: string; ordinal: number } {
  return uniqueFxId(`${sourceId}Custom`, takenIds)
}

/** Every id a new definition must not take — the library's and the definitions'. */
export function takenFxIds(
  library: readonly FxLibraryEntry[],
  definitions: readonly FxDefinition[],
): string[] {
  return [...library.map((e) => e.name), ...definitions.map((d) => d.fxId)]
}

/**
 * Why a built-in may not be forked, or null where it may. The step effects (effects plan §3.6) are
 * the desk's own: their lists name this desk's palettes and settings, resolved per head, and the
 * desk refuses a custom effect with a `STEPS` output.
 */
export function forkRefusal(entry: FxLibraryEntry): string | null {
  return entry.outputType === 'STEPS'
    ? `${displayName(entry.name)} is the desk’s own — its steps name this desk’s palettes and settings, so it cannot be forked`
    : null
}

/**
 * The `POST /fx/definitions` body that forks a built-in (D9): its script, family, output type,
 * mode, parameters, `compatibleProperties` and `timingSource`, named *<name> (Custom)*, under an id
 * [mintForkFxId] mints. The name carries the id's ordinal past the first fork, so two forks of
 * one effect can be told apart in the sheet.
 *
 * **`defaultTiming` is `CYCLE`.** The library entry does not publish the built-in's own default,
 * so a fork cannot copy it — the editor says so rather than leaving it to be discovered.
 */
export function forkRequest(
  entry: FxLibraryEntry,
  library: readonly FxLibraryEntry[],
  definitions: readonly FxDefinition[],
): CreateFxDefinitionRequest {
  const { fxId, ordinal } = mintForkFxId(entry.name, takenFxIds(library, definitions))
  return {
    fxId,
    name: `${displayName(entry.name)} (Custom${ordinal === 1 ? '' : ` ${ordinal}`})`,
    family: entry.family,
    outputType: entry.outputType,
    fxMode: entry.fxMode,
    parameters: entry.parameters.map((p) => ({ ...p })),
    compatibleProperties: [...entry.compatibleProperties],
    script: entry.script ?? '',
    timingSource: entry.timingSource ?? 'BEAT',
    defaultTiming: 'CYCLE',
  }
}
