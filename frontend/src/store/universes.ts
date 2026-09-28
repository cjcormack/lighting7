import { restApi } from "./restApi"
import { lightingApi } from "../api/lightingApi"

export const universesApi = restApi.injectEndpoints({
  endpoints: (build) => {
    return {
      getUniverse: build.query<readonly number[], void>({
        queryFn: () => {
          const value = lightingApi.universes.get()
          return { data: value }
        },
        async onCacheEntryAdded(_, { updateCachedData, cacheEntryRemoved }) {
          const subscription = lightingApi.universes.subscribe((universes) => {
            updateCachedData(() => {
              return universes
            })
          })
          await cacheEntryRemoved
          subscription.unsubscribe()
        },
      }),
    }
  },
  overrideExisting: false,
})

export const { useGetUniverseQuery } = universesApi
