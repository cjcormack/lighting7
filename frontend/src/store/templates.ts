import { restApi } from './restApi'
import { lightingApi } from '../api/lightingApi'
import { store } from './index'
import type { AttributeFamily } from '@/lib/attributeFamily'
import type {
  ApplyTemplateRequest,
  CopyTemplateRequest,
  CopyTemplateResponse,
  ApplyTemplateResponse,
  TemplateInput,
  TemplateResolveRequest,
  TemplateResolveResponse,
  TemplateSummary,
  TemplateTarget,
  ToggleTemplateRequest,
  ToggleTemplateResponse,
} from '../api/templatesApi'

/**
 * Bridge `templateListChanged` into cache invalidation.
 *
 * Called from `main.tsx`, **not** on import — the same trap `startLooksBridge` documents: this slice
 * is imported from the earliest render path (the nav registry, and the programmer's template strip),
 * so touching `lightingApi` in the module body is a runtime import cycle that throws a TDZ
 * `ReferenceError` and takes every export with it, while `tsc`, `vite build` and the unit tests all
 * pass.
 *
 * Note what is **absent** compared to the looks bridge: no `Fixture` / `GroupList`. Those two are
 * invalidated there because `compatibleLookIds` rides on the fixture and group summaries — and a
 * template has no compatibility list at all. Compatibility is capability-only now (D6), and a
 * client can answer it from a fixture's own `capabilities`, so creating a template moves nothing on
 * those two lists.
 */
export function startTemplatesBridge() {
  // `templatePressed` — a press somewhere on this desk. Patched, never invalidated: the frame
  // carries the whole of what changed, a press happens at busking rate, and the sibling signal
  // below drops three caches. Zero network, which is the point of the frame being keyed at all.
  //
  // It fires for **this** tab's own presses too, and that is wanted rather than tolerated: it is
  // what makes the server's stamp the one every client ends up holding, so the row cannot order
  // itself differently in two tabs because their clocks differ.
  lightingApi.templates.subscribePressed(function (event) {
    applyTemplatePressed(store.dispatch, store.getState, event.templateId, event.lastPressedAt)
  })

  lightingApi.templates.subscribe(function () {
    // `Cue` rides along for the reason the looks bridge gives: a template created, copied or
    // deleted elsewhere changes what the cues layering it compose to, and this signal is the only
    // announcement of a delete. A template *retune* rides `cuesRecomposed` instead. `CueList` is
    // the same pairing the looks bridge and every cue-affecting mutation here use — the list's
    // entries carry `layers[].source.name`, so a rename shows through it.
    store.dispatch(restApi.util.invalidateTags(['TemplateList', 'Cue', 'CueList']))
  })
}

/**
 * Write `lastPressedAt` into every cached `templateList` entry holding this template.
 *
 * **Every entry, because the family filter is a query argument**: `/templates?family=colour` and
 * the programmer's unfiltered list are two cache entries of one endpoint, and a press seen through
 * one of them has to move the other or the two disagree about the order of the same library.
 * `selectCachedArgsForQuery` is what enumerates them without this module having to know which
 * families anything happens to be showing.
 *
 * A template the entry does not hold is left alone rather than inserted — a list filtered to
 * COLOUR must not gain a position template because someone pressed one.
 */
function applyTemplatePressed(
  dispatch: typeof store.dispatch,
  getState: typeof store.getState,
  templateId: number,
  lastPressedAt: string,
) {
  for (const args of templatesApi.util.selectCachedArgsForQuery(getState(), 'templateList')) {
    dispatch(
      templatesApi.util.updateQueryData('templateList', args, (draft) => {
        const template = draft.find((t) => t.id === templateId)
        if (template != null) template.lastPressedAt = lastPressedAt
      }),
    )
  }
}

export const templatesApi = restApi.injectEndpoints({
  endpoints: (build) => ({
    /**
     * The library's list.
     *
     * `family` really is an **exact partition** here, unlike `/looks` — a template is in exactly one
     * family by construction — so the filter can be served either side. It is passed through to the
     * server so a deep link lands filtered without the client having to hold the whole list.
     */
    templateList: build.query<TemplateSummary[], { projectId: number; family?: AttributeFamily }>({
      query: ({ projectId, family }) =>
        family
          ? `projects/${projectId}/templates?family=${family}`
          : `projects/${projectId}/templates`,
      providesTags: (_result, _error, { projectId }) => [
        { type: 'TemplateList', id: projectId },
        'TemplateList',
      ],
    }),

    // A single-template read stood here. The editor opens from a `templateList` row and the
    // list carries the whole summary, so nothing fetched one on its own.

    createTemplate: build.mutation<TemplateSummary, { projectId: number } & TemplateInput>({
      query: ({ projectId, ...body }) => ({
        url: `projects/${projectId}/templates`,
        method: 'POST',
        body,
      }),
      // Guarded on the result: a create can fail on a blank name (400), a duplicate (409) or any of
      // the four write-boundary rules (400), and invalidating then refetches to learn nothing moved.
      invalidatesTags: (result) => (result == null ? [] : ['TemplateList']),
    }),

    /**
     * PUT a template.
     *
     * Omit `rows` (or `effect`) for a metadata-only edit; sending either replaces that half. Send
     * **at most one of the two** — which half a template holds is fixed at creation, and a PUT
     * naming the other is a 400. A contents edit is a **live write** — the server republishes every
     * cue layering this template and the programmer's own stack — so `Cue` / `CueList` go with it.
     */
    saveTemplate: build.mutation<
      TemplateSummary,
      { projectId: number; templateId: number } & TemplateInput
    >({
      query: ({ projectId, templateId, ...body }) => ({
        url: `projects/${projectId}/templates/${templateId}`,
        method: 'PUT',
        body,
      }),
      invalidatesTags: (result) =>
        result == null ? [] : ['TemplateList', 'Cue', 'CueList'],
    }),

    deleteTemplate: build.mutation<
      void,
      { projectId: number; templateId: number; force?: boolean }
    >({
      query: ({ projectId, templateId, force }) => ({
        url: `projects/${projectId}/templates/${templateId}${force ? '?force=true' : ''}`,
        method: 'DELETE',
      }),
      // Guarded on the *error*, not the result: a 204 carries no body. A TEMPLATE_IN_USE 409 is an
      // ordinary step (it opens "delete anyway") and nothing was deleted. A forced delete does
      // remove cue layers, hence the cue tags.
      invalidatesTags: (_result, error) =>
        error != null ? [] : ['TemplateList', 'CueList', 'Cue'],
    }),

    /**
     * Copy a template — into this project (the sheet's Duplicate, with a `(Copy n)` name) or another
     * (*Copy to…*). lighting7's route since the library-sheets plan's session 0, on `copyLook`'s
     * model: a fresh uuid and fresh row uuids, `lastPressedAt` cleared, a name clash a 409.
     *
     * The copy lands in the **target** project's library, so only that list is invalidated. Unlike
     * `copyLook` there is no `Fixture` / `GroupList` beside it: those carry Look compatibility, and a
     * template has none (capability-only, D6). The server also fires `templateListChanged`, which
     * the bridge turns into the same invalidation for every other window.
     */
    copyTemplate: build.mutation<CopyTemplateResponse, { projectId: number; templateId: number } & CopyTemplateRequest>({
      query: ({ projectId, templateId, ...body }) => ({
        url: `projects/${projectId}/templates/${templateId}/copy`,
        method: 'POST',
        body,
      }),
      invalidatesTags: (result) => (result == null ? [] : [{ type: 'TemplateList', id: result.targetProjectId }]),
    }),

    /**
     * The editor's "resolves to" panel — the **same** resolver the cook runs, asked about a draft.
     *
     * A mutation rather than a query despite being a read, because the question is "resolve *this*
     * body", which has no stable cache key worth holding: the editor asks again on every change and
     * the answer is only interesting for the draft in front of you.
     */
    resolveTemplate: build.mutation<
      TemplateResolveResponse,
      { projectId: number } & TemplateResolveRequest
    >({
      query: ({ projectId, ...body }) => ({
        url: `projects/${projectId}/templates/resolve`,
        method: 'POST',
        body,
      }),
    }),

    /**
     * **Click**: set literal values in the programmer for the current selection.
     *
     * No dependency — retuning the template later does not move these. That is the whole difference
     * from `toggleTemplate` below, and why the two gestures are two routes rather than a flag.
     *
     * No cache invalidation: the values land in the programmer and surface through the programmer's
     * own WS state, which every consumer already subscribes to.
     */
    applyTemplate: build.mutation<
      ApplyTemplateResponse,
      { projectId: number; templateId: number } & ApplyTemplateRequest
    >({
      query: ({ projectId, templateId, ...body }) => ({
        url: `projects/${projectId}/templates/${templateId}/apply`,
        method: 'POST',
        body,
      }),
      // The acting tab's own fast path onto the recents row. The server stamps this press and
      // broadcasts `templatePressed`, so this is belt as well as braces — but the frame is a
      // round trip away and the chips the operator just pressed are under their hand.
      //
      // **After the response, not before it, and gated on the same rule the server applies**: a
      // click that reaches no head returns 200 having written nothing, and the desk does not stamp
      // that. A provisional clock reading is fine because the frame overwrites it with the
      // server's within the same breath — and it is the server's value every client keeps.
      async onQueryStarted({ templateId }, { dispatch, getState, queryFulfilled }) {
        try {
          const { data } = await queryFulfilled
          if (data.written > 0 || (data.effectIds?.length ?? 0) > 0) {
            applyTemplatePressed(dispatch, getState as typeof store.getState, templateId, new Date().toISOString())
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
    createTemplateFromProgrammer: build.mutation<
      {
        template: TemplateSummary
        isGeneric: boolean
        skipped: { fixtureKey: string; propertyName: string; reason: string }[]
      },
      {
        projectId: number
        name: string
        notes?: string | null
        mask: AttributeFamily[]
        targets: TemplateTarget[]
        source?: 'TOUCHED' | 'ALL' | 'STAGE_SNAPSHOT'
      }
    >({
      query: ({ projectId, ...body }) => ({
        url: `projects/${projectId}/templates/from-programmer`,
        method: 'POST',
        body,
      }),
      invalidatesTags: (result) => (result == null ? [] : ['TemplateList']),
    }),

    /**
     * **⌥click — or a hold on a touchscreen**: add (or remove) a layer that *tracks* the
     * template, targeted at the selection.
     *
     * The layer is the dependency mechanism — it already is, for Looks — so "a colour I can change
     * everywhere later" and "a colour I want right now" are two gestures on one chip rather than two
     * kinds of template.
     */
    toggleTemplate: build.mutation<
      ToggleTemplateResponse,
      { projectId: number; templateId: number } & ToggleTemplateRequest
    >({
      query: ({ projectId, templateId, ...body }) => ({
        url: `projects/${projectId}/templates/${templateId}/toggle`,
        method: 'POST',
        body,
      }),
      // The **on** arm only, for the reason the desk applies the same rule: a second press takes
      // the layer off, and a release is not something you reached for. See `applyTemplate` above
      // for why this runs after the response rather than optimistically.
      async onQueryStarted({ templateId }, { dispatch, getState, queryFulfilled }) {
        try {
          const { data } = await queryFulfilled
          if (data.action === 'applied') {
            applyTemplatePressed(dispatch, getState as typeof store.getState, templateId, new Date().toISOString())
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
  useTemplateListQuery,
  useCreateTemplateFromProgrammerMutation,
  useCreateTemplateMutation,
  useSaveTemplateMutation,
  useDeleteTemplateMutation,
  useCopyTemplateMutation,
  useResolveTemplateMutation,
  useApplyTemplateMutation,
  useToggleTemplateMutation,
} = templatesApi
