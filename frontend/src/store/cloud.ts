import { restApi } from './restApi'
import { lightingApi } from '../api/lightingApi'
import { store } from './index'

/** Mirrors `CloudConnectionStatus` in lighting7's `cloud/CloudState.kt`. */
export type CloudConnection = 'OFF' | 'CONNECTING' | 'ONLINE' | 'RETRYING'

/** A pending adoption. The code and both links are absent on the public listener. */
export interface CloudAdoption {
  userCode?: string | null
  verificationUri?: string | null
  verificationUriComplete?: string | null
  expiresAt: string
  fingerprint: string
}

export interface CloudAdopted {
  installId: string
  orgName: string
  adoptedAt: string
  /** The adopting desk account's display name; null once that account is deleted. */
  adoptedBy?: string | null
  linkedEmail?: string | null
  fingerprint: string
}

/** Mirrors `CloudStateDto`: what `GET /install/cloud`, every Cloud route and `cloud.state` answer. */
export interface CloudState {
  /** `cloud.baseUrl` names a cloud this desk accepts. */
  configured: boolean
  /** False when `cloud.enabled = false` in local.conf. */
  enabled: boolean
  adoption?: CloudAdoption | null
  adopted?: CloudAdopted | null
  connection?: CloudConnection
  retryAt?: string | null
  lastError?: { code: string; message: string } | null
  lastOnlineAt?: string | null
  notice?: string | null
  /** When the desk set [notice]: a dismissal's key, since the same words can come again. */
  noticeAt?: string | null
  /** This answer is for a caller on the public listener, where adopting is refused. */
  remote?: boolean
}

export const cloudApi = restApi.injectEndpoints({
  endpoints: (build) => ({
    cloudState: build.query<CloudState, void>({
      query: () => 'install/cloud',
      providesTags: ['Cloud'],
    }),
    startCloudAdoption: build.mutation<CloudState, void>({
      query: () => ({ url: 'install/cloud/adoption', method: 'POST' }),
      async onQueryStarted(_arg, { dispatch, queryFulfilled }) {
        const data = await answered(queryFulfilled)
        if (data != null) dispatch(cloudApi.util.upsertQueryData('cloudState', undefined, data))
      },
    }),
    cancelCloudAdoption: build.mutation<CloudState, void>({
      query: () => ({ url: 'install/cloud/adoption', method: 'DELETE' }),
      async onQueryStarted(_arg, { dispatch, queryFulfilled }) {
        const data = await answered(queryFulfilled)
        if (data != null) dispatch(cloudApi.util.upsertQueryData('cloudState', undefined, data))
      },
    }),
    unadoptCloud: build.mutation<CloudState, { force?: boolean }>({
      query: ({ force }) => ({ url: force ? 'install/cloud?force=true' : 'install/cloud', method: 'DELETE' }),
      async onQueryStarted(_arg, { dispatch, queryFulfilled }) {
        const data = await answered(queryFulfilled)
        if (data != null) dispatch(cloudApi.util.upsertQueryData('cloudState', undefined, data))
      },
    }),
  }),
  overrideExisting: false,
})

/**
 * Every Cloud route answers the whole state, so a mutation writes it rather than refetching; null
 * for a refusal, which the panel renders while the cache keeps the last confirmed state.
 */
async function answered(queryFulfilled: Promise<{ data: CloudState }>): Promise<CloudState | null> {
  try {
    return (await queryFulfilled).data
  } catch {
    return null
  }
}

export const {
  useCloudStateQuery,
  useStartCloudAdoptionMutation,
  useCancelCloudAdoptionMutation,
  useUnadoptCloudMutation,
} = cloudApi

// The frame is the whole state. An operator's socket is never sent it, and the only reader passes
// `skip: !isAdmin`, so on their desk this patches no entry at all.
lightingApi.cloud.subscribe((state) => {
  store.dispatch(cloudApi.util.updateQueryData('cloudState', undefined, () => state))
})
