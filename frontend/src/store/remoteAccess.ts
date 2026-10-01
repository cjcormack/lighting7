import { restApi } from './restApi'
import { lightingApi } from '../api/lightingApi'
import { store } from './index'

/** Mirrors `TunnelStateDto` in lighting7's `routes/installTunnel.kt`. */
export type TunnelStatus = 'off' | 'installing' | 'starting' | 'online' | 'error' | 'no-binary'

export interface TunnelState {
  status: TunnelStatus
  url?: string | null
  /** ngrok's own code (`ERR_NGROK_…`) when it gave one. */
  code?: string | null
  message?: string | null
  /** An error the desk is retrying on its own (a dropped connection), vs one the operator must fix. */
  retrying?: boolean
  downloadedBytes?: number | null
  totalBytes?: number | null
}

/** Mirrors `TunnelSettingsDto`. The authtoken is write-only: only `hasAuthtoken` ever comes back. */
export interface TunnelSettings {
  enabled: boolean
  domain?: string | null
  allowScripts: boolean
  /** A remote caller may arm the desk and fire or reload a one-shot trigger (stage-view session 9). Absent from an older desk. */
  allowEffects?: boolean
  hasAuthtoken: boolean
  /** The desk's public base URL — the OAuth issuer — as it stands now. */
  publicUrl: string
  /** What to paste into Claude's "Add custom connector". */
  connectorUrl: string
  /** `mcp.publicUrl` in local.conf names a tunnel of the operator's own, and it wins. */
  publicUrlOverridden: boolean
  port: number
  /** The ngrok version this desk downloads, or null when none is pinned for this computer. */
  pinnedVersion?: string | null
  state: TunnelState
}

/** Every field optional: absent leaves it alone. An empty `authtoken` or `domain` clears it. */
export interface UpdateTunnelRequest {
  enabled?: boolean
  domain?: string
  authtoken?: string
  allowScripts?: boolean
  allowEffects?: boolean
}

export const remoteAccessApi = restApi.injectEndpoints({
  endpoints: (build) => ({
    tunnelSettings: build.query<TunnelSettings, void>({
      query: () => 'install/tunnel',
      providesTags: ['RemoteAccess'],
    }),
    saveTunnelSettings: build.mutation<TunnelSettings, UpdateTunnelRequest>({
      query: (body) => ({ url: 'install/tunnel', method: 'PUT', body }),
      // The response is the whole settings document, so write it rather than refetch it.
      async onQueryStarted(_body, { dispatch, queryFulfilled }) {
        try {
          const { data } = await queryFulfilled
          dispatch(remoteAccessApi.util.upsertQueryData('tunnelSettings', undefined, data))
        } catch {
          // The panel renders the refusal; the cache keeps the last confirmed settings.
        }
      },
    }),
  }),
  overrideExisting: false,
})

export const { useTunnelSettingsQuery, useSaveTunnelSettingsMutation } = remoteAccessApi

// The frame carries the whole state, so it patches the cached settings and never refetches: the
// first-enable download streams its progress through here. An operator's socket is never sent it,
// and the only reader passes `skip: !isAdmin`, so on their desk this patches no entry at all.
lightingApi.remoteAccess.subscribe((state) => {
  store.dispatch(
    remoteAccessApi.util.updateQueryData('tunnelSettings', undefined, (draft) => {
      draft.state = state
    }),
  )
})
