import { restApi } from "./restApi"
import { lightingApi } from "../api/lightingApi"
import { store } from "./index"
import { BootStatus } from "../api/bootStatusWsApi"

// Boot status is served by the exempt `GET /api/rest/status` route, which answers
// with a 200 + BootStatus body even mid-boot (before the show exists). Named
// `bootStatus` to avoid colliding with the `status` endpoint in ./status.ts,
// which tracks the WebSocket readyState.
export const bootStatusApi = restApi.injectEndpoints({
  endpoints: (build) => ({
    bootStatus: build.query<BootStatus, void>({
      query: () => 'status',
      providesTags: ['BootStatus'],
    }),
  }),
  overrideExisting: false,
})

export const { useBootStatusQuery } = bootStatusApi

// A pushed `bootProgressState` frame invalidates the cached status, forcing an
// immediate refetch of `/api/rest/status` (the authoritative source). This gives
// smoother-than-polling updates during warm-up, and — because BootGate stops
// polling once ready — is what re-shows the overlay when a runtime project switch
// transiently re-enters warm-up. We refetch rather than patch the cache directly
// so the update always arrives as a query fulfilment, which reliably re-renders
// subscribers (mirrors the WS→invalidateTags bridge in ./show.ts).
lightingApi.bootStatus.subscribe(() => {
  store.dispatch(restApi.util.invalidateTags(['BootStatus']))
})
