import { useSyncExternalStore } from 'react'
import { restApi } from './restApi'
import { lightingApi } from '../api/lightingApi'
import type { OscState } from '../api/oscWsApi'
import type { OscAddressList } from '../lib/oscAddresses'

/** An allowlisted sender, mirroring `OscSenderDto` in lighting7's `osc/OscService.kt`. */
export interface OscSender {
  id: number
  name: string
  address: string
}

/** `GET /install/osc`: this machine's listener and allowlist. Admin only, and never remote. */
export interface OscSettings {
  enabled: boolean
  /** Null listens on every interface. */
  bindAddress?: string | null
  port: number
  senders: OscSender[]
}

/** The whole document: a sender with no `id` is new, and one left out is removed. */
export interface OscSettingsRequest {
  enabled: boolean
  bindAddress?: string | null
  port: number
  senders: { id?: number; name: string; address: string }[]
}

export const oscApi = restApi.injectEndpoints({
  endpoints: (build) => ({
    oscSettings: build.query<OscSettings, void>({
      query: () => 'install/osc',
      providesTags: ['OscSettings'],
    }),
    saveOscSettings: build.mutation<OscSettings, OscSettingsRequest>({
      query: (body) => ({ url: 'install/osc', method: 'PUT', body }),
      // The answer is the document as stored, so write it rather than refetch it.
      async onQueryStarted(_body, { dispatch, queryFulfilled }) {
        try {
          const { data } = await queryFulfilled
          dispatch(oscApi.util.upsertQueryData('oscSettings', undefined, data))
        } catch {
          // The panel shows the refusal; the cache keeps the last confirmed settings.
        }
      },
    }),
    // Read when a menu or the cheat sheet opens, and asked again each time: an address follows a
    // rename, a renumber or a pad moved, none of which this cache hears about.
    oscAddresses: build.query<OscAddressList, number>({
      query: (projectId) => `projects/${projectId}/osc/addresses`,
      keepUnusedDataFor: 5,
    }),
  }),
  overrideExisting: false,
})

export const { useOscSettingsQuery, useSaveOscSettingsMutation, useOscAddressesQuery, useLazyOscAddressesQuery } = oscApi

const subscribe = (notify: () => void) => {
  const subscription = lightingApi.osc.subscribe(() => notify())
  return () => subscription.unsubscribe()
}

/**
 * The live `osc.state`: whether the desk listens, its counts, refused senders and the last 50
 * messages. Every window on the desk's own listener is sent it, an operator's included (OSC plan
 * D12); null until the first frame, and always null on the public listener.
 */
export function useOscState(): OscState | null {
  return useSyncExternalStore(subscribe, lightingApi.osc.current, lightingApi.osc.current)
}
