import { restApi } from "./restApi"
import { lightingApi } from "../api/lightingApi"
import { WS_GESTURE_DROPPED_MESSAGE } from "../api/wsGesture"

/**
 * The RTK error a dropped WebSocket write reports.
 *
 * These three mutations look like REST from the outside — `.unwrap()`, `isError`, the loading
 * flags — but their transport is a fire-and-forget WS frame, and before this they returned
 * success unconditionally: a park that never left the browser was indistinguishable from one the
 * desk acknowledged. `sendGesture` has already toasted by the time this is built, which is why
 * all three sit in `SILENT_ENDPOINTS`; the error exists so callers and the cache agree with the
 * rig, not to report the failure a second time.
 */
const DROPPED_WRITE_ERROR = {
  error: { status: 'CUSTOM_ERROR' as const, error: WS_GESTURE_DROPPED_MESSAGE },
}

export const channelsApi = restApi.injectEndpoints({
  endpoints: (build) => {
    return {
      updateChannel: build.mutation<void, { universe: number, channelNo: number, value: number }>({
        queryFn: ({ universe, channelNo, value }) => {
          if (!lightingApi.channels.update(universe, channelNo, value)) return DROPPED_WRITE_ERROR
          return { data: undefined }
        },
      }),
    }
  },
  overrideExisting: false,
})

export const { useUpdateChannelMutation } = channelsApi
