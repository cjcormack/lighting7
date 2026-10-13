import { restApi } from './restApi'

/** `GET /install/telemetry` (Sentry plan D1): whether this install reports, and how. */
export interface TelemetrySettings {
  /** The admin's choice, unless `sentry.enabled = false` in local.conf keeps it off. */
  enabled: boolean
  release: string
  environment: string
  tracesSampleRate: number
  browserDsn?: string | null
}

export const telemetryApi = restApi.injectEndpoints({
  endpoints: (build) => ({
    telemetry: build.query<TelemetrySettings, void>({
      query: () => 'install/telemetry',
      providesTags: ['Telemetry'],
    }),
    updateTelemetry: build.mutation<TelemetrySettings, { enabled: boolean }>({
      query: (body) => ({ url: 'install/telemetry', method: 'PUT', body }),
      async onQueryStarted(_, { dispatch, queryFulfilled }) {
        try {
          const { data } = await queryFulfilled
          dispatch(telemetryApi.util.upsertQueryData('telemetry', undefined, data))
        } catch {
          // The call site and errorToastMiddleware report a failed PUT.
        }
      },
    }),
  }),
  overrideExisting: false,
})

export const { useTelemetryQuery, useUpdateTelemetryMutation } = telemetryApi
