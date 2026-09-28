import { restApi } from "./restApi"
import { lightingApi } from "../api/lightingApi"
import { WS_GESTURE_DROPPED_MESSAGE } from "../api/wsGesture"

export interface ParkState {
  universe: number
  channel: number
  value: number
}

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

export const parkApi = restApi.injectEndpoints({
  endpoints: (build) => {
    return {
      /**
       * Query whether a specific channel is parked, and its parked value.
       * Returns the parked value (number) or undefined if not parked.
       */
      getChannelParkState: build.query<number | undefined, { universe: number; channelNo: number }>({
        queryFn: ({ universe, channelNo }) => {
          const value = lightingApi.park.getParkedValue(universe, channelNo)
          return { data: value }
        },
        async onCacheEntryAdded({ universe, channelNo }, { updateCachedData, cacheEntryRemoved }) {
          const key = `${universe}:${channelNo}`

          const subscription = lightingApi.park.subscribeToChannel(key, (value) => {
            updateCachedData(() => value)
          })
          await cacheEntryRemoved
          subscription.unsubscribe()
        },
      }),

      /**
       * Query all parked channels as a flat list.
       */
      getParkStateList: build.query<ParkState[], void>({
        queryFn: () => {
          const all = lightingApi.park.getAll()
          const list: ParkState[] = []
          all.forEach((value, key) => {
            const [universe, channel] = key.split(":").map(Number)
            list.push({ universe, channel, value })
          })
          return { data: list }
        },
        async onCacheEntryAdded(_, { updateCachedData, cacheEntryRemoved }) {
          const subscription = lightingApi.park.subscribe((parked) => {
            const list: ParkState[] = []
            parked.forEach((value, key) => {
              const [universe, channel] = key.split(":").map(Number)
              list.push({ universe, channel, value })
            })
            updateCachedData(() => list)
          })
          await cacheEntryRemoved
          subscription.unsubscribe()
        },
      }),

      parkChannel: build.mutation<void, { universe: number; channelNo: number; value: number }>({
        queryFn: ({ universe, channelNo, value }) => {
          if (!lightingApi.park.park(universe, channelNo, value)) return DROPPED_WRITE_ERROR
          return { data: undefined }
        },
      }),

      unparkChannel: build.mutation<void, { universe: number; channelNo: number }>({
        queryFn: ({ universe, channelNo }) => {
          if (!lightingApi.park.unpark(universe, channelNo)) return DROPPED_WRITE_ERROR
          return { data: undefined }
        },
      }),
    }
  },
  overrideExisting: false,
})

export const {
  useGetChannelParkStateQuery,
  useGetParkStateListQuery,
  useParkChannelMutation,
  useUnparkChannelMutation,
} = parkApi
