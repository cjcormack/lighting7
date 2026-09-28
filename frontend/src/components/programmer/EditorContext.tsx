import { createContext, useContext, useMemo, type ReactNode } from 'react'

/**
 * `kind: 'live'` writes go to Layer 4 (direct stage).
 *
 * **There is no `cue` arm.** A cue is read-only and edited by Include, so that there is exactly one
 * place values are set (D2 of the desk-simplification plan). Session 2a made that true and left the
 * arm in place, because 2b — the Run/Show merge — was where it would be decided whether an unlocked
 * cue row got editable cells back. It did not: giving a cue row cells would reopen the question D2
 * settled and make a cue and the programmer two places again. So the arm, its four session
 * helpers, `api/cueEditWsApi.ts` and the fifteen `kind === 'cue'` branches across five hooks were
 * removed in 2b.
 *
 * The `409 CUE_EDIT_SESSION_OPEN` handling outlived the arm for a while, on the argument that the
 * backend protocol was still live and another client could hold a session. Backend sweep item D1
 * retired the whole `cueEdit.*` family — the fifteen socket messages, all four 409 guards and
 * `GET /perf/cueedit-histogram` — so nothing can open one and that response can no longer arrive.
 * The client's `force` senders, both "do it anyway" buttons and the Diagnostics histogram panel
 * went with it. Update's remaining 409, `INCLUDE_TARGET_GONE`, is unrelated and still live.
 *
 * There was a `kind: 'look'` arm, whose writes reached neither: they landed in `LookDraftContext`,
 * because the library's Look editor worked against a *synthetic fixture* built from
 * `editorFixtureType` and its rows were deferred — there was no head on stage to write to until a
 * layer supplied one. Session 3 deleted all of it. Removing the fixture type removes the thing that
 * grid was built out of, and what it was really editing is a **template**, which now has its own
 * family-native editor and no property grid at all.
 *
 * `kind: 'lookLayer'` is the programmer grid focused on one of its Look layers. It writes *bound*
 * rows keyed by (fixture, property) and does so live — the save republishes every cue layering that
 * Look, which is the entire point of composing in place. It carries both ids because the layer says
 * which stack line is focused and the Look says what gets written. A focused **template** layer has
 * no arm here on purpose: a template is one family of intents, and projecting its generic row onto
 * every targeted row would silently convert it to a per-fixture one on the first edit.
 */
export type EditorContextValue =
  | { kind: 'live' }
  | { kind: 'lookLayer'; layerId: number; lookId: number }

const defaultValue: EditorContextValue = { kind: 'live' }

const EditorContext = createContext<EditorContextValue>(defaultValue)

export function EditorContextProvider({
  value,
  children,
}: {
  value: EditorContextValue
  children: ReactNode
}) {
  // Tolerate callers that pass a fresh object literal on every render — only rebroadcast
  // when a field consumers actually care about changes.
  const kind = value.kind
  const lookId = value.kind === 'lookLayer' ? value.lookId : null
  const layerId = value.kind === 'lookLayer' ? value.layerId : null
  // `value` is deliberately not a dependency: depending on it is exactly the
  // per-render rebroadcast this memo exists to absorb.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const stable = useMemo(() => value, [kind, lookId, layerId])
  return <EditorContext.Provider value={stable}>{children}</EditorContext.Provider>
}

export function useEditorContext(): EditorContextValue {
  return useContext(EditorContext)
}
