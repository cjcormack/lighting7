import { restApi } from './restApi'

/** The header an answer carries its request's secret in — lighting7 `routes/stageRenders.kt`. */
export const RENDER_TOKEN_HEADER = 'X-Render-Token'

/**
 * A window's answer to `stageRender.request` (stage-view plan session 4): the PNG, or why there is
 * none. REST rather than a socket frame — the desk's reasons are in `routes/stageRenders.kt` — and
 * accepted only with the request's id, its token and this window's session. Neither provides nor
 * invalidates a tag: nothing on this window reads a render back.
 *
 * Both are in `SILENT_ENDPOINTS`: the render runs on an operator's screen, mid-show, for a model
 * that asked through MCP, and a refused answer (the desk gave up waiting) is the model's to hear —
 * never a toast on that screen.
 */
export const stageRendersApi = restApi.injectEndpoints({
  endpoints: (build) => ({
    uploadStageRender: build.mutation<void, { requestId: string; token: string; png: ArrayBuffer }>({
      query: ({ requestId, token, png }) => ({
        url: `stage-renders/${encodeURIComponent(requestId)}`,
        method: 'POST',
        // The bytes alone: RTK drops a type set on a binary body, and the desk checks the PNG
        // signature rather than trusting one.
        headers: { [RENDER_TOKEN_HEADER]: token },
        body: png,
      }),
    }),
    failStageRender: build.mutation<void, { requestId: string; token: string; reason: string }>({
      query: ({ requestId, token, reason }) => ({
        url: `stage-renders/${encodeURIComponent(requestId)}/failure`,
        method: 'POST',
        headers: { [RENDER_TOKEN_HEADER]: token },
        body: { reason },
      }),
    }),
  }),
})

export const { useUploadStageRenderMutation, useFailStageRenderMutation } = stageRendersApi
