import { restApi } from './restApi'
import { lightingApi } from '../api/lightingApi'
import { store } from './index'
import type {
  Cue,
  CueInput,
  CuePatchInput,
  ApplyCueResponse,
  StopCueResponse,
  CueCookedResponse,
  DeleteCuesRequest,
  DeleteCuesResponse,
  DuplicateCueResponse,
} from '../api/cuesApi'

// Subscribe to WebSocket cue list changes - invalidate all cue caches
lightingApi.cues.subscribe(function () {
  store.dispatch(restApi.util.invalidateTags(['CueList']))
})

// A Look or template contents edit elsewhere changed what these cues compose to. Keyed, so this
// re-reads the named cues rather than dropping every cue cache — see `projectCueCooked` below for
// what this closes, and `cuesWsApi` for why the CRUD signals cannot carry it.
lightingApi.cues.subscribeRecomposed(function (cueIds) {
  if (cueIds.length === 0) return
  store.dispatch(
    restApi.util.invalidateTags(cueIds.map((id) => ({ type: 'Cue' as const, id }))),
  )
})

export const cuesApi = restApi.injectEndpoints({
  endpoints: (build) => ({
    projectCueList: build.query<Cue[], number>({
      query: (projectId) => `projects/${projectId}/cues`,
      providesTags: (_result, _error, projectId) => [
        { type: 'CueList', id: projectId },
        'CueList',
      ],
    }),

    /** `entryId` names the context entry — whose number, follow and stack the answer describes. */
    projectCue: build.query<Cue, { projectId: number; cueId: number; entryId?: number | null }>({
      query: ({ projectId, cueId, entryId }) =>
        entryId == null
          ? `projects/${projectId}/cues/${cueId}`
          : { url: `projects/${projectId}/cues/${cueId}`, params: { entryId } },
      providesTags: (_result, _error, { cueId }) => [{ type: 'Cue', id: cueId }],
    }),

    /**
     * What a cue actually asserts, per (target, property), and which layer won each value.
     *
     * The read behind the cue surface: since session 2a a cue is drawn as the *same* value grid the
     * programmer uses, drawn read-only, so there is no second way to express a cue's values and
     * nothing to keep in step.
     *
     * Server-side, and that is the point — composing this here would mean reimplementing layer
     * order, masks, per-layer amount and blend, group expansion and specificity in the browser,
     * and every one of those is a place for the desk and the display to disagree.
     *
     * Tagged `Cue`, and it is worth being exact about what actually invalidates that tag, because
     * the answer is narrower than it looks:
     *
     * - **This client's own writes** — a PATCH or PUT to the cue, `recordProgrammer`, and
     *   `recordLook` (which invalidates each id in the response's `cuesRepublished`).
     * - **Another client's Look or template CRUD** — `lookListChanged` / `paletteListChanged`
     *   carry `Cue` through the two bridges in `looks.ts` / `templates.ts`. Load-bearing on the
     *   delete path in particular: deleting a Look changes what every cue layering it composes to,
     *   and a delete that took no cue layers fires only the list signal. One that took some also
     *   republishes and fires `cuesRecomposed`, `cueListChanged` and `cueStackListChanged`.
     * - **Another client's Look or template *retune*** — the `cuesRecomposed` frame, which names
     *   the affected cue ids. The CRUD signals deliberately don't fire for a contents edit (they
     *   drop every cached expansion, and a colour retune would be a storm), so this is its own
     *   keyed frame; the bridge is at the top of this file.
     * - **Reconnect** — `Cue` is in `RECONNECT_RESYNC_TAGS`, and in the first wave.
     *
     * That is the whole set, and it is deliberately exhaustive: before the `cuesRecomposed` frame
     * existed, a second tab with this cue expanded held pre-edit composed values indefinitely on a
     * perfectly healthy socket, because a retune moves the rig without touching any signal this
     * read was subscribed to.
     *
     * Effects are **not** here: an effect has no static value to report, so a cue whose look is
     * carried by a chase reads as whatever its values say. They are listed separately.
     */
    projectCueCooked: build.query<CueCookedResponse, { projectId: number; cueId: number }>({
      query: ({ projectId, cueId }) => `projects/${projectId}/cues/${cueId}/cooked`,
      providesTags: (_result, _error, { cueId }) => [{ type: 'Cue', id: cueId }],
    }),

    createProjectCue: build.mutation<Cue, { projectId: number } & CueInput>({
      query: ({ projectId, ...body }) => ({
        url: `projects/${projectId}/cues`,
        method: 'POST',
        body,
      }),
      invalidatesTags: (_result, _error, { projectId }) => [
        { type: 'CueList', id: projectId },
        'CueList',
      ],
    }),

    // `saveProjectCue` (the whole-cue PUT) stood here. A cue's values became read-only in
    // session 2a — they are composed server-side from its layers — so every write left is a
    // field-level `patchProjectCue`, and nothing had sent a full-body PUT since.

    patchProjectCue: build.mutation<
      Cue,
      { projectId: number; cueId: number } & CuePatchInput
    >({
      query: ({ projectId, cueId, ...body }) => ({
        url: `projects/${projectId}/cues/${cueId}`,
        method: 'PATCH',
        body,
      }),
      // Don't invalidate the project-wide CueList on PATCH — that query carries every
      // cue's full children, and keystroke-driven edits would refetch the lot. The WS
      // `cues.subscribe` invalidates CueList for changes that affect list-level fields.
      //
      // CueStackList is different: it's the lightweight entries list that the Program
      // table and Prompt Book rail render name/cue#/fade from, so it has to reflect an
      // inline edit at once. Invalidating on our own response (rather than waiting for
      // the WS echo) also guarantees the refetch sees the committed write — the echo
      // races the commit and lands a refresh one event behind.
      invalidatesTags: (_result, _error, { projectId, cueId }) => [
        { type: 'Cue', id: cueId },
        { type: 'CueStackList', id: projectId },
      ],
    }),

    deleteProjectCue: build.mutation<void, { projectId: number; cueId: number }>({
      query: ({ projectId, cueId }) => ({
        url: `projects/${projectId}/cues/${cueId}`,
        method: 'DELETE',
      }),
      invalidatesTags: (_result, _error, { projectId }) => [
        { type: 'CueList', id: projectId },
        'CueList',
      ],
    }),

    /**
     * Everything that names these cues, from a dry run of `POST cues/delete` — what a delete's
     * confirm lists. A POST, but it deletes nothing, so it is read as a query.
     */
    cueDeletePreview: build.query<DeleteCuesResponse, { projectId: number; cueIds: number[] }>({
      query: ({ projectId, cueIds }) => ({
        url: `projects/${projectId}/cues/delete`,
        method: 'POST',
        body: { cueIds, dryRun: true } satisfies DeleteCuesRequest,
      }),
      keepUnusedDataFor: 0,
    }),

    /**
     * Delete cues after the confirm has listed every use (lighting7 cue model plan D8): `force`, so
     * the entries, pads, slots and anchors go with them. A `FireCue` binding is left reporting its
     * cue missing (`FU-CUE-BINDING-SWEEP`).
     */
    deleteCues: build.mutation<DeleteCuesResponse, { projectId: number; cueIds: number[] }>({
      query: ({ projectId, cueIds }) => ({
        url: `projects/${projectId}/cues/delete`,
        method: 'POST',
        body: { cueIds, force: true } satisfies DeleteCuesRequest,
      }),
      invalidatesTags: (_result, _error, { projectId, cueIds }) => [
        { type: 'CueList', id: projectId },
        'CueList',
        { type: 'CueStackList', id: projectId },
        ...cueIds.map((id) => ({ type: 'Cue' as const, id })),
        'PromptBook',
      ],
    }),

    /**
     * *Duplicate*: a full copy of the cue — its rows, layers, effects, hooks, scenery and events —
     * appended to the end of `stackId`'s list, or loose without one (show-cue-editing plan D12).
     */
    duplicateCue: build.mutation<DuplicateCueResponse, { projectId: number; cueId: number; stackId: number | null }>({
      query: ({ projectId, cueId, stackId }) => ({
        url: `projects/${projectId}/cues/${cueId}/duplicate`,
        method: 'POST',
        body: stackId == null ? {} : { stackId },
      }),
      invalidatesTags: (_result, _error, { projectId }) => [
        { type: 'CueList', id: projectId },
        'CueList',
        { type: 'CueStackList', id: projectId },
      ],
    }),

    // `copyCue` stood here, fired by a `CopyCueDialog` that was deleted before this sweep;
    // copying a cue between projects is `cloneProject` or an export/import today. The route
    // remains server-side.

    applyCue: build.mutation<
      ApplyCueResponse,
      { projectId: number; cueId: number; replaceAll?: boolean }
    >({
      query: ({ projectId, cueId, replaceAll }) => ({
        url: `projects/${projectId}/cues/${cueId}/apply${replaceAll ? '?replaceAll=true' : ''}`,
        method: 'POST',
      }),
      invalidatesTags: () => [
        'FixtureEffects',
        'GroupActiveEffects',
      ],
    }),

    stopCue: build.mutation<
      StopCueResponse,
      { projectId: number; cueId: number }
    >({
      query: ({ projectId, cueId }) => ({
        url: `projects/${projectId}/cues/${cueId}/stop`,
        method: 'POST',
      }),
      invalidatesTags: () => [
        'FixtureEffects',
        'GroupActiveEffects',
      ],
    }),

    // `snapshotCueFromLive` lived here until Session 3. Capturing the stage is now
    // `recordProgrammer({ source: 'STAGE_SNAPSHOT' })` in `programmerOps.ts` — the same
    // capture, plus the programmer overlay it used to miss, as one Record source among
    // several rather than an endpoint of its own.
  }),
  overrideExisting: false,
})

export const {
  useProjectCueListQuery,
  useProjectCueQuery,
  useProjectCueCookedQuery,
  useLazyProjectCueQuery,
  useCreateProjectCueMutation,
  usePatchProjectCueMutation,
  useDeleteProjectCueMutation,
  useCueDeletePreviewQuery,
  useDeleteCuesMutation,
  useDuplicateCueMutation,
  useApplyCueMutation,
  useStopCueMutation,
} = cuesApi
