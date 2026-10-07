// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * The successor to `ProgrammerPane.test.tsx`.
 *
 * That suite mount-counted the three tab bodies, because Radix mounts only the active tab's content
 * and `useListSelection` clears its Redux scope on unmount — so glancing at the layer stack silently
 * discarded the fixture selection Record scopes on. The pane needed a `forceMount` escape hatch for
 * exactly that. Here there is nothing to force, and these assertions are what keeps it that way:
 * all three surfaces on screen at once, and the grid mounted exactly once — across a scope
 * switch, a rail collapse, a drag on the rail's handle, an overlay open/close (session 3), and
 * the two ways session 4 added for the page to change shape around it: the phone's bottom sheet,
 * and the short-height fold that moves row A into row B.
 */
const gridMounts = vi.fn()
vi.mock('@/components/programmer/ProgrammerGrid', async () => {
  const { useEffect } = await import('react')
  // Row B — the scope pills and the Groups toggle — lives in the grid's own `renderToolbar` slot
  // since the space plan's session 1, so the page reaches both *through* this stand-in. The band
  // itself is the real one: the assertions below are about which scope the page is in, and a
  // second fake would only pin the fake.
  const { ProgrammerScopeBand } = await import('@/components/programmer/ProgrammerScopeBand')
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
// The hand's place target in the rail footer — store-touching, and this suite renders without a
// Provider, like every other child mocked here.
vi.mock('@/components/hand/HandLayerTargets', () => ({ HandProgrammerLayerStrip: () => null }))
vi.mock('@/components/programmer/ProgrammerLookStack', () => ({
  ProgrammerLookStack: () => <div data-testid="layers" />,
}))
vi.mock('@/components/programmer/ProgrammerFxList', () => ({
  ProgrammerFxList: () => <div data-testid="fx" />,
}))
// The rail's `+ Effect` offer reads the Redux selection; the sheets behind the footer drag in the
// whole picker and the FX authoring form. The rail itself is real — its header, strip and footer
// are what the collapse and overlay cases below press.
vi.mock('@/components/programmer/ProgrammerAddEffect', () => ({
  useProgrammerAddEffect: () => ({
    disabled: true,
    reason: 'no selection',
    target: null,
    onCreated: vi.fn(),
  }),
  ProgrammerAddEffectSheet: () => null,
}))
// The rail's two docked editors have their own tests (`RailColourTab.test.tsx`); here they only have
// to mount and unmount around one grid.
vi.mock('@/components/programmer/RailColourTab', () => ({
  RailColourTab: () => <div data-testid="rail-colour" />,
}))
vi.mock('@/components/programmer/RailSpreadTab', () => ({
  RailSpreadTab: () => <div data-testid="rail-spread" />,
}))
// The Scenery band has a suite of its own (`RailSceneryBand.test.tsx`); here it is a landmark with
// a ref, and the held count is a value the test sets.
vi.mock('@/components/programmer/RailSceneryBand', async () => {
  const { forwardRef } = await import('react')
  return {
    RailSceneryBand: forwardRef<HTMLDivElement>(function Band(_, ref) {
      return <div ref={ref} data-testid="scenery-band" />
    }),
  }
})
vi.mock('@/components/programmer/ProgrammerSceneryList', () => ({ useHeldSceneryCount: () => sceneryHeld.count }))
const sceneryHeld = vi.hoisted(() => ({ count: 0 }))
vi.mock('@/components/programmer/ProgrammerAddLayerSheet', () => ({
  ProgrammerAddLayerSheet: () => null,
}))
vi.mock('@/store/fixtureFx', () => ({ useActiveEffectsQuery: () => ({ data: [] }) }))
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
  useInclude: () => ({ includeCue: vi.fn() }),
}))
vi.mock('@/hooks/useShowBarProps', () => ({
  useShowBarProps: () => ({
    isShowActive: true,
    showBarProps: {},
    showHeaderProps: { isShowActive: true, canStart: false, onStart: vi.fn(), onStop: vi.fn() },
  }),
}))
vi.mock('@/store/programmer', () => ({
  useProgrammerSummaryQuery: () => ({ data: { blind: false, entryCount: 0, lastIncluded: null } }),
  useProgrammerLayersQuery: () => ({ data: [] }),
  useProgrammerRevision: () => 0,
  programmerClearAll: vi.fn(),
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
// Same reason as `@/store/looks` above, for `FocusedTemplateLayerProvider`: `skip` stops the
// *request*, not the hook, so the query still reaches for the store.
vi.mock('@/store/templates', () => ({ useTemplateListQuery: () => ({ data: [] }) }))
vi.mock('@/store/fixtures', () => ({
  useFixtureListQuery: () => ({ data: [] }),
  useVisibleFixtureListQuery: () => ({ data: [] }),
}))
vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

import { ProgrammerPage } from './ProgrammerPage'

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

function draw() {
  return render(
    <MemoryRouter initialEntries={['/projects/1/programmer']}>
      <Routes>
        <Route path="/projects/:projectId/programmer" element={<ProgrammerPage />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('ProgrammerPage', () => {
  it('shows values, layers and effects at once', () => {
    draw()
    expect(screen.getByTestId('grid')).toBeTruthy()
    expect(screen.getByTestId('layers')).toBeTruthy()
    expect(screen.getByTestId('fx')).toBeTruthy()
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

  it('puts no tab between values, layers and effects — the one strip is the rail header, resting on Stack', () => {
    // The three are readings of ONE live object. A switcher between them is the thing this view
    // exists to delete. Editor-kit session 4 gave the *rail* a tab strip — Stack · Colour · Spread —
    // and Stack is the layers and the effects together, beside the grid, as they always were: the
    // strip adds two docked editors, it separates none of the three readings. Every arrival rests
    // on Stack (call 10), so all three are on screen on arrival.
    draw()
    const tablists = screen.getAllByRole('tablist')
    expect(tablists).toHaveLength(1)
    expect(tablists[0].getAttribute('aria-label')).toBe('Rail')
    expect(screen.getAllByRole('tab').map((tab) => tab.getAttribute('aria-label'))).toEqual(['Stack', 'Colour', 'Spread'])
    expect(screen.getByRole('tab', { name: 'Stack' }).getAttribute('aria-selected')).toBe('true')
    expect(screen.getByTestId('grid')).toBeTruthy()
    expect(screen.getByTestId('layers')).toBeTruthy()
    expect(screen.getByTestId('fx')).toBeTruthy()
  })

  it('mounts the value grid exactly once across a rail tab change', () => {
    // The load-bearing rule, for the thing session 4 adds: the Colour and Spread tabs are the
    // rail's, and switching to them must re-render the page around one grid, never remount it —
    // the marquee they read lives in that grid, and so does the fixture selection Record scopes on.
    draw()
    expect(gridMounts).toHaveBeenCalledTimes(1)

    fireEvent.click(screen.getByRole('tab', { name: 'Colour' }))
    expect(screen.getByRole('tab', { name: 'Colour' }).getAttribute('aria-selected')).toBe('true')
    expect(screen.getByTestId('rail-colour')).toBeTruthy()
    expect(screen.queryByTestId('layers')).toBeNull()
    fireEvent.click(screen.getByRole('tab', { name: 'Spread' }))
    expect(screen.getByTestId('rail-spread')).toBeTruthy()
    fireEvent.click(screen.getByRole('tab', { name: 'Stack' }))
    expect(gridMounts).toHaveBeenCalledTimes(1)
    expect(screen.getByTestId('layers')).toBeTruthy()
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

  it('mounts the value grid exactly once across a rail collapse and expand', () => {
    // Session 3's rule, stated in the plan: the *rail's* contents may unmount freely — nothing in
    // it owns a selection — but the grid beside it must only re-render as the rail comes and
    // goes. A `key` on the row, or a conditional around the grid column, would fail this.
    draw()
    expect(gridMounts).toHaveBeenCalledTimes(1)
    expect(screen.getByTestId('layers')).toBeTruthy()

    fireEvent.click(screen.getByLabelText('Collapse the rail'))
    expect(screen.queryByTestId('layers')).toBeNull()
    expect(gridMounts).toHaveBeenCalledTimes(1)

    fireEvent.click(screen.getByLabelText('Expand the rail'))
    expect(screen.getByTestId('layers')).toBeTruthy()
    expect(gridMounts).toHaveBeenCalledTimes(1)
  })

  it('mounts the value grid exactly once across a drag on the rail handle', () => {
    // The width is state held below the memo barrier and reaches the rail as a CSS variable;
    // every pointer move re-renders the workspace frame and must reach the grid as nothing.
    draw()
    fireEvent.pointerDown(screen.getByRole('separator', { name: 'Resize the rail' }), {
      button: 0,
      clientX: 1000,
    })
    fireEvent.pointerMove(window, { clientX: 900 })
    fireEvent.pointerMove(window, { clientX: 850 })
    fireEvent.pointerUp(window)
    expect(gridMounts).toHaveBeenCalledTimes(1)
    expect(window.localStorage.getItem('programmer.rail.width')).toBe('450')
  })

  it('mounts the value grid exactly once across an overlay open and close', () => {
    // The narrow arm: the strip opens the rail *over* the grid, and Escape, the rail's own
    // chevron or a press on the grid close it. Both flags are in the DOM under jsdom (the arms
    // are container queries), so this drives the narrow arm's controls directly.
    draw()
    fireEvent.click(screen.getByLabelText('Collapse the rail'))
    const stripToggle = () => screen.getByRole('button', { name: 'Open the rail' })
    fireEvent.click(stripToggle())
    expect(screen.getByTestId('layers')).toBeTruthy()
    // The strip is off screen while the body is up, so its chevron is open-only and carries no
    // `aria-expanded` — there is no longer a moment when both it and the header's close chevron
    // are on screen for the state to tell apart.
    expect(gridMounts).toHaveBeenCalledTimes(1)

    fireEvent.keyDown(window, { key: 'Escape' })
    expect(screen.queryByTestId('layers')).toBeNull()

    fireEvent.click(stripToggle())
    fireEvent.pointerDown(screen.getByTestId('grid'))
    expect(screen.queryByTestId('layers')).toBeNull()

    // The strip's chevron no longer closes it — the strip is off screen while the body is up,
    // so it is open-only, and a second press on it is the same press as the first. Closing is
    // Escape, a press on the grid, and the overlay's own header chevron.
    fireEvent.click(stripToggle())
    fireEvent.click(stripToggle())
    expect(screen.getByTestId('layers')).toBeTruthy()
    fireEvent.click(screen.getByLabelText('Close the rail'))
    expect(screen.queryByTestId('layers')).toBeNull()
    expect(gridMounts).toHaveBeenCalledTimes(1)
  })

  it('mounts the value grid exactly once across the bottom sheet opening and closing', () => {
    // Session 4's arm. Below 704px of workspace the rail is a 44px handle across the bottom and
    // the body moves into a `Sheet side="bottom"` — a change of *place* for the rail, which must
    // still be no change at all for the grid. Both arms are in the DOM under jsdom (they are
    // container queries), so this drives the handle's own controls directly.
    draw()
    expect(gridMounts).toHaveBeenCalledTimes(1)

    const opener = screen.getByRole('button', { name: 'Open the layers and effects' })
    expect(opener).toHaveAttribute('aria-expanded', 'false')
    fireEvent.click(opener)
    expect(screen.getByTestId('layers')).toBeTruthy()
    // One body, not two: the docked frame is not rendered while the sheet holds it.
    expect(screen.getAllByTestId('layers')).toHaveLength(1)
    expect(screen.queryByRole('complementary', { name: 'Layers and effects' })).toBeNull()
    expect(gridMounts).toHaveBeenCalledTimes(1)

    fireEvent.keyDown(document.body, { key: 'Escape' })
    expect(screen.getAllByTestId('layers')).toHaveLength(1)
    expect(screen.getByRole('complementary', { name: 'Layers and effects' })).toBeTruthy()
    expect(gridMounts).toHaveBeenCalledTimes(1)
  })

  it('mounts the value grid exactly once across the short-height fold, both ways', () => {
    // Row A stops being drawn above the workspace and its two halves are handed to the grid's
    // toolbar as `leading`. The components move; the grid element does not, and that is the whole
    // of the rule. Driven as a live media change so the transition is what is asserted.
    draw()
    expect(gridMounts).toHaveBeenCalledTimes(1)
    expect(screen.getAllByTestId('source-strip')).toHaveLength(1)

    setShortViewport(true)
    // Still exactly one of each — folded means moved, never duplicated.
    expect(screen.getAllByTestId('source-strip')).toHaveLength(1)
    expect(screen.getAllByTestId('action-bar')).toHaveLength(1)
    // And now inside the grid's own toolbar rather than above the workspace.
    expect(screen.getByTestId('grid').contains(screen.getByTestId('source-strip'))).toBe(true)
    expect(gridMounts).toHaveBeenCalledTimes(1)

    setShortViewport(false)
    expect(screen.getByTestId('grid').contains(screen.getByTestId('source-strip'))).toBe(false)
    expect(gridMounts).toHaveBeenCalledTimes(1)
  })

  it('keeps every door reachable from the strip', () => {
    // The strip's `+` opens the same three doors as the footer, so nothing is reachable only with
    // the rail open. `+ Effect` says why it cannot open rather than vanishing.
    draw()
    fireEvent.click(screen.getByLabelText('Collapse the rail'))
    expect(screen.queryByLabelText('Add a look layer')).toBeNull()
    expect(screen.getByLabelText('Add a layer or an effect')).toBeTruthy()
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
    // Focusing happens on the stack row in the rail. A picker here would be a second way to say
    // the same thing, and this segment is a read-out of that choice.
    draw()
    expect(screen.queryByLabelText('Show the focused layer')).toBeNull()
  })
})
