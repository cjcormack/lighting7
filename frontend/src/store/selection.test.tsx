// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { Provider } from 'react-redux'
import { selectionWs } from '@/test/backendMock'
import { ATTRIBUTE_FAMILIES } from '@/lib/attributeFamily'
import { parseFamilies } from '@/lib/selectionMask'

// lightingApi opens a real WebSocket at import time (jsdom has none). The mock's `selection`
// namespace remembers the subscriber, so `selectionWs.fire` is a `selection.state` frame.
vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())
vi.mock('@/lib/windowIdentity', () => ({ windowName: () => 'Screen 1', useWindowName: () => 'Screen 1' }))

import { lightingApi } from '@/api/lightingApi'
import { decodeSelectionState, sameSelectionSnapshot } from '@/api/selectionApi'
import { store } from './index'
import { restApi } from './restApi'
import {
  clearDeskSelection,
  setDeskSelection,
  subselectDeskSelection,
  toggleDeskSelection,
  useDeskSelection,
  useDeskSelectionSnapshot,
} from './selection'

/**
 * The desk selection's cache entry as one **fact** — targets, mask, mover (multi-screen plan D2,
 * D7) — and the three writes.
 *
 * Two of these are the vocabulary pins the plan asks for: `parseFamilies` against
 * `ATTRIBUTE_FAMILIES`, the way `maskPicker.test.ts` pins `MASK_GROUPS`, so a fifth family fails
 * here and in lighting7's `PropertyMaskTest` at once; and the two absences decoding to "every
 * attribute" and "nobody", which is what the desk's Json means by omitting them.
 */

function wrapper({ children }: { children: ReactNode }) {
  return <Provider store={store}>{children}</Provider>
}

beforeEach(() => {
  selectionWs.last = null
  selectionWs.callback = null
  store.dispatch(restApi.util.resetApiState())
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('the families vocabulary', () => {
  it('is ATTRIBUTE_FAMILIES, in order, and nothing else', () => {
    expect(parseFamilies([...ATTRIBUTE_FAMILIES].reverse().slice(1))).toEqual(
      ATTRIBUTE_FAMILIES.filter((f) => f !== ATTRIBUTE_FAMILIES[ATTRIBUTE_FAMILIES.length - 1]),
    )
    for (const family of ATTRIBUTE_FAMILIES) {
      expect(parseFamilies([family])).toEqual([family])
    }
    // A name outside the vocabulary is dropped, as the desk's lenient parser drops one.
    expect(parseFamilies(['COLOUR', 'GOBO'])).toEqual(['COLOUR'])
    expect(parseFamilies(['GOBO'])).toBeNull()
  })

  it('reads none and all four as no mask — the one spelling', () => {
    expect(parseFamilies(undefined)).toBeNull()
    expect(parseFamilies([])).toBeNull()
    expect(parseFamilies([...ATTRIBUTE_FAMILIES])).toBeNull()
  })
})

describe('decodeSelectionState', () => {
  it('reads absent families as every attribute and absent source as nobody', () => {
    const snapshot = decodeSelectionState(
      { type: 'selection.state', targets: [{ type: 'fixture', key: 'par-1' }] },
      null,
    )
    expect(snapshot).toEqual({ targets: [{ type: 'fixture', key: 'par-1' }], families: null, source: null })
  })

  it('reads the three fields, and drops a source it does not recognise', () => {
    expect(
      decodeSelectionState(
        {
          type: 'selection.state',
          targets: [],
          families: ['COLOUR'],
          source: { kind: 'window', name: 'Screen 2' },
        },
        null,
      ),
    ).toEqual({ targets: [], families: ['COLOUR'], source: { kind: 'window', name: 'Screen 2' } })
    expect(
      decodeSelectionState({ type: 'selection.state', targets: [], source: { kind: 'robot', name: 'x' } }, null)
        .source,
    ).toBeNull()
  })

  it('keeps the previous frame’s identities for the parts that did not move', () => {
    // A source-only frame — another window set the same heads — must hand every reader keyed on
    // `targets` the array it already had, or the bridge's apply effect re-runs for the chip's sake.
    const first = decodeSelectionState(
      { type: 'selection.state', targets: [{ type: 'fixture', key: 'par-1' }], families: ['COLOUR'] },
      null,
    )
    const second = decodeSelectionState(
      {
        type: 'selection.state',
        targets: [{ type: 'fixture', key: 'par-1' }],
        families: ['COLOUR'],
        source: { kind: 'window', name: 'Screen 2' },
      },
      first,
    )
    expect(second.targets).toBe(first.targets)
    expect(second.families).toBe(first.families)
    expect(sameSelectionSnapshot(first, second)).toBe(false)
  })
})

describe('the cache entry', () => {
  it('seeds from the connect snapshot and follows every frame that changes the fact', async () => {
    selectionWs.last = { targets: [{ type: 'fixture', key: 'par-1' }], families: ['COLOUR'], source: null }
    const { result } = renderHook(() => useDeskSelectionSnapshot(), { wrapper })
    await waitFor(() => expect(result.current.targets).toEqual([{ type: 'fixture', key: 'par-1' }]))
    expect(result.current.families).toEqual(['COLOUR'])
    await waitFor(() => expect(selectionWs.callback).not.toBeNull())

    act(() => {
      selectionWs.fire({
        targets: [{ type: 'fixture', key: 'par-1' }],
        families: ['COLOUR'],
        source: { kind: 'window', name: 'Screen 2' },
      })
    })
    await waitFor(() => expect(result.current.source).toEqual({ kind: 'window', name: 'Screen 2' }))
  })

  it('records a source-only frame — the chip’s reader moves while the targets reader does not', async () => {
    selectionWs.last = { targets: [{ type: 'fixture', key: 'par-1' }], families: null, source: null }
    const { result } = renderHook(
      () => ({ snapshot: useDeskSelectionSnapshot(), targets: useDeskSelection() }),
      { wrapper },
    )
    await waitFor(() => expect(result.current.targets).toHaveLength(1))
    await waitFor(() => expect(selectionWs.callback).not.toBeNull())
    const before = result.current.targets
    // Decoded as the WS layer decodes it — against the previous frame, which is what keeps the
    // untouched `targets` array's identity (see `decodeSelectionState`).
    act(() => {
      selectionWs.fire(
        decodeSelectionState(
          {
            type: 'selection.state',
            targets: [{ type: 'fixture', key: 'par-1' }],
            source: { kind: 'surface', name: 'Control surface' },
          },
          result.current.snapshot,
        ),
      )
    })
    await waitFor(() =>
      expect(result.current.snapshot.source).toEqual({ kind: 'surface', name: 'Control surface' }),
    )
    expect(result.current.targets).toBe(before)
  })

  it('is not rewritten for a frame identical in all three parts', async () => {
    selectionWs.last = {
      targets: [{ type: 'fixture', key: 'par-1' }],
      families: ['COLOUR'],
      source: { kind: 'window', name: 'Screen 2' },
    }
    const { result } = renderHook(() => useDeskSelectionSnapshot(), { wrapper })
    await waitFor(() => expect(result.current.targets).toHaveLength(1))
    await waitFor(() => expect(selectionWs.callback).not.toBeNull())
    const before = result.current
    await act(async () => {
      selectionWs.fire({
        targets: [{ type: 'fixture', key: 'par-1' }],
        families: ['COLOUR'],
        source: { kind: 'window', name: 'Screen 2' },
      })
    })
    expect(result.current).toBe(before)
  })
})

describe('the writes', () => {
  it('sends the whole fact on a set — targets and families, with no name on the write', () => {
    const set = vi.spyOn(lightingApi.selection, 'set')
    setDeskSelection([{ type: 'group', key: 'Front wash' }], ['COLOUR'])
    expect(set).toHaveBeenCalledWith([{ type: 'group', key: 'Front wash' }], ['COLOUR'])
  })

  it('sends a set with no families for a row selection, which clears the desk’s mask', () => {
    const set = vi.spyOn(lightingApi.selection, 'set')
    setDeskSelection([{ type: 'fixture', key: 'par-1' }])
    expect(set).toHaveBeenCalledWith([{ type: 'fixture', key: 'par-1' }], null)
  })

  it('sends a bare toggle, and nothing but the clear on a clear', () => {
    const toggle = vi.spyOn(lightingApi.selection, 'toggle')
    const clear = vi.spyOn(lightingApi.selection, 'clear')
    toggleDeskSelection({ type: 'fixture', key: 'par-1' })
    expect(toggle).toHaveBeenCalledWith({ type: 'fixture', key: 'par-1' })
    clearDeskSelection()
    expect(clear).toHaveBeenCalledWith()
  })

  it('sends a subselect as the bare mode — the desk keeps the mask and answers with the state frame', () => {
    const subselect = vi.spyOn(lightingApi.selection, 'subselect')
    subselectDeskSelection('NEXT')
    expect(subselect).toHaveBeenCalledWith('NEXT')
  })
})
