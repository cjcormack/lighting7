import { createContext, useContext, useEffect, useMemo, useRef, useState } from 'react'
import { useProgrammerLayersQuery } from '@/store/programmer'
import type { ReactNode } from 'react'

/**
 * What the programmer's value grid is currently pointed at.
 *
 * Three readings of the same rig, through the same grid, the same cell editors and the same
 * drag-select — only the band above it and the dimmed columns change. That sameness is the point:
 * it is what makes editing a Look *feel* local rather than feel like a trip to a library.
 *
 * - `output` — the cook, read-only. Every fixture, every column, tinted by which stack entry won
 *   it. Clicking a tinted cell jumps the scope to its owner, which is what finally makes the
 *   ownership colours navigational rather than decorative.
 * - `local` — what the operator set and nothing else, so "what will Record take?" is something to
 *   look at rather than a colour to trust.
 * - `layer` — one Look layer's own rows, editable in place.
 *
 * Keyed by **`layerId`, not `lookId`**: two layers in one stack may apply the same Look at
 * different amounts or masks, and focusing "the Warm Wash layer" has to mean one of them.
 */
export type ProgrammerScope =
  | { kind: 'output' }
  | { kind: 'local' }
  | { kind: 'layer'; layerId: number }

const LOCAL_SCOPE: ProgrammerScope = { kind: 'local' }
const OUTPUT_SCOPE: ProgrammerScope = { kind: 'output' }

export interface ProgrammerScopeActions {
  setScope: (next: ProgrammerScope) => void
  /**
   * Point the grid at a programmer layer **if the programmer's stack actually holds it**, and say
   * whether it did.
   *
   * Guarded here rather than at the call site for two reasons. It is called from a grid cell, and
   * asking every visible row to subscribe to the layer list to answer it would be a query
   * subscription per row for a question the provider already has the answer to. And the guard is
   * not obvious: `ProvenanceEntry.layerId` is present for a **cue's** layers as well as the
   * programmer's, so a cell lit by a cue's Warm Wash reports a `layerId` that names nothing in
   * this stack — jumping there would land the grid on a layer that does not exist.
   */
  focusLayer: (layerId: number) => boolean
}

/**
 * Value and actions are **separate contexts** so the setter's identity can be stable while the
 * scope changes: the grid's row views read the value and re-render on every switch, but the
 * cell-click handler that *sets* it must not be a new function each time or every memoised row
 * would re-render on unrelated scope traffic.
 */
const ScopeValueContext = createContext<ProgrammerScope | null>(null)
const ScopeActionsContext = createContext<ProgrammerScopeActions | null>(null)

/**
 * The scope, or `null` outside a provider.
 *
 * Nullable on purpose, following `useLookDraft()`: the read seam lands in `FixturesTable`, which
 * `/fixtures` and `/groups` also mount, and those two must not have to be told about a concept
 * they don't have. A `null` scope means "the plain fixtures list" — behave exactly as before.
 */
export function useProgrammerScope(): ProgrammerScope | null {
  return useContext(ScopeValueContext)
}

export function useProgrammerScopeActions(): ProgrammerScopeActions | null {
  return useContext(ScopeActionsContext)
}

/**
 * Owns the scope for one programmer page.
 *
 * **Deliberately not persisted.** `usePersistentState` would mean landing on the page focused on a
 * layer another tab removed while you were away — a bad first frame, and the fallback effect below
 * would then bounce the operator to Output for reasons they never saw.
 *
 * **Deliberately not an `EditorContextValue` arm.** That type is a *write-routing* discriminator
 * with about fifteen consumers, and Output and Local differ only in what is **read** — they are
 * the same write target, so they would be two arms indistinguishable to every consumer but one.
 * It also has to carry a setter, which would defeat the memo `EditorContextProvider` exists for.
 * `ProgrammerGrid` derives a `lookLayer` editor context from this instead.
 */
export function ProgrammerScopeProvider({ children }: { children: ReactNode }) {
  const [scope, setScope] = useState<ProgrammerScope>(LOCAL_SCOPE)
  const { data: layers } = useProgrammerLayersQuery()

  // Through a ref so the actions object stays identity-stable across layer-stack broadcasts: it is
  // read by every visible row, and a fresh setter each frame would defeat their memoisation.
  const layersRef = useRef(layers)
  layersRef.current = layers

  const actions = useMemo<ProgrammerScopeActions>(
    () => ({
      setScope: (next) => setScope((prev) => (scopesEqual(prev, next) ? prev : next)),
      focusLayer: (layerId) => {
        if (!layersRef.current?.some((l) => l.layerId === layerId)) return false
        setScope((prev) => (prev.kind === 'layer' && prev.layerId === layerId ? prev : { kind: 'layer', layerId }))
        return true
      },
    }),
    [],
  )

  // The programmer is shared, and the layer stack arrives as a broadcast — a second desk can
  // remove the layer this grid is pointed at. Fall back rather than rendering a scope with no
  // subject.
  useEffect(() => {
    if (scope.kind !== 'layer' || layers === undefined) return
    if (!layers.some((l) => l.layerId === scope.layerId)) setScope(OUTPUT_SCOPE)
  }, [scope, layers])

  return (
    <ScopeActionsContext.Provider value={actions}>
      <ScopeValueContext.Provider value={scope}>{children}</ScopeValueContext.Provider>
    </ScopeActionsContext.Provider>
  )
}

function scopesEqual(a: ProgrammerScope, b: ProgrammerScope): boolean {
  if (a.kind !== b.kind) return false
  return a.kind !== 'layer' || b.kind !== 'layer' || a.layerId === b.layerId
}

/** The focused layer's id, or null in any other scope. */
export function focusedLayerId(scope: ProgrammerScope | null): number | null {
  return scope?.kind === 'layer' ? scope.layerId : null
}
