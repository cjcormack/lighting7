import { restApi } from "./restApi"
import { lightingApi } from "../api/lightingApi"
import { store } from "./index"
import type {
  StageViewpointDto,
  CreateStageViewpointRequest,
  UpdateStageViewpointRequest,
} from "../api/stageViewpointApi"

lightingApi.stageViewpoints.subscribe(() => {
  store.dispatch(restApi.util.invalidateTags(['StageViewpoint']))
})

/** Saved viewpoints (stage-view plan session 2), invalidated by `stageViewpointListChanged`. */
export const stageViewpointsApi = restApi.injectEndpoints({
  endpoints: (build) => ({
    stageViewpointList: build.query<StageViewpointDto[], number>({
      query: (projectId) => `projects/${projectId}/stage-viewpoints`,
      providesTags: ['StageViewpoint'],
    }),

    createStageViewpoint: build.mutation<StageViewpointDto, { projectId: number } & CreateStageViewpointRequest>({
      query: ({ projectId, ...body }) => ({
        url: `projects/${projectId}/stage-viewpoints`,
        method: 'POST',
        body,
      }),
      // The new row goes into the list the moment the create answers, before the refetch: *Save
      // this view…* moves the window onto it at once, and a list without it would read the view as
      // one that does not exist here.
      async onQueryStarted({ projectId }, { dispatch, queryFulfilled }) {
        try {
          const { data } = await queryFulfilled
          dispatch(
            stageViewpointsApi.util.updateQueryData('stageViewpointList', projectId, (draft) => {
              if (!draft.some((row) => row.uuid === data.uuid)) draft.push(data)
            }),
          )
        } catch {
          // The sheet reports the refusal.
        }
      },
      invalidatesTags: ['StageViewpoint'],
    }),

    updateStageViewpoint: build.mutation<
      StageViewpointDto,
      { projectId: number; viewpointId: number } & UpdateStageViewpointRequest
    >({
      query: ({ projectId, viewpointId, ...body }) => ({
        url: `projects/${projectId}/stage-viewpoints/${viewpointId}`,
        method: 'PUT',
        body,
      }),
      invalidatesTags: ['StageViewpoint'],
    }),

    deleteStageViewpoint: build.mutation<void, { projectId: number; viewpointId: number }>({
      query: ({ projectId, viewpointId }) => ({
        url: `projects/${projectId}/stage-viewpoints/${viewpointId}`,
        method: 'DELETE',
      }),
      invalidatesTags: ['StageViewpoint'],
    }),
  }),
  overrideExisting: false,
})

export const {
  useStageViewpointListQuery,
  useCreateStageViewpointMutation,
  useUpdateStageViewpointMutation,
  useDeleteStageViewpointMutation,
} = stageViewpointsApi
