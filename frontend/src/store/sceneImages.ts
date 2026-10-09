import { restApi } from "./restApi"
import type { SceneImageInfo, SceneImageMediaType } from "../api/sceneImageApi"
import type { StageElementDto } from "../api/stageElementApi"

/**
 * The project's scene images (scrim plan §3.4). The list is what this machine holds — the element
 * sheet's thumbnails, its aspect hint and its "missing on this machine" all read it — and an
 * upload adds to it. No frame of its own: an image matters only once an element names it, and that
 * write fires `stageElementListChanged`, whose bridge (`store/stageElements.ts`) invalidates this
 * list too — which covers `upload_scene_image` + `set_scene` and a sync pull while a sheet is open.
 */
export const sceneImagesApi = restApi.injectEndpoints({
  endpoints: (build) => ({
    sceneImageList: build.query<SceneImageInfo[], number>({
      query: (projectId) => `projects/${projectId}/scene-images`,
      providesTags: (_result, _error, projectId) => [{ type: 'SceneImage', id: projectId }],
    }),

    /**
     * The raw bytes, typed by their media type; the desk answers what it stored. Idempotent by
     * hash. Refusals (`SCENE_IMAGE_INVALID`, 413 over 25 MB) are drawn by the sheet, so this is in
     * `SILENT_ENDPOINTS`.
     *
     * The body is a **typed `Blob`**, never a bare `ArrayBuffer` with a `Content-Type` header:
     * `fetchBaseQuery` deletes the content type of any body it cannot serialise as JSON (so a
     * `FormData` can set its own boundary), and the desk refuses an image whose type it is not
     * told. A Blob's own type is what the browser then sends.
     */
    uploadSceneImage: build.mutation<
      SceneImageInfo,
      { projectId: number; bytes: ArrayBuffer | Blob; mediaType: SceneImageMediaType }
    >({
      query: ({ projectId, bytes, mediaType }) => ({
        url: `projects/${projectId}/scene-images`,
        method: 'POST',
        body: new Blob([bytes], { type: mediaType }),
      }),
      invalidatesTags: (_result, _error, { projectId }) => [{ type: 'SceneImage', id: projectId }],
    }),

    /** The per-machine *Full detail* switch (D12): the 4096 px copy for this element, here only. */
    setElementDisplayDetail: build.mutation<StageElementDto, { projectId: number; elementId: number; full: boolean }>({
      query: ({ projectId, elementId, full }) => ({
        url: `projects/${projectId}/stage-elements/${elementId}/display-detail`,
        method: 'PUT',
        body: { full },
      }),
      invalidatesTags: ['StageElement'],
    }),
  }),
  overrideExisting: false,
})

export const { useSceneImageListQuery, useUploadSceneImageMutation, useSetElementDisplayDetailMutation } = sceneImagesApi
