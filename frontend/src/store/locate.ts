import { restApi } from "./restApi"

// === Types ===

export type LocateTargetType = 'fixture' | 'group'

export interface LocateTarget {
  type: LocateTargetType
  key: string
}

export interface LocateState {
  targets: LocateTarget[]
}

export interface ToggleLocateResponse {
  active: boolean
  writeCount: number
  /**
   * True when the toggle came back inactive *because* park masks every property locate would
   * have written — otherwise indistinguishable from "this target has no DMX-backed
   * properties".
   */
  parkMasked?: boolean
}

// === API ===

export const locateApi = restApi.injectEndpoints({
  endpoints: (build) => ({
    /** All currently-located fixtures and groups. */
    locateState: build.query<LocateState, void>({
      query: () => 'locate',
      providesTags: ['Locate'],
    }),

    /**
     * Toggle locate for a fixture or group.
     *
     * Locate writes programmer entries (owner `locate`) rather than destroying effects:
     * covering effects are *suppressed* while the locate holds and resume on release. The
     * effects tags are deliberately not invalidated here — nothing is removed to refetch,
     * and a bare tag would refetch every per-fixture effects query on each toggle.
     */
    toggleLocate: build.mutation<ToggleLocateResponse, LocateTarget>({
      query: (body) => ({
        url: 'locate/toggle',
        method: 'POST',
        body,
      }),
      invalidatesTags: ['Locate'],
    }),
  }),
  overrideExisting: false,
})

export const { useLocateStateQuery, useToggleLocateMutation } = locateApi
