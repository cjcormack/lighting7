// @vitest-environment jsdom
import { Provider } from 'react-redux'
import { MemoryRouter, useSearchParams } from 'react-router'
import { DndContext } from '@dnd-kit/core'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, configure, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { installRelativeUrlRequest } from '@/test/backendMock'

vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

// Everything here waits on a REST round trip through the mock's 1ms timer. The default 1s is
// tight when the whole suite runs in parallel and the event loop is saturated — these tests
// went flaky at it, and a longer ceiling costs nothing when they pass.
configure({ asyncUtilTimeout: 5000 })
// The band, the rail and the palette are tested in their own files; stubbing them keeps this one
// about the view's own decisions — which page is showing, and what first open does.
// The band renders the selection's keys, which is the only DOM readout of it this file has. The
// pad's press handler is built at render time, so a test that fires a `selection.state` frame and
// presses has to wait for the *render*, not merely for the cache write — `findByTestId` on this
// content is that wait, and without it the press sends the pair from the frame before last.
vi.mock('./RigBand', async () => {
  const real = await import('./RigBand')
  return {
    FOCUS_WORD_CLASS: real.FOCUS_WORD_CLASS,
    COMPACT_FOCUS_WORD_CLASS: real.COMPACT_FOCUS_WORD_CLASS,
    VERB_WORD_CLASS: real.VERB_WORD_CLASS,
    EDIT_WORD_CLASS: real.EDIT_WORD_CLASS,
    // The band draws the host's `controls` at its one row's end — the Focus control lives there
    // in Split and Rig. The verbs it is handed are the host's one instance, echoed for the test.
    RigBand: ({ selectedTargets, focus, compact, controls, verbs }: { selectedTargets: Map<string, unknown>; focus: string; compact: boolean; controls?: React.ReactNode; verbs: unknown }) => (
      <div data-testid="target-band" data-focus={focus} data-compact={compact ? 'true' : 'false'} data-verbs={verbs != null ? 'true' : 'false'}>
        <span data-testid="target-band-selection">{[...selectedTargets.keys()].join(' ') || 'none'}</span>
        {controls}
      </div>
    ),
  }
})
vi.mock('./RigStrip', () => ({
  RigStrip: ({ controls }: { controls?: React.ReactNode }) => (
    <div data-testid="rig-strip-row">
      <button data-testid="rig-strip">
        unfold
      </button>
      {controls}
    </div>
  ),
  // The strip's pieces, as the short board's merged row mounts them.
  RigStripContent: () => (
    <button data-testid="rig-strip-content">
      unfold
    </button>
  ),
}))
vi.mock('./SideSheet', async () => {
  const real = await import('./SideSheet')
  return {
    sideSheetTabs: real.sideSheetTabs,
    SideSheet: () => <div data-testid="side-sheet" />,
    // The overlay as a marker of what it is mounted with; the real one is tested in its own file.
    SideSheetOverlay: ({ projectId }: { projectId: number }) => <div data-testid="side-sheet-overlay" data-project={projectId} />,
  }
})
vi.mock('./LibraryPalette', () => ({ LibraryPalette: () => <div data-testid="palette" /> }))
// The route's bar state, threaded to the (mocked) side sheet for its Show tab; nothing here reads it.
const showStub = {
  transport: { activeStack: undefined, serverActiveCueId: null, activeCueId: null, standbyCueId: null, completedCueIds: [] },
  showBarProps: { dbo: false, onDbo: () => {} },
  activeCue: null,
  standbyCue: null,
  nextStack: null,
} as unknown as import('./ShowTab').ShowTabSource

import { store } from '@/store'
import { enterBuskEdit } from '@/store/buskEditSlice'
import { restApi } from '@/store/restApi'
import { BuskingView, RELINK_TOAST_ID } from './BuskingView'
import type { BuskPage, BuskPressResponse } from '@/api/buskApi'
import { buskPageWs, selectionWs } from '@/test/backendMock'
import { getLocalSelection, isFollowingDesk, resetDeskFollowStores, unlinkFromDesk } from '@/lib/deskFollow'
import {
  BUSK_PAGE_FOLLOW_KEY,
  isFollowingBuskPage,
  keepFollowingBuskPage,
  relinkBuskPage,
  resetBuskPageFollowStores,
  showingBuskPage,
  unlinkBuskPage,
} from '@/lib/buskPageFollow'
import { toast } from 'sonner'
import { getBuskFocus, getBuskSheet, resetBuskWindowStores, setBuskFocus, setBuskSheet } from '@/lib/buskWindow'

const emptyPage: BuskPage = { id: 4, uuid: 'p4', name: 'Ballads', sortOrder: 0, rows: [] }
const second: BuskPage = { id: 5, uuid: 'p5', name: 'Dance', sortOrder: 1, rows: [] }

/** What the layout PUT answers with — the page as written, ids minted. */
const generated: BuskPage = {
  ...emptyPage,
  rows: [
    {
      columns: [
        {
          id: 11,
          uuid: 'c11',
          width: 3,
          banks: [{ id: 21, uuid: 'b21', name: 'Colour', solo: false, flow: 'WRAP', pads: [] }],
        },
      ],
    },
  ],
}

/** A page holding one colour template pad, for the press tests. */
const warmAmber = {
  id: 7,
  uuid: 't7',
  name: 'Warm Amber',
  notes: null,
  family: 'COLOUR',
  kind: 'value',
  isGeneric: true,
  rows: [{ propertyName: 'rgbColour', value: '#ffaa00', targetType: 'generic', targetKey: null, sortOrder: 0 }],
  effect: null,
  requiredEmitters: [],
  lastPressedAt: null,
  layerCount: 0,
} as never
const padPage: BuskPage = {
  ...emptyPage,
  rows: [
    {
      columns: [
        {
          id: 11,
          uuid: 'c11',
          width: 12,
          banks: [
            {
              id: 21,
              uuid: 'b21',
              name: 'Colour',
              solo: false,
              flow: 'WRAP',
              pads: [{ id: 31, uuid: 'pd31', kind: 'TEMPLATE', template: warmAmber }],
            },
          ],
        },
      ],
    },
  ],
}

/** The rig the busk selection rehydrates against — a target the lists cannot resolve is not sent. */
const rig = {
  fixtures: [{ key: 'par-1', name: 'PAR 1', typeKey: 'par' }],
  groups: [
    { name: 'Movers', memberCount: 4, capabilities: [], symmetricMode: 'NONE', defaultDistribution: 'LINEAR', compatibleLookIds: [] },
  ],
}

/** What the press route answers; a test overrides `skippedFamilies` for the Look case. */
let pressAnswer: BuskPressResponse = {
  kind: 'TEMPLATE',
  action: 'applied',
  effectCount: 0,
  released: 0,
  skippedFamilies: [],
}

/**
 * Every value the router's `?page=` takes, in order — not just the settled one. The mirror effect's
 * bug was a *transient* wrong write that corrects itself on the next tick, which a `waitFor` can
 * never see. `MemoryRouter` keeps its own history and never touches `window.location`, so this has
 * to read `useSearchParams` from inside the router.
 */
const urlSeen: (string | null)[] = []
/** Every `focus=`/`sheet=` pair the URL takes, for the mirror's own transient. */
const shapeSeen: string[] = []
function PageProbe() {
  const [params] = useSearchParams()
  const page = params.get('page')
  if (urlSeen[urlSeen.length - 1] !== page) urlSeen.push(page)
  const shape = `${params.get('focus')}/${params.get('sheet')}`
  if (shapeSeen[shapeSeen.length - 1] !== shape) shapeSeen.push(shape)
  return null
}

function draw(pages: BuskPage[], path = '/projects/1/busk') {
  const calls: { url: string; method: string; body?: string }[] = []
  const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
    const request = input as Request
    const sent = request.method === 'POST' ? await request.clone().text() : undefined
    calls.push({ url: request.url, method: request.method, body: sent })
    const body =
      request.method !== 'GET'
        ? []
        : request.url.includes('/busk/pages')
          ? pages
          : request.url.endsWith('/fixtures')
            ? rig.fixtures
            : request.url.endsWith('/groups')
              ? rig.groups
              : []
    const created = request.method === 'POST' && request.url.endsWith('/busk/pages')
    const written = request.method === 'PUT' && request.url.includes('/layout')
    const pressed = request.method === 'POST' && request.url.includes('/busk/pads/')
    return new Response(
      JSON.stringify(created ? emptyPage : written ? generated : pressed ? pressAnswer : body),
      { status: 200, headers: { 'Content-Type': 'application/json' } },
    )
  })
  vi.stubGlobal('fetch', fetchMock)

  const utils = render(
    <Provider store={store}>
      <MemoryRouter initialEntries={[path]}>
        <DndContext>
          <PageProbe />
          <BuskingView projectId={1} show={showStub} />
        </DndContext>
      </MemoryRouter>
    </Provider>,
  )
  return { ...utils, calls }
}

/** Which board `matchMedia` describes: a desk screen by default; `short` is the landscape phone. */
function surface({ short = false, narrow = false } = {}) {
  vi.stubGlobal(
    'matchMedia',
    (query: string) => ({
      matches: query.includes('max-width')
        ? narrow
        : query.includes('500px')
          ? short
          : query.includes('750px')
            ? short
            : query.startsWith('(min-width') && !(narrow && query.includes('768px')),
      media: query,
      addEventListener: () => {},
      removeEventListener: () => {},
      addListener: () => {},
      removeListener: () => {},
      onchange: null,
      dispatchEvent: () => false,
    }),
  )
}

describe('the busk view', () => {
  beforeEach(() => {
    installRelativeUrlRequest()
    // jsdom has no matchMedia; the view asks it which board this is. A desk screen: every
    // `min-width` matches, neither height fold does, so the defaults are Split and Speed.
    surface()
  })

  afterEach(() => {
    cleanup()
    store.dispatch(restApi.util.resetApiState())
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
    selectionWs.last = null
    selectionWs.callback = null
    window.sessionStorage.clear()
    resetDeskFollowStores()
    resetBuskPageFollowStores()
    resetBuskWindowStores()
    shapeSeen.length = 0
    pressAnswer = { kind: 'TEMPLATE', action: 'applied', effectCount: 0, released: 0, skippedFamilies: [] }
    buskPageWs.reset()
    urlSeen.length = 0
  })

  it('offers the two starting points when the project has no pages', async () => {
    draw([])
    expect(await screen.findByText('Start from your library')).toBeTruthy()
    expect(screen.getByText('Start empty')).toBeTruthy()
  })

  it('creates the page before writing the generated layout into it', async () => {
    const { calls } = draw([])
    fireEvent.click(await screen.findByText('Start from your library'))

    await waitFor(() => {
      const writes = calls.filter((c) => c.method !== 'GET')
      expect(writes).toHaveLength(2)
    })
    const writes = calls.filter((c) => c.method !== 'GET')
    // The layout write needs the id the create answers with, so the order is not incidental.
    expect(writes[0].method).toBe('POST')
    expect(writes[0].url.endsWith('/busk/pages')).toBe(true)
    expect(writes[1].method).toBe('PUT')
    expect(writes[1].url).toContain(`/busk/pages/${emptyPage.id}/layout`)
  })

  it('shows the generated layout without waiting for a socket frame', async () => {
    // The one layout write outside the commit queue: `saveBuskLayout` neither invalidates nor
    // patches on its own, so the generator has to seed the cache itself or the operator reads
    // "this page is empty" until an unrelated busk.layoutChanged happens along.
    draw([])
    fireEvent.click(await screen.findByText('Start from your library'))
    expect(await screen.findByText('Colour')).toBeTruthy()
  })

  it('shows the first page when the URL names none', async () => {
    draw([emptyPage, second])
    await screen.findByRole('button', { name: 'Ballads' })
    expect(screen.getByRole('button', { name: 'Ballads' }).getAttribute('aria-current')).toBe('page')
  })

  it('shows the page the URL names', async () => {
    draw([emptyPage, second], '/projects/1/busk?page=5')
    await screen.findByRole('button', { name: 'Dance' })
    expect(screen.getByRole('button', { name: 'Dance' }).getAttribute('aria-current')).toBe('page')
  })

  it('falls back to the first page when the URL names one that is gone', async () => {
    draw([emptyPage, second], '/projects/1/busk?page=999')
    await screen.findByRole('button', { name: 'Ballads' })
    expect(screen.getByRole('button', { name: 'Ballads' }).getAttribute('aria-current')).toBe('page')
  })

  it('prefers the desk over the URL for a window that already follows — a hardware next-page press and a tab click are one gesture', async () => {
    // The desk already answers 5 before this tab ever mounts (e.g. another client moved it), and
    // the URL still names 4. This window has decided to follow — which is what a reload of a
    // following window looks like, its own mirrored `?page=` and all — so the desk wins.
    keepFollowingBuskPage()
    buskPageWs.last = second.id
    draw([emptyPage, second], `/projects/1/busk?page=${emptyPage.id}`)
    await screen.findByRole('button', { name: 'Dance' })
    expect(screen.getByRole('button', { name: 'Dance' }).getAttribute('aria-current')).toBe('page')
    expect(isFollowingBuskPage()).toBe(true)
  })

  /**
   * The showing page is the desk's *and* this window's, and a per-tab flag says which
   * (`lib/buskPageFollow.ts`). The desk still holds one page and the MIDI page buttons still move
   * it; what changed is that a window may decline to be pinned to it.
   */
  describe('which page this window shows', () => {
    it('takes a `?page=` this window arrived with as its own, unlinking it from the desk', async () => {
      // A launcher URL — `?window=Screen 2&page=5` — is an explicit statement about this window, so
      // it beats the desk rather than being a stale mirror of it.
      buskPageWs.last = emptyPage.id
      draw([emptyPage, second], `/projects/1/busk?page=${second.id}`)
      await waitFor(() =>
        expect(screen.getByRole('button', { name: 'Dance' }).getAttribute('aria-current')).toBe('page'),
      )
      // Unlinked, the page chip is drawn — it is drawn only then (D18).
      expect(await screen.findByRole('button', { name: 'Page: Own' })).toBeTruthy()
    })

    it('records that it follows when it arrives with no usable `?page=`, so a reload is not an arrival', async () => {
      draw([emptyPage, second], '/projects/1/busk?page=999')
      await screen.findByRole('button', { name: 'Ballads' })
      await waitFor(() => expect(window.sessionStorage.getItem(BUSK_PAGE_FOLLOW_KEY)).toBe('true'))
      // Following: no page chip at all (D18).
      expect(screen.queryByRole('button', { name: /^Page:/ })).toBeNull()
    })

    it('does not read a bare `/busk` as an arrival — `Number(null)` is 0, not a page id', async () => {
      // Latching the raw parameter rather than `Number(...)` of it is what keeps a page whose id
      // really were 0 from making every plain load unlink an ordinary tab with nobody asking.
      draw([{ ...emptyPage, id: 0 }, second], '/projects/1/busk')
      await screen.findByRole('button', { name: 'Ballads' })
      await waitFor(() => expect(window.sessionStorage.getItem(BUSK_PAGE_FOLLOW_KEY)).toBe('true'))
      expect(screen.queryByRole('button', { name: /^Page:/ })).toBeNull()
    })

    it('mirrors the arrival page into `?page=`, never the desk page it is unlinking from', async () => {
      // Both effects run in one commit, arrival first, and the unlink only *schedules* the
      // re-render that moves `activePage` — so an ungated mirror writes the desk's page into the
      // URL and corrects it a tick later. `urlSeen` records every value the parameter ever takes,
      // which is the only way to catch a transient: the settled state is right either way.
      buskPageWs.last = emptyPage.id
      draw([emptyPage, second], `/projects/1/busk?page=${second.id}`)
      await waitFor(() =>
        expect(screen.getByRole('button', { name: 'Dance' }).getAttribute('aria-current')).toBe('page'),
      )
      expect(urlSeen).toEqual([String(second.id)])
    })

    it('draws no page chip before the pages arrive, so a click cannot spend the arrival decision', async () => {
      // Unlinking *is* a decision, so a click on a chip drawn over an empty list would leave a
      // window launched at `?page=` never landing on it — and an unlinked tab with no pages has
      // nothing to draw the chip beside.
      unlinkBuskPage(null)
      draw([], '/projects/1/busk')
      await screen.findByText('Start from your library')
      expect(screen.queryByRole('button', { name: /^Page:/ })).toBeNull()
    })

    it('moves a following window when the desk’s page changes', async () => {
      draw([emptyPage, second])
      await screen.findByRole('button', { name: 'Ballads' })
      act(() => buskPageWs.fire(second.id))
      await waitFor(() =>
        expect(screen.getByRole('button', { name: 'Dance' }).getAttribute('aria-current')).toBe('page'),
      )
    })

    it('leaves a local window where it is when the desk’s page changes — the MIDI page buttons included', async () => {
      unlinkBuskPage(emptyPage.id)
      draw([emptyPage, second])
      await screen.findByRole('button', { name: 'Ballads' })
      // `BuskPageNext` and friends write `BuskPageState`, which arrives here as this frame.
      act(() => buskPageWs.fire(second.id))
      await new Promise((resolve) => setTimeout(resolve, 20))
      expect(screen.getByRole('button', { name: 'Ballads' }).getAttribute('aria-current')).toBe('page')
      expect(screen.getByRole('button', { name: 'Dance' }).getAttribute('aria-current')).toBeNull()
    })

    it('keeps the page it is showing when unlinked, rather than jumping to the first', async () => {
      buskPageWs.last = second.id
      keepFollowingBuskPage()
      draw([emptyPage, second])
      await waitFor(() =>
        expect(screen.getByRole('button', { name: 'Dance' }).getAttribute('aria-current')).toBe('page'),
      )
      // While paged with the desk the mark is the badge, not a chip (desk-follow D7): the unlink is
      // ⌘K's, the Screens sheet's or a `?page=` arrival's, and it snapshots the page this window is
      // showing — which the view reports for a Screens row's `pageFollows: 'false'` to keep (D6).
      expect(screen.queryByRole('button', { name: /^Page:/ })).toBeNull()
      expect(screen.getByRole('button', { name: 'Paged with the desk' })).toBeTruthy()
      expect(showingBuskPage()).toBe(second.id)
      act(() => unlinkBuskPage(second.id))
      // Unlinked, still on Dance — and the desk moving no longer reaches this window.
      expect(screen.getByRole('button', { name: 'Page: Own' })).toBeTruthy()
      act(() => buskPageWs.fire(emptyPage.id))
      await new Promise((resolve) => setTimeout(resolve, 20))
      expect(screen.getByRole('button', { name: 'Dance' }).getAttribute('aria-current')).toBe('page')
    })

    it('adopts the desk’s page again on the chip’s press, and sends nothing — and the chip then goes', async () => {
      unlinkBuskPage(emptyPage.id)
      buskPageWs.last = second.id
      draw([emptyPage, second])
      await screen.findByRole('button', { name: 'Ballads' })
      fireEvent.click(screen.getByRole('button', { name: 'Page: Own' }))
      await waitFor(() =>
        expect(screen.getByRole('button', { name: 'Dance' }).getAttribute('aria-current')).toBe('page'),
      )
      expect(buskPageWs.sent).toEqual([])
      expect(screen.queryByRole('button', { name: /^Page:/ })).toBeNull()
      expect(screen.getByRole('button', { name: 'Paged with the desk' })).toBeTruthy()
    })

    it('writes the desk on a tab click while following, and only this window once unlinked', async () => {
      draw([emptyPage, second])
      await screen.findByRole('button', { name: 'Ballads' })
      fireEvent.click(screen.getByRole('button', { name: 'Dance' }))
      expect(buskPageWs.sent).toEqual([second.id])

      act(() => relinkBuskPage())
      act(() => unlinkBuskPage(second.id))
      fireEvent.click(screen.getByRole('button', { name: 'Ballads' }))
      await waitFor(() =>
        expect(screen.getByRole('button', { name: 'Ballads' }).getAttribute('aria-current')).toBe('page'),
      )
      // Still one — an unlinked window's click is its own and the desk is not written.
      expect(buskPageWs.sent).toEqual([second.id])
    })
  })

  it('unlinks this window when a page click never reaches the desk, rather than doing nothing', async () => {
    // `setShowingBuskPage` returns `sendGesture`'s boolean; `false` means the socket was down and
    // the desk never heard the click. There is no separate offline override any more — a failed
    // write is folded into the local page, which is the same shape and *says* what happened.
    buskPageWs.landed = false
    draw([emptyPage, second])
    await screen.findByRole('button', { name: 'Ballads' })
    fireEvent.click(screen.getByRole('button', { name: 'Dance' }))
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Dance' }).getAttribute('aria-current')).toBe(
        'page',
      ),
    )
    expect(screen.getByRole('button', { name: 'Page: Own' })).toBeTruthy()
    expect(isFollowingBuskPage()).toBe(false)
  })

  it('swaps the side sheet for the library while editing, and puts it back', async () => {
    draw([emptyPage])
    // Wait for the page list, or the Edit layout button is still disabled on an empty project.
    await screen.findByRole('button', { name: 'Ballads' })
    fireEvent.click(screen.getByRole('button', { name: 'Edit layout' }))
    expect(await screen.findByTestId('palette')).toBeTruthy()
    expect(screen.queryByTestId('side-sheet')).toBeNull()

    fireEvent.click(screen.getByText('Done'))
    expect(await screen.findByTestId('side-sheet')).toBeTruthy()
    expect(screen.queryByTestId('palette')).toBeNull()
  })

  describe('focus — the three shapes', () => {
    it('is Split by default: the band over the page strip, with the Focus control on the band', async () => {
      draw([emptyPage])
      await screen.findByRole('button', { name: 'Ballads' })
      expect(screen.getByTestId('target-band')).toHaveAttribute('data-focus', 'split')
      expect(screen.queryByTestId('rig-strip')).toBeNull()
      expect(screen.getByRole('radiogroup', { name: 'Focus' })).toBeTruthy()
      expect(document.querySelector('[data-busk-page-strip="open"]')).not.toBeNull()
    })

    it('draws the pad row and no band in Pads focus on the desk board, with the Focus control and Edit layout on the pad row (D17)', async () => {
      setBuskFocus('pads')
      draw([emptyPage])
      await screen.findByRole('button', { name: 'Ballads' })
      // No band and no strip: the pad row is the body's top row, and carries the selection's
      // verbs, the summary and the host's controls.
      expect(screen.queryByTestId('target-band')).toBeNull()
      expect(screen.queryByTestId('rig-strip')).toBeNull()
      const padRow = document.querySelector('[data-pad-row="pads"]') as HTMLElement
      expect(padRow).not.toBeNull()
      expect(padRow.querySelector('[aria-label="Focus"]')).not.toBeNull()
      expect(within(padRow).getByRole('button', { name: 'Edit layout' })).toBeInTheDocument()
      expect(within(padRow).getByRole('button', { name: 'Spread…' })).toBeInTheDocument()
      expect(within(padRow).getByRole('button', { name: 'Locate' })).toBeInTheDocument()
      expect(within(padRow).getByRole('button', { name: 'Highlight' })).toBeInTheDocument()
      expect(padRow.querySelector('[data-pad-summary]')).toHaveTextContent('nothing selected')
      // No Cells menu, no steps, no Clear: those act on tiles, which are on the rig screen.
      expect(within(padRow).queryByRole('button', { name: /^Cells:/ })).toBeNull()
      expect(within(padRow).queryByRole('button', { name: 'Clear' })).toBeNull()
      // Back in Split the band carries the Focus control and the pad row is tabs only.
      act(() => setBuskFocus('split'))
      expect(await screen.findByTestId('target-band')).toHaveAttribute('data-focus', 'split')
      expect(screen.getByTestId('target-band')).toHaveAttribute('data-verbs', 'true')
      const splitRow = document.querySelector('[data-pad-row="split"]') as HTMLElement
      expect(splitRow.querySelector('[aria-label="Focus"]')).toBeNull()
      expect(within(splitRow).queryByRole('button', { name: 'Spread…' })).toBeNull()
      expect(splitRow.querySelector('[data-pad-summary]')).toBeNull()
    })

    it('fills the body with the band in Rig focus and folds the page to the board’s 40px strip at the bottom — name and bank count', async () => {
      setBuskFocus('rig')
      draw([generated])
      await screen.findByText('Ballads')
      expect(screen.getByTestId('target-band')).toHaveAttribute('data-focus', 'rig')
      const strip = document.querySelector('[data-busk-page-strip="folded"]')!
      expect(strip).not.toBeNull()
      // The board's strip, not the tab strip drawn folded: 40px, the page named and its banks
      // counted, no tabs and no page chip — a page is chosen in Split.
      expect(strip.className).toContain('h-10')
      expect(strip).toHaveTextContent('Ballads')
      expect(strip).toHaveTextContent('1 bank')
      expect(within(strip as HTMLElement).queryByRole('button', { name: 'Ballads' })).toBeNull()
      expect(within(strip as HTMLElement).queryByRole('button', { name: /^Page:/ })).toBeNull()
      // *Edit layout* and the Focus control are on the band's one row, where they are in every
      // shape — so Rig focus has its way into edit mode, and the strip carries neither.
      expect(within(strip as HTMLElement).queryByRole('button', { name: 'Edit layout' })).toBeNull()
      expect(strip.querySelector('[aria-label="Focus"]')).toBeNull()
      const band = screen.getByTestId('target-band')
      expect(band.querySelector('[aria-label="Focus"]')).not.toBeNull()
      expect(within(band).getByRole('button', { name: 'Edit layout' })).toBeEnabled()
      expect(band.compareDocumentPosition(strip) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
      // The strip's chevron is the way back to Split — the rig strip's twin, in the same slot.
      fireEvent.click(within(strip as HTMLElement).getByRole('button', { name: 'Show the page again: Split' }))
      expect(screen.getByTestId('target-band')).toHaveAttribute('data-focus', 'split')
      expect(document.querySelector('[data-busk-page-strip="folded"]')).toBeNull()
      setBuskFocus('rig')
      await screen.findByText('1 bank')
      // And pressing Edit layout enters edit mode, which forces Split: the page unfolds.
      fireEvent.click(within(screen.getByTestId('target-band')).getByRole('button', { name: 'Edit layout' }))
      expect(await screen.findByText('Done')).toBeTruthy()
      expect(screen.getByTestId('target-band')).toHaveAttribute('data-focus', 'split')
    })

    it('moves the fact from the segmented control', async () => {
      draw([emptyPage])
      await screen.findByRole('button', { name: 'Ballads' })
      fireEvent.click(screen.getByRole('radio', { name: 'Rig' }))
      expect(getBuskFocus()).toBe('rig')
      expect(await screen.findByTestId('target-band')).toHaveAttribute('data-focus', 'rig')
    })

    it('forces Split while editing and restores the window’s focus on Done', async () => {
      setBuskFocus('pads')
      draw([emptyPage])
      await screen.findByRole('button', { name: 'Ballads' })
      expect(screen.queryByTestId('target-band')).toBeNull()
      fireEvent.click(screen.getByRole('button', { name: 'Edit layout' }))
      expect(await screen.findByTestId('target-band')).toHaveAttribute('data-focus', 'split')
      expect(screen.getByRole('radio', { name: 'Split' })).toBeDisabled()
      // The fact itself is untouched: Done reads it back.
      expect(getBuskFocus()).toBe('pads')
      fireEvent.click(screen.getByText('Done'))
      await waitFor(() => expect(screen.queryByTestId('target-band')).toBeNull())
      expect(document.querySelector('[data-pad-row="pads"]')).not.toBeNull()
    })

    it('forces Split on a project with no pages, so the first-open screen is never folded away', async () => {
      setBuskFocus('rig')
      draw([])
      await screen.findByText('Start from your library')
      expect(screen.getByTestId('target-band')).toHaveAttribute('data-focus', 'split')
      expect(document.querySelector('[data-busk-page-strip="open"]')).not.toBeNull()
      // The fact is untouched: the first page created, the window is back in Rig focus.
      expect(getBuskFocus()).toBe('rig')
    })

    it('takes ?focus= and ?sheet= as this window’s on arrival, once, and mirrors them back', async () => {
      draw([emptyPage], '/projects/1/busk?focus=pads&sheet=none')
      await screen.findByRole('button', { name: 'Ballads' })
      expect(getBuskFocus()).toBe('pads')
      expect(getBuskSheet()).toBe('none')
      expect(screen.queryByTestId('target-band')).toBeNull()
      expect(document.querySelector('[data-pad-row="pads"]')).not.toBeNull()
      // No transient: the mirror waited for the decision to render, so the URL never held the
      // defaults the window was arriving from.
      expect(shapeSeen).toEqual(['pads/none'])
      act(() => setBuskFocus('rig'))
      await waitFor(() => expect(shapeSeen.at(-1)).toBe('rig/none'))
    })

    it('mirrors the defaults into the URL on a plain arrival, so a copied link reproduces the shape', async () => {
      draw([emptyPage])
      await screen.findByRole('button', { name: 'Ballads' })
      await waitFor(() => expect(shapeSeen.at(-1)).toBe('split/speed'))
      expect(window.sessionStorage.getItem('busk.windowDecided')).toBe('true')
    })

    describe('Rig and Pads always follow the desk selection (desk-follow D2, D3)', () => {
      it('relinks a local window that enters Pads, dropping its own selection, and says so', async () => {
        const message = vi.spyOn(toast, 'message').mockImplementation(() => '' as never)
        selectionWs.last = { targets: [{ type: 'fixture', key: 'par-1' }], families: null, source: null }
        unlinkFromDesk({ targets: [{ type: 'group', key: 'Movers' }], families: ['POSITION'] })
        draw([emptyPage])
        await screen.findByRole('button', { name: 'Ballads' })
        // Split is where a selection of its own means something: nothing moved yet.
        expect(isFollowingDesk()).toBe(false)
        expect(message).not.toHaveBeenCalled()
        fireEvent.click(screen.getByRole('radio', { name: 'Pads' }))
        await waitFor(() => expect(isFollowingDesk()).toBe(true))
        expect(getLocalSelection()).toEqual({ targets: [], families: null })
        expect(message).toHaveBeenCalledTimes(1)
        expect(message.mock.calls[0]![0]).toMatch(/ follows the desk selection again$/)
        // Keyed, so a second relink replaces the toast rather than stacking another.
        expect(message.mock.calls[0]![1]).toMatchObject({
          id: RELINK_TOAST_ID,
          description: "Pads focus presses onto the desk's selection. Your own was dropped.",
        })
      })

      it('relinks a window that arrives in Rig focus while local — keyed on the focus, not on a move', async () => {
        const message = vi.spyOn(toast, 'message').mockImplementation(() => '' as never)
        setBuskFocus('rig')
        unlinkFromDesk({ targets: [{ type: 'group', key: 'Movers' }], families: null })
        draw([emptyPage])
        await waitFor(() => expect(isFollowingDesk()).toBe(true))
        expect(message.mock.calls[0]![1]).toMatchObject({
          description: "Rig focus presses onto the desk's selection. Your own was dropped.",
        })
      })

      it('says nothing for a following window entering Pads', async () => {
        const message = vi.spyOn(toast, 'message').mockImplementation(() => '' as never)
        draw([emptyPage])
        await screen.findByRole('button', { name: 'Ballads' })
        fireEvent.click(screen.getByRole('radio', { name: 'Pads' }))
        await waitFor(() => expect(getBuskFocus()).toBe('pads'))
        await act(async () => {
          await new Promise((resolve) => setTimeout(resolve, 20))
        })
        expect(message).not.toHaveBeenCalled()
        expect(isFollowingDesk()).toBe(true)
      })
    })

    it('does not read its own mirror as an arrival on reload', async () => {
      // A tab that had decided, chose Rig, and reloads at the URL the mirror wrote for it earlier
      // — while the URL, edited by hand since, says pads.
      window.sessionStorage.setItem('busk.windowDecided', 'true')
      setBuskFocus('rig')
      setBuskSheet('none')
      draw([emptyPage], '/projects/1/busk?focus=pads&sheet=speed')
      // Rig focus folds the page to its strip, which names the page and draws no tab.
      await screen.findByText('Ballads')
      expect(getBuskFocus()).toBe('rig')
      await waitFor(() => expect(shapeSeen.at(-1)).toBe('rig/none'))
    })
  })

  describe('the short board — short beats narrow (Phones, landscape)', () => {
    it('merges the rig strip and the page strip into one 32px row in Pads focus, with no docked sheet and no Edit layout', async () => {
      surface({ short: true })
      draw([emptyPage])
      await screen.findByRole('button', { name: 'Ballads' })
      // The ladder's defaults on a short viewport: Pads, the sheet folded — read, not written.
      expect(getBuskFocus()).toBe('pads')
      expect(getBuskSheet()).toBe('none')
      // One row: the strip's pieces lead the page strip; there is no strip row of its own.
      const strip = document.querySelector('[data-busk-page-strip="open"]')!
      expect(strip).toHaveAttribute('data-busk-page-strip-dense', 'true')
      // 32px with its border inside: the height is the wrapper's, and the row fills it.
      expect(strip.className).toContain('h-8')
      expect(strip.querySelector('[data-pad-row]')!.className).toContain('h-full')
      expect(strip.querySelector('[data-testid="rig-strip-content"]')).not.toBeNull()
      expect(screen.queryByTestId('rig-strip')).toBeNull()
      expect(screen.queryByTestId('target-band')).toBeNull()
      // Neither the fold nor the docked rail: the sheet is the overlay, opened from the row's button.
      expect(screen.queryByTestId('side-sheet')).toBeNull()
      expect(screen.getByTestId('side-sheet-overlay')).toBeTruthy()
      expect(screen.queryByRole('button', { name: 'Edit layout' })).toBeNull()
      const sheetButton = screen.getByRole('button', { name: /Sheet/ })
      expect(sheetButton).toBeEnabled()
      expect(sheetButton).toHaveAttribute('title', 'Open the Colour tab')
      fireEvent.click(sheetButton)
      expect(getBuskSheet()).toBe('colour')
    })

    it('shows the band compact — one row of 48px tiles with the row chip — in Split, on the dense page row', async () => {
      surface({ short: true })
      setBuskFocus('split')
      draw([emptyPage])
      await screen.findByRole('button', { name: 'Ballads' })
      const band = screen.getByTestId('target-band')
      expect(band).toHaveAttribute('data-focus', 'split')
      expect(band).toHaveAttribute('data-compact', 'true')
      // Split carries the strip's pieces on the band itself; the page row holds only the page —
      // and keeps a flexible spacer, so the controls still sit at the right edge.
      const strip = document.querySelector('[data-busk-page-strip="open"]')!
      expect(strip).toHaveAttribute('data-busk-page-strip-dense', 'true')
      expect(strip.querySelector('[data-testid="rig-strip-content"]')).toBeNull()
      expect(strip.querySelector('[data-pad-row] > .flex-1')).not.toBeNull()
      expect(screen.queryByTestId('side-sheet')).toBeNull()
    })

    it('draws the wrapping row, not the 32px one, while editing — a desk window shortened mid-edit keeps its verbs', async () => {
      surface({ short: true })
      store.dispatch(enterBuskEdit(emptyPage.id))
      draw([emptyPage])
      await screen.findByRole('button', { name: 'Ballads' })
      const strip = document.querySelector('[data-busk-page-strip="open"]')!
      expect(strip).not.toHaveAttribute('data-busk-page-strip-dense')
      expect(screen.getByText('Done')).toBeTruthy()
    })

    it('leaves the desk board as it was: two rows, the docked sheet, Edit layout offered, the band at full size', async () => {
      draw([emptyPage])
      await screen.findByRole('button', { name: 'Ballads' })
      expect(screen.getByTestId('target-band')).toHaveAttribute('data-compact', 'false')
      const strip = document.querySelector('[data-busk-page-strip="open"]')!
      expect(strip).not.toHaveAttribute('data-busk-page-strip-dense')
      expect(screen.getByTestId('side-sheet')).toBeTruthy()
      expect(screen.queryByTestId('side-sheet-overlay')).toBeNull()
      expect(screen.getByRole('button', { name: 'Edit layout' })).toBeTruthy()
      expect(screen.queryByRole('button', { name: /Sheet/ })).toBeNull()
    })

    it('below md the sheet button opens the overlay onto Colour, and Edit layout is withheld', async () => {
      surface({ narrow: true })
      draw([emptyPage])
      await screen.findByRole('button', { name: 'Ballads' })
      expect(screen.getByTestId('target-band')).toHaveAttribute('data-compact', 'true')
      expect(screen.queryByTestId('side-sheet')).toBeNull()
      expect(screen.getByTestId('side-sheet-overlay')).toBeTruthy()
      expect(screen.queryByRole('button', { name: 'Edit layout' })).toBeNull()
      fireEvent.click(screen.getByRole('button', { name: /Sheet/ }))
      expect(getBuskSheet()).toBe('colour')
    })
  })

  it('asks before deleting a page, and sends nothing until it is confirmed', async () => {
    const { calls } = draw([emptyPage])
    await screen.findByRole('button', { name: 'Ballads' })
    fireEvent.click(screen.getByRole('button', { name: 'Edit layout' }))
    // Radix's DropdownMenuTrigger opens on `pointerdown`, not `click`.
    fireEvent.pointerDown(await screen.findByLabelText('Options for Ballads'), {
      button: 0,
      ctrlKey: false,
      pointerType: 'mouse',
    })
    fireEvent.click(await screen.findByText('Delete page'))

    // The dialog is up and nothing has been sent — this is the one edit-mode gesture with no undo.
    expect(await screen.findByText('Delete “Ballads”?')).toBeTruthy()
    expect(calls.filter((c) => c.method === 'DELETE')).toHaveLength(0)

    fireEvent.click(screen.getByRole('button', { name: 'Delete page' }))
    await waitFor(() => expect(calls.filter((c) => c.method === 'DELETE')).toHaveLength(1))
  })

  /**
   * A press sends the pair it is acting on (multi-screen plan D4): the desk's targets and mask
   * while this tab follows the desk, the tab's own once unlinked (D8). And the skip is toasted on
   * the pressing window (D6), in the desk's words plus *rows*.
   */
  describe('a pad press', () => {
    async function press(calls: { url: string; method: string; body?: string }[]) {
      // A pad presses on the pointer pair, not on click — `useLongPress` owns the gesture.
      const pad = await screen.findByTitle('Warm Amber')
      fireEvent.pointerDown(pad, { clientX: 0, clientY: 0 })
      fireEvent.pointerUp(pad)
      await waitFor(() => expect(calls.some((c) => c.url.includes('/busk/pads/31/press'))).toBe(true))
      return JSON.parse(calls.find((c) => c.url.includes('/busk/pads/31/press'))!.body!)
    }

    it('sends the desk’s targets and mask while following', async () => {
      selectionWs.last = {
        targets: [{ type: 'fixture', key: 'par-1' }],
        families: ['COLOUR'],
        source: null,
      }
      const { calls } = draw([padPage])
      expect(await press(calls)).toEqual({
        targets: [{ type: 'fixture', key: 'par-1' }],
        families: ['COLOUR'],
      })
    })

    it('sends no families for an unmasked selection', async () => {
      selectionWs.last = { targets: [{ type: 'fixture', key: 'par-1' }], families: null, source: null }
      const { calls } = draw([padPage])
      expect(await press(calls)).toEqual({ targets: [{ type: 'fixture', key: 'par-1' }] })
    })

    it('sends this tab’s own pair once unlinked, whatever the desk holds', async () => {
      selectionWs.last = { targets: [{ type: 'fixture', key: 'par-1' }], families: ['COLOUR'], source: null }
      unlinkFromDesk({ targets: [{ type: 'group', key: 'Movers' }], families: ['POSITION'] })
      const { calls } = draw([padPage])
      expect(await press(calls)).toEqual({
        targets: [{ type: 'group', key: 'Movers' }],
        families: ['POSITION'],
      })
    })

    it('toasts the rows a masked Look skipped, saying rows', async () => {
      selectionWs.last = { targets: [{ type: 'fixture', key: 'par-1' }], families: ['COLOUR'], source: null }
      pressAnswer = { kind: 'LOOK', action: 'applied', effectCount: 0, released: 0, skippedFamilies: ['POSITION'] }
      const warning = vi.spyOn(toast, 'warning').mockImplementation(() => '' as never)
      const { calls } = draw([padPage])
      await press(calls)
      await waitFor(() =>
        expect(warning).toHaveBeenCalledWith('Position rows skipped — the selection is Colour'),
      )
    })

    /**
     * **The headline case, and the one assertion that would fail if the two flags were ever folded
     * into one.** Colour templates on one screen and position templates on another, pressed onto
     * one selection: the two screens must share the selection and differ in the page, at the same
     * time. Neither half is caught by testing the two facts separately.
     */
    it('moves this window’s selection while leaving its page alone, when only the page is unlinked', async () => {
      selectionWs.last = { targets: [{ type: 'fixture', key: 'par-1' }], families: ['COLOUR'], source: null }
      unlinkBuskPage(padPage.id)
      const { calls } = draw([padPage, second])
      await screen.findByRole('button', { name: 'Ballads' })

      // The desk's page moves — a hardware next-page press, or the other screen's tab click.
      act(() => buskPageWs.fire(second.id))
      await new Promise((resolve) => setTimeout(resolve, 20))
      expect(screen.getByRole('button', { name: 'Ballads' }).getAttribute('aria-current')).toBe('page')

      // …and the desk's selection moves, which this window *does* take, because the selection's
      // own flag is untouched.
      act(() =>
        selectionWs.fire({ targets: [{ type: 'group', key: 'Movers' }], families: ['POSITION'], source: null }),
      )
      await screen.findByText('group:Movers')
      expect(await press(calls)).toEqual({
        targets: [{ type: 'group', key: 'Movers' }],
        families: ['POSITION'],
      })
    })

    it('moves this window’s page while leaving its selection alone, when only the selection is unlinked', async () => {
      selectionWs.last = { targets: [{ type: 'fixture', key: 'par-1' }], families: ['COLOUR'], source: null }
      unlinkFromDesk({ targets: [{ type: 'group', key: 'Movers' }], families: ['POSITION'] })
      const { calls } = draw([padPage, second])
      await screen.findByRole('button', { name: 'Ballads' })

      // The desk's selection moves and this window does not take it. The band reads the local
      // pair before *and* after, so a tick is given to any render the frame might have caused.
      await screen.findByText('group:Movers')
      act(() =>
        selectionWs.fire({ targets: [{ type: 'fixture', key: 'par-1' }], families: ['COLOUR'], source: null }),
      )
      await act(async () => {
        await new Promise((resolve) => setTimeout(resolve, 20))
      })
      expect(screen.getByTestId('target-band-selection').textContent).toBe('group:Movers')
      expect(await press(calls)).toEqual({
        targets: [{ type: 'group', key: 'Movers' }],
        families: ['POSITION'],
      })

      // …while the desk's page still moves it, because the page's own flag is untouched.
      act(() => buskPageWs.fire(second.id))
      await waitFor(() =>
        expect(screen.getByRole('button', { name: 'Dance' }).getAttribute('aria-current')).toBe('page'),
      )
    })

    it('toasts nothing for a press that skipped nothing', async () => {
      selectionWs.last = { targets: [{ type: 'fixture', key: 'par-1' }], families: ['COLOUR'], source: null }
      const warning = vi.spyOn(toast, 'warning').mockImplementation(() => '' as never)
      const { calls } = draw([padPage])
      await press(calls)
      await new Promise((resolve) => setTimeout(resolve, 20))
      expect(warning).not.toHaveBeenCalled()
    })
  })

  it('leaves edit mode when the view unmounts, so no other surface keeps drawing it', async () => {
    const { unmount } = draw([emptyPage])
    await screen.findByRole('button', { name: 'Ballads' })
    fireEvent.click(screen.getByRole('button', { name: 'Edit layout' }))
    await waitFor(() => expect(store.getState().buskEdit.editing).toBe(true))
    unmount()
    expect(store.getState().buskEdit.editing).toBe(false)
  })
})
