import { useCallback, useMemo, useRef, useSyncExternalStore } from 'react'
import { lightingApi } from '../../api/lightingApi'
import type { CellPropertyKey, RowCell } from './useRowValues'

/**
 * The per-row programmer subscription mechanism, shared by `useRowOwnership` and the Local
 * scope's value hook: one subscription set per row (not per cell), keyed on a stable signature
 * string so rebuilding the row list doesn't churn subscriptions, and a cached snapshot identity
 * so an unrelated programmer push never re-renders the row.
 *
 * The subscriptions are per `(target, property)` rather than whole-state, because
 * `provenanceState` is a *full* snapshot pushed on every layer event — a cue change would
 * otherwise wake every mounted row in the sheet. Blind is the one global both consumers care
 * about (it decides whether ownership stages a value, and whether Local's rows are showing one),
 * so the whole-state channel is watched for *just* that transition.
 *
 * An empty cell list means **off**: no subscriptions at all, and the frozen [emptySnapshot]
 * back. FixturesTable leans on this from both ends — the plain Fixtures/Groups lists pass empty
 * ownership cells, and `useScopedRowValues` switches its scope hooks off by handing them empty
 * cell lists. Registering even the blind listener while off would put every mounted row of a
 * several-hundred-row table on the global programmer channel, waking them all on every cue
 * change and effect start — the exact cost the per-key split exists to avoid.
 *
 * [computeSnapshot] is the consumer's aggregation, reading `lightingApi.programmer` directly.
 * It and [emptySnapshot] must both be referentially stable (module-level): a per-render compute
 * closure would rebuild `getSnapshot` every render for nothing the cache could tell apart, and
 * an inline `{}` for the empty snapshot would hand `getSnapshot` a fresh object on every call
 * while the hook is off — the common state for both consumers — tripping React's
 * "getSnapshot should be cached" loop detection.
 */
export function useProgrammerRowSnapshot<T>(
  cells: readonly RowCell[],
  emptySnapshot: T,
  computeSnapshot: (cells: readonly RowCell[]) => T,
): T {
  const keysSignature = useMemo(() => signatureOf(cells), [cells])

  const subscribedKeys = useMemo<CellPropertyKey[]>(
    () => dedupeKeys(cells),
    // Keyed on the signature, not `cells`: buildRows mints fresh Row objects on every filter
    // keystroke, and re-registering every subscription for every mounted row would make
    // typing in the filter box quadratic.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [keysSignature],
  )

  const cachedRef = useRef<{
    cells: readonly RowCell[]
    keys: readonly CellPropertyKey[]
    version: number
    snapshot: T
  } | null>(null)
  const versionRef = useRef(0)

  const subscribe = useCallback(
    (callback: () => void) => {
      if (subscribedKeys.length === 0) return () => {}

      // Registration itself invalidates: a notification can land between a row's render and
      // this passive effect (React yields under a time-sliced scope switch), and it would
      // otherwise be lost — the row wasn't subscribed yet, and nothing re-announces it. The
      // post-subscribe snapshot check then recomputes once, which is the price of never
      // serving that gap's value.
      versionRef.current += 1

      const bump = () => {
        versionRef.current += 1
        callback()
      }
      const subscriptions = subscribedKeys.map((key) =>
        lightingApi.programmer.subscribeToKey(key.targetKey, key.propertyName, bump),
      )
      // Blind is global rather than per-key, and flipping it changes what every cell displays.
      // Filter to *just* that transition — the whole-state channel also fires on every
      // provenance push, and reacting to those here would undo the per-key split.
      let lastBlind = lightingApi.programmer.isBlind()
      subscriptions.push(
        lightingApi.programmer.subscribe((state) => {
          if (state.blind === lastBlind) return
          lastBlind = state.blind
          bump()
        }),
      )
      return () => subscriptions.forEach((s) => s.unsubscribe())
    },
    [subscribedKeys],
  )

  const getSnapshot = useCallback((): T => {
    if (subscribedKeys.length === 0) return emptySnapshot
    // Cache on the programmer version AND the cell identity (a repatch or a column toggle
    // changes what to aggregate without any programmer event firing) AND the subscription set's
    // identity. The last one is what heals an off→on cycle: `versionRef` only advances from
    // notifications received *while subscribed*, and a change during an off window is never
    // re-announced (`diffSignatures` diffs against the api's own signature maps, which kept
    // advancing), so a snapshot cached before the hook was switched off would otherwise be
    // served again on re-entry — `cells` identity survives a scope switch, and the grid never
    // remounts. A (re)registered subscription set always arrives as a fresh `subscribedKeys`
    // array, so its identity invalidates during the re-entry render itself, before any stale
    // frame paints; the version bump in `subscribe` closes the remaining render→registration
    // gap.
    const cached = cachedRef.current
    if (
      cached &&
      cached.cells === cells &&
      cached.keys === subscribedKeys &&
      cached.version === versionRef.current
    ) {
      return cached.snapshot
    }
    const snapshot = computeSnapshot(cells)
    cachedRef.current = { cells, keys: subscribedKeys, version: versionRef.current, snapshot }
    return snapshot
  }, [cells, subscribedKeys, emptySnapshot, computeSnapshot])

  return useSyncExternalStore(subscribe, getSnapshot, getSnapshot)
}

function signatureOf(cells: readonly RowCell[]): string {
  return cells
    .map(
      (cell) =>
        `${cell.col}=${cell.keys.map((k) => `${k.targetKey}|${k.propertyName}`).join(',')}`,
    )
    .join(';')
}

function dedupeKeys(cells: readonly RowCell[]): CellPropertyKey[] {
  const seen = new Set<string>()
  const out: CellPropertyKey[] = []
  for (const cell of cells) {
    for (const key of cell.keys) {
      const id = `${key.targetKey}|${key.propertyName}`
      if (seen.has(id)) continue
      seen.add(id)
      out.push(key)
    }
  }
  return out
}
