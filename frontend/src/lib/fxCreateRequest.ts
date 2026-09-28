import type { ApplyFxRequest, BlendMode, DistributionStrategy, ElementMode } from '@/api/groupsApi'
import type { AddFixtureFxRequest } from '@/store/fixtureFx'

/**
 * The one place that knows how "start this effect" is spelled for a fixture and for a group.
 *
 * The two create routes differ in more than a key name — a group carries a `distribution` and an
 * `elementMode`, a fixture carries `startOnBeat` and spells its distribution `distributionStrategy`
 * — so every surface that starts an effect used to write the branch out for itself. There were four
 * copies between the add/edit sheet and the busking pad, and they had already drifted: only some of
 * them sent `stepTiming`, and the sheet suppressed a fixture's distribution while the pad sent it
 * unconditionally.
 *
 * This does **not** cover the two *update* routes. They take a nested `body` with a shape of their
 * own (a group update spells its distribution `distributionStrategy`, unlike a group create), and
 * only the sheet issues them — so there is nothing to share and folding them in here would mean
 * inventing a union that fits neither well.
 */

/** A target, reduced to the one field its create route addresses it by. */
export type FxCreateTarget =
  | { type: 'group'; groupName: string }
  | { type: 'fixture'; fixtureKey: string }

export interface FxCreateRequest {
  effectType: string
  propertyName: string
  beatDivision: number
  blendMode: BlendMode
  phaseOffset: number
  parameters: Record<string, string>
  /**
   * The operator's distribution choice, or absent when there was no meaningful control to make one
   * with — a single-head fixture, or the pad's one-tap.
   *
   * The two routes treat absence differently, and deliberately: the group route requires a
   * distribution, so it falls back to `LINEAR`, while the fixture route's is optional and is left
   * off entirely rather than pinning a choice nobody made onto the instance.
   */
  distribution?: DistributionStrategy
  /** Group only — how a multi-element member's heads are addressed. */
  elementMode?: ElementMode
  /** Restrict the effect to some of a multi-head fixture's elements. Absent means all of them. */
  elementFilter?: string
  stepTiming?: boolean
  speedMasterUuid?: string | null
  rateSpeedMasterUuid?: string | null
  /**
   * Create in the programmer's reserved priority band. Always set by the busking pad; the sheet
   * sets it only for the programmer's own `+ Effect`.
   */
  programmerOwned?: boolean
  /** Fixture only; the group route has no per-instance phase anchor. Defaults to true. */
  startOnBeat?: boolean
}

/** One create request, addressed to whichever route the target needs. */
export type FxCreateAddition =
  | { kind: 'group'; groupName: string; payload: ApplyFxRequest }
  | { kind: 'fixture'; payload: AddFixtureFxRequest }

export function fxCreateAddition(
  target: FxCreateTarget,
  request: FxCreateRequest,
): FxCreateAddition {
  const shared = {
    effectType: request.effectType,
    propertyName: request.propertyName,
    beatDivision: request.beatDivision,
    blendMode: request.blendMode,
    phaseOffset: request.phaseOffset,
    parameters: { ...request.parameters },
    ...(request.programmerOwned ? { programmerOwned: true } : {}),
    ...(request.elementFilter !== undefined ? { elementFilter: request.elementFilter } : {}),
    ...(request.stepTiming !== undefined ? { stepTiming: request.stepTiming } : {}),
    ...(request.speedMasterUuid != null ? { speedMasterUuid: request.speedMasterUuid } : {}),
    ...(request.rateSpeedMasterUuid != null
      ? { rateSpeedMasterUuid: request.rateSpeedMasterUuid }
      : {}),
  }

  if (target.type === 'group') {
    return {
      kind: 'group',
      groupName: target.groupName,
      payload: {
        ...shared,
        distribution: request.distribution ?? 'LINEAR',
        ...(request.elementMode ? { elementMode: request.elementMode } : {}),
      },
    }
  }

  return {
    kind: 'fixture',
    payload: {
      ...shared,
      fixtureKey: target.fixtureKey,
      startOnBeat: request.startOnBeat ?? true,
      ...(request.distribution !== undefined
        ? { distributionStrategy: request.distribution }
        : {}),
    },
  }
}
