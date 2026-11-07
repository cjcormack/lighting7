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
  /** The sidecar that carries remote sessions: absent from a desk before it supervised one. */
  link?: 'OFF' | 'STARTING' | 'RUNNING' | 'RESTARTING'
  retryAt?: string | null
  lastError?: { code: string; message: string } | null
  lastOnlineAt?: string | null
  notice?: string | null
  /** When the desk set [notice]: a dismissal's key, since the same words can come again. */
  noticeAt?: string | null
  /** This answer is for a caller on the public listener, where adopting is refused. */
  remote?: boolean
  /** What may come through the cloud now: absent from a desk before the Cloud tab carried it. */
  remoteSwitches?: Pick<RemoteAccessSettings, 'remoteAccess' | 'mcpAccess' | 'approveSessions'>
}

/**
 * Mirrors `RemoteAccessDto` in lighting7's `routes/installCloud.kt`: what may come through the cloud
 * (the first three, adopted desks only) and what any remote caller may do. Changed only from the
 * desk's own listener.
 */
export interface RemoteAccessSettings {
  remoteAccess: boolean
  mcpAccess: boolean
  approveSessions: boolean
  allowScripts: boolean
  allowEffects: boolean
  allowCommands: boolean
}

/** Mirrors `RemoteSessionDto`: one remote session, open or from the audit. */
export interface RemoteSession {
  sid: string
  email: string
  /** Known only while the session is open. */
  displayName?: string | null
  /** The desk user it was, by their name now; absent for an unlinked session. */
  user?: string | null
  startedAt: string
  endedAt?: string | null
  /** `DIRECT` or `RELAY`, the last the sidecar reported. */
  path?: string | null
  /** A `RemoteSessionEnd` name, once ended. */
  endReason?: string | null
}

export interface RemoteSessions {
  open: RemoteSession[]
  recent: RemoteSession[]
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
    remoteAccessSettings: build.query<RemoteAccessSettings, void>({
      query: () => 'install/cloud/remote',
      providesTags: ['CloudRemoteAccess'],
    }),
    setRemoteAccess: build.mutation<RemoteAccessSettings, Partial<RemoteAccessSettings>>({
      query: (body) => ({ url: 'install/cloud/remote', method: 'PUT', body }),
      async onQueryStarted(_arg, { dispatch, queryFulfilled }) {
        try {
          const { data } = await queryFulfilled
          dispatch(cloudApi.util.upsertQueryData('remoteAccessSettings', undefined, data))
        } catch {
          // The section renders the refusal; the cache keeps the last confirmed switches.
        }
      },
    }),
    remoteSessions: build.query<RemoteSessions, void>({
      query: () => 'install/cloud/sessions',
      providesTags: ['CloudRemoteSessions'],
    }),
    endRemoteSession: build.mutation<RemoteSessions, string>({
      query: (sid) => ({ url: `install/cloud/sessions/${encodeURIComponent(sid)}`, method: 'DELETE' }),
      async onQueryStarted(_sid, { dispatch, queryFulfilled }) {
        try {
          const { data } = await queryFulfilled
          dispatch(cloudApi.util.upsertQueryData('remoteSessions', undefined, data))
        } catch {
          // Toasted by the middleware: a session already gone is the usual cause.
        }
      },
    }),
    allowRemoteSession: build.mutation<void, string>({
      query: (sid) => ({ url: `install/cloud/sessions/${encodeURIComponent(sid)}/allow`, method: 'POST' }),
      invalidatesTags: ['CloudRemoteSessions'],
    }),
    denyRemoteSession: build.mutation<void, string>({
      query: (sid) => ({ url: `install/cloud/sessions/${encodeURIComponent(sid)}/deny`, method: 'POST' }),
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
  useRemoteAccessSettingsQuery,
  useSetRemoteAccessMutation,
  useRemoteSessionsQuery,
  useEndRemoteSessionMutation,
  useAllowRemoteSessionMutation,
  useDenyRemoteSessionMutation,
} = cloudApi

// The frame is the whole state. An operator's socket is never sent it, and the only reader passes
// `skip: !isAdmin`, so on their desk this patches no entry at all.
lightingApi.cloud.subscribe((state) => {
  const before = cloudApi.endpoints.cloudState.select()(store.getState()).data
  store.dispatch(cloudApi.util.updateQueryData('cloudState', undefined, () => state))
  // The cloud switches change on another window, and an adoption starts them all off again, so the
  // frame is what keeps every window's Remote access section true to the desk.
  const switches = state.remoteSwitches
  if (switches != null) {
    store.dispatch(cloudApi.util.updateQueryData('remoteAccessSettings', undefined, (draft) => ({ ...draft, ...switches })))
  }
  if (before?.adopted?.installId !== state.adopted?.installId) {
    store.dispatch(cloudApi.util.invalidateTags(['CloudRemoteAccess', 'CloudRemoteSessions']))
  }
})
