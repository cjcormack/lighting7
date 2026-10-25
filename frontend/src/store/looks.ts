import { restApi } from './restApi'
import { lightingApi } from '../api/lightingApi'
import { store } from './index'
import type { AttributeFamily } from '@/lib/attributeFamily'
import type {
  ApplyLookRequest,
  ApplyLookResponse,
  CopyLookRequest,
  CopyLookResponse,
  LookDetails,
  LookInput,
  LookSummary,
  ToggleLookRequest,
  ToggleLookResponse,
} from '../api/looksApi'

/**
 * Bridge `lookListChanged` into cache invalidation.
 *
 * Called from `main.tsx`, **not** on import, and that is load-bearing rather than stylistic: this
 * slice is imported from the earliest render path (the nav registry, and pickers that mount all
 * over), so touching `lightingApi` in the module body would be a runtime import cycle. It throws a
 * TDZ `ReferenceError` that takes every export with it, and `tsc`, `vite build` and the unit tests
 * (which mock the module) all pass anyway — it shows up solely as a broken app in the browser. See
 * `startOAuthIdentityBridge` for the same trap.
 */
export function startLooksBridge() {
  lightingApi.looks.subscribe(function () {
    // `Cue` because creating, copying or deleting a Look changes what every cue
    // layering it composes to, and `projectCueCooked` — the read behind the read-only cue grid —
    // is tagged `Cue`. Affordable because `lookListChanged` is CRUD-only — a contents edit does not
    // fire it (see `looksWsApi`) — and load-bearing on the delete
    // path, which fires only this signal unless it took cue layers (then `cuesRecomposed` too).
    // A Look *retune* is the other half and does not come through here: it rides the keyed
    // `cuesRecomposed` frame instead.
    //
    // `CueList` alongside it, the pairing every cue-affecting write in this repo uses: a cue's
    // entry in that list carries `layers[].source.name`, so a Look renamed elsewhere leaves the
    // old name in the cached list otherwise.
    store.dispatch(
      restApi.util.invalidateTags(['Look', 'LookList', 'Cue', 'CueList']),
    )
  })
}

/**
 * Three endpoints were deleted here in session 3, each because its only caller went:
 *
 * - **`createLook`** — a Look is *recorded* now (D9), never hand-authored, so the create form that
 *   called this does not exist. `POST /looks` stays server-side; nothing on the desk sends it.
 * - **`previewLook` / `clearLookPreview`** — they drove `LookLivePreview`, the Look editor's rig
 *   preview, which went with the editor. Backend sweep item D4 then deleted the two routes and
 *   `ProgrammerLayerStack.installPreview` as well, so live preview no longer exists on either side.
 *   A template editor that wants one would be building it, not re-adopting it.
 */
export const looksApi = restApi.injectEndpoints({
  endpoints: (build) => ({
    /**
     * The library's list.
     *
     * `family` is served by filtering *derived* families server-side (there is no column to query),
     * so an unknown value is a 400. The library itself asks for the unfiltered list and filters in
     * the browser, because a Look can sit in several families at once and "All" needs them all
     * anyway; the parameter is here for callers that genuinely want one bank.
     */
    lookList: build.query<LookSummary[], { projectId: number; family?: AttributeFamily }>({
      query: ({ projectId, family }) =>
        family
          ? `projects/${projectId}/looks?family=${family}`
          : `projects/${projectId}/looks`,
      providesTags: (_result, _error, { projectId }) => [
        { type: 'LookList', id: projectId },
        'LookList',
      ],
      // No `upsertQueryData` fan-out into the detail cache, unlike the preset list this replaces:
      // that list returned whole presets, so seeding the detail query from it was free. A
      // `LookSummary` is deliberately *not* a `LookDetails` — it carries derived counts and a
      // preview instead of rows and effects (see the DTO comment for why they are separate types)
      // — so there is nothing here to seed a detail entry with.
    }),

    look: build.query<LookDetails, { projectId: number; lookId: number }>({
      query: ({ projectId, lookId }) => `projects/${projectId}/looks/${lookId}`,
      providesTags: (_result, _error, { lookId }) => [{ type: 'Look', id: lookId }],
    }),

    /**
     * PUT a Look.
     *
     * Send only the keys you mean to write: the backend receives a raw JSON object so **absent and
     * empty are different** — omitting `rows` leaves the contents alone, sending `[]` clears them.
     * That is what makes a rename cheap and, more importantly, what stops a metadata edit from
     * wiping the rows every live consumer is resolving through.
     */
    saveLook: build.mutation<LookDetails, { projectId: number; lookId: number } & LookInput>({
      query: ({ projectId, lookId, ...body }) => ({
        url: `projects/${projectId}/looks/${lookId}`,
        method: 'PUT',
        body,
      }),
      // A rename can collide (409) and a bad row can 400; nothing moved when either happens.
      // Cues are invalidated because a contents edit republishes every cue layering this Look.
      // Never the fixture list: `LookRowStore` saves every 400 ms through a layer-scope drag, and
      // nothing on a fixture or group summary depends on a Look.
      invalidatesTags: (result, _error, { lookId }) =>
        result == null
          ? []
          : [
              { type: 'Look' as const, id: lookId },
              'LookList' as const,
              'Cue' as const,
              'CueList' as const,
            ],
    }),

    deleteLook: build.mutation<void, { projectId: number; lookId: number; force?: boolean }>({
      query: ({ projectId, lookId, force }) => ({
        url: `projects/${projectId}/looks/${lookId}${force ? '?force=true' : ''}`,
        method: 'DELETE',
      }),
      // Guarded on the *error*, not the result: a 204 carries no body. A LOOK_IN_USE 409 is an
      // ordinary step in the flow (it opens "delete anyway"), and nothing was deleted, so
      // refetching the library would only churn. A forced delete does remove cue layers, hence
      // the cue tags.
      invalidatesTags: (_result, error, { lookId }) =>
        error != null
          ? []
          : [
              'LookList',
              { type: 'Look', id: lookId },
              'CueList',
              'Cue',
            ],
    }),

    copyLook: build.mutation<
      CopyLookResponse,
      { projectId: number; lookId: number } & CopyLookRequest
    >({
      query: ({ projectId, lookId, ...body }) => ({
        url: `projects/${projectId}/looks/${lookId}/copy`,
        method: 'POST',
        body,
      }),
      // The copy lands in the *target* project's library, which may not be the one on screen — so
      // the list tag is scoped to that project.
      invalidatesTags: (result) => (result == null ? [] : [{ type: 'LookList', id: result.targetProjectId }]),
    }),

    /**
     * Move running programmer-band effects into a Look — what `+ Effect` does with a layer focused.
     *
     * MERGE on the server: the Look keeps the effects it has and gains these, because the operator
     * asked to add one. Invalidates the effect tags as well as the Look, since the instances leave
     * the programmer band and come back through the layer.
     */
    absorbLookFx: build.mutation<
      { look: LookDetails; absorbed: number },
      { projectId: number; lookId: number; fxIds: number[] }
    >({
      query: ({ projectId, lookId, fxIds }) => ({
        url: `projects/${projectId}/looks/${lookId}/absorb-fx`,
        method: 'POST',
        body: { fxIds },
      }),
      invalidatesTags: (_result, _error, { lookId }) => [
        { type: 'Look' as const, id: lookId },
        'LookList' as const,
        'FixtureFx' as const,
      ],
    }),

    /**
     * Put a Look on, or take it off again — `usePress`'s path. It names no targets: a Look
     * lands whole, and its effects reach the FX lists through `fxChanged`.
     */
    toggleLook: build.mutation<
      ToggleLookResponse,
      { projectId: number; lookId: number } & ToggleLookRequest
    >({
      query: ({ projectId, lookId, ...body }) => ({
        url: `projects/${projectId}/looks/${lookId}/toggle`,
        method: 'POST',
        body,
      }),
    }),

    /**
     * *Apply as values* for a Look — ⌥click and the hold menu's second item (apply-model plan
     * D8). Lands Local values and Local effects; like the toggle it names no targets to invalidate.
     */
    applyLook: build.mutation<ApplyLookResponse, { projectId: number; lookId: number } & ApplyLookRequest>({
      query: ({ projectId, lookId, ...body }) => ({
        url: `projects/${projectId}/looks/${lookId}/apply`,
        method: 'POST',
        body,
      }),
    }),
  }),
  overrideExisting: false,
})

export const {
  useLookListQuery,
  useLookQuery,
  useSaveLookMutation,
  useDeleteLookMutation,
  useCopyLookMutation,
  useAbsorbLookFxMutation,
  useToggleLookMutation,
  useApplyLookMutation,
} = looksApi
