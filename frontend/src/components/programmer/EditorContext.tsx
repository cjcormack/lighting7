import { createContext, useContext, useMemo, type ReactNode } from 'react'

/**
 * `kind: 'live'` writes go to Layer 4 (direct stage).
 *
 * **The `cue` arm writes the stored cue, never the rig** (show-cue-editing plan P2; its own paragraph
 * below). It is not the arm the Run/Show merge (session 2b) removed, which wrote a cue *live* through
 * a `cueEdit.*` session on the rig and made a cue and the programmer two places to set a value on
 * stage. That arm, its four session helpers, `api/cueEditWsApi.ts` and the fifteen branches it fed
 * went in 2b, and backend sweep item D1 retired the whole `cueEdit.*` family — the socket messages,
 * the `409 CUE_EDIT_SESSION_OPEN` guards and `GET /perf/cueedit-histogram` — so it cannot come back
 * by accident. Update's remaining 409, `INCLUDE_TARGET_GONE`, is unrelated and still live.
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
 *
 * **`kind: 'cue'` writes the stored cue**: the cue editor's Values grid (show-cue-editing plan
 * §3.3, session 5) lands set, colour, position and clear in its `CueRowStore`, which PATCHes the cue's
 * own rows and sends nothing to the stage (P2) — a live cue lands the edit on its next first hold.
 * `useCellWriters` has **no live fallback** on this arm: a write with no store goes nowhere. `entryId`
 * names the place the editor was opened from; the Values grid reads nothing of it yet, and it is in
 * the memo keys so a host that does is told when it moves.
 */
export type EditorContextValue =
  | { kind: 'live' }
  | { kind: 'lookLayer'; layerId: number; lookId: number }
  | { kind: 'cue'; cueId: number; entryId?: number | null }

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
  const cueId = value.kind === 'cue' ? value.cueId : null
  const entryId = value.kind === 'cue' ? (value.entryId ?? null) : null
  // `value` is deliberately not a dependency: depending on it is exactly the
  // per-render rebroadcast this memo exists to absorb. The keys are every field of every arm.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const stable = useMemo(() => value, [kind, lookId, layerId, cueId, entryId])
  return <EditorContext.Provider value={stable}>{children}</EditorContext.Provider>
}

export function useEditorContext(): EditorContextValue {
  return useContext(EditorContext)
}
