import { restApi } from "./restApi"
import { lightingApi } from "../api/lightingApi"
import { store } from "./index"
import type {
  StageRegionDto,
  CreateStageRegionRequest,
  UpdateStageRegionRequest,
} from "../api/stageRegionApi"

lightingApi.stageRegions.subscribe(() => {
  store.dispatch(restApi.util.invalidateTags(['StageRegion']))
})

export const stageRegionsApi = restApi.injectEndpoints({
  endpoints: (build) => ({
    stageRegionList: build.query<StageRegionDto[], number>({
      query: (projectId) => `projects/${projectId}/stage-regions`,
      providesTags: ['StageRegion'],
    }),

    createStageRegion: build.mutation<StageRegionDto, { projectId: number } & CreateStageRegionRequest>({
      query: ({ projectId, ...body }) => ({
        url: `projects/${projectId}/stage-regions`,
        method: 'POST',
        body,
      }),
      invalidatesTags: ['StageRegion'],
    }),

    updateStageRegion: build.mutation<StageRegionDto, { projectId: number; regionId: number } & UpdateStageRegionRequest>({
      query: ({ projectId, regionId, ...body }) => ({
        url: `projects/${projectId}/stage-regions/${regionId}`,
        method: 'PUT',
        body,
      }),
      invalidatesTags: ['StageRegion'],
    }),

    deleteStageRegion: build.mutation<void, { projectId: number; regionId: number }>({
      query: ({ projectId, regionId }) => ({
        url: `projects/${projectId}/stage-regions/${regionId}`,
        method: 'DELETE',
      }),
      invalidatesTags: ['StageRegion'],
    }),
  }),
  overrideExisting: false,
})

export const {
  useStageRegionListQuery,
  useCreateStageRegionMutation,
  useUpdateStageRegionMutation,
  useDeleteStageRegionMutation,
} = stageRegionsApi
