import type { FxLibraryEntry, FxParameterDef } from '@/store/fixtureFx'

/**
 * D12's centre rules (fixture-fx-sheets plan D12, D16), pure and free of any store, so a pad face
 * (`padFace.ts`) can say *around* without importing what the editor's model reaches for. Re-exported
 * from `fxEditorModel.ts`, where the editor reads them.
 */

/** The byte a centre parameter is pinned at while the effect orbits what is underneath. */
export const CENTRE_BYTE = 128

/** D12: a movement effect's centre is the position underneath, or a place of its own. */
export type CentreMode = 'around' | 'absolute'

export const CENTRE_PARAMS = ['panCentre', 'tiltCentre'] as const

function paramNamed(entry: Pick<FxLibraryEntry, 'parameters'>, name: string): FxParameterDef | undefined {
  return entry.parameters.find((p) => p.name === name)
}

/**
 * The movement effects — a position effect with a centre pair drawn for what it holds: a Path's
 * Circle, Figure 8 or Random (fx-settings plan session 3), whose centres a Line's `shownWhen` hides.
 * Those are the ones D12's question is about: *Around* pins the centre at 128 and adds the shape to
 * whatever holds the position underneath, which is the only way a Circle orbits a set position. A
 * line has no centre to pin, so it asks nothing and keeps its blend under Advanced.
 */
export function hasCentre(
  entry: Pick<FxLibraryEntry, 'family' | 'parameters'>,
  parameters: Record<string, string> = {},
): boolean {
  if (entry.family !== 'position') return false
  return CENTRE_PARAMS.every((name) => {
    const param = paramNamed(entry, name)
    return param != null && isParamShown(entry, param, parameters)
  })
}

/** Whether [param] is drawn while the FX holds [parameters] — its `shownWhen`, against the library's defaults. */
export function isParamShown(
  entry: Pick<FxLibraryEntry, 'parameters'>,
  param: FxParameterDef,
  parameters: Record<string, string>,
): boolean {
  const when = param.shownWhen
  if (when == null) return true
  const other = paramNamed(entry, when.param)
  if (other == null) return true
  const value = (parameters[other.name] || other.defaultValue).trim().toUpperCase()
  return when.values.some((v) => v.toUpperCase() === value)
}

/**
 * What a stored effect reads back as. **Around is Additive with both centres at 128** — the
 * spelling P2 gives it, so an effect stored by any surface reads the same; Absolute is Override.
 * Anything else (Max, Min, an Additive with a centre of its own) is neither, and the segment shows
 * nothing pressed while Advanced still shows the blend.
 */
export function centreModeOf(blendMode: string, parameters: Record<string, string>): CentreMode | null {
  const blend = blendMode.toUpperCase()
  if (blend === 'OVERRIDE') return 'absolute'
  if (blend !== 'ADDITIVE') return null
  return CENTRE_PARAMS.every((name) => Number(parameters[name]) === CENTRE_BYTE) ? 'around' : null
}

/** The blend and parameters D12 spells for [mode]: Around pins both centres; Absolute keeps them. */
export function withCentreMode(
  spec: { blendMode: string; parameters: Record<string, string> },
  mode: CentreMode,
): { blendMode: string; parameters: Record<string, string> } {
  if (mode === 'absolute') return { blendMode: 'OVERRIDE', parameters: spec.parameters }
  const parameters = { ...spec.parameters }
  for (const name of CENTRE_PARAMS) parameters[name] = String(CENTRE_BYTE)
  return { blendMode: 'ADDITIVE', parameters }
}

/** Every parameter at its declared default. */
export function defaultParameters(entry: Pick<FxLibraryEntry, 'parameters'>): Record<string, string> {
  return Object.fromEntries(entry.parameters.map((p) => [p.name, p.defaultValue]))
}

/**
 * What a stored effect answers to D12, or null for an effect that does not ask it (no centre) or a
 * blend that is neither. An absent centre parameter is the library's default (128 on every
 * built-in), so a Circle stored without spelling it is still Around. A stored movement template
 * still on Override reads **Absolute** (call 6) — nothing is migrated.
 */
export function storedCentreMode(
  effect: { blendMode: string; parameters: Record<string, string> },
  entry: Pick<FxLibraryEntry, 'family' | 'parameters'> | undefined,
): CentreMode | null {
  if (entry == null || !hasCentre(entry, effect.parameters)) return null
  return centreModeOf(effect.blendMode, { ...defaultParameters(entry), ...effect.parameters })
}

/**
 * Whether a stored effect is spelled *Around* — a movement effect (a `position` effect carrying both
 * centre parameters) on `ADDITIVE` with both at 128. For the pad face's *around* (D16), which cannot
 * read the library: a template's effect carries every parameter its editor seeded, so the centres
 * are present on any movement template it made; one stored without them says nothing. A Path's Line
 * carries them too, and draws no centre, so it is never Around whatever its blend.
 */
export function isAroundSpelling(effect: { family: string; blendMode: string; parameters: Record<string, string> }): boolean {
  if (effect.family.toLowerCase() !== 'position') return false
  if (effect.parameters.shape?.trim().toUpperCase() === 'LINE') return false
  if (!CENTRE_PARAMS.every((name) => effect.parameters[name] != null)) return false
  return centreModeOf(effect.blendMode, effect.parameters) === 'around'
}
