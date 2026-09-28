import { restApi } from "./restApi"
import { lightingApi } from "../api/lightingApi"
import { store } from "./index"

export interface Install {
  uuid: string
  friendlyName: string
  createdAt: string
}

export interface UpdateInstallRequest {
  friendlyName: string
}

export const installsApi = restApi.injectEndpoints({
  endpoints: (build) => ({
    install: build.query<Install, void>({
      query: () => 'install',
      providesTags: ['Install'],
    }),
    updateInstall: build.mutation<Install, UpdateInstallRequest>({
      query: (body) => ({
        url: 'install',
        method: 'PUT',
        body,
      }),
      invalidatesTags: ['Install'],
    }),
  }),
  overrideExisting: false,
})

export const {
  useInstallQuery,
  useUpdateInstallMutation,
} = installsApi

// The desk was renamed on another client. Machine-scoped state, so this arrives on the same
// socket band as the user-list frame rather than on the per-project fixtures bus — see
// `plugins/MachineSocket.kt`.
lightingApi.install.subscribe(() => {
  store.dispatch(restApi.util.invalidateTags(['Install']))
})
