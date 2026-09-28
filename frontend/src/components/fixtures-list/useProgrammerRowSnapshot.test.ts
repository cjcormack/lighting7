// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { act, renderHook } from '@testing-library/react'

/**
 * A live-subscription harness rather than `backendMock`'s no-op `subscribeToKey`: this suite is
 * about the subscription mechanics themselves — what the hook recomputes, and *when* — so the
 * per-key callbacks and the api's own advancing state have to be real.
 */
const harness = vi.hoisted(() => {
  const entries = new Map<string, { value: string }>()
  const keySubscribers = new Map<string, Set<() => void>>()
  const stateSubscribers = new Set<(state: { blind: boolean }) => void>()
  let blind = false
  return {
    entries,
    keySubscribers,
    stateSubscribers,
    isBlind: () => blind,
    /** Advance the api's own map — and only `notify: true` announces it, like the real client. */
    setEntry(targetKey: string, propertyName: string, value: string, opts: { notify?: boolean } = {}) {
      const id = `${targetKey}|${propertyName}`
      entries.set(id, { value })
      if (opts.notify) for (const fn of [...(keySubscribers.get(id) ?? [])]) fn()
    },
    /** A whole-state push — every provenance frame looks like this to the blind filter. */
    pushState(patch: { blind?: boolean } = {}) {
      if (patch.blind !== undefined) blind = patch.blind
      for (const fn of [...stateSubscribers]) fn({ blind })
    },
    subscriberCount(targetKey: string, propertyName: string) {
      return keySubscribers.get(`${targetKey}|${propertyName}`)?.size ?? 0
    },
    reset() {
      entries.clear()
      keySubscribers.clear()
      stateSubscribers.clear()
      blind = false
    },
  }
})

vi.mock('@/api/lightingApi', () => ({
  lightingApi: {
    programmer: {
      isBlind: harness.isBlind,
      getKeyState: (targetKey: string, propertyName: string) => ({
        entry: harness.entries.get(`${targetKey}|${propertyName}`),
      }),
      subscribeToKey: (targetKey: string, propertyName: string, fn: () => void) => {
        const id = `${targetKey}|${propertyName}`
        let set = harness.keySubscribers.get(id)
        if (!set) {
          set = new Set()
          harness.keySubscribers.set(id, set)
        }
        set.add(fn)
        return { unsubscribe: () => set!.delete(fn) }
      },
      subscribe: (fn: (state: { blind: boolean }) => void) => {
        harness.stateSubscribers.add(fn)
        return { unsubscribe: () => harness.stateSubscribers.delete(fn) }
      },
    },
  },
}))

import { lightingApi } from '@/api/lightingApi'
import { useProgrammerRowSnapshot } from './useProgrammerRowSnapshot'
import type { CellPropertyKey, RowCell } from './useRowValues'
import type { ColumnKey } from './columns'

function cellFor(col: ColumnKey, keys: CellPropertyKey[]): RowCell {
  return { col, resolutions: [], targetKeys: [], keys }
}

const EMPTY_SNAPSHOT: Record<string, string | undefined> = {}

/** A stand-in consumer aggregation: read each covered key straight off the api, as both do. */
function computeEntryValues(cells: readonly RowCell[]): Record<string, string | undefined> {
  const out: Record<string, string | undefined> = {
    blind: lightingApi.programmer.isBlind() ? 'blind' : undefined,
  }
  for (const cell of cells) {
    for (const key of cell.keys) {
      out[`${key.targetKey}|${key.propertyName}`] = lightingApi.programmer.getKeyState(
        key.targetKey,
        key.propertyName,
      ).entry?.value
    }
  }
  return out
}

const CELLS: readonly RowCell[] = [cellFor('dimmer', [{ targetKey: 'f1', propertyName: 'dimmer' }])]
const OFF: readonly RowCell[] = []

function render(initialCells: readonly RowCell[] = CELLS) {
  return renderHook(
    ({ cells }: { cells: readonly RowCell[] }) =>
      useProgrammerRowSnapshot(cells, EMPTY_SNAPSHOT, computeEntryValues),
    { initialProps: { cells: initialCells } },
  )
}

beforeEach(() => harness.reset())

describe('useProgrammerRowSnapshot', () => {
  it('recomputes on a per-key notification', () => {
    harness.setEntry('f1', 'dimmer', '10')
    const rendered = render()
    expect(rendered.result.current['f1|dimmer']).toBe('10')

    act(() => harness.setEntry('f1', 'dimmer', '20', { notify: true }))
    expect(rendered.result.current['f1|dimmer']).toBe('20')
  })

  it('serves fresh state when re-entering after an off window', () => {
    // The S1 stale-snapshot bug (FS-BUG-STALE-ROW-SNAPSHOT): the version counter only advances
    // from notifications received *while subscribed*, and a change during an off window is never
    // re-announced — the api's diffing is against its own maps, which kept moving. Under blind
    // the stale snapshot's `staged` renders as the cell's value and the editors seed from it, so
    // an operator nudging the cell commits a value derived from a lie.
    harness.setEntry('f1', 'dimmer', '10')
    const rendered = render()
    expect(rendered.result.current['f1|dimmer']).toBe('10')

    // Off: the empty-cells-means-off contract — everything unsubscribes, the frozen empty back.
    rendered.rerender({ cells: OFF })
    expect(rendered.result.current).toBe(EMPTY_SNAPSHOT)
    expect(harness.subscriberCount('f1', 'dimmer')).toBe(0)

    // The change lands while nobody is listening (a locate, an Include, a second tab busking).
    harness.setEntry('f1', 'dimmer', '200')

    // Back on with the SAME cells identity — the grid never remounts, and rows survive a scope
    // switch. The snapshot must reflect the off-window change, not the pre-switch grid.
    rendered.rerender({ cells: CELLS })
    expect(rendered.result.current['f1|dimmer']).toBe('200')
  })

  it('keeps snapshot identity across pushes that change nothing it watches', () => {
    // The cached-identity contract the consumers rely on: an unrelated provenance push (a
    // whole-state frame with blind unchanged) must not recompute, or every mounted row would
    // re-render on every cue change — the cost the per-key split exists to avoid.
    harness.setEntry('f1', 'dimmer', '10')
    const rendered = render()
    const first = rendered.result.current

    act(() => harness.pushState())
    rendered.rerender({ cells: CELLS })
    expect(rendered.result.current).toBe(first)
  })

  it('recomputes on a blind transition, and only on the transition', () => {
    harness.setEntry('f1', 'dimmer', '10')
    const rendered = render()
    expect(rendered.result.current.blind).toBeUndefined()

    act(() => harness.pushState({ blind: true }))
    expect(rendered.result.current.blind).toBe('blind')

    // A second frame with blind still on is filtered out.
    const afterTransition = rendered.result.current
    act(() => harness.pushState({ blind: true }))
    expect(rendered.result.current).toBe(afterTransition)
  })

  it('registers no subscriptions at all while off', () => {
    const rendered = render(OFF)
    expect(rendered.result.current).toBe(EMPTY_SNAPSHOT)
    expect(harness.subscriberCount('f1', 'dimmer')).toBe(0)
    expect(harness.stateSubscribers.size).toBe(0)
    rendered.unmount()
  })
})
