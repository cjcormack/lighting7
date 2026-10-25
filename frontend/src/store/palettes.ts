import { restApi } from './restApi'
import { lightingApi } from '../api/lightingApi'
import { store } from './index'
import type { AttributeFamily } from '@/lib/attributeFamily'
import type {
  ApplyPaletteRequest,
  CopyPaletteRequest,
  CopyPaletteResponse,
  ApplyPaletteResponse,
  PaletteInput,
  PaletteResolveRequest,
  PaletteResolveResponse,
  PaletteSummary,
  PaletteTarget,
  TogglePaletteRequest,
  TogglePaletteResponse,
} from '../api/palettesApi'

/**
 * Bridge `paletteListChanged` into cache invalidation.
 *
 * Called from `main.tsx`, **not** on import — the same trap `startLooksBridge` documents: this slice
 * is imported from the earliest render path (the nav registry, and the programmer's template strip),
 * so touching `lightingApi` in the module body is a runtime import cycle that throws a TDZ
 * `ReferenceError` and takes every export with it, while `tsc`, `vite build` and the unit tests all
 * pass.
 *
 * No `Fixture` / `GroupList`: compatibility is capability-only (D6), and a client answers it from a
 * fixture's own `capabilities`, so creating a palette moves nothing on those two lists.
 */
export function startPalettesBridge() {
  // `palettePressed` — a press somewhere on this desk. Patched, never invalidated: the frame
  // carries the whole of what changed, a press happens at busking rate, and the sibling signal
  // below drops three caches. Zero network, which is the point of the frame being keyed at all.
  //
  // It fires for **this** tab's own presses too, and that is wanted rather than tolerated: it is
  // what makes the server's stamp the one every client ends up holding, so the row cannot order
  // itself differently in two tabs because their clocks differ.
  lightingApi.palettes.subscribePressed(function (event) {
    applyPalettePressed(store.dispatch, store.getState, event.paletteId, event.lastPressedAt)
  })

  lightingApi.palettes.subscribe(function () {
    // `Cue` rides along for the reason the looks bridge gives: a template created, copied or
    // deleted elsewhere changes what the cues layering it compose to, and this signal is the only
    // announcement of a delete. A template *retune* rides `cuesRecomposed` instead. `CueList` is
    // the same pairing the looks bridge and every cue-affecting mutation here use — the list's
    // entries carry `layers[].source.name`, so a rename shows through it.
    store.dispatch(restApi.util.invalidateTags(['PaletteList', 'Cue', 'CueList']))
  })
}

/**
 * Write `lastPressedAt` into every cached `paletteList` entry holding this template.
 *
 * **Every entry, because the family filter is a query argument**: `/palettes?family=colour` and
 * the programmer's unfiltered list are two cache entries of one endpoint, and a press seen through
 * one of them has to move the other or the two disagree about the order of the same library.
 * `selectCachedArgsForQuery` is what enumerates them without this module having to know which
 * families anything happens to be showing.
 *
 * A template the entry does not hold is left alone rather than inserted — a list filtered to
 * COLOUR must not gain a position template because someone pressed one.
 */
function applyPalettePressed(
  dispatch: typeof store.dispatch,
  getState: typeof store.getState,
  paletteId: number,
  lastPressedAt: string,
) {
  for (const args of palettesApi.util.selectCachedArgsForQuery(getState(), 'paletteList')) {
    dispatch(
      palettesApi.util.updateQueryData('paletteList', args, (draft) => {
        const template = draft.find((t) => t.id === paletteId)
        if (template != null) template.lastPressedAt = lastPressedAt
      }),
    )
  }
}

export const palettesApi = restApi.injectEndpoints({
  endpoints: (build) => ({
    /**
     * The library's list.
     *
     * `family` really is an **exact partition** here, unlike `/looks` — a template is in exactly one
     * family by construction — so the filter can be served either side. It is passed through to the
     * server so a deep link lands filtered without the client having to hold the whole list.
     */
    paletteList: build.query<PaletteSummary[], { projectId: number; family?: AttributeFamily }>({
      query: ({ projectId, family }) =>
        family
          ? `projects/${projectId}/palettes?family=${family}`
          : `projects/${projectId}/palettes`,
      providesTags: (_result, _error, { projectId }) => [
        { type: 'PaletteList', id: projectId },
        'PaletteList',
      ],
    }),

    // A single-template read stood here. The editor opens from a `paletteList` row and the
    // list carries the whole summary, so nothing fetched one on its own.

    createPalette: build.mutation<PaletteSummary, { projectId: number } & PaletteInput>({
      query: ({ projectId, ...body }) => ({
        url: `projects/${projectId}/palettes`,
        method: 'POST',
        body,
      }),
      // Guarded on the result: a create can fail on a blank name (400), a duplicate (409) or any of
      // the four write-boundary rules (400), and invalidating then refetches to learn nothing moved.
      invalidatesTags: (result) => (result == null ? [] : ['PaletteList']),
    }),

    /**
     * PUT a template.
     *
     * Omit `rows` (or `effect`) for a metadata-only edit; sending either replaces that half. Send
     * **at most one of the two** — which half a template holds is fixed at creation, and a PUT
     * naming the other is a 400. A contents edit is a **live write** — the server republishes every
     * cue layering this template and the programmer's own stack — so `Cue` / `CueList` go with it.
     */
    savePalette: build.mutation<
      PaletteSummary,
      { projectId: number; paletteId: number } & PaletteInput
    >({
      query: ({ projectId, paletteId, ...body }) => ({
        url: `projects/${projectId}/palettes/${paletteId}`,
        method: 'PUT',
        body,
      }),
      invalidatesTags: (result) =>
        result == null ? [] : ['PaletteList', 'Cue', 'CueList'],
    }),

    deletePalette: build.mutation<
      void,
      { projectId: number; paletteId: number; force?: boolean }
    >({
      query: ({ projectId, paletteId, force }) => ({
        url: `projects/${projectId}/palettes/${paletteId}${force ? '?force=true' : ''}`,
        method: 'DELETE',
      }),
      // Guarded on the *error*, not the result: a 204 carries no body. A PALETTE_IN_USE 409 is an
      // ordinary step (it opens "delete anyway") and nothing was deleted. A forced delete does
      // remove cue layers, hence the cue tags.
      invalidatesTags: (_result, error) =>
        error != null ? [] : ['PaletteList', 'CueList', 'Cue'],
    }),

    /**
     * Copy a template — into this project (the sheet's Duplicate, with a `(Copy n)` name) or another
     * (*Copy to…*). lighting7's route since the library-sheets plan's session 0, on `copyLook`'s
     * model: a fresh uuid and fresh row uuids, `lastPressedAt` cleared, a name clash a 409.
     *
     * The copy lands in the **target** project's library, so only that list is invalidated. Unlike
     * `copyLook` there is no `Fixture` / `GroupList` beside it: those carry Look compatibility, and a
     * template has none (capability-only, D6). The server also fires `paletteListChanged`, which
     * the bridge turns into the same invalidation for every other window.
     */
    copyPalette: build.mutation<CopyPaletteResponse, { projectId: number; paletteId: number } & CopyPaletteRequest>({
      query: ({ projectId, paletteId, ...body }) => ({
        url: `projects/${projectId}/palettes/${paletteId}/copy`,
        method: 'POST',
        body,
      }),
      invalidatesTags: (result) => (result == null ? [] : [{ type: 'PaletteList', id: result.targetProjectId }]),
    }),

    /**
     * The editor's "resolves to" panel — the **same** resolver the cook runs, asked about a draft.
     *
     * A mutation rather than a query despite being a read, because the question is "resolve *this*
     * body", which has no stable cache key worth holding: the editor asks again on every change and
     * the answer is only interesting for the draft in front of you.
     */
    resolvePalette: build.mutation<
      PaletteResolveResponse,
      { projectId: number } & PaletteResolveRequest
    >({
      query: ({ projectId, ...body }) => ({
        url: `projects/${projectId}/palettes/resolve`,
        method: 'POST',
        body,
      }),
    }),

    /**
     * ***Apply as values*** — ⌥click, or the hold menu's second item: set literal values in the
     * programmer for the current selection (`write: false` answers them without writing).
     *
     * No dependency — retuning the template later does not move these. That is the whole difference
     * from `togglePalette` below, and why the two gestures are two routes rather than a flag.
     *
     * No cache invalidation: the values land in the programmer and surface through the programmer's
     * own WS state, which every consumer already subscribes to.
     */
    applyPalette: build.mutation<
      ApplyPaletteResponse,
      { projectId: number; paletteId: number } & ApplyPaletteRequest
    >({
      query: ({ projectId, paletteId, ...body }) => ({
        url: `projects/${projectId}/palettes/${paletteId}/apply`,
        method: 'POST',
        body,
      }),
      // The acting tab's own fast path onto the recents row. The server stamps this press and
      // broadcasts `palettePressed`, so this is belt as well as braces — but the frame is a
      // round trip away and the chips the operator just pressed are under their hand.
      //
      // **After the response, not before it, and gated on the same rule the server applies**: an
      // apply that reaches no head returns 200 having written nothing, and the desk does not stamp
      // that. A provisional clock reading is fine because the frame overwrites it with the
      // server's within the same breath — and it is the server's value every client keeps.
      async onQueryStarted({ paletteId, write }, { dispatch, getState, queryFulfilled }) {
        try {
          const { data } = await queryFulfilled
          // `write: false` wrote nothing, and the desk logs no press for it: `written` there counts
          // what it *would* have written.
          if (write !== false && (data.written > 0 || (data.fxIds?.length ?? 0) > 0)) {
            applyPalettePressed(dispatch, getState as typeof store.getState, paletteId, new Date().toISOString())
          }
        } catch {
          // A refused press is not a press. The toast is the caller's.
        }
      },
    }),

    /**
     * **New from selection**: record what is selected as a new template.
     *
     * Server-side, and the third route that is for the same reason as the other two: converting a
     * recorded *literal* back into an **intent** is per-head arithmetic that has to agree with the
     * resolver, and a client doing it would be a second opinion about what the rig is showing. It
     * also decides generic-vs-per-fixture from the data rather than from a toggle.
     */
    createPaletteFromProgrammer: build.mutation<
      {
        palette: PaletteSummary
        isGeneric: boolean
        skipped: { fixtureKey: string; attribute: string; reason: string }[]
      },
      {
        projectId: number
        name: string
        notes?: string | null
        mask: AttributeFamily[]
        targets: PaletteTarget[]
        source?: 'TOUCHED' | 'ALL' | 'STAGE_SNAPSHOT'
      }
    >({
      query: ({ projectId, ...body }) => ({
        url: `projects/${projectId}/palettes/from-programmer`,
        method: 'POST',
        body,
      }),
      invalidatesTags: (result) => (result == null ? [] : ['PaletteList']),
    }),

    /**
     * **The press** — a tap on a chip or pad: add (or remove) a layer that *tracks* the template,
     * targeted at the selection.
     *
     * The layer is the dependency mechanism — it already is, for Looks — so "a colour I can change
     * everywhere later" and "a colour I want right now" are two gestures on one chip rather than two
     * kinds of template.
     */
    togglePalette: build.mutation<
      TogglePaletteResponse,
      { projectId: number; paletteId: number } & TogglePaletteRequest
    >({
      query: ({ projectId, paletteId, ...body }) => ({
        url: `projects/${projectId}/palettes/${paletteId}/toggle`,
        method: 'POST',
        body,
      }),
      // The **on** arm only, for the reason the desk applies the same rule: a second press takes
      // the layer off, and a release is not something you reached for. See `applyPalette` above
      // for why this runs after the response rather than optimistically.
      async onQueryStarted({ paletteId }, { dispatch, getState, queryFulfilled }) {
        try {
          const { data } = await queryFulfilled
          if (data.action === 'applied') {
            applyPalettePressed(dispatch, getState as typeof store.getState, paletteId, new Date().toISOString())
          }
        } catch {
          // Refused, or the target is gone. Not a press.
        }
      },
    }),
  }),
  overrideExisting: false,
})

export const {
  usePaletteListQuery,
  useCreatePaletteFromProgrammerMutation,
  useCreatePaletteMutation,
  useSavePaletteMutation,
  useDeletePaletteMutation,
  useCopyPaletteMutation,
  useResolvePaletteMutation,
  useApplyPaletteMutation,
  useTogglePaletteMutation,
} = palettesApi
