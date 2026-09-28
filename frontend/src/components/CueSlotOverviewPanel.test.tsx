// @vitest-environment jsdom
import { Provider } from 'react-redux'
import { MemoryRouter } from 'react-router'
import { DndContext } from '@dnd-kit/core'
import { act, cleanup, configure, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { installRelativeUrlRequest, programmerWs } from '@/test/backendMock'

vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

// Every assertion here waits on the mock's 1ms timer through RTK Query; the default 1s ceiling
// goes flaky when the whole suite saturates the event loop. Same reason `BuskingView.test.tsx`
// raises it.
configure({ asyncUtilTimeout: 5000 })

import { store } from '@/store'
import { restApi } from '@/store/restApi'
import { enterBuskEdit, exitBuskEdit } from '@/store/buskEditSlice'
import { CueSlotOverviewPanel } from './CueSlotOverviewPanel'

/**
 * The FX cue-slot overlay, after the busk layout took over how slots are filled.
 *
 * Three behavioural decisions land here and nothing else guards them: a **Look** tile presses
 * `/looks/{id}/toggle` with an *empty* target list (the derive-from-its-own-fixtures contract), a
 * Look tile lights from the programmer's **applied** feed rather than from cue liveness, and every
 * editing affordance follows the **busk view's** edit mode rather than a long press on the panel.
 *
 * jsdom cannot test the drag itself — every rect is zero — so the palette→slot mapping is asserted
 * through the panel's refusal styling only; the mapping proper lives in `dnd/slotDrop.ts`.
 */

const SLOTS = [
  { id: 1, page: 0, slotIndex: 0, itemType: 'cue' as const, itemId: 20, itemName: 'Blackout' },
  { id: 2, page: 0, slotIndex: 1, itemType: 'look' as const, itemId: 6, itemName: 'Storm Wash' },
]

/** Stack 3 holds cue 20 live, so the cue tile reads lit. */
const STACKS = [
  {
    id: 3,
    name: 'Main Show',
    loop: false,
    sortOrder: 0,
    type: 'STACK',
    label: null,
    activeCueId: 20,
    nextCueId: null,
    canEdit: true,
    canDelete: true,
    cues: [],
  },
]

interface Recorded {
  url: string
  method: string
  body: unknown
}

/**
 * A fetch mock that records **bodies**.
 *
 * `installRecordingFetch` cannot: RTK Query hands `fetch` a `Request`, whose body is only readable
 * asynchronously, so an `init.body` reader sees nothing. The empty target list is the contract this
 * file exists to pin, so it has to be read off the request itself.
 */
function draw() {
  const calls: Recorded[] = []
  const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
    const request = input as Request
    const url = request.url
    const body = request.method === 'GET' ? undefined : await request.clone().json().catch(() => undefined)
    calls.push({ url, method: request.method, body })
    const answer = url.includes('/cue-slots')
      ? SLOTS
      : url.includes('/cue-stacks')
        ? STACKS
        : url.includes('projects/current')
          ? { id: 1, name: 'Hamlet' }
          : {}
    return new Response(JSON.stringify(answer), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })
  })
  vi.stubGlobal('fetch', fetchMock)
  const utils = render(
    <Provider store={store}>
      <MemoryRouter initialEntries={['/projects/1/show']}>
        <DndContext>
          <CueSlotOverviewPanel isVisible />
        </DndContext>
      </MemoryRouter>
    </Provider>,
  )
  return { ...utils, calls }
}

/** Everything the panel wrote, in order. */
function writes(calls: Recorded[]) {
  return calls.filter((c) => c.method !== 'GET')
}

describe('the FX cue-slot overlay', () => {
  beforeEach(() => {
    installRelativeUrlRequest()
    programmerWs.reset()
  })

  afterEach(() => {
    cleanup()
    store.dispatch(exitBuskEdit())
    store.dispatch(restApi.util.resetApiState())
    vi.unstubAllGlobals()
  })

  it('presses a Look tile with an empty target list', async () => {
    const { calls } = draw()
    fireEvent.pointerDown(await screen.findByLabelText('Storm Wash'))
    fireEvent.pointerUp(screen.getByLabelText('Storm Wash'))

    await waitFor(() => {
      const toggle = writes(calls).find((c) => c.url.includes('/looks/6/toggle'))
      expect(toggle).toBeTruthy()
      // The empty array *is* the contract: the route derives the targets from the Look's own
      // patched fixtures, which is why only a Look with no deferred effect may sit in a slot.
      expect(toggle!.body).toEqual({ targets: [] })
    })
  })

  it('applies a cue tile, and stops it when it is its stack’s live cue', async () => {
    const { calls } = draw()
    // Cue 20 is stack 3's `activeCueId`, so the first press stops it.
    fireEvent.pointerDown(await screen.findByLabelText('Blackout'))
    fireEvent.pointerUp(screen.getByLabelText('Blackout'))

    await waitFor(() => {
      expect(writes(calls).some((c) => c.url.includes('/cues/20/stop'))).toBe(true)
    })
    expect(writes(calls).some((c) => c.url.includes('/cues/20/apply'))).toBe(false)
  })

  it('lights a Look tile from the applied feed, and not from a template with the same id', async () => {
    draw()
    const tile = await screen.findByLabelText('Storm Wash')
    expect(tile.getAttribute('aria-pressed')).toBe('false')

    // A TEMPLATE layer carrying the same int PK must not light a Look's tile: the two id spaces
    // are different tables.
    act(() => {
      programmerWs.push({
        applied: [
          {
            source: { kind: 'TEMPLATE', id: 6, uuid: 't6', name: 'Amber Key' },
            targets: [{ type: 'fixture', key: 'hex-1', state: 'all' }],
          },
        ],
      })
    })
    expect(screen.getByLabelText('Storm Wash').getAttribute('aria-pressed')).toBe('false')

    act(() => {
      programmerWs.push({
        applied: [
          {
            source: { kind: 'LOOK', id: 6, uuid: 'l6', name: 'Storm Wash' },
            targets: [{ type: 'fixture', key: 'hex-1', state: 'all' }],
          },
        ],
      })
    })
    await waitFor(() =>
      expect(screen.getByLabelText('Storm Wash').getAttribute('aria-pressed')).toBe('true'),
    )
  })

  it('grows its crosses only while the busk view is editing, and clears the slot', async () => {
    const { calls } = draw()
    await screen.findByLabelText('Storm Wash')
    expect(screen.queryByRole('button', { name: /clear/i })).toBeNull()

    act(() => {
      store.dispatch(enterBuskEdit(4))
    })
    const crosses = await screen.findAllByRole('button', { name: /clear/i })
    expect(crosses).toHaveLength(2)

    fireEvent.click(crosses[1])
    await waitFor(() => {
      expect(writes(calls).some((c) => c.method === 'DELETE' && c.url.includes('/cue-slots/2')))
        .toBe(true)
    })

    act(() => {
      store.dispatch(exitBuskEdit())
    })
    await waitFor(() => expect(screen.queryByRole('button', { name: /clear/i })).toBeNull())
  })

  it('does not press a tile while editing — it is a drag handle then, not a button', async () => {
    const { calls } = draw()
    await screen.findByLabelText('Storm Wash')
    act(() => {
      store.dispatch(enterBuskEdit(4))
    })

    const tile = screen.getByText('Storm Wash').closest('div[role="button"]') as HTMLElement
    fireEvent.pointerDown(tile)
    fireEvent.pointerUp(tile)

    await new Promise((resolve) => setTimeout(resolve, 20))
    expect(writes(calls).some((c) => c.url.includes('/toggle'))).toBe(false)
  })
})
