import { restApi } from './restApi'
import type { AttributeFamily } from '../lib/attributeFamily'
import type { Cue, CueTarget } from '../api/cuesApi'
import type { LookDetails } from '../api/looksApi'
import type { IncludedTarget } from '../api/programmerWsApi'

/**
 * Record / Include / Update — the programmer's authoring loop.
 *
 * These are REST mutations rather than `programmer.*` WS ops (which is what everything else in
 * `store/programmer.ts` is) for one reason: they all need a *structured reply* — the created
 * cue, the fixtures to select, the Mode B checklist — and the programmer WS channel is
 * fire-and-forget with no request/response correlation. Going through RTK Query also gets us
 * cue-tag invalidation and per-call error state, both of which the dialogs need.
 *
 * All three are silent in `errorToastMiddleware`/`saveStatusSlice`: their sheets render their
 * own errors, so a global toast would double up.
 */

/**
 * Which attribute families an operation touches. Omitted or all four means "everything".
 *
 * An **alias**, not a second declaration: this is the same four-value vocabulary
 * `lib/attributeFamily.ts` calls [AttributeFamily], and one Kotlin enum (`PropertyMaskGroup`)
 * behind both. The name survives because it is the wire's — these request bodies are mirrors of
 * the backend's own field — but the *type* must not, or a literal added to one union still
 * typechecks against the other while meaning nothing to the server. `maskPicker.test.ts` pins the
 * vocabulary; this pins the types.
 */
export type PropertyMaskGroup = AttributeFamily

export type RecordMode = 'CREATE' | 'MERGE' | 'REMOVE' | 'UPDATE_EXISTING'

export type RecordSource = 'TOUCHED' | 'ALL' | 'STAGE_SNAPSHOT'

/** An entry that couldn't be written, and why. Rendered so a partial record isn't silent. */
export interface ProgrammerSkip {
  targetKey?: string
  propertyName?: string
  universe?: number
  channel?: number
  reason:
    | 'ELEMENT_TARGET'
    | 'MISSING_FIXTURE'
    | 'MISSING_PROPERTY'
    | 'NO_BACKING_PROPERTY'
    | 'MASKED_OUT'
    /**
     * Outside the request's fixture scope — the `targets` it sent, when it sent any.
     *
     * The **Look** record routes always pass the operator's selection, because a Look recorded
     * from the *whole* programmer would capture every head it happens to hold and "Warm Amber"
     * would mean the rig. A cue Record scoped to targets passes one too, and gets the same
     * reason back for everything it left out.
     */
    | 'OUT_OF_SCOPE'
}

/**
 * What a write deliberately left alone. Every count is optional: the server defaults all five to
 * zero and does not encode defaults, so a wholly uneventful Record omits the lot.
 */
export interface ProgrammerPreservedCounts {
  triggers?: number
  /** Timed layers left untouched. Named `timedPresetApplications` before a preset became a layer. */
  timedLayers?: number
  timedAdHocEffects?: number
  outOfMaskAssignments?: number
  /** Rows left alone because they name a fixture outside the request's `targets`. */
  outOfScopeAssignments?: number
}

export interface RecordRequest {
  projectId: number
  mode: RecordMode
  source?: RecordSource
  /** Required for CREATE. */
  cueStackId?: number
  /** Required for MERGE / REMOVE / UPDATE_EXISTING. */
  cueId?: number
  mask?: PropertyMaskGroup[]
  includeFx?: boolean
  name?: string
  cueNumber?: string
  /**
   * Restrict the record to these fixtures — "put just these heads into this cue". Groups are
   * expanded server-side.
   *
   * Omitted records the whole programmer, which is the right default for a cue: capturing
   * everything the operator busked is usually what was meant. A Look is the opposite case — see
   * the Look record's own `targets`, and `OUT_OF_SCOPE`.
   */
  targets?: CueTarget[]
}

export interface RecordResponse {
  cue: Cue
  created: boolean
  assignmentsWritten: number
  assignmentsRemoved: number
  groupRowsEmitted: number
  fxWritten: number
  preserved: ProgrammerPreservedCounts
  republishedLive: boolean
  skipped: ProgrammerSkip[]
  warnings: string[]
}

// ── Record into a Look ──────────────────────────────────────────────────────

export interface RecordLookRequest {
  projectId: number
  /** CREATE mints a new Look; the other three need [lookId]. */
  mode: RecordMode
  lookId?: number
  name?: string
  notes?: string
  source?: RecordSource
  /**
   * Which attribute families to record. Explicit because a Look has **no type to imply it**:
   * nothing about the destination says "this is a colour record", so the operator's mask is the
   * only statement of intent there is.
   */
  mask?: PropertyMaskGroup[]
  /**
   * The operator's selection. Groups are expanded server-side.
   *
   * Strongly recommended rather than optional in practice: a Look recorded from the whole
   * programmer captures every head the programmer happens to hold, which is almost never what
   * "Warm Amber" is meant to mean.
   */
  targets?: CueTarget[]
  /**
   * Running programmer-band effects to fold into the Look, by `ActiveEffect.id`.
   *
   * **Explicit ids, not an `includeFx` flag** — unlike `RecordRequest`. Which effects belong in a
   * Look is a per-effect judgement ("the colour chase is the look; the tilt sine was me looking at
   * it"), and a boolean cannot say that.
   *
   * A ticked effect is **moved**: the server removes it from the programmer band, because the layer
   * this Look is applied through starts running it immediately and two copies would beat against
   * each other. An unticked effect **keeps running** — leaving one out of a Look is not the same as
   * stopping it, which is exactly why these are checkboxes.
   *
   * Timing does not travel: `LookEffect` has no delay/interval fields, so a busked "fire after 3s"
   * becomes the *layer's* delay rather than something baked into the Look.
   */
  effectIds?: number[]
}

export interface RecordLookResponse {
  look: LookDetails
  created: boolean
  rowsWritten: number
  rowsRemoved: number
  groupRowsEmitted: number
  /** Programmer-band effects folded in, and so removed from the band. */
  effectsWritten?: number
  skipped: ProgrammerSkip[]
  /** Set when the Look was already live: what the re-resolve moved. */
  programmerKeysRefreshed: number
  cuesRepublished: number[]
}

export interface IncludeRequest {
  projectId: number
  /** Exactly one of `cueId` / `lookId`. The backend 400s on both or neither. */
  cueId?: number
  lookId?: number
  mask?: PropertyMaskGroup[]
  includeFx?: boolean
  fadeMs?: number
}

export interface IncludeResponse {
  /**
   * A `PALETTE` arm stood here on the grounds that the backend one still existed. It does not:
   * `ProgrammerIncludeResponse` is constructed at exactly two sites, one per kind, and the
   * `IncludedTarget.Kind` enum behind them has only `CUE` and `LOOK`.
   */
  kind: 'CUE' | 'LOOK'
  /** Null unless a cue was included. */
  cueId?: number
  cueStackId?: number
  lookId?: number
  /**
   * The cue's *or* the Look's name — `name` rather than `cueName` because it is now either, and a
   * field called `cueName` holding a Look name is a lie. Named the same on the wire.
   */
  name: string
  entriesWritten: number
  /** MagicQ's "Select Heads on Include" — the sheet selects these. */
  fixtureKeys: string[]
  groupKeys: string[]
  fxSpawned: number
  fxAlreadyRunning: number
  fxTimedSkipped: number
  lastIncluded?: IncludedTarget | null
  skipped: ProgrammerSkip[]
  warnings: string[]
}

/** One (fixture, property) the programmer is currently overriding. */
export interface ChecklistKey {
  targetKey: string
  propertyName: string
  currentValue: string
  cueValue?: string
  /** The cue drives this through an effect, so a written assignment would be masked on GO. */
  viaEffect: boolean
}

export interface ChecklistCue {
  cueId: number
  cueNumber?: string
  /** True when `cueNumber` was derived from position rather than typed — rendered dimmed. */
  cueNumberAuto: boolean
  cueName: string
  isActive: boolean
  keyCount: number
  viaEffectKeyCount: number
  sample: ChecklistKey[]
}

export interface ChecklistStack {
  cueStackId?: number
  cueStackName?: string
  isActive: boolean
  cues: ChecklistCue[]
}

export interface UpdateChecklist {
  stacks: ChecklistStack[]
  /** Keys with no cue underneath — programmer over baseline. Offer "record a new cue". */
  unattributed: ChecklistKey[]
  totalKeys: number
}

export interface UpdateRequest {
  projectId: number
  /** Cue ids to write (Mode B). Omit for Mode A, or for the checklist when nothing is included. */
  targets?: number[]
  mask?: PropertyMaskGroup[]
  /** Fetch the checklist without writing, even when an include target exists. */
  preview?: boolean
  includeFx?: boolean
}

export interface UpdateResult {
  cueId: number
  cueStackId?: number
  cueName: string
  assignmentsWritten: number
  fxWritten: number
  republishedLive: boolean
}

/**
 * Mode A written back into a **Look** rather than a cue.
 *
 * A separate field from `results` rather than a nullable `cueId` on `UpdateResult`, matching the
 * backend: everything that already reads `results` for cue counts keeps working untouched.
 */
export interface LookUpdateResult {
  lookId: number
  lookName: string
  rowsWritten: number
  /** What the re-resolve moved: the live consumers of the Look. */
  programmerKeysRefreshed: number
  cuesRepublished: number[]
}

export interface UpdateResponse {
  applied: boolean
  mode: 'A' | 'B' | 'CHECKLIST'
  results: UpdateResult[]
  /** Set when Mode A's include target was a Look. Mode B is cue-only, by design. */
  lookResult?: LookUpdateResult
  checklist?: UpdateChecklist
  skipped: ProgrammerSkip[]
  warnings: string[]
}

// `POST /programmer/make-hard` and its request/response pair stood here. The route replaced the
// programmer's `ref:` slots with the literals they resolved to; the grammar retired in session 4, so
// there is no longer such a slot to harden. Detaching a *cue* from the library is
// `POST /{projectId}/cues/{cueId}/flatten`.

/**
 * Update's 409 body: the cue or Look Include staged has been deleted since.
 *
 * A one-arm union rather than a bare string, because it is the *narrowed* remains of three. There
 * was `INCLUDE_TARGET_READ_ONLY`, for a Look target Update could not write back to — both halves
 * of that guard went when the write-back path stopped leading into the retired palette tables, and
 * it was never handled here, only declared. And there was `CUE_EDIT_SESSION_OPEN`, the one
 * recoverable arm, which both Record and Update offered a "do it anyway" for: backend sweep item
 * D1 retired the `cueEdit.*` sessions, so nothing can hold one and no request can be refused for
 * it. Record now has no conflict path at all.
 */
export interface ProgrammerConflict {
  error: string
  code: 'INCLUDE_TARGET_GONE'
  cueId?: number
}

// ── Spread ──────────────────────────────────────────────────────────────────

/** Titan's four fan shapes, as `fx/SpreadPlan.kt` names them. */
export type SpreadCurve = 'LINE' | 'MIRROR' | 'ARROW' | 'WINGS'

/**
 * A `DistributionStrategy` name the desk resolves through `byName`: `LINEAR` is rig order
 * (`state/BuskRigOrder.kt`), `REVERSE` the other way, `CENTER_OUT` folds the two ends together,
 * `RANDOM` is a permutation seeded from the request. The strategy has more names (`EDGES_IN`,
 * `PING_PONG`, `SPLIT`, `POSITIONAL`, `UNIFIED`); the Spread tab offers these four, and
 * `lib/spreadIntent.ts` says which of the design's labels has no desk order yet.
 */
export type SpreadOrder = 'LINEAR' | 'REVERSE' | 'CENTER_OUT' | 'RANDOM'

/** Whether a multi-head fixture is one step or each of its cells is. */
export type SpreadOver = 'HEADS' | 'CELLS'

/**
 * `POST /projects/{id}/programmer/spread` — a mirror of lighting7's `SpreadRequest`
 * (`routes/programmerSpread.kt`). `from` and `to` are serialised `TemplateIntent`s of the
 * property's shape, or `tmpl:{uuid}` for a colour; the desk interpolates in the intent's own space
 * and resolves one literal per head, so nothing here is a value (busk-further plan D9).
 */
export interface SpreadRequest {
  projectId: number
  targets: CueTarget[]
  /** The selection's attribute mask; absent is every attribute. */
  families?: PropertyMaskGroup[]
  /** A `TemplateProperty` name — `rgbColour`, `dimmer`, `position`, `white` … */
  property: string
  from: string
  to: string
  curve?: SpreadCurve
  order?: SpreadOrder
  parts?: number
  over?: SpreadOver
  fadeMs?: number
  /** For `order = RANDOM`. */
  seed?: number
  /**
   * `false` resolves and answers without writing — the programmer's focused-Look-layer arm, which
   * lands `written[]` in the layer's draft (editor-kit plan D6). **Present only when false**: the
   * desk's REST Json refuses an unknown key, so a desk mid-upgrade would 400 every Local spread
   * that carried `write: true` (plan §6). `SpreadPanel.spreadRequestOf` is the one builder.
   */
  write?: boolean
}

/**
 * One head's literal: the head, the property it actually landed on, and the **value it got** in
 * the Look row grammar (`"0".."255"`, `"#rrggbb;w128"`, `"pan,tilt"`) — `parseProgrammerValue`
 * reads it. It carried the interpolated intent until the editor kit's session 3; a desk that still
 * answers one is told apart by parsing, and the Look-layer arm refuses rather than landing it.
 */
export interface SpreadWrite {
  target: CueTarget
  propertyName: string
  value: string
}

export interface SpreadSkip {
  target: CueTarget
  reason: string
}

/**
 * The desk's answer: what it wrote, in the order it used (rig order), and what it could not.
 * Every list is optional on this side because the server omits a defaulted empty collection
 * (`encodeDefaults = false`), the `TemplateSummary.rows` rule.
 */
export interface SpreadResponse {
  written?: SpreadWrite[]
  skipped?: SpreadSkip[]
  /** The property's family, when the mask kept it out — then nothing was written. */
  skippedFamilies?: string[]
}

export const programmerOpsApi = restApi.injectEndpoints({
  endpoints: (build) => ({
    recordProgrammer: build.mutation<RecordResponse, RecordRequest>({
      query: ({ projectId, ...body }) => ({
        url: 'programmer/record',
        method: 'POST',
        body: { ...body, projectId: String(projectId) },
      }),
      // Guarded on the result: nothing was written on a failure, so refetching the whole cue
      // list behind the sheet would be pure churn.
      invalidatesTags: (result, _error, { projectId, cueId }) =>
        result == null
          ? []
          : [
              { type: 'CueList', id: projectId },
              ...(cueId ? [{ type: 'Cue' as const, id: cueId }] : []),
              'CueList',
              // Only a CREATE changes stack membership.
              ...(result.created ? (['CueStackList'] as const) : []),
            ],
    }),

    /**
     * `POST /programmer/record-look` — the gesture that creates a **bound** Look, which nothing
     * could do while the only record destination was the retired palette tables.
     *
     * Invalidating the cue tags is not defensive: writing a Look's contents ends in a republish, so
     * re-recording one that cues already layer moves them immediately, and their read is stale.
     */
    recordLook: build.mutation<RecordLookResponse, RecordLookRequest>({
      query: ({ projectId, ...body }) => ({
        url: 'programmer/record-look',
        method: 'POST',
        body: { ...body, projectId: String(projectId) },
      }),
      invalidatesTags: (result) =>
        result == null
          ? []
          : [
              'LookList',
              { type: 'Look' as const, id: result.look.id },
              // A Look's rows resolve per fixture, so the fixture and group reads carry them.
              'Fixture',
              'GroupList',
              ...(result.cuesRepublished.length > 0
                ? ([
                    'CueList' as const,
                    ...result.cuesRepublished.map((id) => ({ type: 'Cue' as const, id })),
                  ])
                : []),
            ],
    }),

    /**
     * Named `includeIntoProgrammer`, not `includeCue`: the same route also loads a Look's bound
     * rows, and `ProgrammerStore.lastIncludedTarget` is single-valued so the two could not have
     * been separate endpoints anyway.
     */
    includeIntoProgrammer: build.mutation<IncludeResponse, IncludeRequest>({
      query: ({ projectId, ...body }) => ({
        url: 'programmer/include',
        method: 'POST',
        body: { ...body, projectId: String(projectId) },
      }),
      // Include spawns programmer-band FX, which the FX sheet and the Clear button count.
      invalidatesTags: ['FixtureEffects'],
    }),

    updateProgrammer: build.mutation<UpdateResponse, UpdateRequest>({
      query: ({ projectId, ...body }) => ({
        url: 'programmer/update',
        method: 'POST',
        body: { ...body, projectId: String(projectId) },
      }),
      // A checklist fetch writes nothing, so it must not invalidate — the dialog opens with
      // one, and a refetch storm behind an open dialog is pure churn.
      invalidatesTags: (result, _error, { projectId }) =>
        result?.applied
          ? [
              { type: 'CueList', id: projectId },
              'CueList',
            ]
          : [],
    }),

    /**
     * `POST /projects/{id}/programmer/spread` — spread, resolved on the desk (busk-further plan D9).
     *
     * A REST mutation for this file's reason: the tab reads the **structured reply** — the desk's
     * `skippedFamilies`, toasted in the press's vocabulary (a preview strip read `written[]` too,
     * until 2026-09-21) — and the programmer WS channel has no reply to read.
     * It invalidates nothing — a write lands as an ordinary Local entry and rides
     * `programmer.entryChanged`, which is how the rig tiles and the grid already learn of it; and
     * a `write: false` request lands nothing at all — its `written[]` is the programmer's to put
     * in the focused Look layer's draft (editor-kit plan D6).
     *
     * **Not** in `SILENT_ENDPOINTS`: the two 400s (`SPREAD_INVALID`, `SPREAD_NEEDS_SELECTION`) are
     * toasted by `errorToastMiddleware` under the endpoint's own id, so a Live gesture that keeps
     * failing replaces one toast rather than stacking twenty. The tab pre-empts the second with its
     * own sentence and never sends under an empty selection.
     */
    spread: build.mutation<SpreadResponse, SpreadRequest>({
      query: ({ projectId, ...body }) => ({
        url: `projects/${projectId}/programmer/spread`,
        method: 'POST',
        body,
      }),
    }),
  }),
  overrideExisting: false,
})

export const {
  useRecordProgrammerMutation,
  useRecordLookMutation,
  useIncludeIntoProgrammerMutation,
  useUpdateProgrammerMutation,
  useSpreadMutation,
} = programmerOpsApi
