import { restApi } from "./restApi"
import { lightingApi } from "../api/lightingApi"
import { ChannelMappingEntry, ChannelMappings } from "../api/channelMappingApi"

// Convert Map<number, Map<number, Entry>> to Record<number, Record<number, Entry>> for serialization
type ChannelMappingsRecord = Record<number, Record<number, ChannelMappingEntry>>

function mappingsToRecord(mappings: ChannelMappings): ChannelMappingsRecord {
  const result: ChannelMappingsRecord = {}
  for (const [universe, channels] of mappings.entries()) {
    const channelRecord: Record<number, ChannelMappingEntry> = {}
    for (const [channel, entry] of channels.entries()) {
      channelRecord[channel] = entry
    }
    result[universe] = channelRecord
  }
  return result
}

export const channelMappingApi = restApi.injectEndpoints({
  endpoints: (build) => {
    return {
      getChannelMapping: build.query<ChannelMappingEntry | undefined, { universe: number, channelNo: number }>({
        queryFn: ({ universe, channelNo }) => {
          const mapping = lightingApi.channelMapping.get(universe, channelNo)
          return { data: mapping }
        },
        async onCacheEntryAdded({ universe, channelNo }, { updateCachedData, cacheEntryRemoved }) {
          const subscription = lightingApi.channelMapping.subscribe((mappings) => {
            updateCachedData(() => {
              return mappings.get(universe)?.get(channelNo)
            })
          })
          await cacheEntryRemoved
          subscription.unsubscribe()
        },
      }),

      // Get all channel mappings organized by universe
      getChannelMappingList: build.query<ChannelMappingsRecord, void>({
        queryFn: () => {
          return { data: mappingsToRecord(lightingApi.channelMapping.getAll()) }
        },
        async onCacheEntryAdded(_arg, { updateCachedData, cacheEntryRemoved }) {
          const subscription = lightingApi.channelMapping.subscribe((mappings) => {
            updateCachedData(() => mappingsToRecord(mappings))
          })
          await cacheEntryRemoved
          subscription.unsubscribe()
        },
      }),
    }
  },
  overrideExisting: false,
})

export const { useGetChannelMappingQuery, useGetChannelMappingListQuery } = channelMappingApi
export type { ChannelMappingEntry } from "../api/channelMappingApi"
