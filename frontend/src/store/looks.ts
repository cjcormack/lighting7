import { restApi } from './restApi'
import { lightingApi } from '../api/lightingApi'
import { store } from './index'
import type { AttributeFamily } from '@/lib/attributeFamily'
import type {
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
    // `Fixture` and `GroupList` for the same reason the CRUD mutations carry them:
    // `compatibleLookIds` rides on the fixture and group summaries, so a Look created, copied or
    // deleted on **another** client would otherwise exist here and be offered nowhere —
    // `LookTogglePicker` omits it and `LayerPicker` disables every head for it. Affordable
    // precisely because `lookListChanged` is CRUD-only: a contents edit does not fire it (see
    // `looksWsApi`), so this is not on the per-resolution path.
    //
    // `Cue` for a related reason: creating, copying or deleting a Look changes what every cue
    // layering it composes to, and `projectCueCooked` — the read behind the read-only cue grid —
    // is tagged `Cue`. Affordable on the same CRUD-cadence grounds, and load-bearing on the delete
    // path, which fires this signal without republishing. A Look *retune* is the other half and
    // does not come through here: it rides the keyed `cuesRecomposed` frame instead.
    //
    // `CueList` alongside it, the pairing every cue-affecting write in this repo uses: a cue's
    // entry in that list carries `layers[].source.name`, so a Look renamed elsewhere leaves the
    // old name in the cached list otherwise.
    store.dispatch(
      restApi.util.invalidateTags(['Look', 'LookList', 'Fixture', 'GroupList', 'Cue', 'CueList']),
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
      //
      // `Fixture`/`GroupList` ride on **`effects` alone**, and that conditional is the point.
      // `compatibleLookIds` is derived server-side by `compatibleIdsFor`, which filters on a Look's
      // effect categories and nothing else — the `editorFixtureType` type gate that used to sit
      // beside it went with the column in session 3, so the comment that once justified these two
      // tags for *any* edit was describing a filter that no longer exists. Rows and metadata
      // provably cannot move compatibility; only adding (or clearing) an effect of a family can.
      //
      // Unconditional was expensive in the one place it fires most: `LookRowStore` saves rows-only
      // bodies every 400 ms through a layer-scope drag, and `Fixture` is the fixture list — 48
      // consumers here, `loadLookCompatibilityInfos` + `detectCapabilities` per fixture server-side
      // — so every drag tick refetched it and handed every consumer a new array identity mid-drag.
      invalidatesTags: (result, _error, { lookId, effects }) =>
        result == null
          ? []
          : [
              { type: 'Look' as const, id: lookId },
              'LookList' as const,
              'Cue' as const,
              'CueList' as const,
              ...(effects === undefined
                ? []
                : (['Fixture', 'GroupList'] as const)),
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
              // Same reason as create: the deleted Look has to leave every `compatibleLookIds`.
              'Fixture',
              'GroupList',
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
      // the list tag is scoped to that project. `Fixture`/`GroupList` are **not** conditional on
      // the target, though, and comparing it against the *source* project would be the wrong test:
      // those two lists belong to the **active** project (`fixtures` and `groups` take no
      // project), which this mutation cannot see. Copying from another project's library *into* the
      // active one — the main "Copy to Project" flow — is exactly the case a source==target check
      // skips, and it is the case that most needs the refresh: the new Look would exist and be
      // offered nowhere. Always invalidating costs one refetch of two lists, as a create does.
      invalidatesTags: (result) =>
        result == null
          ? []
          : [{ type: 'LookList', id: result.targetProjectId }, 'Fixture', 'GroupList'],
    }),

    /**
     * Move running programmer-band effects into a Look — what `+ Effect` does with a layer focused.
     *
     * MERGE on the server: the Look keeps the effects it has and gains these, because the operator
     * asked to add one. Invalidates the effect tags as well as the Look, since the instances leave
     * the programmer band and come back through the layer.
     */
    absorbLookEffects: build.mutation<
      { look: LookDetails; absorbed: number },
      { projectId: number; lookId: number; effectIds: number[] }
    >({
      query: ({ projectId, lookId, effectIds }) => ({
        url: `projects/${projectId}/looks/${lookId}/absorb-effects`,
        method: 'POST',
        body: { effectIds },
      }),
      // Absorbing is an effect write, so it moves `compatibleLookIds` the same way
      // `saveLook`'s `effects` arm does — hence `Fixture`/`GroupList` here too.
      invalidatesTags: (_result, _error, { lookId }) => [
        { type: 'Look' as const, id: lookId },
        'LookList' as const,
        'FixtureEffects' as const,
        'Fixture' as const,
        'GroupList' as const,
      ],
    }),

    /**
     * Put a Look on these targets, or take it off again — the busking pad's path.
     *
     * Only the Look's **deferred** rows and effects are applied: the pad supplies the targets, so a
     * bound row would land on fixtures the operator never selected.
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
      invalidatesTags: (_result, _error, { targets }) => {
        const tags: Array<
          | { type: 'FixtureEffects'; id: string }
          | { type: 'GroupActiveEffects'; id: string }
        > = []
        for (const t of targets) {
          if (t.type === 'fixture') {
            tags.push({ type: 'FixtureEffects', id: t.key })
          } else {
            tags.push({ type: 'GroupActiveEffects', id: t.key })
          }
        }
        return tags
      },
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
  useAbsorbLookEffectsMutation,
  useToggleLookMutation,
} = looksApi
