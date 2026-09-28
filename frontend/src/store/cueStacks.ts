import { restApi } from './restApi'
import { lightingApi } from '../api/lightingApi'
import { store } from './index'
import { applyServerRunState } from './runnerSlice'
import type {
  CueStack,
  CueStackInput,
  CueStackCueEntry,
  ReorderCuesRequest,
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

    // Deleting a stack cascades its cues (standalone cues no longer exist). Deleting a separator
    // just removes that divider row.
    deleteProjectCueStack: build.mutation<
      void,
      { projectId: number; stackId: number }
    >({
      query: ({ projectId, stackId }) => ({
        url: `projects/${projectId}/cue-stacks/${stackId}`,
        method: 'DELETE',
      }),
      invalidatesTags: (_result, _error, { projectId }) => [
        { type: 'CueStackList', id: projectId },
        'CueStackList',
        { type: 'CueList', id: projectId },
        'CueList',
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
      async onQueryStarted({ projectId, stackId, cueIds }, { dispatch, queryFulfilled }) {
        const patchResult = dispatch(
          cueStacksApi.util.updateQueryData('projectCueStackList', projectId, (draft) => {
            const stack = draft.find((s) => s.id === stackId)
            if (!stack) return
            const byId = new Map(stack.cues.map((cue) => [cue.id, cue]))
            const reordered = cueIds
              .map((id) => byId.get(id))
              .filter((cue): cue is CueStackCueEntry => cue != null)
            // Anything the request didn't name keeps its relative order at the end, matching
            // the server, which only rewrites sortOrder for the ids it was given.
            const named = new Set(cueIds)
            for (const cue of stack.cues) {
              if (!named.has(cue.id)) reordered.push(cue)
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

    // `addCueToCueStack` stood here. A cue is created straight into its stack
    // (`createProjectCue` carries `cueStackId`, the one request that honours it) and ordered
    // within one by `reorderCueStackCues` — so nothing ever sent the add-cue POST.

    activateCueStack: build.mutation<
      CueStackActivateResponse,
      { projectId: number; stackId: number } & ActivateCueStackRequest
    >({
      query: ({ projectId, stackId, ...body }) => ({
        url: `projects/${projectId}/cue-stacks/${stackId}/activate`,
        method: 'POST',
        body,
      }),
      // Optimistic update: immediately set activeCueId so the UI highlights correctly
      async onQueryStarted({ projectId, stackId, cueId }, { dispatch, queryFulfilled }) {
        const patchResult = dispatch(
          cueStacksApi.util.updateQueryData('projectCueStackList', projectId, (draft) => {
            const stack = draft.find((s) => s.id === stackId)
            if (stack) {
              stack.activeCueId = cueId ?? stack.cues[0]?.id ?? null
            }
          }),
        )
        try {
          const { data } = await queryFulfilled
          dispatch(
            cueStacksApi.util.updateQueryData('projectCueStackList', projectId, (draft) => {
              const stack = draft.find((s) => s.id === stackId)
              if (stack) stack.activeCueId = data.cueId
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
      // Optimistic update: clear activeCueId immediately
      async onQueryStarted({ projectId, stackId }, { dispatch, queryFulfilled }) {
        const patchResult = dispatch(
          cueStacksApi.util.updateQueryData('projectCueStackList', projectId, (draft) => {
            const stack = draft.find((s) => s.id === stackId)
            if (stack) stack.activeCueId = null
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
            const currentIdx = stack.cues.findIndex((c) => c.id === stack.activeCueId)
            const delta = direction === 'FORWARD' ? 1 : -1
            let nextIdx = currentIdx + delta
            if (stack.loop) {
              nextIdx = ((nextIdx % stack.cues.length) + stack.cues.length) % stack.cues.length
            } else {
              nextIdx = Math.max(0, Math.min(stack.cues.length - 1, nextIdx))
            }
            stack.activeCueId = stack.cues[nextIdx]?.id ?? stack.activeCueId
          }),
        )
        try {
          const { data } = await queryFulfilled
          dispatch(
            cueStacksApi.util.updateQueryData('projectCueStackList', projectId, (draft) => {
              const stack = draft.find((s) => s.id === stackId)
              if (stack) stack.activeCueId = data.cueId
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
    // carries. Jumping to a cue *is* `activateCueStack({ cueId })` — one endpoint whether the
    // stack was already live or not — so the go-to POST had no caller.

    /**
     * Arm the next GO. No optimistic cache patch: the instant row highlight is the runner slice's
     * `setStandby` dispatch in `useShowTransport`, and the server's `cueRunStateChanged` frame is
     * what every session (this one included) ends up believing.
     */
    setCueStackStandby: build.mutation<
      CueStackRunState,
      { projectId: number; stackId: number; cueId: number | null }
    >({
      query: ({ projectId, stackId, cueId }) => ({
        url: `projects/${projectId}/cue-stacks/${stackId}/standby`,
        method: 'POST',
        body: { cueId },
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
     * `cueId` is required rather than "null means the effective next": under a query the arg *is*
     * the cache key, and a key meaning "whatever the server currently thinks" would serve one
     * cue's look under another cue's identity. The caller knows the id — the server broadcasts it.
     */
    previewCueLook: build.query<
      PreviewCueResponse,
      { projectId: number; stackId: number; cueId: number }
    >({
      query: ({ projectId, stackId, cueId }) => ({
        url: `projects/${projectId}/cue-stacks/${stackId}/preview`,
        params: { cueId },
      }),
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
  usePreviewCueLookQuery,
  useSortCueStackByCueNumberMutation,
  useActivateProgramMutation,
  useDeactivateProgramMutation,
  useAdvanceProgramMutation,
  useGoToStackMutation,
} = cueStacksApi
