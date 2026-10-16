import { toast } from 'sonner'
import type { CueTarget } from '@/api/cuesApi'
import type { EffectDirection } from '@/api/fxApi'
import { restApi } from './restApi'
import { lightingApi } from '../api/lightingApi'
import { store } from './index'
import type { BlendMode } from '../api/groupsApi'
import type { FxError } from '../api/fxApi'

// WebSocket subscription: invalidate fixture effects when any FX changes
lightingApi.fx.subscribe(() => {
  store.dispatch(restApi.util.invalidateTags(['FixtureEffects']))
})

/**
 * Shared sonner id for every refused `updateFx`, **per effect** (fixture-fx-sheets plan W3).
 *
 * The live editor writes on every move of a drag, so a refused field is refused once per frame;
 * keying the toast by effect makes sonner replace rather than stack, while two effects refused at
 * once still each say their piece — `speedMasterErrorToastId`'s reasoning, per master.
 */
export const fxErrorToastId = (effectId: number) => `fx-error-${effectId}`

/** `fxError` as the operator reads it: the desk's own prose, keyed by the effect. */
export function toastFxError(error: FxError) {
  toast.error(error.message, { id: fxErrorToastId(error.effectId) })
}

// …and `fxError`, the live editor's refusals. Unicast to the socket that sent the `updateFx`, so a
// second tab never toasts for someone else's edit; nothing to invalidate — a refused update changed
// nothing. Module scope, beside the subscription above (form 1).
lightingApi.fx.subscribeToErrors(toastFxError)

// …and `fxDefinitionListChanged` for the effect *library* — the vocabulary an effect is chosen
// from, which changes for entirely unrelated reasons to the running set above.
//
// Bridged here rather than in `store/fxDefinitions.ts`, where the definition CRUD endpoints live:
// `effectLibrary` below is the `FxLibrary` consumer that mounts everywhere (the FX sheet, the
// programmer's add-effect popover), while that slice is reached only from `routes/FxLibrary.tsx`.
// A bridge there would exist only once someone had opened the library page.
lightingApi.fxDefinitions.subscribe(() => {
  store.dispatch(restApi.util.invalidateTags(['FxLibrary']))
})

// === Types ===

export interface FixtureDirectEffect {
  id: number
  effectType: string
  targetKey: string
  propertyName: string
  beatDivision: number
  blendMode: string
  isRunning: boolean
  phaseOffset: number
  currentPhase: number
  parameters: Record<string, string>
  isGroupTarget: false
  distributionStrategy: string | null
  elementFilter: string | null
  stepTiming: boolean
  cueId: number | null
  /** Speed master this effect subscribes to (null → master 1). */
  speedMasterUuid?: string | null
  /** Wall-clock rate master (null → unscaled); only WALL_CLOCK effects read it. */
  rateSpeedMasterUuid?: string | null
  /** D7's reading: follows no master. */
  seconds?: boolean
}

export interface FixtureIndirectEffect {
  id: number
  effectType: string
  /** The group reaching the fixture; a selection's canonical key when [targetType] is `selection`. */
  groupName: string
  /** `group`, or `selection` for an effect over a selection naming the fixture. Absent before D3. */
  targetType?: 'group' | 'selection'

  propertyName: string
  beatDivision: number
  blendMode: string
  isRunning: boolean
  phaseOffset: number
  currentPhase: number
  parameters: Record<string, string>
  distributionStrategy: string
  stepTiming: boolean
  /** Speed master this effect subscribes to (null → master 1). */
  speedMasterUuid?: string | null
  /** Wall-clock rate master (null → unscaled); only WALL_CLOCK effects read it. */
  rateSpeedMasterUuid?: string | null
  /** D7's reading: follows no master. */
  seconds?: boolean
}

export interface FixtureEffects {
  direct: FixtureDirectEffect[]
  indirect: FixtureIndirectEffect[]
}

export interface EffectParameterDef {
  name: string
  type: string
  defaultValue: string
  description: string
}

export interface EffectLibraryEntry {
  name: string
  category: string
  outputType: string
  effectMode: string
  /**
   * `BEAT` (the default) or `WALL_CLOCK`. Decides which master picker is meaningful and how
   * the beat-division control is labelled — a wall-clock effect reads its division as cycle
   * *seconds* and never consults a speed master at all.
   */
  timingSource?: string
  description?: string
  parameters: EffectParameterDef[]
  compatibleProperties: string[]
  source?: string
  sourceDefinitionId?: number | null
  script?: string | null
}

export interface AddFixtureFxRequest {
  effectType: string
  /** The fixture or cell to run on; absent beside [targets]. */
  fixtureKey?: string
  /**
   * One effect over these fixtures and groups, spread across them in this order as a group's
   * members are (effects plan D3). One item is that fixture or group alone.
   */
  targets?: CueTarget[]
  propertyName: string
  beatDivision: number
  blendMode: BlendMode
  startOnBeat: boolean
  phaseOffset: number
  parameters: Record<string, string>
  distributionStrategy?: string
  elementFilter?: string
  stepTiming?: boolean
  /** Speed master to subscribe to, as the master's uuid (omitted → master 1). */
  speedMasterUuid?: string
  /**
   * Wall-clock rate master, as the master's uuid (omitted → unscaled). Read only by
   * WALL_CLOCK effects; it sits alongside `speedMasterUuid` rather than replacing it.
   */
  rateSpeedMasterUuid?: string
  /** On *Seconds* (D7): the division is seconds, no master. Never beside a master. */
  seconds?: boolean
  /** Size (effects plan D2), 0–1; omitted → 1. */
  size?: number
  /** Direction (D5); omitted → forward. */
  direction?: EffectDirection
  /** Runs (D9), at least 1; omitted → forever. */
  nShot?: number
  /** The spread's shape (effects plan D4) — `lib/spreadShape.ts`; each absent at its default. */
  spread?: number
  wings?: number
  blocks?: number
  groups?: number
  /**
   * Create the effect in the programmer's reserved priority band, so it composes *on top of*
   * programmer values instead of being suppressed by them, and Clear sweeps it with them.
   * Set by the busking pad; cue and script authoring leave it off.
   */
  programmerOwned?: boolean
}

/**
 * One running effect, as `GET /api/rest/fx/active` reports it. Richer than the `fxState`
 * WebSocket frame, which carries no `propertyName` or group flag — both of which the FX
 * sheet needs to place a chip in the right fixture x column.
 */
export interface ActiveEffect {
  id: number
  effectType: string
  /** A fixture's key or a group's name; a selection's canonical key, which names neither. */
  targetKey: string
  /**
   * `fixture`, `group` or `selection` (effects plan D3). Absent from a desk before effects plan
   * session 3. Read through `lib/effectTargets.ts`, never by comparing [targetKey].
   */
  targetType?: 'fixture' | 'group' | 'selection'
  /** A selection's fixtures and groups, in the order the effect spreads over them. */
  targets?: CueTarget[] | null
  propertyName: string
  beatDivision: number
  blendMode: string
  isRunning: boolean
  phaseOffset: number
  currentPhase: number
  parameters: Record<string, string>
  isGroupTarget: boolean
  distributionStrategy: string | null
  elementMode: string | null
  elementFilter: string | null
  stepTiming: boolean
  /**
   * The Look this effect came out of, when it came out of one.
   *
   * Both this and [programmerLayerId] have been on the wire since the layer model landed and were
   * simply never declared here. They are what lets the programmer's effect band say where an effect
   * *lives* — "in Storm Wash · layer 2" rather than an unattributed row — which is the answer to
   * "why can't I delete this?".
   *
   * Not interchangeable with [programmerLayerId]: one Look may be applied by two layers, so the
   * Look says what the effect is and the layer says which stack line spawned it.
   */
  lookId: number | null
  /**
   * The **template** this effect came out of, when it came out of one.
   *
   * A sibling of [lookId] rather than a widening of it: the backend kept `lookId` meaning a Look and
   * added this beside it, so the two are never both set and a reader has to check both. Both are
   * null on a *detached* copy — the one a plain click on an effect template's chip mints — which
   * carries no source at all, deliberately, so nothing can rebuild it as a tracking layer.
   */
  templateId: number | null
  /** The Look's or template's name, for the home badge. Null when the effect has no source. */
  sourceName: string | null
  /** The programmer layer that spawned it. Null for an effect the operator busked directly. */
  programmerLayerId: number | null
  cueId: number | null
  timingSource: string
  /** True when the effect sits in the programmer's priority band. */
  programmerOwned: boolean
  /**
   * The envelope, 0–1: the runtime factor of the effect's weight a fade moves, beside [size]
   * (effects plan P3). 1 until the cue fades move it.
   */
  intensityMultiplier: number
  /** Speed master this effect subscribes to (null → master 1). */
  speedMasterUuid: string | null
  /** Wall-clock rate master (null → unscaled); only WALL_CLOCK effects read it. */
  rateSpeedMasterUuid: string | null
  /**
   * D7's reading (venue-visit plan S15): the effect follows no master — a beat effect on its own
   * cycle in seconds (its `timingSource` then reads `WALL_CLOCK`, the loop it runs on), or a
   * wall-clock effect with no rate master. Absent from a desk before S15; `isOnSeconds` reads it.
   */
  seconds?: boolean
  /** Size (effects plan D2), 0–1: how much reaches the stage over what is underneath. Absent at 1. */
  size?: number
  /** Direction (D5): `FORWARD`, `BACKWARD` or `BOUNCE`. Absent when forward. */
  direction?: EffectDirection
  /** Runs (D9): the cycles an N-shot runs; absent or null runs forever. */
  nShot?: number | null
  /** The spread's shape (effects plan D4) — `lib/spreadShape.ts`; each absent at its default. */
  spread?: number
  wings?: number
  blocks?: number
  groups?: number
  /**
   * An N-shot that has run its count and is kept, painting nothing, until its programmer layer goes
   * (the desk's `FxInstance.spent`). Any other spent N-shot has already left the list.
   */
  spent?: boolean
  /**
   * The Look whose effect Include ran here — never `lookId`, which a busk pad lights from. Absent
   * from a desk before Include ran a Look's effects, and on every other effect.
   */
  includedLookId?: number | null
  /** An included Look effect changed since Include: what Update would write back. */
  editedSinceInclude?: boolean
}

// === RTK Query Endpoints ===

export const fixtureFxApi = restApi.injectEndpoints({
  endpoints: (build) => ({
    fixtureEffects: build.query<FixtureEffects, string>({
      query: (fixtureKey) => `fx/fixture/${encodeURIComponent(fixtureKey)}`,
      providesTags: (_result, _error, fixtureKey) => [
        { type: 'FixtureEffects', id: fixtureKey },
      ],
    }),

    /** Every running effect, rig-wide. Invalidated by the `fxChanged` subscription above. */
    activeEffects: build.query<ActiveEffect[], void>({
      query: () => 'fx/active',
      providesTags: ['FixtureEffects'],
    }),

    effectLibrary: build.query<EffectLibraryEntry[], void>({
      query: () => 'fx/library',
      providesTags: ['FxLibrary'],
    }),

    addFixtureFx: build.mutation<{ effectId: number }, AddFixtureFxRequest>({
      query: (request) => ({
        url: 'fx/add',
        method: 'POST',
        body: request,
      }),
      // A selection names several fixtures, so it invalidates every fixture's list.
      invalidatesTags: (_result, _error, { fixtureKey }) =>
        fixtureKey != null ? [{ type: 'FixtureEffects', id: fixtureKey }] : ['FixtureEffects'],
    }),

    removeFx: build.mutation<void, { id: number; fixtureKey: string }>({
      query: ({ id }) => ({
        url: `fx/${id}`,
        method: 'DELETE',
      }),
      invalidatesTags: (_result, _error, { fixtureKey }) => [
        { type: 'FixtureEffects', id: fixtureKey },
      ],
    }),

    pauseFx: build.mutation<void, { id: number; fixtureKey: string }>({
      query: ({ id }) => ({
        url: `fx/${id}/pause`,
        method: 'POST',
      }),
      invalidatesTags: (_result, _error, { fixtureKey }) => [
        { type: 'FixtureEffects', id: fixtureKey },
      ],
    }),

    /**
     * Put a palette layer's current effect back on the instance it spawned, keeping the id and
     * the phase — `POST /fx/{id}/reset` (fixture-fx-sheets plan W5), the busk Effects tab's *Reset
     * to palette*. 409 `FX_NOT_FROM_PALETTE` for an instance no programmer palette layer
     * started, 409 `FX_PALETTE_GONE` when its palette no longer holds an effect.
     */
    resetFxToTemplate: build.mutation<ActiveEffect, { id: number }>({
      query: ({ id }) => ({
        url: `fx/${id}/reset`,
        method: 'POST',
      }),
      invalidatesTags: ['FixtureEffects'],
    }),

    resumeFx: build.mutation<void, { id: number; fixtureKey: string }>({
      query: ({ id }) => ({
        url: `fx/${id}/resume`,
        method: 'POST',
      }),
      invalidatesTags: (_result, _error, { fixtureKey }) => [
        { type: 'FixtureEffects', id: fixtureKey },
      ],
    }),
  }),
  overrideExisting: false,
})

export const {
  useFixtureEffectsQuery,
  useActiveEffectsQuery,
  useEffectLibraryQuery,
  useAddFixtureFxMutation,
  useRemoveFxMutation,
  usePauseFxMutation,
  useResumeFxMutation,
  useResetFxToTemplateMutation,
} = fixtureFxApi
