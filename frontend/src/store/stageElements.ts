import { restApi } from "./restApi"
import { lightingApi } from "../api/lightingApi"
import { store } from "./index"
import type {
  StageElementDto,
  CreateStageElementRequest,
  UpdateStageElementRequest,
} from "../api/stageElementApi"

lightingApi.stageElements.subscribe(() => {
  store.dispatch(restApi.util.invalidateTags(['StageElement']))
})

/**
 * The scene document's elements (stage-view plan session 2). Invalidated by
 * `stageElementListChanged`, which REST and `set_scene` both fire. Deleting a seating that seat views
 * still name answers 409 `STAGE_ELEMENT_IN_USE`; `force` goes ahead and leaves them dangling.
 */
export const stageElementsApi = restApi.injectEndpoints({
  endpoints: (build) => ({
    stageElementList: build.query<StageElementDto[], number>({
      query: (projectId) => `projects/${projectId}/stage-elements`,
      providesTags: ['StageElement'],
    }),

    createStageElement: build.mutation<StageElementDto, { projectId: number } & CreateStageElementRequest>({
      query: ({ projectId, ...body }) => ({
        url: `projects/${projectId}/stage-elements`,
        method: 'POST',
        body,
      }),
      invalidatesTags: ['StageElement'],
    }),

    updateStageElement: build.mutation<
      StageElementDto,
      { projectId: number; elementId: number; force?: boolean } & UpdateStageElementRequest
    >({
      query: ({ projectId, elementId, force, ...body }) => ({
        url: `projects/${projectId}/stage-elements/${elementId}${force ? '?force=true' : ''}`,
        method: 'PUT',
        body,
      }),
      invalidatesTags: ['StageElement'],
    }),

    deleteStageElement: build.mutation<void, { projectId: number; elementId: number; force?: boolean }>({
      query: ({ projectId, elementId, force }) => ({
        url: `projects/${projectId}/stage-elements/${elementId}${force ? '?force=true' : ''}`,
        method: 'DELETE',
      }),
      invalidatesTags: ['StageElement'],
    }),
  }),
  overrideExisting: false,
})

export const {
  useStageElementListQuery,
  useCreateStageElementMutation,
  useUpdateStageElementMutation,
  useDeleteStageElementMutation,
} = stageElementsApi
