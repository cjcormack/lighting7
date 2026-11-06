import { useCallback, useMemo, useSyncExternalStore } from 'react'
import { createSyncStore, sessionStorageArea, type SyncStore } from './syncStore'

/**
 * Which groups and multi-head fixtures a list has open — per window, per list, per project
 * (programmer-fixes plan D9). One `sessionStorage` entry per project and selection scope, so the
 * programmer, Fixtures and Groups lists each keep their own, a show's same-named groups never open
 * another show's rows, and the rows a list had open are still open after navigating away and back,
 * or a reload.
 *
 * The entry holds group names and fixture keys, the two sets `buildRows` takes. A name the rig no
 * longer has is ignored on read and dropped on the next write, once the rig has loaded (`known`),
 * so the entry never outgrows the rig.
 */
export interface ListExpansion {
  groups: readonly string[]
  fixtures: readonly string[]
}

export type ExpansionScope = 'programmer' | 'fixtures' | 'groups'

/** The rig's group names and fixture keys, or null while it loads. */
export interface KnownRig {
  groups: ReadonlySet<string>
  fixtures: ReadonlySet<string>
}

const EMPTY: ListExpansion = { groups: [], fixtures: [] }

export function expansionKey(projectId: number, scope: ExpansionScope): string {
  return `fixturesList.expansion.${projectId}.${scope}`
}

/** Narrow a stored value: two string arrays, anything else read as empty. */
export function parseListExpansion(parsed: unknown): ListExpansion {
  if (parsed == null || typeof parsed !== 'object') return EMPTY
  const strings = (value: unknown): string[] =>
    Array.isArray(value) ? [...new Set(value.filter((v): v is string => typeof v === 'string'))] : []
  const record = parsed as Record<string, unknown>
  return { groups: strings(record.groups), fixtures: strings(record.fixtures) }
}

const stores = new Map<string, SyncStore<ListExpansion>>()

function storeFor(projectId: number, scope: ExpansionScope): SyncStore<ListExpansion> {
  const key = expansionKey(projectId, scope)
  let store = stores.get(key)
  if (store == null) {
    store = createSyncStore({ key, fallback: EMPTY, parse: parseListExpansion, storage: sessionStorageArea })
    stores.set(key, store)
  }
  return store
}

/** Test seam: forget every store, so each test reads storage afresh. */
export function resetListExpansionStores(): void {
  for (const store of stores.values()) store.reset()
  stores.clear()
}

type SetUpdate = (prev: ReadonlySet<string>) => ReadonlySet<string>

export interface UseListExpansion {
  expandedGroups: ReadonlySet<string>
  expandedFixtures: ReadonlySet<string>
  setExpandedGroups: (update: SetUpdate) => void
  setExpandedFixtures: (update: SetUpdate) => void
}

const keep = (names: readonly string[], known: ReadonlySet<string> | undefined): readonly string[] =>
  known == null ? names : names.filter((name) => known.has(name))

/**
 * The list's open rows, as the `useState` pair they replace: each setter takes an updater, applied
 * to the stored value at the moment it is called, so several in one effect compose.
 */
export function useListExpansion(projectId: number, scope: ExpansionScope, known: KnownRig | null): UseListExpansion {
  const store = storeFor(Number.isFinite(projectId) ? projectId : -1, scope)
  const stored = useSyncExternalStore(store.subscribe, store.getSnapshot, store.getServerSnapshot)
  const expandedGroups = useMemo(() => new Set(keep(stored.groups, known?.groups)), [stored.groups, known])
  const expandedFixtures = useMemo(() => new Set(keep(stored.fixtures, known?.fixtures)), [stored.fixtures, known])

  const write = useCallback(
    (part: keyof ListExpansion, update: SetUpdate) => {
      const current = store.getSnapshot()
      const next = [...update(new Set(current[part]))]
      const groups = keep(part === 'groups' ? next : current.groups, known?.groups)
      const fixtures = keep(part === 'fixtures' ? next : current.fixtures, known?.fixtures)
      if (sameNames(groups, current.groups) && sameNames(fixtures, current.fixtures)) return
      store.set({ groups, fixtures })
    },
    [store, known],
  )
  const setExpandedGroups = useCallback((update: SetUpdate) => write('groups', update), [write])
  const setExpandedFixtures = useCallback((update: SetUpdate) => write('fixtures', update), [write])

  return { expandedGroups, expandedFixtures, setExpandedGroups, setExpandedFixtures }
}

function sameNames(a: readonly string[], b: readonly string[]): boolean {
  return a.length === b.length && a.every((name, i) => name === b[i])
}
