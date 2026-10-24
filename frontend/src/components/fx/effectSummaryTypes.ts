/**
 * Normalised effect data and adapter functions for the unified EffectSummary component.
 */
import type { LookEffect } from '@/api/looksApi'
import type { CueAdHocEffect } from '@/api/cuesApi'
import type {
  FixtureDirectEffect,
  FixtureIndirectEffect,
  EffectLibraryEntry,
  EffectParameterDef,
} from '@/store/fixtureFx'
import type { GroupActiveEffect } from '@/api/groupsApi'
import { drivenCategoryOf } from '@/lib/stepEffects'

/** Normalised effect data consumed by EffectSummary. */
export interface EffectSummaryData {
  effectType: string
  category: string
  propertyName: string | null
  beatDivision: number
  blendMode: string
  distribution: string | null
  phaseOffset: number | null
  stepTiming: boolean | null
  elementMode: string | null
  elementFilter: string | null
  parameters: Record<string, string>
  /** Parameter definitions from effect library — used to determine param types for colour swatch rendering. */
  parameterDefs?: EffectParameterDef[]
  /**
   * Speed master this effect subscribes to (null → master 1). Every adapter below must
   * carry it — an adapter that drops it renders the effect as master-1 in summaries even
   * though it runs elsewhere.
   */
  speedMasterUuid: string | null
  /**
   * Wall-clock rate master (null → unscaled). Same rule as [speedMasterUuid]: every adapter
   * must carry it, or a wall-clock effect renders as unscaled while it is running scaled.
   */
  rateSpeedMasterUuid: string | null
  /**
   * D7's flag (venue-visit plan S15) — with [timingSource], what says the division is seconds. Every
   * adapter carries it, for [speedMasterUuid]'s reason.
   */
  seconds: boolean
  /** The effect type's timing source from the library, null where the library does not have it. */
  timingSource: string | null
}

// ─── Category inference ────────────────────────────────────────────────────

/** Map effectType names to categories (derived from EFFECT_DESCRIPTIONS). */
const EFFECT_CATEGORY_MAP: Record<string, string> = {}

// Build the map from known effect names
const DIMMER_EFFECTS = ['sinewave', 'rampup', 'rampdown', 'ramp', 'pulse', 'squarewave', 'dimmerstrobe', 'flicker']
const COLOUR_EFFECTS = ['colourcycle', 'rainbowcycle', 'colourstrobe', 'colourwave', 'colourfade', 'colourflicker']
const POSITION_EFFECTS = ['circle', 'figure8', 'sweep', 'pansweep', 'tiltsweep', 'randomposition']

for (const name of DIMMER_EFFECTS) EFFECT_CATEGORY_MAP[name] = 'intensity'
for (const name of COLOUR_EFFECTS) EFFECT_CATEGORY_MAP[name] = 'colour'
for (const name of POSITION_EFFECTS) EFFECT_CATEGORY_MAP[name] = 'position'

/** Look up category from the effect library, falling back to a static map. */
export function lookupCategory(
  effectType: string,
  library?: EffectLibraryEntry[],
): string {
  if (library) {
    const entry = library.find((e) => e.name === effectType)
    if (entry) return drivenCategoryOf(entry)
  }
  return EFFECT_CATEGORY_MAP[effectType.toLowerCase()] ?? 'intensity'
}

/** Look up an effect type's timing source from the effect library. */
function lookupTimingSource(effectType: string, library?: EffectLibraryEntry[]): string | null {
  return library?.find((e) => e.name === effectType)?.timingSource ?? null
}

/** Look up parameter defs from the effect library. */
function lookupParameterDefs(
  effectType: string,
  library?: EffectLibraryEntry[],
): EffectParameterDef[] | undefined {
  if (!library) return undefined
  const entry = library.find((e) => e.name === effectType)
  return entry?.parameters
}

// ─── Adapter functions ─────────────────────────────────────────────────────

export function fromLookEffect(e: LookEffect, library?: EffectLibraryEntry[]): EffectSummaryData {
  return {
    effectType: e.effectType,
    category: e.category,
    propertyName: e.propertyName ?? null,
    beatDivision: e.beatDivision,
    blendMode: e.blendMode,
    distribution: e.distribution,
    phaseOffset: e.phaseOffset ?? 0,
    stepTiming: e.stepTiming ?? null,
    elementMode: e.elementMode ?? null,
    elementFilter: e.elementFilter ?? null,
    parameters: e.parameters,
    parameterDefs: lookupParameterDefs(e.effectType, library),
    speedMasterUuid: e.speedMasterUuid ?? null,
    rateSpeedMasterUuid: e.rateSpeedMasterUuid ?? null,
    seconds: e.seconds ?? false,
    timingSource: lookupTimingSource(e.effectType, library),
  }
}

export function fromCueAdHocEffect(
  e: CueAdHocEffect,
  library?: EffectLibraryEntry[],
): EffectSummaryData {
  return {
    effectType: e.effectType,
    category: e.category,
    propertyName: e.propertyName,
    beatDivision: e.beatDivision,
    blendMode: e.blendMode,
    distribution: e.distribution,
    phaseOffset: e.phaseOffset,
    stepTiming: e.stepTiming,
    elementMode: e.elementMode,
    elementFilter: e.elementFilter,
    parameters: e.parameters,
    parameterDefs: lookupParameterDefs(e.effectType, library),
    speedMasterUuid: e.speedMasterUuid ?? null,
    rateSpeedMasterUuid: e.rateSpeedMasterUuid ?? null,
    seconds: e.seconds ?? false,
    timingSource: lookupTimingSource(e.effectType, library),
  }
}

export function fromFixtureDirectEffect(
  e: FixtureDirectEffect,
  library?: EffectLibraryEntry[],
): EffectSummaryData {
  return {
    effectType: e.effectType,
    category: lookupCategory(e.effectType, library),
    propertyName: e.propertyName,
    beatDivision: e.beatDivision,
    blendMode: e.blendMode,
    distribution: e.distributionStrategy,
    phaseOffset: e.phaseOffset,
    stepTiming: e.stepTiming,
    elementMode: null,
    elementFilter: e.elementFilter,
    parameters: e.parameters,
    parameterDefs: lookupParameterDefs(e.effectType, library),
    speedMasterUuid: e.speedMasterUuid ?? null,
    rateSpeedMasterUuid: e.rateSpeedMasterUuid ?? null,
    seconds: e.seconds ?? false,
    timingSource: lookupTimingSource(e.effectType, library),
  }
}

export function fromFixtureIndirectEffect(
  e: FixtureIndirectEffect,
  library?: EffectLibraryEntry[],
): EffectSummaryData {
  return {
    effectType: e.effectType,
    category: lookupCategory(e.effectType, library),
    propertyName: e.propertyName,
    beatDivision: e.beatDivision,
    blendMode: e.blendMode,
    distribution: e.distributionStrategy,
    phaseOffset: e.phaseOffset,
    stepTiming: e.stepTiming,
    elementMode: null,
    elementFilter: null,
    parameters: e.parameters,
    parameterDefs: lookupParameterDefs(e.effectType, library),
    speedMasterUuid: e.speedMasterUuid ?? null,
    rateSpeedMasterUuid: e.rateSpeedMasterUuid ?? null,
    seconds: e.seconds ?? false,
    timingSource: lookupTimingSource(e.effectType, library),
  }
}

export function fromGroupActiveEffect(
  e: GroupActiveEffect,
  library?: EffectLibraryEntry[],
): EffectSummaryData {
  return {
    effectType: e.effectType,
    category: lookupCategory(e.effectType, library),
    propertyName: e.propertyName,
    beatDivision: e.beatDivision,
    blendMode: e.blendMode,
    distribution: e.distribution,
    phaseOffset: e.phaseOffset,
    stepTiming: e.stepTiming,
    elementMode: e.elementMode,
    elementFilter: e.elementFilter,
    parameters: e.parameters,
    parameterDefs: lookupParameterDefs(e.effectType, library),
    speedMasterUuid: e.speedMasterUuid ?? null,
    rateSpeedMasterUuid: e.rateSpeedMasterUuid ?? null,
    seconds: e.seconds ?? false,
    timingSource: lookupTimingSource(e.effectType, library),
  }
}
