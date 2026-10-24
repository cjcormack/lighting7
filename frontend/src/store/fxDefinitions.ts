import type { Timing } from '../api/fxApi'
import { restApi } from './restApi'
import type { FxParameterDef } from './fixtureFx'

// === Types ===

export interface FxDefinition {
  id: number
  fxId: string
  name: string
  family: string
  outputType: string
  fxMode: string
  parameters: FxParameterDef[]
  compatibleProperties: string[]
  script: string
  defaultTiming: Timing
  timingSource?: 'BEAT' | 'WALL_CLOCK'
}

export interface CreateFxDefinitionRequest {
  fxId: string
  name: string
  family: string
  outputType?: string
  fxMode?: string
  parameters?: FxParameterDef[]
  compatibleProperties?: string[]
  script: string
  defaultTiming?: Timing
  timingSource?: string
}

export interface UpdateFxDefinitionRequest {
  fxId?: string
  name?: string
  family?: string
  outputType?: string
  fxMode?: string
  parameters?: FxParameterDef[]
  compatibleProperties?: string[]
  script?: string
  defaultTiming?: Timing
  timingSource?: string
}

// === RTK Query Endpoints ===

export const fxDefinitionsApi = restApi.injectEndpoints({
  endpoints: (build) => ({
    // The running show's definitions — the `fx_definitions` rows, not the effect vocabulary (that
    // is `fxLibrary` in `store/fixtureFx.ts`, built-ins included). The FX Library sheet reads it
    // for one fact the library entry does not carry: which `USER` entries are **custom** (a
    // definition's `fxId` is the entry's id) and which a script registered (library-sheets plan
    // §3.2). Tagged `FxLibrary`, so the `fxDefinitionListChanged` bridge beside `fxLibrary`
    // invalidates it on the same frame, and so do the three definition writes below.
    fxDefinitionList: build.query<FxDefinition[], void>({
      query: () => 'fx/definitions',
      providesTags: [{ type: 'FxLibrary', id: 'DEFINITIONS' }],
    }),

    fxDefinition: build.query<FxDefinition, number>({
      query: (id) => `fx/definitions/${id}`,
      providesTags: (_result, _error, id) => [{ type: 'FxLibrary', id }],
    }),

    createFxDefinition: build.mutation<FxDefinition, CreateFxDefinitionRequest>({
      query: (body) => ({
        url: 'fx/definitions',
        method: 'POST',
        body,
      }),
      invalidatesTags: ['FxLibrary'],
    }),

    updateFxDefinition: build.mutation<FxDefinition, { id: number } & UpdateFxDefinitionRequest>({
      query: ({ id, ...body }) => ({
        url: `fx/definitions/${id}`,
        method: 'PUT',
        body,
      }),
      invalidatesTags: ['FxLibrary'],
    }),

    deleteFxDefinition: build.mutation<void, number>({
      query: (id) => ({
        url: `fx/definitions/${id}`,
        method: 'DELETE',
      }),
      invalidatesTags: ['FxLibrary'],
    }),

    // The three FX-definition compile/test mutations stood here. `routes/FxLibrary.tsx` compiles
    // through `compileProjectScript` instead — one dialog and one code path for every script
    // type, the effect editor included — so none of them was ever fired.
  }),
  overrideExisting: false,
})

export const {
  useFxDefinitionListQuery,
  useFxDefinitionQuery,
  useCreateFxDefinitionMutation,
  useUpdateFxDefinitionMutation,
  useDeleteFxDefinitionMutation,
} = fxDefinitionsApi
