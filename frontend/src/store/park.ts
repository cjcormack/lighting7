import { restApi } from "./restApi"
import { lightingApi } from "../api/lightingApi"
import { WS_GESTURE_DROPPED_MESSAGE } from "../api/wsGesture"
import type { ParkEntry, ParkOwner, ReleasedPark } from "../api/parkApi"

export type { ParkOwner, ReleasedPark } from "../api/parkApi"

/**
 * One parked channel. `owner` is whose park it is — the project's own (`SHOW`), or inherited from
 * its venue's pinned version (`VENUE`); `venueValue` is the venue's park under a show's own re-park
 * of the channel, which unparking the show's brings back.
 */
export interface ParkState {
  universe: number
  channel: number
  value: number
  owner: ParkOwner
  venueValue?: number
}

function parkStateList(entries: Map<string, ParkEntry>): ParkState[] {
  const list: ParkState[] = []
  entries.forEach((entry, key) => {
    const [universe, channel] = key.split(":").map(Number)
    list.push({ universe, channel, ...entry })
  })
  return list
}

/**
 * The RTK error a dropped WebSocket write reports.
 *
 * These four mutations look like REST from the outside — `.unwrap()`, `isError`, the loading
 * flags — but their transport is a fire-and-forget WS frame, and before this they returned
 * success unconditionally: a park that never left the browser was indistinguishable from one the
 * desk acknowledged. `sendGesture` has already toasted by the time this is built, which is why
 * all four sit in `SILENT_ENDPOINTS`; the error exists so callers and the cache agree with the
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
        queryFn: () => ({ data: parkStateList(lightingApi.park.getEntries()) }),
        async onCacheEntryAdded(_, { updateCachedData, cacheEntryRemoved }) {
          const subscription = lightingApi.park.subscribeEntries((entries) => {
            updateCachedData(() => parkStateList(entries))
          })
          await cacheEntryRemoved
          subscription.unsubscribe()
        },
      }),

      /**
       * The venue parks this show has released for its run (a show on a venue only; empty
       * elsewhere). Each says what restoring it brings back, or nothing when the version no longer
       * parks the channel.
       */
      getReleasedParkList: build.query<ReleasedPark[], void>({
        queryFn: () => ({ data: lightingApi.park.getReleased() }),
        async onCacheEntryAdded(_, { updateCachedData, cacheEntryRemoved }) {
          const subscription = lightingApi.park.subscribeEntries((_entries, released) => {
            updateCachedData(() => released)
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

      /** Take the venue's park back on a channel (a show on a venue): see `ParkApi.restore`. */
      restoreChannelPark: build.mutation<void, { universe: number; channelNo: number }>({
        queryFn: ({ universe, channelNo }) => {
          if (!lightingApi.park.restore(universe, channelNo)) return DROPPED_WRITE_ERROR
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
  useGetReleasedParkListQuery,
  useParkChannelMutation,
  useUnparkChannelMutation,
  useRestoreChannelParkMutation,
} = parkApi
