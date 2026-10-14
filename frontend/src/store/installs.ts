import { restApi } from "./restApi"
import { lightingApi } from "../api/lightingApi"
import { store } from "./index"

export interface Install {
  uuid: string
  friendlyName: string
  createdAt: string
  /** The first admin has not yet finished or skipped onboarding; absent from a desk that predates it. */
  onboardingPending?: boolean
}

export interface UpdateInstallRequest {
  friendlyName: string
}

export interface FinishOnboardingRequest {
  /** The project to open once onboarding is done; absent keeps the current one. */
  openProjectId?: number
}

export interface FinishOnboardingResponse {
  currentProjectId: number
  /** The empty placeholder project the desk booted on, when finishing removed it. */
  removedPlaceholder?: string | null
  onboardingPending: boolean
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
    finishOnboarding: build.mutation<FinishOnboardingResponse, FinishOnboardingRequest>({
      query: (body) => ({
        url: 'install/onboarding/finish',
        method: 'POST',
        body,
      }),
      invalidatesTags: ['Install', 'ProjectList', 'Project'],
    }),
  }),
  overrideExisting: false,
})

export const {
  useInstallQuery,
  useUpdateInstallMutation,
  useFinishOnboardingMutation,
} = installsApi

// The desk was renamed, or its telemetry switched, on another client. Machine-scoped state, so this
// arrives on the same socket band as the user-list frame rather than on the per-project fixtures
// bus — see `plugins/MachineSocket.kt`.
lightingApi.install.subscribe(() => {
  store.dispatch(restApi.util.invalidateTags(['Install', 'Telemetry']))
})
