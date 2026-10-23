import type { CueAdHocEffect, CueTarget } from '@/api/cuesApi'
import type { DraftEffectPick, FxTarget } from './FxPicker'

/**
 * The stored effect a draft pick makes (show-cue-editing plan D17), on the picker's target: one
 * fixture, one group, or the selection as one effect (effects plan D3). What the cue editor's FX tab
 * and its bands' *+ Effect …* store, running nothing.
 */
export function storedEffectOf(pick: DraftEffectPick, target: FxTarget): CueAdHocEffect {
  const where =
    target.type === 'fixture'
      ? { targetType: 'fixture' as const, targetKey: target.fixture.key, distribution: 'NORMAL' }
      : target.type === 'group'
        ? { targetType: 'group' as const, targetKey: target.group.name, distribution: target.group.defaultDistribution || 'NORMAL' }
        : { targetType: 'selection' as const, targetKey: selectionKeyOf(target.targets), distribution: 'NORMAL' }
  return {
    ...where,
    effectType: pick.effectType,
    category: pick.category,
    propertyName: pick.propertyName,
    beatDivision: pick.beatDivision,
    direction: pick.direction,
    blendMode: pick.blendMode,
    phaseOffset: 0,
    elementMode: null,
    elementFilter: pick.elementFilter ?? null,
    stepTiming: null,
    parameters: pick.parameters,
    ...(pick.speedMasterUuid != null ? { speedMasterUuid: pick.speedMasterUuid } : {}),
  }
}

/** A selection's canonical key, as the desk writes it: `[{"key":…,"type":…}]`, keys alphabetical, in order. */
export function selectionKeyOf(targets: readonly CueTarget[]): string {
  return JSON.stringify(targets.map((t) => ({ key: t.key, type: t.type })))
}

/**
 * Where [effect] sits in [stored], matched by what a draft pick sets — type, property and target —
 * the newest first, since a pick appends; -1 when the cue no longer holds it.
 */
export function lastIndexOfDraft(stored: readonly CueAdHocEffect[], effect: CueAdHocEffect): number {
  for (let i = stored.length - 1; i >= 0; i--) {
    const e = stored[i]
    if (
      e.effectType === effect.effectType &&
      (e.propertyName ?? null) === (effect.propertyName ?? null) &&
      e.targetType === effect.targetType &&
      e.targetKey === effect.targetKey
    ) {
      return i
    }
  }
  return -1
}
