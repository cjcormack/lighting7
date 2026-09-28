// @vitest-environment jsdom
import { act, cleanup, render } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const saveLook = vi.hoisted(() => vi.fn(() => ({ unwrap: () => Promise.resolve({}) })))
const LOOKS = vi.hoisted(() => ({
  3: {
    id: 3,
    rows: [{ targetType: 'fixture', targetKey: 'a', propertyName: 'dimmer', value: '10' }],
  },
  4: {
    id: 4,
    rows: [{ targetType: 'fixture', targetKey: 'b', propertyName: 'dimmer', value: '20' }],
  },
}))
/** Which layer the grid is pointed at — mutable, because moving the focus is a scenario. */
const focused = vi.hoisted(() => ({ current: 7 }))
/** The last `useLookQuery` call, so the TEMPLATE case can assert the fetch was skipped. */
const lookQuery = vi.hoisted(() => ({ last: null as { lookId: number; skip?: boolean } | null }))

vi.mock('@/store/programmer', () => ({
  useProgrammerLayersQuery: () => ({
    data: [
      {
        layerId: 7,
        source: { kind: 'LOOK', id: 3, uuid: 'u3', name: 'Warm Wash' },
        targets: [],
        propertyMask: null,
      },
      {
        layerId: 8,
        source: { kind: 'LOOK', id: 4, uuid: 'u4', name: 'Cold Wash' },
        targets: [],
        propertyMask: null,
      },
      {
        layerId: 9,
        source: { kind: 'LOOK', id: 4, uuid: 'u4', name: 'Cold Wash' },
        targets: [{ type: 'fixture', key: 'b' }],
        propertyMask: null,
      },
      // A TEMPLATE layer whose `source.id` **collides with LOOK 3** on purpose: the two live in
      // different tables and share an int PK space, so a gate written against `source.id` alone
      // would hand this layer Warm Wash's rows and let the grid edit them.
      {
        layerId: 10,
        source: { kind: 'TEMPLATE', id: 3, uuid: 't3', name: 'Warm Tilt' },
        targets: [],
        propertyMask: null,
      },
    ],
  }),
}))
vi.mock('@/store/looks', () => ({
  // Keyed on the argument, like the real query: a focus change hands back the *new* Look's rows,
  // which is precisely what a teardown flush must not write the outgoing draft into.
  useLookQuery: ({ lookId }: { lookId: number }, opts?: { skip?: boolean }) => {
    lookQuery.last = { lookId, skip: opts?.skip }
    return { data: LOOKS[lookId as keyof typeof LOOKS], isSuccess: true }
  },
  useSaveLookMutation: () => [saveLook],
}))
vi.mock('@/store/fixtures', () => ({ useFixtureListQuery: () => ({ data: [] }) }))
vi.mock('./ProgrammerScope', () => ({
  useProgrammerScope: () => ({ kind: 'layer', layerId: focused.current }),
  focusedLayerId: (scope: { kind: string; layerId?: number } | null) =>
    scope?.kind === 'layer' ? (scope.layerId ?? null) : null,
}))

import { LookRowStoreProvider, useLookRowStore, useLookSaveState } from './LookRowStore'

let captured: ReturnType<typeof useLookRowStore> = null
let saveState = 'clean'

function Probe() {
  captured = useLookRowStore()
  saveState = useLookSaveState()
  return null
}

function draw() {
  return render(
    <LookRowStoreProvider projectId={1}>
      <Probe />
    </LookRowStoreProvider>,
  )
}

beforeEach(() => {
  vi.useFakeTimers()
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.clearAllMocks()
  captured = null
  focused.current = 7
  lookQuery.last = null
})

describe('LookRowStoreProvider', () => {
  it('exposes the focused layer and its committed rows', () => {
    draw()
    expect(captured?.lookId).toBe(3)
    expect(captured?.lookName).toBe('Warm Wash')
    // `targets: []` means "the Look's own targets", not "no targets" — reading it as an empty set
    // would dim every row in the grid on an ordinary bound layer.
    expect(captured?.targetedKeys).toBeNull()
  })

  it('coalesces a burst of commits into one save', () => {
    // A cell drag commits at ~30 Hz. Each save invalidates the fixture and group lists *and*
    // republishes the Look's live consumers, so the cadence is how often the rig moves — not just
    // how much traffic there is.
    draw()
    act(() => {
      for (let i = 0; i < 20; i++) captured?.setValue('a', 'dimmer', String(100 + i))
    })
    expect(saveLook).not.toHaveBeenCalled()

    act(() => void vi.advanceTimersByTime(400))
    expect(saveLook).toHaveBeenCalledTimes(1)
    expect(saveLook).toHaveBeenCalledWith({
      projectId: 1,
      lookId: 3,
      rows: [{ targetType: 'fixture', targetKey: 'a', propertyName: 'dimmer', value: '119' }],
    })
  })

  it('persists progressively through a long drag rather than only at the end', () => {
    // The ceiling. Without it a slow, continuous drag would hold everything back until the
    // operator let go, and a page closed mid-gesture would lose the lot.
    draw()
    act(() => {
      for (let tick = 0; tick < 12; tick++) {
        captured?.setValue('a', 'dimmer', String(100 + tick))
        vi.advanceTimersByTime(200)
      }
    })
    expect(saveLook).toHaveBeenCalled()
  })

  it('reports dirty before the save and clean after it', async () => {
    draw()
    act(() => captured?.setValue('a', 'dimmer', '200'))
    expect(saveState).toBe('dirty')
    await act(async () => {
      vi.advanceTimersByTime(400)
      await Promise.resolve()
    })
    // Clean, even though the draft still holds the entry: it is retired against *server* rows, so
    // it stays full until the refetch lands, and reading its size here would report a
    // freshly-saved Look as unsaved.
    expect(saveState).toBe('clean')
  })

  it('flushes a pending edit against the look it was made in, not the one just focused', () => {
    // The teardown flush runs *after* the render that moved the focus, so anything reading "the
    // current look" at that point is already reading the next one — and would write this draft into
    // a Look the operator never touched. Both halves are asserted: it saves, and it saves to 3.
    const { rerender } = draw()
    act(() => captured?.setValue('a', 'dimmer', '200'))
    expect(saveLook).not.toHaveBeenCalled()

    focused.current = 8
    act(() => {
      rerender(
        <LookRowStoreProvider projectId={1}>
          <Probe />
        </LookRowStoreProvider>,
      )
    })

    expect(saveLook).toHaveBeenCalledTimes(1)
    expect(saveLook).toHaveBeenCalledWith({
      projectId: 1,
      lookId: 3,
      rows: [{ targetType: 'fixture', targetKey: 'a', propertyName: 'dimmer', value: '200' }],
    })
  })

  it('refuses a write to a fixture outside the layer targets', () => {
    // The paint guard (`pointer-events-none`) only stops a *click*; the marquee selects by
    // rectangle off the rows wrapper, so a batch commit can still reach an untargeted row.
    // Widening a layer has to stay an explicit press.
    focused.current = 9
    draw()
    expect(captured?.targetedKeys).toEqual(new Set(['b']))
    act(() => captured?.setValue('a', 'dimmer', '200'))
    act(() => void vi.advanceTimersByTime(400))
    expect(saveLook).not.toHaveBeenCalled()

    // …and the targeted one still writes, so this is a filter and not a lock.
    act(() => captured?.setValue('b', 'dimmer', '200'))
    act(() => void vi.advanceTimersByTime(400))
    expect(saveLook).toHaveBeenCalledTimes(1)
  })

  it('stays disengaged for a focused TEMPLATE layer, whatever id it carries', () => {
    // **The LOOK-only gate.** A template is one family of intents edited in its own family-native
    // editor; projecting its generic row onto every targeted row would silently convert it to a
    // per-fixture one on the first edit. `LayerRowNotices` explains that in the grid — it does not
    // enforce it, this does. Asserted on the store's own output rather than on the notice, so the
    // pin survives any rewording there.
    //
    // Layer 10 names TEMPLATE 3 while layer 7 names LOOK 3, so the tempting simplification of
    // `layer?.source.kind === 'LOOK' ? layer.source.id : undefined` to `layer?.source.id` fails
    // here and nowhere else in this file.
    focused.current = 10
    draw()
    expect(captured).toBeNull()
    expect(lookQuery.last).toEqual({ lookId: 0, skip: true })

    // …and no write can reach a Look through it: there is no `setValue` to call, and nothing is
    // saved when the debounce would have fired.
    act(() => captured?.setValue('a', 'dimmer', '200'))
    act(() => void vi.advanceTimersByTime(2500))
    expect(saveLook).not.toHaveBeenCalled()
  })

  it('flushes a pending edit on unmount rather than dropping it', () => {
    // Leaving the layer — or the page — must not discard a value the operator set.
    const { unmount } = draw()
    act(() => captured?.setValue('a', 'dimmer', '200'))
    expect(saveLook).not.toHaveBeenCalled()
    act(() => unmount())
    expect(saveLook).toHaveBeenCalledTimes(1)
  })
})
