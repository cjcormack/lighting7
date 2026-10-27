import type { SpreadUnit } from '@/lib/spreadShape'
import type { ApplyFxRequest, BlendMode, SpreadOrder, ElementMode } from '@/api/groupsApi'
import type { AddFixtureFxRequest } from '@/store/fixtureFx'
import type { FxDirection, Timing } from '@/api/fxApi'
import type { CueTarget } from '@/api/cuesApi'

/**
 * The one place that knows how "start this effect" is spelled for a fixture and for a group.
 *
 * The two create routes differ — a group requires an `order`, a
 * fixture carries `startOnBeat` and leaves its `order` optional — so every surface that starts an effect used to write the branch out for itself. There were four
 * copies between the old add/edit sheet and the busking pad, and they had already drifted: only some of
 * them sent the Timing, and the sheet suppressed a fixture's Order while the pad sent it
 * unconditionally.
 *
 * This does **not** cover updates. A running effect is edited over the `updateFx` frame
 * (`lightingApi.fx.updateFx`, the live editor's), whose body is the same for a fixture's effect and a
 * group's — there is nothing route-shaped left to share.
 */

/**
 * A target, reduced to the one field its create route addresses it by. A selection goes to the
 * fixture route as `targets` — one effect over them all, in order (effects plan D3).
 */
export type FxCreateTarget =
  | { type: 'group'; groupName: string }
  | { type: 'fixture'; fixtureKey: string }
  | { type: 'selection'; targets: CueTarget[] }

export interface FxCreateRequest {
  fxType: string
  attribute: string
  cycle: number
  blendMode: BlendMode
  phaseOffset: number
  parameters: Record<string, string>
  /**
   * The operator's distribution choice, or absent when there was no meaningful control to make one
   * with — a single-head fixture, or the pad's one-tap.
   *
   * The two routes treat absence differently, and deliberately: the group route requires a
   * distribution, so it falls back to `NORMAL`, while the fixture route's is optional and is left
   * off entirely rather than pinning a choice nobody made onto the instance.
   */
  order?: SpreadOrder
  /** What the spread counts over fixtures' elements (*Over*); the desk starts an absent one on `WHOLE`. */
  elementMode?: ElementMode
  /** Restrict the effect to some of a multi-head fixture's elements. Absent means all of them. */
  elementFilter?: string
  timing?: Timing
  speedMasterUuid?: string | null
  rateSpeedMasterUuid?: string | null
  /** On *Seconds* (D7): the division is seconds, no master. */
  seconds?: boolean
  /** Amount (effects plan D2), 0–1; omitted → 1. */
  amount?: number
  /** Direction (D5); omitted → forward. */
  direction?: FxDirection
  /** Runs (D9), at least 1; omitted → forever. */
  runs?: number
  /** The spread's shape (effects plan D4) — `lib/spreadShape.ts`; each absent at its default. */
  spread?: number
  wings?: number
  segments?: number
  parts?: number
  /** What `spread` counts (fx-settings plan D6): `STEP`, the FX's own steps; absent is `CYCLE`. */
  spreadUnit?: SpreadUnit
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
    fxType: request.fxType,
    attribute: request.attribute,
    cycle: request.cycle,
    blendMode: request.blendMode,
    phaseOffset: request.phaseOffset,
    parameters: { ...request.parameters },
    ...(request.programmerOwned ? { programmerOwned: true } : {}),
    ...(request.elementFilter !== undefined ? { elementFilter: request.elementFilter } : {}),
    ...(request.elementMode ? { elementMode: request.elementMode } : {}),
    ...(request.timing !== undefined ? { timing: request.timing } : {}),
    ...(request.speedMasterUuid != null ? { speedMasterUuid: request.speedMasterUuid } : {}),
    ...(request.rateSpeedMasterUuid != null
      ? { rateSpeedMasterUuid: request.rateSpeedMasterUuid }
      : {}),
    ...(request.seconds ? { seconds: true } : {}),
    ...(request.amount != null && request.amount !== 1 ? { amount: request.amount } : {}),
    ...(request.direction != null && request.direction !== 'FORWARD' ? { direction: request.direction } : {}),
    ...(request.runs != null ? { runs: request.runs } : {}),
    // The spread's shape (effects plan D4), said only where it is not the default.
    ...(request.spread != null && request.spread !== 1 ? { spread: request.spread } : {}),
    ...(request.wings != null && request.wings !== 1 ? { wings: request.wings } : {}),
    ...(request.segments != null && request.segments !== 1 ? { segments: request.segments } : {}),
    ...(request.parts != null && request.parts !== 0 ? { parts: request.parts } : {}),
    // What the spread counts, whenever the caller says: absent, a step FX starts one step behind.
    ...(request.spreadUnit != null ? { spreadUnit: request.spreadUnit } : {}),
  }

  if (target.type === 'group') {
    return {
      kind: 'group',
      groupName: target.groupName,
      payload: {
        ...shared,
        order: request.order ?? 'NORMAL',
      },
    }
  }

  return {
    kind: 'fixture',
    payload: {
      ...shared,
      ...(target.type === 'selection' ? { targets: target.targets } : { fixtureKey: target.fixtureKey }),
      startOnBeat: request.startOnBeat ?? true,
      ...(request.order !== undefined
        ? { order: request.order }
        : {}),
    },
  }
}
