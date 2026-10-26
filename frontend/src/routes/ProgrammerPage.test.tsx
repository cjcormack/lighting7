// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * The successor to `ProgrammerPane.test.tsx`.
 *
 * That suite mount-counted the three tab bodies, because Radix mounts only the active tab's content
 * and `useListSelection` clears its Redux scope on unmount — so glancing at the layer stack silently
 * discarded the fixture selection Record scopes on. The pane needed a `forceMount` escape hatch for
 * exactly that. Here there is nothing to force, and these assertions are what keeps it that way: the
 * grid mounted exactly once — across a table tab switch, a scope switch and the short-height fold
 * that moves row A into row B.
 */
const gridMounts = vi.fn()
vi.mock('@/components/programmer/ProgrammerGrid', async () => {
  const { useEffect } = await import('react')
  // Row B — the scope pills and the Groups toggle — lives in the grid's own `renderToolbar` slot
  // since the space plan's session 1, so the page reaches both *through* this stand-in. The band
  // itself is the real one: the assertions below are about which scope the page is in, and a
  // second fake would only pin the fake.
  const { ProgrammerScopeBand } = await import('@/components/programmer/ProgrammerScopeBand')
  const { ProgrammerTableTabs } = await import('@/components/programmer/ProgrammerTableTabs')
  return {
    ProgrammerGrid: ({
      grouped,
      onGroupedChange,
      leading,
    }: {
      grouped: boolean
      onGroupedChange: (next: boolean) => void
      leading?: React.ReactNode
    }) => {
      // In an effect, not in the render body: a re-render is fine and expected, a re-MOUNT is the
      // thing that would throw the selection away.
      useEffect(() => gridMounts(), [])
      return (
        <div data-testid="grid">
          {/* Row A's two halves, in the short-height arm. The stand-in has to render them or the
              fold is invisible to this suite while being a real change of place in the page. */}
          {leading}
          <ProgrammerTableTabs />
          <ProgrammerScopeBand />
          <button
            type="button"
            title="Show group rows with their members"
            onClick={() => onGroupedChange(!grouped)}
          />
        </div>
      )
    },
  }
})
// The hand's place target under the tables — store-touching, and this suite renders without a
// Provider, like every other child mocked here.
vi.mock('@/components/hand/HandLayerTargets', () => ({ HandProgrammerLayerStrip: () => null }))
// ⌘K's `+ Effect` door reads the Redux selection once asked; here it only has to be mounted.
vi.mock('@/components/programmer/ProgrammerAddFx', () => ({
  ProgrammerAddFxDoor: () => null,
}))
// ⌘K's Look and palette doors press through the store; here they only have to be mounted.
vi.mock('@/components/programmer/ProgrammerPressDoors', () => ({
  ProgrammerLookDoor: () => null,
  ProgrammerPaletteDoor: () => null,
}))
vi.mock('@/components/programmer/ProgrammerSceneryList', () => ({ useHeldSceneryCount: () => sceneryHeld.count }))
// The three tables have their suite (`AppliedTables.test.tsx`); here each is its own row B's tabs
// and a landmark, mounted on demand beside the one grid.
vi.mock('@/components/applied/StackTable', async () => {
  const { ProgrammerTableTabs } = await import('@/components/programmer/ProgrammerTableTabs')
  return { StackTable: () => <div data-testid="stack-table"><ProgrammerTableTabs /></div> }
})
vi.mock('@/components/fxEditor/ProgrammerFxTab', async () => {
  const { ProgrammerTableTabs } = await import('@/components/programmer/ProgrammerTableTabs')
  return { ProgrammerFxTab: () => <div data-testid="fx-table"><ProgrammerTableTabs /></div> }
})
vi.mock('@/components/applied/SceneryTable', async () => {
  const { ProgrammerTableTabs } = await import('@/components/programmer/ProgrammerTableTabs')
  return { SceneryTable: () => <div data-testid="scenery-table"><ProgrammerTableTabs /></div> }
})
const sceneryHeld = vi.hoisted(() => ({ count: 0 }))
vi.mock('@/components/press/ApplyPicker', () => ({ ApplyPicker: () => null }))
vi.mock('@/hooks/usePress', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/hooks/usePress')>()),
  useWindowPressPair: () => ({ targets: [], families: null }),
  useWindowPaletteFit: () => ({ families: null, emitters: null }),
}))
vi.mock('@/store/fixtureFx', () => ({ useActiveFxQuery: () => ({ data: [] }) }))
vi.mock('@/components/programmer/ProgrammerSourceStrip', () => ({
  ProgrammerSourceStrip: () => <div data-testid="source-strip" />,
}))
vi.mock('@/components/programmer/ProgrammerActionBar', () => ({
  ProgrammerActionBar: () => <div data-testid="action-bar" />,
}))
vi.mock('@/components/programmer/ProgrammerSheets', () => ({
  ProgrammerSheetsProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  useProgrammerSheets: () => ({
    openRecord: vi.fn(),
    openRecordLook: vi.fn(),
    openInclude: vi.fn(),
    openUpdate: vi.fn(),
    openMakeLayer: vi.fn(),
  }),
}))
vi.mock('@/components/ShowHeader', () => ({
  ShowHeader: ({ view }: { view: string }) => <div data-testid="header">{view}</div>,
}))
// Mocked so a stray import can never quietly mount the real one behind the "no show bar" case
// below — this page is the only live view that draws no `ShowBar`, and the assertion that it does
// not is worth more than a mock that would render nothing anyway.
vi.mock('@/components/ShowBar', () => ({ ShowBar: () => <div data-testid="show-bar" /> }))
vi.mock('@/components/programmer/EditorContext', () => ({
  EditorContextProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}))
vi.mock('@/components/programmer/useInclude', () => ({
  includeRequestFor: () => null,
  useInclude: () => ({ include: vi.fn() }),
}))
vi.mock('@/hooks/useShowBarProps', () => ({
  useShowBarProps: () => ({
    isShowActive: true,
    showBarProps: {},
    showHeaderProps: { isShowActive: true, canStart: false, onStart: vi.fn(), onStop: vi.fn() },
  }),
}))
vi.mock('@/components/programmer/useReplacementNotes', () => ({
  ReplacementNotesProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  useReplacementNotes: () => new Map(),
}))
vi.mock('@/store/programmer', () => ({
  useProgrammerSummaryQuery: () => ({ data: { blind: false, entryCount: 0, lastIncluded: null } }),
  useProgrammerLayersQuery: () => ({ data: [] }),
  useProgrammerAppliedQuery: () => ({ data: [] }),
  useProgrammerRevision: () => 0,
  programmerClearAll: vi.fn(),
  programmerClearAllConfirmed: vi.fn(() => Promise.resolve(true)),
}))
vi.mock('@/store/projects', () => ({
  useCurrentProjectQuery: () => ({ data: { id: 1 }, isLoading: false }),
  useProjectQuery: () => ({ data: { id: 1, name: 'Hamlet' }, isLoading: false }),
}))
// `LookRowStoreProvider` is mounted unconditionally so the tree shape never changes with the
// scope — which means its queries run here even with nothing focused.
vi.mock('@/store/looks', () => ({
  useLookQuery: () => ({ data: undefined, isSuccess: false }),
  useSaveLookMutation: () => [vi.fn()],
}))
// Same reason as `@/store/looks` above, for `FocusedPaletteLayerProvider`: `skip` stops the
// *request*, not the hook, so the query still reaches for the store.
vi.mock('@/store/palettes', () => ({ usePaletteListQuery: () => ({ data: [] }) }))
vi.mock('@/store/fixtures', () => ({
  useFixtureListQuery: () => ({ data: [] }),
  useVisibleFixtureListQuery: () => ({ data: [] }),
}))
vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

import { ProgrammerPage } from './ProgrammerPage'
import { resetProgrammerTableStore } from '@/lib/programmerTables'

/**
 * jsdom has no `matchMedia`, and `ProgrammerBody` asks it whether the viewport is short enough to
 * fold rows A and B into one (space plan D8). The stand-in below is live rather than a constant:
 * `setShortViewport` flips the answer AND fires the `change` the hook subscribes to, which is
 * what lets the fold be exercised as a transition rather than only as two separate mounts — and
 * a transition is where a remount would hide.
 */
let shortViewport = false
const mediaListeners = new Set<(event: MediaQueryListEvent) => void>()

beforeEach(() => {
  shortViewport = false
  mediaListeners.clear()
  sessionStorage.clear()
  resetProgrammerTableStore()
  vi.stubGlobal('matchMedia', (query: string) => ({
    get matches() {
      return query === '(max-height: 500px)' ? shortViewport : false
    },
    media: query,
    addEventListener: (_: string, cb: (event: MediaQueryListEvent) => void) =>
      mediaListeners.add(cb),
    removeEventListener: (_: string, cb: (event: MediaQueryListEvent) => void) =>
      mediaListeners.delete(cb),
    addListener: () => {},
    removeListener: () => {},
    onchange: null,
    dispatchEvent: () => false,
  }))
})

function setShortViewport(next: boolean) {
  shortViewport = next
  act(() => {
    for (const cb of mediaListeners) cb({ matches: next } as MediaQueryListEvent)
  })
}

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
  vi.unstubAllGlobals()
  window.localStorage.clear()
})

function Where() {
  const { pathname, search } = useLocation()
  return <div data-testid="where">{pathname + search}</div>
}

function draw(path = '/projects/1/programmer') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/projects/:projectId/programmer" element={<ProgrammerPage />} />
      </Routes>
      <Where />
    </MemoryRouter>,
  )
}

/** The visible row B's tab for [table]. Every table draws the tabs; only the shown one counts. */
function tableTab(name: RegExp) {
  const lists = screen.getAllByRole('tablist', { name: 'Programmer tables' }).filter((list) => list.closest('[inert]') == null)
  expect(lists).toHaveLength(1)
  return within(lists[0]).getByRole('tab', { name })
}

describe('ProgrammerPage', () => {
  it('draws the grid with no side panel beside it', () => {
    // Colour and Fan are the grid's own editors — a Colour cell's popover and row C's Fan — so
    // nothing docks on the right.
    draw()
    expect(screen.getByTestId('grid')).toBeTruthy()
    expect(screen.queryByRole('complementary')).toBeNull()
    expect(screen.queryByRole('separator')).toBeNull()
  })

  it('draws no show bar — the one live view without one', () => {
    // The space plan's session 5, as it was actually decided at the desk: rather than folding
    // `ShowHeader` into `ShowBar` on all four live views, the programmer simply stops drawing the
    // bar and the other three are left alone. That is ~60px of blackout, tempo, cue numbers and
    // transport returned to the grid, on the one page whose whole subject is editing values.
    //
    // The header stays, because the switcher in it is how you reach a view that HAS the bar. Blind
    // is not an argument for the bar any more: `PD-BLIND-ON-PROGRAMMER` made it the action bar's
    // own control (pinned in `ProgrammerActionBar.test.tsx`), so if this ever fails, check that
    // nobody brought the bar back for some other tile — see the note beside the header in
    // `ProgrammerPage`.
    //
    // Tempo is not an argument for it either, and `PD-SPEED-OVERLAY` is why: the bank is reached
    // from the Speed Masters *overview panel*, which `Layout` hangs under the app header on every
    // route. That is not this page's chrome and is not rendered by this page, so it cannot show
    // up here — which is exactly what keeps this assertion true through that change.
    draw()
    expect(screen.queryByTestId('show-bar')).toBeNull()
    expect(screen.getByTestId('header')).toBeTruthy()
  })

  it('rests on Values, with nothing that lists beside it', () => {
    draw()
    expect(screen.getAllByRole('tablist').map((list) => list.getAttribute('aria-label'))).toEqual(['Programmer tables'])
    expect(tableTab(/^Values/).getAttribute('aria-selected')).toBe('true')
    expect(screen.queryByLabelText('Add a look layer')).toBeNull()
    expect(screen.queryByLabelText('Add a layer or an effect')).toBeNull()
    expect(screen.queryByText('Per-fixture FX')).toBeNull()
  })

  it('mounts the value grid exactly once across a tab switch, hidden and inert under the other tables', () => {
    // The load-bearing rule for the tabs: `useListSelection` clears the scoped selection on
    // unmount, so the grid stays mounted under Stack, FX and Scenery, which mount on demand.
    draw()
    expect(gridMounts).toHaveBeenCalledTimes(1)
    const values = () => screen.getByTestId('grid').closest('[data-programmer-table="values"]')!

    for (const [tab, testId, table] of [
      [/^Stack/, 'stack-table', 'stack'],
      [/^FX/, 'fx-table', 'fx'],
      [/^Scenery/, 'scenery-table', 'scenery'],
    ] as const) {
      fireEvent.click(tableTab(tab))
      expect(screen.getByTestId(testId)).toBeTruthy()
      expect(screen.getByTestId('where').textContent).toBe(`/projects/1/programmer?table=${table}`)
      expect(values()).toHaveAttribute('inert')
      expect(values()).toHaveClass('hidden')
      expect(gridMounts).toHaveBeenCalledTimes(1)
    }

    fireEvent.click(tableTab(/^Values/))
    expect(screen.queryByTestId('scenery-table')).toBeNull()
    expect(values()).not.toHaveAttribute('inert')
    expect(values()).not.toHaveClass('hidden')
    expect(screen.getByTestId('where').textContent).toBe('/projects/1/programmer?table=values')
    expect(gridMounts).toHaveBeenCalledTimes(1)
  })

  it('moves between the tables from the keyboard, one tab stop, focus following the table', async () => {
    draw()
    const values = tableTab(/^Values/)
    expect(values).toHaveAttribute('tabindex', '0')
    expect(tableTab(/^Stack/)).toHaveAttribute('tabindex', '-1')
    expect(values).toHaveAttribute('aria-controls', 'programmer-table-values')
    expect(document.getElementById('programmer-table-values')).toHaveAttribute('role', 'tabpanel')

    values.focus()
    fireEvent.keyDown(values, { key: 'ArrowRight' })
    expect(screen.getByTestId('stack-table')).toBeTruthy()
    expect(screen.getByTestId('where').textContent).toBe('/projects/1/programmer?table=stack')
    await act(() => new Promise((resolve) => requestAnimationFrame(() => resolve(undefined))))
    expect(document.activeElement?.closest('#programmer-table-stack')).not.toBeNull()

    fireEvent.keyDown(tableTab(/^Stack/), { key: 'End' })
    expect(screen.getByTestId('scenery-table')).toBeTruthy()
    fireEvent.keyDown(tableTab(/^Scenery/), { key: 'ArrowRight' })
    expect(screen.getByTestId('where').textContent).toBe('/projects/1/programmer?table=values')
    expect(gridMounts).toHaveBeenCalledTimes(1)
  })

  it('opens on the table the address names', () => {
    draw('/projects/1/programmer?table=fx')
    expect(screen.getByTestId('fx-table')).toBeTruthy()
    expect(tableTab(/^FX/).getAttribute('aria-selected')).toBe('true')
    expect(gridMounts).toHaveBeenCalledTimes(1)
  })

  it('always names what is loaded, above the verbs that act on it', () => {
    draw()
    expect(screen.getByTestId('source-strip')).toBeTruthy()
    expect(screen.getByTestId('action-bar')).toBeTruthy()
  })

  it('mounts the value grid exactly once, across a state change elsewhere on the page', () => {
    // The load-bearing one. `useListSelection` clears its Redux scope on unmount, so anything that
    // remounts the grid — a tab, a collapse, a conditional — silently discards the fixture
    // selection Record and Record-look scope on. Toggling Groups is a real page-state change; the
    // grid must re-render through it, never remount. The toggle now renders on row B, inside the
    // grid's toolbar — which is exactly why it is a page-state change worth asserting: the state
    // still lives in `ProgrammerBody`, above the grid.
    draw()
    expect(gridMounts).toHaveBeenCalledTimes(1)

    fireEvent.click(screen.getByTitle('Show group rows with their members'))
    expect(gridMounts).toHaveBeenCalledTimes(1)
  })

  it('mounts the value grid exactly once across a scope change', () => {
    // The same rule, for the thing session 2a adds. Switching Output/Local/one layer must be a
    // re-render of one grid, never a swap between per-scope grids — the moment it becomes a
    // conditional mount or a `key`, the fixture selection is silently gone.
    draw()
    expect(gridMounts).toHaveBeenCalledTimes(1)

    fireEvent.click(screen.getByLabelText('Show the composed output'))
    fireEvent.click(screen.getByLabelText('Show only the values you set'))
    expect(gridMounts).toHaveBeenCalledTimes(1)
  })

  it('mounts the value grid exactly once across the short-height fold, both ways', () => {
    // Row A stops being drawn above the tables and its two halves are handed to the grid's
    // toolbar as `leading`. The components move; the grid element does not, and that is the whole
    // of the rule. Driven as a live media change so the transition is what is asserted.
    draw()
    expect(gridMounts).toHaveBeenCalledTimes(1)
    expect(screen.getAllByTestId('source-strip')).toHaveLength(1)

    setShortViewport(true)
    // Still exactly one of each — folded means moved, never duplicated.
    expect(screen.getAllByTestId('source-strip')).toHaveLength(1)
    expect(screen.getAllByTestId('action-bar')).toHaveLength(1)
    // And now inside the grid's own toolbar rather than above the tables.
    expect(screen.getByTestId('grid').contains(screen.getByTestId('source-strip'))).toBe(true)
    expect(gridMounts).toHaveBeenCalledTimes(1)

    setShortViewport(false)
    expect(screen.getByTestId('grid').contains(screen.getByTestId('source-strip'))).toBe(false)
    expect(gridMounts).toHaveBeenCalledTimes(1)
  })

  it('keeps row A on a row of its own under another table, folded or not', () => {
    // Row A folds into the grid's row B, and the grid's row B is hidden under the other tables —
    // so there it keeps its own row, and the source box and verbs never leave the page.
    draw()
    setShortViewport(true)
    expect(screen.getByTestId('grid').contains(screen.getByTestId('source-strip'))).toBe(true)

    fireEvent.click(tableTab(/^Stack/))
    expect(screen.getAllByTestId('source-strip')).toHaveLength(1)
    expect(screen.getByTestId('grid').contains(screen.getByTestId('source-strip'))).toBe(false)

    fireEvent.click(tableTab(/^Values/))
    expect(screen.getByTestId('grid').contains(screen.getByTestId('source-strip'))).toBe(true)
    expect(gridMounts).toHaveBeenCalledTimes(1)
  })

  it('names what the grid is pointed at', () => {
    draw()
    // Local is the landing scope: the programmer opens on what you are about to busk, not on a
    // read-only view of the cook.
    expect(screen.getByLabelText('Show only the values you set')).toHaveAttribute(
      'data-state',
      'on',
    )
    fireEvent.click(screen.getByLabelText('Show the composed output'))
    expect(screen.getByLabelText('Show the composed output')).toHaveAttribute('data-state', 'on')
  })

  it('offers no layer segment while no layer is focused', () => {
    // Focusing happens on the Stack table. A picker here would be a second way to say the same
    // thing, and this segment is a read-out of that choice.
    draw()
    expect(screen.queryByLabelText('Show the focused layer')).toBeNull()
  })
})
