import { restApi } from "./restApi"
import { lightingApi } from "../api/lightingApi"
import { store } from "./index"
import type {
  RiggingDto,
  CreateRiggingRequest,
  UpdateRiggingRequest,
} from "../api/riggingApi"

// Subscribe to WebSocket rigging changes. Backend also emits patchListChanged
// after rigging mutations (rig pose recomposes patch worldPosition*), which is
// why we don't need to invalidate Patch from here.
lightingApi.riggings.subscribe(() => {
  store.dispatch(restApi.util.invalidateTags(['Rigging']))
})

export const riggingsApi = restApi.injectEndpoints({
  endpoints: (build) => ({
    riggingList: build.query<RiggingDto[], number>({
      query: (projectId) => `projects/${projectId}/riggings`,
      providesTags: ['Rigging'],
    }),

    createRigging: build.mutation<RiggingDto, { projectId: number } & CreateRiggingRequest>({
      query: ({ projectId, ...body }) => ({
        url: `projects/${projectId}/riggings`,
        method: 'POST',
        body,
      }),
      invalidatesTags: ['Rigging'],
    }),

    updateRigging: build.mutation<RiggingDto, { projectId: number; riggingId: number } & UpdateRiggingRequest>({
      query: ({ projectId, riggingId, ...body }) => ({
        url: `projects/${projectId}/riggings/${riggingId}`,
        method: 'PUT',
        body,
      }),
      invalidatesTags: ['Rigging', 'Patch'],
    }),

    deleteRigging: build.mutation<void, { projectId: number; riggingId: number }>({
      query: ({ projectId, riggingId }) => ({
        url: `projects/${projectId}/riggings/${riggingId}`,
        method: 'DELETE',
      }),
      invalidatesTags: ['Rigging', 'Patch'],
    }),
  }),
  overrideExisting: false,
})

export const {
  useRiggingListQuery,
  useCreateRiggingMutation,
  useUpdateRiggingMutation,
  useDeleteRiggingMutation,
} = riggingsApi
