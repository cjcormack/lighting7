import type { SpreadUnit } from '@/lib/spreadShape'
import { useCallback } from 'react'
import { useDispatch } from 'react-redux'
import { toast } from 'sonner'
import type { CueTarget } from '@/api/cuesApi'
import type { FxDirection, UpdateFxRequest } from '@/api/fxApi'
import type { AttributeFamily } from '@/lib/attributeFamily'
import { restApi } from './restApi'
import { lightingApi } from '../api/lightingApi'
import { store } from './index'
import type { BlendMode } from '../api/groupsApi'
import type { FxError, Timing } from '../api/fxApi'

// WebSocket subscription: invalidate fixture effects when any FX changes
lightingApi.fx.subscribe(() => {
  store.dispatch(restApi.util.invalidateTags(['FixtureFx']))
})

/**
 * Shared sonner id for every refused `updateFx`, **per effect** (fixture-fx-sheets plan W3).
 *
 * The live editor writes on every move of a drag, so a refused field is refused once per frame;
 * keying the toast by effect makes sonner replace rather than stack, while two effects refused at
 * once still each say their piece — `speedMasterErrorToastId`'s reasoning, per master.
 */
export const fxErrorToastId = (fxId: number) => `fx-error-${fxId}`

/** `fxError` as the operator reads it: the desk's own prose, keyed by the effect. */
export function toastFxError(error: FxError) {
  toast.error(error.message, { id: fxErrorToastId(error.fxId) })
}

// …and `fxError`, the live editor's refusals. Unicast to the socket that sent the `updateFx`, so a
// second tab never toasts for someone else's edit; nothing to invalidate — a refused update changed
// nothing. Module scope, beside the subscription above (form 1).
lightingApi.fx.subscribeToErrors(toastFxError)

/**
 * Re-rank a Local FX in apply order (`programmer.moveFx`, FX editor plan §3.5): directly below
 * [above]'s FX, or at the bottom of the Local FX for null. The answer is the ordinary `fxChanged`,
 * which refetches the list above, or an `fxError` toasted by [toastFxError]; the verdict is the send's.
 */
export function moveLocalFx(fxId: number, above: number | null): boolean {
  return lightingApi.fx.moveFx(fxId, above)
}

// …and `fxDefinitionListChanged` for the effect *library* — the vocabulary an effect is chosen
// from, which changes for entirely unrelated reasons to the running set above.
//
// Bridged here rather than in `store/fxDefinitions.ts`, where the definition CRUD endpoints live:
// `fxLibrary` below is the `FxLibrary` consumer that mounts everywhere (the FX sheet, the
// programmer's add-effect popover), while that slice is reached only from `routes/FxLibrary.tsx`.
// A bridge there would exist only once someone had opened the library page.
lightingApi.fxDefinitions.subscribe(() => {
  store.dispatch(restApi.util.invalidateTags(['FxLibrary']))
})

// === Types ===

export interface FixtureDirectFx {
  id: number
  fxType: string
  targetKey: string
  attribute: string
  cycle: number
  blendMode: string
  isRunning: boolean
  phaseOffset: number
  currentPhase: number
  parameters: Record<string, string>
  isGroupTarget: false
  order: string | null
  elementFilter: string | null
  timing: Timing
  cueId: number | null
  /** Speed master this effect subscribes to (null → master 1). */
  speedMasterUuid?: string | null
  /** Wall-clock rate master (null → unscaled); only WALL_CLOCK effects read it. */
  rateSpeedMasterUuid?: string | null
  /** D7's reading: follows no master. */
  seconds?: boolean
}

export interface FixtureIndirectFx {
  id: number
  fxType: string
  /** The group reaching the fixture; a selection's canonical key when [targetType] is `selection`. */
  groupName: string
  /** `group`, or `selection` for an effect over a selection naming the fixture. Absent before D3. */
  targetType?: 'group' | 'selection'

  attribute: string
  cycle: number
  blendMode: string
  isRunning: boolean
  phaseOffset: number
  currentPhase: number
  parameters: Record<string, string>
  order: string
  timing: Timing
  /** Speed master this effect subscribes to (null → master 1). */
  speedMasterUuid?: string | null
  /** Wall-clock rate master (null → unscaled); only WALL_CLOCK effects read it. */
  rateSpeedMasterUuid?: string | null
  /** D7's reading: follows no master. */
  seconds?: boolean
}

export interface FixtureFx {
  direct: FixtureDirectFx[]
  indirect: FixtureIndirectFx[]
}

/**
 * One FX parameter, and how the desk says to draw it (fx-settings plan D1). Every field after
 * `description` is optional: a script FX that declares none is drawn as the editor guesses
 * (`paramView` in `fxEditorModel.ts`).
 */
export interface FxParameterDef {
  name: string
  type: string
  defaultValue: string
  description: string
  label?: string | null
  /** In the stored value's terms: a byte's 0–255, a ratio's 0–1. */
  min?: number | null
  max?: number | null
  step?: number | null
  unit?: FxParamUnit | null
  /** A `choice`'s values, and the desk's curves on an `easingCurve`. */
  options?: { value: string; label: string }[] | null
  /** `detail` folds under *More*. */
  role?: 'main' | 'detail' | null
  /** Drawn only while `param` holds one of `values`. */
  shownWhen?: { param: string; values: string[] } | null
  /** On a low, the high it is drawn with as one range. */
  pair?: string | null
}

/**
 * `%` a byte level read as a percent of full, `ratio` a 0–1 share as a percent, `dmx` a byte in DMX
 * steps, `position` a byte about its axis's centre, `ms` milliseconds — the desk's `PARAMETER_UNITS`.
 */
export type FxParamUnit = '%' | 'ratio' | 'dmx' | 'position' | 'ms'

export interface FxLibraryEntry {
  name: string
  family: string
  outputType: string
  fxMode: string
  /**
   * `BEAT` (the default) or `WALL_CLOCK`. Decides which master picker is meaningful and how
   * the beat-division control is labelled — a wall-clock effect reads its division as cycle
   * *seconds* and never consults a speed master at all.
   */
  timingSource?: string
  /** The division a new one runs at, in beats (seconds for a wall-clock FX); absent from an older desk. */
  defaultCycle?: number
  /** The Direction a new one runs in — `BOUNCE` for the FX that bounce; absent from an older desk. */
  defaultDirection?: string
  description?: string
  parameters: FxParameterDef[]
  compatibleAttributes: string[]
  source?: string
  sourceDefinitionId?: number | null
  script?: string | null
  /** The picker's familiar names for this FX on one attribute (fx-settings plan D4). */
  presets?: FxPreset[]
  /** Parameters a new FX on an attribute starts with, by attribute — Wave's zoom at 64–192. */
  attributeDefaults?: Record<string, Record<string, string>>
  /**
   * Kept for what is already stored and never offered (fx-settings plan D3): Colour Fade and Colour
   * Wave, which Colour Cycle draws a DMX step apart. It runs and edits as any FX; no picker lists it.
   */
  legacy?: boolean
  /**
   * The list parameter whose entries are this FX's steps (fx-settings plan D6) — Colour Cycle's
   * `colours` — absent for an FX with none: only one that names it takes a `STEP` spread.
   */
  stepsFrom?: string
}

/**
 * A familiar name the picker lists beside the FX types (fx-settings plan D4): its FX on
 * `attribute`, starting with `parameters` over the FX's defaults, at its own `cycle` (beats) and
 * `direction` where it names them.
 */
export interface FxPreset {
  name: string
  attribute: string
  parameters?: Record<string, string>
  cycle?: number | null
  direction?: string | null
}

export interface AddFixtureFxRequest {
  fxType: string
  /** The fixture or cell to run on; absent beside [targets]. */
  fixtureKey?: string
  /**
   * One effect over these fixtures and groups, spread across them in this order as a group's
   * members are (effects plan D3). One item is that fixture or group alone.
   */
  targets?: CueTarget[]
  attribute: string
  cycle: number
  blendMode: BlendMode
  startOnBeat: boolean
  phaseOffset: number
  parameters: Record<string, string>
  order?: string
  elementFilter?: string
  /** What the spread counts over fixtures' elements; absent starts on Fixtures (`WHOLE`). */
  elementMode?: string
  timing?: Timing
  /** Speed master to subscribe to, as the master's uuid (omitted → master 1). */
  speedMasterUuid?: string
  /**
   * Wall-clock rate master, as the master's uuid (omitted → unscaled). Read only by
   * WALL_CLOCK effects; it sits alongside `speedMasterUuid` rather than replacing it.
   */
  rateSpeedMasterUuid?: string
  /** On *Seconds* (D7): the division is seconds, no master. Never beside a master. */
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
   * Create the effect in the programmer's reserved priority band, so it composes *on top of*
   * programmer values instead of being suppressed by them, and Clear sweeps it with them.
   * Set by the busking pad; cue and script authoring leave it off.
   */
  programmerOwned?: boolean
}

/**
 * The bare effect press, `POST /projects/{id}/programmer/fx` (apply-model plan §3.4, D5): an
 * effect type from the library onto the selection as **one Local effect** over it. The effect's
 * fields are [AddFixtureFxRequest]'s; where it runs is always the selection and whose it is always
 * the programmer's, so neither `fixtureKey` nor `programmerOwned` is sent.
 */
export interface ProgrammerFxRequest
  extends Omit<
    AddFixtureFxRequest,
    'fixtureKey' | 'targets' | 'programmerOwned' | 'cycle' | 'startOnBeat' | 'blendMode'
  > {
  projectId: number
  /** The selection, in the order the effect spreads over it. */
  targets: CueTarget[]
  /** The selection's attribute mask; absent is every attribute. */
  families?: AttributeFamily[]
  /** One bar when absent (venue-visit plan D16). */
  cycle?: number
  startOnBeat?: boolean
  elementMode?: string
  /** Override when absent, or the family's stacking start under [stack]. */
  blendMode?: BlendMode
  /**
   * Add the effect on top of the Local effects on the selection, stopping and narrowing none
   * (FX editor plan D6). An absent blend or `amount` starts on the family's row: Intensity *Within*,
   * Position *Around*, Colour and Beam *Replace* at 50 %.
   */
  stack?: boolean
}

/**
 * The press's answer: the new effect, the Local effects of its family it stopped (every head they
 * painted was selected), and those it narrowed — gone, their unselected heads now run by `into`.
 * Both empty for a `stack` press.
 */
export interface ProgrammerFxResponse {
  fxId: number
  stopped: number[]
  narrowed: { fxId: number; into: number[] }[]
}

/**
 * `PUT /fx/{id}`'s answer when its `targets` named some of the effect's heads (apply-model plan
 * §3.4): the effect that is gone, the copies the edit landed on, and the copies left as they were.
 */
export interface FxSplitResponse {
  splitFxId: number
  edited: ActiveFx[]
  rest: ActiveFx[]
}

/** Whether a `PUT /fx/{id}` answered a split rather than the effect edited in place. */
export function isFxSplit(answer: ActiveFx | FxSplitResponse): answer is FxSplitResponse {
  return 'splitFxId' in answer
}

/**
 * One running effect, as `GET /api/rest/fx/active` reports it. Richer than the `fxState`
 * WebSocket frame, which carries no `attribute` or group flag — both of which the FX
 * sheet needs to place a chip in the right fixture x column.
 */
export interface ActiveFx {
  id: number
  fxType: string
  /** A fixture's key or a group's name; a selection's canonical key, which names neither. */
  targetKey: string
  /**
   * `fixture`, `group` or `selection` (effects plan D3). Absent from a desk before effects plan
   * session 3. Read through `lib/fxTargets.ts`, never by comparing [targetKey].
   */
  targetType?: 'fixture' | 'group' | 'selection'
  /** A selection's fixtures and groups, in the order the effect spreads over them. */
  targets?: CueTarget[] | null
  attribute: string
  cycle: number
  blendMode: string
  isRunning: boolean
  phaseOffset: number
  currentPhase: number
  parameters: Record<string, string>
  isGroupTarget: boolean
  order: string | null
  elementMode: string | null
  elementFilter: string | null
  timing: Timing
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
   * null on a *detached* copy — the one *Apply as values* on an effect palette's chip mints — which
   * carries no source at all, deliberately, so nothing can rebuild it as a tracking layer.
   */
  paletteId: number | null
  /** The Look's or template's name, for the home badge. Null when the effect has no source. */
  sourceName: string | null
  /** The programmer layer that spawned it. Null for an effect the operator busked directly. */
  programmerLayerId: number | null
  cueId: number | null
  /** The stack whose GO spawned it (its cue's first hold), when a stack's did. */
  cueStackId?: number | null
  timingSource: string
  /** True when the effect sits in the programmer's priority band. */
  programmerOwned: boolean
  /**
   * The envelope, 0–1: the runtime factor of the effect's weight a fade moves, beside [amount]
   * (effects plan P3), as it stood when the desk sent the list.
   */
  intensityMultiplier: number
  /**
   * Let go on a fade and shrinking to nothing (the desk's `FxInstance.leaving`): nobody's, so not
   * Local and not recordable, and gone at 0. Absent from a desk before effects plan session 4.
   */
  leaving?: boolean
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
  /** Amount (effects plan D2), 0–1: how much reaches the stage over what is underneath. Absent at 1. */
  amount?: number
  /** Direction (D5): `FORWARD`, `BACKWARD` or `BOUNCE`. Absent when forward. */
  direction?: FxDirection
  /** Runs (D9): the cycles an N-shot runs; absent or null runs forever. */
  runs?: number | null
  /** The spread's shape (effects plan D4) — `lib/spreadShape.ts`; each absent at its default. */
  spread?: number
  wings?: number
  segments?: number
  parts?: number
  /** What `spread` counts (fx-settings plan D6): `STEP`, the FX's own steps; absent is `CYCLE`. */
  spreadUnit?: SpreadUnit
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
    fixtureFx: build.query<FixtureFx, string>({
      query: (fixtureKey) => `fx/fixture/${encodeURIComponent(fixtureKey)}`,
      providesTags: (_result, _error, fixtureKey) => [
        { type: 'FixtureFx', id: fixtureKey },
      ],
    }),

    /** Every running effect, rig-wide. Invalidated by the `fxChanged` subscription above. */
    activeFx: build.query<ActiveFx[], void>({
      query: () => 'fx/active',
      providesTags: ['FixtureFx'],
    }),

    fxLibrary: build.query<FxLibraryEntry[], void>({
      query: () => 'fx/library',
      providesTags: ['FxLibrary'],
    }),

    addFixtureFx: build.mutation<{ fxId: number }, AddFixtureFxRequest>({
      query: (request) => ({
        url: 'fx/add',
        method: 'POST',
        body: request,
      }),
      // A selection names several fixtures, so it invalidates every fixture's list.
      invalidatesTags: (_result, _error, { fixtureKey }) =>
        fixtureKey != null ? [{ type: 'FixtureFx', id: fixtureKey }] : ['FixtureFx'],
    }),

    /** The bare effect press ([ProgrammerFxRequest]). Current project only. */
    pressProgrammerFx: build.mutation<ProgrammerFxResponse, ProgrammerFxRequest>({
      query: ({ projectId, ...body }) => ({
        url: `projects/${projectId}/programmer/fx`,
        method: 'POST',
        body,
      }),
      invalidatesTags: ['FixtureFx'],
    }),

    /**
     * An edit over REST, for the one the socket frame cannot answer: with `targets` naming some of
     * the effect's heads it splits them off and answers the copies ([FxSplitResponse]), where the
     * `updateFx` frame answers only `fxChanged` REMOVED and ADDED. Without, the in-place edit.
     */
    updateFx: build.mutation<ActiveFx | FxSplitResponse, { id: number; update: UpdateFxRequest }>({
      query: ({ id, update }) => ({
        url: `fx/${id}`,
        method: 'PUT',
        body: update,
      }),
      invalidatesTags: ['FixtureFx'],
    }),

    removeFx: build.mutation<void, { id: number; fixtureKey: string }>({
      query: ({ id }) => ({
        url: `fx/${id}`,
        method: 'DELETE',
      }),
      invalidatesTags: (_result, _error, { fixtureKey }) => [
        { type: 'FixtureFx', id: fixtureKey },
      ],
    }),

    pauseFx: build.mutation<void, { id: number; fixtureKey: string }>({
      query: ({ id }) => ({
        url: `fx/${id}/pause`,
        method: 'POST',
      }),
      invalidatesTags: (_result, _error, { fixtureKey }) => [
        { type: 'FixtureFx', id: fixtureKey },
      ],
    }),

    /**
     * Put a palette layer's current effect back on the instance it spawned, keeping the id and
     * the phase — `POST /fx/{id}/reset` (fixture-fx-sheets plan W5), the busk Effects tab's *Reset
     * to palette*. 409 `FX_NOT_FROM_PALETTE` for an instance no programmer palette layer
     * started, 409 `FX_PALETTE_GONE` when its palette no longer holds an effect.
     */
    resetFxToPalette: build.mutation<ActiveFx, { id: number }>({
      query: ({ id }) => ({
        url: `fx/${id}/reset`,
        method: 'POST',
      }),
      invalidatesTags: ['FixtureFx'],
    }),

    resumeFx: build.mutation<void, { id: number; fixtureKey: string }>({
      query: ({ id }) => ({
        url: `fx/${id}/resume`,
        method: 'POST',
      }),
      invalidatesTags: (_result, _error, { fixtureKey }) => [
        { type: 'FixtureFx', id: fixtureKey },
      ],
    }),
  }),
  overrideExisting: false,
})

export const {
  useFixtureFxQuery,
  useActiveFxQuery,
  useFxLibraryQuery,
  useAddFixtureFxMutation,
  usePressProgrammerFxMutation,
  useUpdateFxMutation,
  useRemoveFxMutation,
  usePauseFxMutation,
  useResumeFxMutation,
  useResetFxToPaletteMutation,
} = fixtureFxApi

/**
 * `PUT /fx/{id}` for an editor that follows what it edits: answers as the mutation does, null on a
 * refusal (already toasted), and puts a split's copies into the running list **before** it answers —
 * so a host reading the edited copy by id finds it at once, rather than reading nothing until the
 * refetch the invalidation starts comes back, and closing its editor in between.
 */
export function useSplitFx(): {
  splitFx: (id: number, update: UpdateFxRequest) => Promise<ActiveFx | FxSplitResponse | null>
  splitting: boolean
} {
  const dispatch = useDispatch<typeof store.dispatch>()
  const [updateFx, { isLoading }] = useUpdateFxMutation()
  const splitFx = useCallback(
    async (id: number, update: UpdateFxRequest) => {
      const answer = await updateFx({ id, update })
        .unwrap()
        .catch(() => null)
      if (answer != null && isFxSplit(answer)) {
        dispatch(
          fixtureFxApi.util.updateQueryData('activeFx', undefined, (effects) => [
            ...effects.filter((e) => e.id !== answer.splitFxId),
            ...answer.edited,
            ...answer.rest,
          ]),
        )
      }
      return answer
    },
    [dispatch, updateFx],
  )
  return { splitFx, splitting: isLoading }
}
