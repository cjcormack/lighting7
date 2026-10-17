import { restApi } from './restApi'
import { lightingApi } from '../api/lightingApi'
import { store } from './index'
import { applyServerRunState } from './runnerSlice'
import { isCueRow } from '../api/cueStacksApi'
import type {
  CueStack,
  CueStackInput,
  CueStackCueEntry,
  ReorderCuesRequest,
  MarkerEntryRequest,
  ReorderCueStacksRequest,
  ActivateCueStackRequest,
  AdvanceCueStackRequest,
  CueRunStateEvent,
  CueStackRunState,
  PreviewCueResponse,
  CueStackActivateResponse,
  CueStackDeactivateResponse,
  ProgramState,
  AdvanceProgramRequest,
  GoToStackRequest,
  ProgramActivateResponse,
  SortByCueNumberResponse,
  CueEntryPatch,
  DeleteCueStackResponse,
} from '../api/cueStacksApi'
export type { CueStack, CueStackCueEntry } from '../api/cueStacksApi'
export type { CueType, StackType } from '../api/cueStacksApi'

// Subscribe to WebSocket cue stack list changes - invalidate all cue stack caches
lightingApi.cueStacks.subscribe(function () {
  store.dispatch(restApi.util.invalidateTags(['CueStackList']))
})

export const cueStacksApi = restApi.injectEndpoints({
  endpoints: (build) => ({
    projectCueStackList: build.query<CueStack[], number>({
      query: (projectId) => `projects/${projectId}/cue-stacks`,
      providesTags: (_result, _error, projectId) => [
        { type: 'CueStackList', id: projectId },
        'CueStackList',
      ],
    }),

    // A single-stack read stood here. Every surface drills into `projectCueStackList` instead
    // — the list carries each stack's cues in full, so a second cache entry for one of them was
    // only ever a way for the two to disagree.

    // The project's playhead — which stack is currently live. The ordered stack list itself
    // comes from projectCueStackList; this is just the transport state.
    projectProgramState: build.query<ProgramState, number>({
      query: (projectId) => `projects/${projectId}/show`,
      providesTags: (_result, _error, projectId) => [{ type: 'ProgramState', id: projectId }],
    }),

    createProjectCueStack: build.mutation<CueStack, { projectId: number } & CueStackInput>({
      query: ({ projectId, ...body }) => ({
        url: `projects/${projectId}/cue-stacks`,
        method: 'POST',
        body,
      }),
      invalidatesTags: (_result, _error, { projectId }) => [
        { type: 'CueStackList', id: projectId },
        'CueStackList',
      ],
    }),

    saveProjectCueStack: build.mutation<
      CueStack,
      { projectId: number; stackId: number } & CueStackInput
    >({
      query: ({ projectId, stackId, ...body }) => ({
        url: `projects/${projectId}/cue-stacks/${stackId}`,
        method: 'PUT',
        body,
      }),
      invalidatesTags: (_result, _error, { projectId }) => [
        { type: 'CueStackList', id: projectId },
        'CueStackList',
      ],
    }),

    /**
     * Deleting a stack deletes its entries and never its cues: those it was the last place of are
     * left loose, unless `deleteLooseCues` deletes them too (everything naming them with them).
     */
    deleteProjectCueStack: build.mutation<
      DeleteCueStackResponse,
      { projectId: number; stackId: number; deleteLooseCues?: boolean }
    >({
      query: ({ projectId, stackId, deleteLooseCues }) => ({
        url: `projects/${projectId}/cue-stacks/${stackId}${deleteLooseCues ? '?deleteLooseCues=true' : ''}`,
        method: 'DELETE',
      }),
      invalidatesTags: (_result, _error, { projectId, deleteLooseCues }) => [
        { type: 'CueStackList', id: projectId },
        'CueStackList',
        { type: 'CueList', id: projectId },
        'CueList',
        'PromptBook',
        ...(deleteLooseCues ? (['BuskPage', 'CueSlotList'] as const) : []),
      ],
    }),

    // Reorder the project's stacks + separators (the show order).
    reorderCueStacks: build.mutation<
      void,
      { projectId: number } & ReorderCueStacksRequest
    >({
      query: ({ projectId, ...body }) => ({
        url: `projects/${projectId}/cue-stacks/reorder`,
        method: 'POST',
        body,
      }),
      // Optimistically rewrite sortOrder so the list doesn't snap back mid-drag.
      async onQueryStarted({ projectId, stackIds }, { dispatch, queryFulfilled }) {
        const patchResult = dispatch(
          cueStacksApi.util.updateQueryData('projectCueStackList', projectId, (draft) => {
            for (const stack of draft) {
              const newIndex = stackIds.indexOf(stack.id)
              if (newIndex !== -1) stack.sortOrder = newIndex
            }
            draft.sort((a, b) => a.sortOrder - b.sortOrder)
          }),
        )
        try {
          await queryFulfilled
        } catch {
          patchResult.undo()
        }
      },
      invalidatesTags: (_result, _error, { projectId }) => [
        { type: 'CueStackList', id: projectId },
        'CueStackList',
      ],
    }),

    reorderCueStackCues: build.mutation<
      CueStackCueEntry[],
      { projectId: number; stackId: number } & ReorderCuesRequest
    >({
      query: ({ projectId, stackId, ...body }) => ({
        url: `projects/${projectId}/cue-stacks/${stackId}/reorder`,
        method: 'POST',
        body,
      }),
      // Optimistically reorder the stack's cues so the dragged row stays where it was
      // dropped instead of springing back until the refetch lands.
      //
      // This has to patch `projectCueStackList` — that's the query the Program table renders
      // from (`StackDetail` reads `stack.cues`). An earlier version patched
      // `cuesApi.projectCueList`, which nothing in Program reads, so the drag had no optimism
      // at all. Mirrors `reorderCueStacks` above.
      async onQueryStarted({ projectId, stackId, entryIds }, { dispatch, queryFulfilled }) {
        const patchResult = dispatch(
          cueStacksApi.util.updateQueryData('projectCueStackList', projectId, (draft) => {
            const stack = draft.find((s) => s.id === stackId)
            if (!stack) return
            // Rows are entries, keyed by entry id — a marker has no cue id at all.
            const byId = new Map(stack.cues.map((cue) => [cue.entryId, cue]))
            const reordered = entryIds
              .map((id) => byId.get(id))
              .filter((cue): cue is CueStackCueEntry => cue != null)
            // Anything the request didn't name keeps its relative order at the end, matching
            // the server, which appends the entries a request leaves out.
            const named = new Set(entryIds)
            for (const cue of stack.cues) {
              if (!named.has(cue.entryId)) reordered.push(cue)
            }
            reordered.forEach((cue, index) => {
              cue.sortOrder = index
            })
            stack.cues = reordered
          }),
        )
        try {
          await queryFulfilled
        } catch {
          patchResult.undo()
        }
      },
      invalidatesTags: (_result, _error, { projectId }) => [
        { type: 'CueStackList', id: projectId },
        'CueStackList',
        { type: 'CueList', id: projectId },
        'CueList',
      ],
    }),

    /**
     * A marker entry — a labelled divider in a stack's list, not a cue (lighting7 cue model plan
     * D12). Created, renamed and removed on the stack, never through the cue routes.
     */
    createMarkerEntry: build.mutation<
      CueStackCueEntry,
      { projectId: number; stackId: number } & MarkerEntryRequest
    >({
      query: ({ projectId, stackId, ...body }) => ({
        url: `projects/${projectId}/cue-stacks/${stackId}/markers`,
        method: 'POST',
        body,
      }),
      invalidatesTags: (_result, _error, { projectId }) => [
        { type: 'CueStackList', id: projectId },
        'CueStackList',
      ],
    }),

    renameMarkerEntry: build.mutation<
      CueStackCueEntry,
      { projectId: number; stackId: number; entryId: number; label: string }
    >({
      query: ({ projectId, stackId, entryId, label }) => ({
        url: `projects/${projectId}/cue-stacks/${stackId}/markers/${entryId}`,
        method: 'PUT',
        body: { label },
      }),
      invalidatesTags: (_result, _error, { projectId }) => [
        { type: 'CueStackList', id: projectId },
        'CueStackList',
      ],
    }),

    deleteMarkerEntry: build.mutation<void, { projectId: number; stackId: number; entryId: number }>({
      query: ({ projectId, stackId, entryId }) => ({
        url: `projects/${projectId}/cue-stacks/${stackId}/markers/${entryId}`,
        method: 'DELETE',
      }),
      invalidatesTags: (_result, _error, { projectId }) => [
        { type: 'CueStackList', id: projectId },
        'CueStackList',
        { type: 'PromptBook', id: projectId },
      ],
    }),

    /**
     * *Add to stack…*: a new entry naming the cue, appended and auto-numbered. The cue's other
     * entries are untouched — it is shared, not moved.
     */
    addCueToStack: build.mutation<CueStack, { projectId: number; stackId: number; cueId: number }>({
      query: ({ projectId, stackId, cueId }) => ({
        url: `projects/${projectId}/cue-stacks/${stackId}/add-cue`,
        method: 'POST',
        body: { cueId },
      }),
      invalidatesTags: (_result, _error, { projectId, cueId }) => [
        { type: 'CueStackList', id: projectId },
        'CueStackList',
        { type: 'CueList', id: projectId },
        { type: 'Cue', id: cueId },
      ],
    }),

    /**
     * *Move to stack…*: the entry moves whole to the end of `toStackId` — its number, follow and
     * prompt-book anchor go with it — rather than being added there and removed here.
     */
    moveCueStackEntry: build.mutation<
      { stackId: number; entryId: number },
      { projectId: number; stackId: number; entryId: number; cueId: number; toStackId: number }
    >({
      query: ({ projectId, stackId, entryId, toStackId }) => ({
        url: `projects/${projectId}/cue-stacks/${stackId}/entries/${entryId}/move`,
        method: 'POST',
        body: { toStackId },
      }),
      invalidatesTags: (_result, _error, { projectId, cueId }) => [
        { type: 'CueStackList', id: projectId },
        'CueStackList',
        { type: 'CueList', id: projectId },
        { type: 'Cue', id: cueId },
        'PromptBook',
      ],
    }),

    /**
     * *Remove from stack*: deletes the entry, never the cue — a cue whose last entry this was is
     * left loose. The entry's anchor goes with it. `cueId` only keys the cue's caches.
     */
    removeCueStackEntry: build.mutation<
      void,
      { projectId: number; stackId: number; entryId: number; cueId: number }
    >({
      query: ({ projectId, stackId, entryId }) => ({
        url: `projects/${projectId}/cue-stacks/${stackId}/entries/${entryId}`,
        method: 'DELETE',
      }),
      async onQueryStarted({ projectId, stackId, entryId }, { dispatch, queryFulfilled }) {
        const patchResult = dispatch(
          cueStacksApi.util.updateQueryData('projectCueStackList', projectId, (draft) => {
            const stack = draft.find((s) => s.id === stackId)
            if (stack) stack.cues = stack.cues.filter((c) => c.entryId !== entryId)
          }),
        )
        try {
          await queryFulfilled
        } catch {
          patchResult.undo()
        }
      },
      invalidatesTags: (_result, _error, { projectId, cueId }) => [
        { type: 'CueStackList', id: projectId },
        'CueStackList',
        { type: 'CueList', id: projectId },
        { type: 'Cue', id: cueId },
        'PromptBook',
      ],
    }),

    /**
     * An entry's number and follow — the entry's, not the cue's, so a shared cue numbers each place
     * on its own. Invalidates what the cue PATCH did; `cueId` keys those and the save indicator.
     */
    patchCueStackEntry: build.mutation<
      CueStackCueEntry,
      { projectId: number; stackId: number; entryId: number; cueId: number } & CueEntryPatch
    >({
      query: ({ projectId, stackId, entryId, cueId: _cueId, ...body }) => ({
        url: `projects/${projectId}/cue-stacks/${stackId}/entries/${entryId}`,
        method: 'PATCH',
        body,
      }),
      invalidatesTags: (_result, _error, { projectId, cueId }) => [
        { type: 'Cue', id: cueId },
        { type: 'CueStackList', id: projectId },
      ],
    }),

    activateCueStack: build.mutation<
      CueStackActivateResponse,
      { projectId: number; stackId: number } & ActivateCueStackRequest
    >({
      query: ({ projectId, stackId, ...body }) => ({
        url: `projects/${projectId}/cue-stacks/${stackId}/activate`,
        method: 'POST',
        body,
      }),
      // Optimistic update: immediately set the live entry so the UI highlights correctly
      async onQueryStarted({ projectId, stackId, entryId }, { dispatch, queryFulfilled }) {
        const patchResult = dispatch(
          cueStacksApi.util.updateQueryData('projectCueStackList', projectId, (draft) => {
            const stack = draft.find((s) => s.id === stackId)
            if (stack) {
              const row = stack.cues.filter(isCueRow).find((c) => entryId == null || c.entryId === entryId)
              stack.activeEntryId = row?.entryId ?? null
              stack.activeCueId = row?.id ?? null
            }
          }),
        )
        try {
          const { data } = await queryFulfilled
          dispatch(
            cueStacksApi.util.updateQueryData('projectCueStackList', projectId, (draft) => {
              const stack = draft.find((s) => s.id === stackId)
              if (stack) {
                stack.activeEntryId = data.entryId
                stack.activeCueId = data.cueId
              }
            }),
          )
        } catch {
          patchResult.undo()
        }
      },
      invalidatesTags: () => [
        'FixtureEffects',
        'GroupActiveEffects',
      ],
    }),

    deactivateCueStack: build.mutation<
      CueStackDeactivateResponse,
      { projectId: number; stackId: number }
    >({
      query: ({ projectId, stackId }) => ({
        url: `projects/${projectId}/cue-stacks/${stackId}/deactivate`,
        method: 'POST',
      }),
      // Optimistic update: clear the live entry and its cue immediately
      async onQueryStarted({ projectId, stackId }, { dispatch, queryFulfilled }) {
        const patchResult = dispatch(
          cueStacksApi.util.updateQueryData('projectCueStackList', projectId, (draft) => {
            const stack = draft.find((s) => s.id === stackId)
            if (stack) {
              stack.activeCueId = null
              stack.activeEntryId = null
            }
          }),
        )
        try {
          await queryFulfilled
        } catch {
          patchResult.undo()
        }
      },
      invalidatesTags: () => [
        'FixtureEffects',
        'GroupActiveEffects',
      ],
    }),

    advanceCueStack: build.mutation<
      CueStackActivateResponse,
      { projectId: number; stackId: number } & AdvanceCueStackRequest
    >({
      query: ({ projectId, stackId, ...body }) => ({
        url: `projects/${projectId}/cue-stacks/${stackId}/advance`,
        method: 'POST',
        body,
      }),
      // Optimistic update: compute next cue locally for instant feedback
      async onQueryStarted({ projectId, stackId, direction }, { dispatch, queryFulfilled }) {
        const patchResult = dispatch(
          cueStacksApi.util.updateQueryData('projectCueStackList', projectId, (draft) => {
            const stack = draft.find((s) => s.id === stackId)
            if (!stack || stack.cues.length === 0) return
            // Markers are never gone to, so the walk is over the cue entries alone — by entry, since
            // a stack may name one cue twice.
            const cues = stack.cues.filter(isCueRow)
            if (cues.length === 0) return
            const currentIdx = cues.findIndex((c) => c.entryId === stack.activeEntryId)
            const delta = direction === 'FORWARD' ? 1 : -1
            let nextIdx = currentIdx + delta
            if (stack.loop) {
              nextIdx = ((nextIdx % cues.length) + cues.length) % cues.length
            } else {
              nextIdx = Math.max(0, Math.min(cues.length - 1, nextIdx))
            }
            const next = cues[nextIdx]
            if (next) {
              stack.activeCueId = next.id
              stack.activeEntryId = next.entryId
            }
          }),
        )
        try {
          const { data } = await queryFulfilled
          dispatch(
            cueStacksApi.util.updateQueryData('projectCueStackList', projectId, (draft) => {
              const stack = draft.find((s) => s.id === stackId)
              if (stack) {
                stack.activeCueId = data.cueId
                stack.activeEntryId = data.entryId
              }
            }),
          )
        } catch {
          patchResult.undo()
        }
      },
      invalidatesTags: () => [
        'FixtureEffects',
        'GroupActiveEffects',
      ],
    }),

    // `goToCueInStack` stood here, with the same optimistic cache patch `activateCueStack`
    // carries. Jumping to a cue *is* `activateCueStack({ entryId })` — one endpoint whether the
    // stack was already live or not — so the go-to POST had no caller.

    /**
     * Arm the next GO. No optimistic cache patch: the instant row highlight is the runner slice's
     * `setStandby` dispatch in `useShowTransport`, and the server's `cueRunStateChanged` frame is
     * what every session (this one included) ends up believing.
     */
    /** `entryId` names the stack entry to arm (lighting7 cue model plan P2); null disarms. */
    setCueStackStandby: build.mutation<
      CueStackRunState,
      { projectId: number; stackId: number; entryId: number | null }
    >({
      query: ({ projectId, stackId, entryId }) => ({
        url: `projects/${projectId}/cue-stacks/${stackId}/standby`,
        method: 'POST',
        body: { entryId },
      }),
    }),

    /**
     * What a cue *would* look like on stage, composed by the backend's own resolver. Layer 4
     * only — see `PreviewCueResponse`.
     *
     * A plain GET: the backend sweep's F4 moved this off POST since it only reads (see
     * `docs/api-conventions.md` §"POST-for-read" in the backend repo). Several stage surfaces can
     * be mounted at once (the Stage route's canvas and the globally-mounted overview panel), and
     * RTK Query collapses their identical args into one request; the subscribers all see the same
     * `isError`, so the View menu can say a preview failed rather than describing a look nobody is
     * being shown.
     *
     * `entryId` (the stack entry, cue model plan P2) is required rather than "null means the
     * effective next": under a query the arg *is* the cache key, and a key meaning "whatever the
     * server currently thinks" would serve one cue's look under another cue's identity. The caller
     * knows the id — the server broadcasts it.
     */
    previewCueLook: build.query<
      PreviewCueResponse,
      { projectId: number; stackId: number; entryId: number }
    >({
      query: ({ projectId, stackId, entryId }) => ({
        url: `projects/${projectId}/cue-stacks/${stackId}/preview`,
        params: { entryId },
      }),
    }),

    /**
     * [previewCueLook] for a cue with no entry — composed as a loose press would land it, above
     * every stack (show-cue-editing plan §3.7): the Stage view's *Cue in edit* for a loose cue, or
     * one in several places opened from none. Same answer, same three limits.
     */
    previewLooseCue: build.query<PreviewCueResponse, { projectId: number; cueId: number }>({
      query: ({ projectId, cueId }) => ({ url: `projects/${projectId}/cues/${cueId}/preview` }),
    }),

    sortCueStackByCueNumber: build.mutation<
      SortByCueNumberResponse,
      { projectId: number; stackId: number }
    >({
      query: ({ projectId, stackId }) => ({
        url: `projects/${projectId}/cue-stacks/${stackId}/sort-by-cue-number`,
        method: 'POST',
      }),
      invalidatesTags: (_result, _error, { projectId }) => [
        { type: 'CueStackList', id: projectId },
        'CueStackList',
        { type: 'CueList', id: projectId },
        'CueList',
      ],
    }),

    // ─── Program transport (project playhead over the ordered stacks) ─────────

    activateProgram: build.mutation<ProgramActivateResponse, { projectId: number }>({
      query: ({ projectId }) => ({ url: `projects/${projectId}/show/activate`, method: 'POST' }),
      // Patch the playhead as soon as the server confirms so `isShowActive` flips immediately.
      async onQueryStarted({ projectId }, { dispatch, queryFulfilled }) {
        try {
          const { data } = await queryFulfilled
          dispatch(
            cueStacksApi.util.updateQueryData('projectProgramState', projectId, (draft) => {
              draft.activeStackId = data.activeStackId
            }),
          )
        } catch {
          // Mutation failed — nothing to patch
        }
      },
      invalidatesTags: (_result, _error, { projectId }) => [
        { type: 'ProgramState', id: projectId },
        'CueStackList',
        'FixtureEffects',
        'GroupActiveEffects',
      ],
    }),

    deactivateProgram: build.mutation<void, { projectId: number }>({
      query: ({ projectId }) => ({ url: `projects/${projectId}/show/deactivate`, method: 'POST' }),
      async onQueryStarted({ projectId }, { dispatch, queryFulfilled }) {
        try {
          await queryFulfilled
          dispatch(
            cueStacksApi.util.updateQueryData('projectProgramState', projectId, (draft) => {
              draft.activeStackId = null
            }),
          )
        } catch {
          // Mutation failed — nothing to patch
        }
      },
      invalidatesTags: (_result, _error, { projectId }) => [
        { type: 'ProgramState', id: projectId },
        'CueStackList',
        'FixtureEffects',
        'GroupActiveEffects',
      ],
    }),

    advanceProgram: build.mutation<
      ProgramActivateResponse,
      { projectId: number } & AdvanceProgramRequest
    >({
      query: ({ projectId, ...body }) => ({
        url: `projects/${projectId}/show/advance`,
        method: 'POST',
        body,
      }),
      async onQueryStarted({ projectId }, { dispatch, queryFulfilled }) {
        try {
          const { data } = await queryFulfilled
          dispatch(
            cueStacksApi.util.updateQueryData('projectProgramState', projectId, (draft) => {
              draft.activeStackId = data.activeStackId
            }),
          )
        } catch {
          // Mutation failed — nothing to patch
        }
      },
      invalidatesTags: (_result, _error, { projectId }) => [
        { type: 'ProgramState', id: projectId },
        'CueStackList',
        'FixtureEffects',
        'GroupActiveEffects',
      ],
    }),

    goToStack: build.mutation<
      ProgramActivateResponse,
      { projectId: number } & GoToStackRequest
    >({
      query: ({ projectId, ...body }) => ({
        url: `projects/${projectId}/show/go-to`,
        method: 'POST',
        body,
      }),
      async onQueryStarted({ projectId, stackId }, { dispatch, queryFulfilled }) {
        // The playhead only: which cue a bare go-to lands on is the server's decision, and this
        // side must not guess it.
        const patch = dispatch(
          cueStacksApi.util.updateQueryData('projectProgramState', projectId, (draft) => {
            draft.activeStackId = stackId
          }),
        )
        try {
          await queryFulfilled
        } catch {
          patch.undo()
        }
      },
      invalidatesTags: (_result, _error, { projectId }) => [
        { type: 'ProgramState', id: projectId },
        'CueStackList',
        'FixtureEffects',
        'GroupActiveEffects',
      ],
    }),
  }),
  overrideExisting: false,
})

// Subscribe to WebSocket program-state changes (activate/deactivate/advance/go-to). Patches
// activeStackId directly so the playhead updates immediately in other browsers without a refetch.
lightingApi.cueStacks.subscribeToProgramState(function (event) {
  store.dispatch(
    cueStacksApi.util.updateQueryData('projectProgramState', event.projectId, (draft) => {
      draft.activeStackId = event.activeStackId
    }),
  )
})

// Subscribe to per-stack run state (live cue, armed next, fade). Two jobs: keep the runner
// slice — which drives the NEXT pill and the fade animation — following whichever surface moved
// the show, and patch the cached stack so a later refetch doesn't flap back to a stale cue.
lightingApi.cueStacks.subscribeToRunState(function (event: CueRunStateEvent) {
  store.dispatch(applyServerRunState(event))
  store.dispatch(
    cueStacksApi.util.updateQueryData('projectCueStackList', event.projectId, (draft) => {
      const stack = draft.find((s) => s.id === event.stackId)
      if (!stack) return
      stack.activeCueId = event.activeCueId
      stack.nextCueId = event.nextCueId
      stack.activeEntryId = event.activeEntryId
      stack.nextEntryId = event.nextEntryId
      stack.standbyEntryId = event.standbyEntryId
    }),
  )
})

export const {
  useProjectCueStackListQuery,
  useProjectProgramStateQuery,
  useCreateProjectCueStackMutation,
  useSaveProjectCueStackMutation,
  useDeleteProjectCueStackMutation,
  useReorderCueStacksMutation,
  useReorderCueStackCuesMutation,
  useActivateCueStackMutation,
  useDeactivateCueStackMutation,
  useAdvanceCueStackMutation,
  useSetCueStackStandbyMutation,
  useCreateMarkerEntryMutation,
  useRenameMarkerEntryMutation,
  useAddCueToStackMutation,
  useRemoveCueStackEntryMutation,
  useMoveCueStackEntryMutation,
  usePatchCueStackEntryMutation,
  useDeleteMarkerEntryMutation,
  usePreviewCueLookQuery,
  usePreviewLooseCueQuery,
  useSortCueStackByCueNumberMutation,
  useActivateProgramMutation,
  useDeactivateProgramMutation,
  useAdvanceProgramMutation,
  useGoToStackMutation,
} = cueStacksApi
