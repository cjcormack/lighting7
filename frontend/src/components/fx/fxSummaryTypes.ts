/**
 * Normalised effect data and adapter functions for the unified FxSummary component.
 */
import type { LookFx } from '@/api/looksApi'
import type { CueAdHocFx } from '@/api/cuesApi'
import type {
  FixtureDirectFx,
  FixtureIndirectFx,
  FxLibraryEntry,
  FxParameterDef,
} from '@/store/fixtureFx'
import type { GroupActiveFx } from '@/api/groupsApi'
import type { Timing } from '@/api/fxApi'
import { drivenCategoryOf } from '@/lib/stepFx'

/** Normalised effect data consumed by FxSummary. */
export interface FxSummaryData {
  fxType: string
  family: string
  propertyName: string | null
  cycle: number
  blendMode: string
  order: string | null
  phaseOffset: number | null
  timing: Timing | null
  elementMode: string | null
  elementFilter: string | null
  parameters: Record<string, string>
  /** Parameter definitions from effect library — used to determine param types for colour swatch rendering. */
  parameterDefs?: FxParameterDef[]
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

/** Map fxType names to categories (derived from FX_DESCRIPTIONS). */
const FX_CATEGORY_MAP: Record<string, string> = {}

// Build the map from known effect names
const DIMMER_FX = ['sinewave', 'rampup', 'rampdown', 'ramp', 'pulse', 'squarewave', 'dimmerstrobe', 'flicker']
const COLOUR_FX = ['colourcycle', 'rainbowcycle', 'colourstrobe', 'colourwave', 'colourfade', 'colourflicker']
const POSITION_FX = ['circle', 'figure8', 'sweep', 'pansweep', 'tiltsweep', 'randomposition']

for (const name of DIMMER_FX) FX_CATEGORY_MAP[name] = 'intensity'
for (const name of COLOUR_FX) FX_CATEGORY_MAP[name] = 'colour'
for (const name of POSITION_FX) FX_CATEGORY_MAP[name] = 'position'

/** Look up category from the effect library, falling back to a static map. */
export function lookupCategory(
  fxType: string,
  library?: FxLibraryEntry[],
): string {
  if (library) {
    const entry = library.find((e) => e.name === fxType)
    if (entry) return drivenCategoryOf(entry)
  }
  return FX_CATEGORY_MAP[fxType.toLowerCase()] ?? 'intensity'
}

/** Look up an effect type's timing source from the effect library. */
function lookupTimingSource(fxType: string, library?: FxLibraryEntry[]): string | null {
  return library?.find((e) => e.name === fxType)?.timingSource ?? null
}

/** Look up parameter defs from the effect library. */
function lookupParameterDefs(
  fxType: string,
  library?: FxLibraryEntry[],
): FxParameterDef[] | undefined {
  if (!library) return undefined
  const entry = library.find((e) => e.name === fxType)
  return entry?.parameters
}

// ─── Adapter functions ─────────────────────────────────────────────────────

export function fromLookFx(e: LookFx, library?: FxLibraryEntry[]): FxSummaryData {
  return {
    fxType: e.fxType,
    family: e.family,
    propertyName: e.propertyName ?? null,
    cycle: e.cycle,
    blendMode: e.blendMode,
    order: e.order,
    phaseOffset: e.phaseOffset ?? 0,
    timing: e.timing ?? null,
    elementMode: e.elementMode ?? null,
    elementFilter: e.elementFilter ?? null,
    parameters: e.parameters,
    parameterDefs: lookupParameterDefs(e.fxType, library),
    speedMasterUuid: e.speedMasterUuid ?? null,
    rateSpeedMasterUuid: e.rateSpeedMasterUuid ?? null,
    seconds: e.seconds ?? false,
    timingSource: lookupTimingSource(e.fxType, library),
  }
}

export function fromCueAdHocFx(
  e: CueAdHocFx,
  library?: FxLibraryEntry[],
): FxSummaryData {
  return {
    fxType: e.fxType,
    family: e.family,
    propertyName: e.propertyName,
    cycle: e.cycle,
    blendMode: e.blendMode,
    order: e.order,
    phaseOffset: e.phaseOffset,
    timing: e.timing,
    elementMode: e.elementMode,
    elementFilter: e.elementFilter,
    parameters: e.parameters,
    parameterDefs: lookupParameterDefs(e.fxType, library),
    speedMasterUuid: e.speedMasterUuid ?? null,
    rateSpeedMasterUuid: e.rateSpeedMasterUuid ?? null,
    seconds: e.seconds ?? false,
    timingSource: lookupTimingSource(e.fxType, library),
  }
}

export function fromFixtureDirectFx(
  e: FixtureDirectFx,
  library?: FxLibraryEntry[],
): FxSummaryData {
  return {
    fxType: e.fxType,
    family: lookupCategory(e.fxType, library),
    propertyName: e.propertyName,
    cycle: e.cycle,
    blendMode: e.blendMode,
    order: e.order,
    phaseOffset: e.phaseOffset,
    timing: e.timing,
    elementMode: null,
    elementFilter: e.elementFilter,
    parameters: e.parameters,
    parameterDefs: lookupParameterDefs(e.fxType, library),
    speedMasterUuid: e.speedMasterUuid ?? null,
    rateSpeedMasterUuid: e.rateSpeedMasterUuid ?? null,
    seconds: e.seconds ?? false,
    timingSource: lookupTimingSource(e.fxType, library),
  }
}

export function fromFixtureIndirectFx(
  e: FixtureIndirectFx,
  library?: FxLibraryEntry[],
): FxSummaryData {
  return {
    fxType: e.fxType,
    family: lookupCategory(e.fxType, library),
    propertyName: e.propertyName,
    cycle: e.cycle,
    blendMode: e.blendMode,
    order: e.order,
    phaseOffset: e.phaseOffset,
    timing: e.timing,
    elementMode: null,
    elementFilter: null,
    parameters: e.parameters,
    parameterDefs: lookupParameterDefs(e.fxType, library),
    speedMasterUuid: e.speedMasterUuid ?? null,
    rateSpeedMasterUuid: e.rateSpeedMasterUuid ?? null,
    seconds: e.seconds ?? false,
    timingSource: lookupTimingSource(e.fxType, library),
  }
}

export function fromGroupActiveFx(
  e: GroupActiveFx,
  library?: FxLibraryEntry[],
): FxSummaryData {
  return {
    fxType: e.fxType,
    family: lookupCategory(e.fxType, library),
    propertyName: e.propertyName,
    cycle: e.cycle,
    blendMode: e.blendMode,
    order: e.order,
    phaseOffset: e.phaseOffset,
    timing: e.timing,
    elementMode: e.elementMode,
    elementFilter: e.elementFilter,
    parameters: e.parameters,
    parameterDefs: lookupParameterDefs(e.fxType, library),
    speedMasterUuid: e.speedMasterUuid ?? null,
    rateSpeedMasterUuid: e.rateSpeedMasterUuid ?? null,
    seconds: e.seconds ?? false,
    timingSource: lookupTimingSource(e.fxType, library),
  }
}
