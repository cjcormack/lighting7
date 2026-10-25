import type { CueTarget } from '@/api/cuesApi'
import type { FxDirection, Timing } from '@/api/fxApi'
import type { AttributeFamily } from '@/lib/attributeFamily'
import { FX_ATTRIBUTE_SENTINELS } from '@/lib/fxTargetAttributes'
import { isPaletteSteps, paletteStepsAttribute } from '@/lib/stepFx'
import type { PaletteSummary } from '@/api/palettesApi'
import type { FxLibraryEntry } from '@/store/fixtureFx'
import type { FxPreviewDraft } from '@/store/fxPreview'
import { LANE_ATTRIBUTE } from './fxEditorList'

/** A stored FX's fields as a palette or a cue holds them — `PaletteFx`'s and `CueAdHocFx`'s, structurally. */
export interface StoredFxFields {
  fxType: string
  parameters: Record<string, string>
  cycle: number
  blendMode: string
  order: string
  phaseOffset?: number
  elementMode?: string | null
  elementFilter?: string | null
  timing?: Timing | null
  speedMasterUuid?: string | null
  rateSpeedMasterUuid?: string | null
  seconds?: boolean
  amount?: number
  direction?: FxDirection
  runs?: number | null
  spread?: number
  wings?: number
  segments?: number
  parts?: number
}

/**
 * The attribute a stored FX that names none drives, as the desk resolves it when it spawns one
 * (`FxSpawner`): Palette Steps' from the palettes it steps, else the family's own — dimmer, colour,
 * position — or a beam FX's one declared attribute. Null where it cannot be said without a head (a
 * Beam FX declaring several) or before [templates] has loaded.
 */
export function storedFxAttribute(
  effect: { fxType: string; attribute?: string | null; parameters: Record<string, string> },
  family: AttributeFamily | null,
  entry: Pick<FxLibraryEntry, 'compatibleAttributes'> | undefined,
  templates: readonly PaletteSummary[] | undefined,
): string | null {
  const { attribute } = effect
  if (attribute != null && attribute !== '') return attribute
  if (isPaletteSteps(effect.fxType)) return paletteStepsAttribute(effect.parameters.palettes, templates)
  if (family == null) return null
  if (family !== 'BEAM') return LANE_ATTRIBUTE[family]
  const declared = entry?.compatibleAttributes.length === 1 ? entry.compatibleAttributes[0] : null
  return declared != null && !FX_ATTRIBUTE_SENTINELS.has(declared) ? declared : null
}

/**
 * A stored FX as the preview's `{draft}` (FX editor plan §3.3): `POST /fx/add`'s body, built by the
 * add's own write boundary and never added. Only what the FX says is sent, each field at the add's
 * spelling, so the strict decode names nothing it does not know; a master beside *Seconds* is left out,
 * as the add refuses both. [programmerOwned] puts it in the programmer's band, where a pressed palette
 * runs, so a programmer value on the heads does not hold it back.
 */
export function previewDraftOf(
  effect: StoredFxFields,
  attribute: string,
  { targets, programmerOwned = false }: { targets?: readonly CueTarget[]; programmerOwned?: boolean } = {},
): FxPreviewDraft {
  const seconds = effect.seconds === true
  return {
    fxType: effect.fxType,
    attribute,
    ...(targets != null ? { targets: targets.map((t) => ({ type: t.type, key: t.key })) } : {}),
    ...(programmerOwned ? { programmerOwned: true } : {}),
    cycle: effect.cycle,
    blendMode: effect.blendMode as FxPreviewDraft['blendMode'],
    parameters: { ...effect.parameters },
    order: effect.order,
    ...(effect.phaseOffset != null ? { phaseOffset: effect.phaseOffset } : {}),
    ...(effect.elementMode != null ? { elementMode: effect.elementMode } : {}),
    ...(effect.elementFilter != null ? { elementFilter: effect.elementFilter } : {}),
    ...(effect.timing != null ? { timing: effect.timing } : {}),
    ...(seconds ? { seconds: true } : {}),
    ...(!seconds && effect.speedMasterUuid != null ? { speedMasterUuid: effect.speedMasterUuid } : {}),
    ...(!seconds && effect.rateSpeedMasterUuid != null ? { rateSpeedMasterUuid: effect.rateSpeedMasterUuid } : {}),
    ...(effect.amount != null ? { amount: effect.amount } : {}),
    ...(effect.direction != null ? { direction: effect.direction } : {}),
    ...(effect.runs != null ? { runs: effect.runs } : {}),
    ...(effect.spread != null ? { spread: effect.spread } : {}),
    ...(effect.wings != null ? { wings: effect.wings } : {}),
    ...(effect.segments != null ? { segments: effect.segments } : {}),
    ...(effect.parts != null ? { parts: effect.parts } : {}),
  }
}
