import { restApi } from './restApi'
import { lightingApi } from '../api/lightingApi'
import { store } from './index'

// ─── Types ──────────────────────────────────────────────────────────────

export interface CueSlot {
  id: number
  page: number
  slotIndex: number
  /** Which arm the slot holds. A cue *stack* was a third arm until the busk layout replaced it. */
  itemType: 'cue' | 'look'
  itemId: number
  itemName: string
}

/**
 * Exactly one of [cueId] / [lookId] — the server refuses two, and refuses a Look with a deferred
 * effect (409 `CUE_SLOT_LOOK_NEEDS_SELECTION`), because a slot has no selection to give it.
 */
export interface AssignCueSlotRequest {
  page: number
  slotIndex: number
  cueId?: number
  lookId?: number
}

export interface SwapCueSlotsRequest {
  fromPage: number
  fromSlotIndex: number
  toPage: number
  toSlotIndex: number
}

// ─── WebSocket subscription ─────────────────────────────────────────────

lightingApi.cueSlots.subscribe(function () {
  store.dispatch(restApi.util.invalidateTags(['CueSlotList']))
})

// ─── RTK Query endpoints ────────────────────────────────────────────────

export const cueSlotsApi = restApi.injectEndpoints({
  endpoints: (build) => ({
    projectCueSlots: build.query<CueSlot[], number>({
      query: (projectId) => `projects/${projectId}/cue-slots`,
      providesTags: (_result, _error, projectId) => [
        { type: 'CueSlotList', id: projectId },
        'CueSlotList',
      ],
    }),

    assignCueSlot: build.mutation<
      CueSlot,
      { projectId: number } & AssignCueSlotRequest
    >({
      query: ({ projectId, ...body }) => ({
        url: `projects/${projectId}/cue-slots`,
        method: 'POST',
        body,
      }),
      async onQueryStarted({ projectId, ...input }, { dispatch, queryFulfilled }) {
        const patchResult = dispatch(
          cueSlotsApi.util.updateQueryData('projectCueSlots', projectId, (draft) => {
            const idx = draft.findIndex(
              (s) => s.page === input.page && s.slotIndex === input.slotIndex,
            )
            const placeholder: CueSlot = {
              id: -1,
              page: input.page,
              slotIndex: input.slotIndex,
              itemType: input.cueId != null ? 'cue' : 'look',
              itemId: (input.cueId ?? input.lookId)!,
              itemName: '...',
            }
            if (idx >= 0) draft[idx] = placeholder
            else draft.push(placeholder)
          }),
        )
        try {
          await queryFulfilled
        } catch {
          patchResult.undo()
        }
      },
      invalidatesTags: (_r, _e, { projectId }) => [
        { type: 'CueSlotList', id: projectId },
      ],
    }),

    swapCueSlots: build.mutation<
      void,
      { projectId: number } & SwapCueSlotsRequest
    >({
      query: ({ projectId, ...body }) => ({
        url: `projects/${projectId}/cue-slots/swap`,
        method: 'POST',
        body,
      }),
      async onQueryStarted({ projectId, ...swap }, { dispatch, queryFulfilled }) {
        const patchResult = dispatch(
          cueSlotsApi.util.updateQueryData('projectCueSlots', projectId, (draft) => {
            const fromIdx = draft.findIndex(
              (s) => s.page === swap.fromPage && s.slotIndex === swap.fromSlotIndex,
            )
            const toIdx = draft.findIndex(
              (s) => s.page === swap.toPage && s.slotIndex === swap.toSlotIndex,
            )
            if (fromIdx >= 0 && toIdx >= 0) {
              // Swap page+slotIndex values
              const [fp, fi] = [draft[fromIdx].page, draft[fromIdx].slotIndex]
              draft[fromIdx].page = draft[toIdx].page
              draft[fromIdx].slotIndex = draft[toIdx].slotIndex
              draft[toIdx].page = fp
              draft[toIdx].slotIndex = fi
            } else if (fromIdx >= 0) {
              // Move from occupied to empty
              draft[fromIdx].page = swap.toPage
              draft[fromIdx].slotIndex = swap.toSlotIndex
            }
          }),
        )
        try {
          await queryFulfilled
        } catch {
          patchResult.undo()
        }
      },
      invalidatesTags: (_r, _e, { projectId }) => [
        { type: 'CueSlotList', id: projectId },
      ],
    }),

    clearCueSlot: build.mutation<void, { projectId: number; slotId: number }>({
      query: ({ projectId, slotId }) => ({
        url: `projects/${projectId}/cue-slots/${slotId}`,
        method: 'DELETE',
      }),
      async onQueryStarted({ projectId, slotId }, { dispatch, queryFulfilled }) {
        const patchResult = dispatch(
          cueSlotsApi.util.updateQueryData('projectCueSlots', projectId, (draft) => {
            const idx = draft.findIndex((s) => s.id === slotId)
            if (idx >= 0) draft.splice(idx, 1)
          }),
        )
        try {
          await queryFulfilled
        } catch {
          patchResult.undo()
        }
      },
      invalidatesTags: (_r, _e, { projectId }) => [
        { type: 'CueSlotList', id: projectId },
      ],
    }),
  }),
  overrideExisting: false,
})

export const {
  useProjectCueSlotsQuery,
  useAssignCueSlotMutation,
  useSwapCueSlotsMutation,
  useClearCueSlotMutation,
} = cueSlotsApi
