// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation, useNavigate } from 'react-router'
import { Provider } from 'react-redux'
import type { CueStack } from '@/api/cueStacksApi'

vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

/**
 * Characterisation suite written **before** session 2b merges Run into Show
 * (`desk-simplification-plan.md` §Session 2b, phase 0). 2b changes how a cue is *addressed* — it
 * folds Run's local `Set` of expanded cues into this view — and "deep links survive" is one of its
 * own stated rules, so the addressing needs a net first. There was none: §8 records that
 * `ShowPage`, `StackDetail`, `ShowOverview`, `ShowView` and `CueCardEditor` are all untested.
 *
 * `?cue=` is an external contract — the Prompt Book's "Edit cue" mints it and the `/program*`
 * redirects carry the search string precisely to keep it — so everything here is about the URL,
 * not about what a cue row draws.
 */

// ── Controllable data layer. The URL logic is what's under test; the network is not. ──
const stacksState = {
  data: undefined as CueStack[] | undefined,
  isLoading: false,
  isFetching: false,
}
const programState = {
  data: undefined as { activeStackId: number | null; canEdit?: boolean } | undefined,
}

vi.mock('../store/cueStacks', () => ({
  useProjectCueStackListQuery: () => stacksState,
  useProjectProgramStateQuery: () => programState,
  useActivateProgramMutation: () => [vi.fn(() => ({ unwrap: () => Promise.resolve({}) }))],
  useDeactivateProgramMutation: () => [vi.fn(() => ({ unwrap: () => Promise.resolve({}) }))],
  useGoToStackMutation: () => [goToStack],
  useDeactivateCueStackMutation: () => [deactivateCueStack],
}))
vi.mock('../store/cues', () => ({
  useCreateProjectCueMutation: () => [vi.fn(() => ({ unwrap: () => Promise.resolve({ id: 77 }) }))],
}))
vi.mock('../store/promptBooks', () => ({
  useProjectCueLocationsQuery: () => ({ data: [] }),
  useProjectPromptBookQuery: () => ({ data: undefined }),
}))
const blindState = { blind: false }
vi.mock('../store/programmer', () => ({
  useProgrammerSummaryQuery: () => ({ data: blindState }),
}))
// Desktop by default; one case flips it to exercise the phone layout.
const narrow = { value: false }
vi.mock('../hooks/useContainerBand', () => ({
  useNarrowContainer: () => [vi.fn(), narrow.value],
}))
const stripProbe: { onSelectStack?: (s: CueStack) => void } = {}
vi.mock('../components/runner/StackTabStrip', () => ({
  StackTabStrip: (p: {
    selectedStackId: number | null
    liveStackId: number | null
    onSelectStack: (s: CueStack) => void
    unlockedWarning?: boolean
  }) => {
    stripProbe.onSelectStack = p.onSelectStack
    return (
      <div
        data-testid="tab-strip"
        data-selected={p.selectedStackId}
        data-live={p.liveStackId}
        data-warn={String(p.unlockedWarning)}
      />
    )
  },
}))
const bannerProbe: { onMakeLive?: () => void; onJumpToLive?: () => void } = {}
vi.mock('../components/runner/OffPlayheadBanner', () => ({
  OffPlayheadBanner: (p: {
    liveStackName: string | null
    onMakeLive: () => void
    onJumpToLive: () => void
  }) => {
    bannerProbe.onMakeLive = p.onMakeLive
    bannerProbe.onJumpToLive = p.onJumpToLive
    return <div data-testid="off-playhead">{p.liveStackName}</div>
  },
}))
vi.mock('../components/runner/mobile/RunMobile', () => ({
  RunMobile: () => <div data-testid="mobile" />,
}))
vi.mock('../components/runner/ShowLockControl', () => ({
  ShowLockControl: (p: { locked: boolean; onToggle: () => void }) => (
    <button data-testid="lock" data-locked={p.locked} onClick={p.onToggle}>
      lock
    </button>
  ),
}))
vi.mock('../store/projects', () => ({
  useCurrentProjectQuery: () => ({ data: { id: 1 }, isLoading: false }),
  useProjectQuery: () => ({ data: { id: 1, name: 'Hamlet' }, isLoading: false }),
}))
const goToStack = vi.fn(() => ({ unwrap: () => Promise.resolve({}) }))
const deactivateCueStack = vi.fn(() => ({ unwrap: () => Promise.resolve({}) }))
const transportSpies = {
  go: vi.fn(),
  back: vi.fn(),
  setStandby: vi.fn(),
  cancelAnimations: vi.fn(),
}
const barPropsOpts: { canOperate?: boolean; onBeforeGo?: () => void } = {}
vi.mock('../hooks/useShowBarProps', () => ({
  useShowBarProps: (_projectId: number, opts: { onBeforeGo?: () => void } = {}) => {
    barPropsOpts.onBeforeGo = opts.onBeforeGo
    return ({
    showBarProps: {
      dbo: false,
      onDbo: vi.fn(),
      goDisabled: false,
      stackName: 'Act 1',
      fade: null,
    },
    showHeaderProps: { isShowActive: false, canStart: false, onStart: vi.fn(), onStop: vi.fn() },
    transport: {
      standbyCueId: null,
      completedCueIds: [],
      fadeProgress: null,
      fadeRemainMs: null,
      autoProgress: null,
      activeCueId: null,
      // Derived exactly as the real hook derives it, from the same controllable data — the page
      // reads the server cursor through the transport now, not off the cache directly.
      serverActiveCueId:
        programState.data?.activeStackId != null
          ? (stacksState.data?.find((s) => s.id === programState.data!.activeStackId)
              ?.activeCueId ?? null)
          : null,
      go: transportSpies.go,
      back: transportSpies.back,
      setStandby: transportSpies.setStandby,
      cancelAnimations: transportSpies.cancelAnimations,
    },
    nextStack: null,
    activeCue: null,
    standbyCue: null,
    })
  },
}))
vi.mock('../components/programmer/useInclude', () => ({
  useInclude: () => ({ includeCue: vi.fn(), isLoading: false }),
}))
vi.mock('../components/ShowHeader', () => ({
  ShowHeader: (p: { actions?: React.ReactNode; unlockedWarning?: boolean }) => (
    <div data-testid="header" data-warn={p.unlockedWarning}>
      {p.actions}
    </div>
  ),
}))
const barProps: {
  onBlind?: () => void
  showShortcuts?: boolean
  stackName?: string | null
  unlockedWarning?: boolean
} = {}
vi.mock('../components/ShowBar', () => ({
  ShowBar: (p: {
    onBlind?: () => void
    showShortcuts?: boolean
    stackName?: string | null
    unlockedWarning?: boolean
  }) => {
    barProps.onBlind = p.onBlind
    barProps.showShortcuts = p.showShortcuts
    barProps.stackName = p.stackName
    barProps.unlockedWarning = p.unlockedWarning
    return <div data-testid="show-bar" />
  },
}))
vi.mock('../components/programmer/RecordSheet', () => ({
  RecordSheet: () => <div data-testid="record-sheet" />,
}))

// A probe standing in for the whole cue list: reports what the page derived from the URL, and
// gives the test the two callbacks the real view drives.
const probe: {
  drillStackId: number | null
  openedCueId: number | null
  activeCueId: number | null
  locked?: boolean
  unlockedWarning?: boolean
  isExpanded: (id: number) => boolean
  toggle: (id: number) => void
  drill: (id: number | null) => void
} = {
  drillStackId: null,
  openedCueId: null,
  activeCueId: null,
  isExpanded: () => false,
  toggle: () => {},
  drill: () => {},
}
vi.mock('../components/runner/ShowView', () => ({
  ShowView: (p: {
    drillStackId: number | null
    openedCueId?: number | null
    activeCueId: number | null
    locked?: boolean
    unlockedWarning?: boolean
    isExpanded: (id: number) => boolean
    onToggleExpanded: (id: number) => void
    onDrillStack: (id: number | null) => void
  }) => {
    probe.drillStackId = p.drillStackId
    probe.openedCueId = p.openedCueId ?? null
    probe.activeCueId = p.activeCueId
    probe.locked = p.locked
    probe.unlockedWarning = p.unlockedWarning
    probe.isExpanded = p.isExpanded
    probe.toggle = p.onToggleExpanded
    probe.drill = p.onDrillStack
    return <div data-testid="program-view" />
  },
}))

import { store } from '../store'
import { lockRequested } from '../store/editLockSlice'
import { ShowPage } from './ShowPage'

const mkStack = (over: Partial<CueStack> = {}): CueStack => ({
  id: 10,
  name: 'Act 1',
  loop: false,
  sortOrder: 0,
  type: 'STACK',
  label: null,
  cues: [],
  activeCueId: null,
  nextCueId: null,
  canEdit: true,
  canDelete: true,
  ...over,
})

function Where() {
  const l = useLocation()
  return <div data-testid="where">{l.pathname + l.search}</div>
}

/** A back button, so "does this write history?" can be asked directly. */
function GoBack() {
  const navigate = useNavigate()
  return <button onClick={() => navigate(-1)}>back</button>
}

function where() {
  return screen.getByTestId('where').textContent
}

// A fresh element per render: handed an identical one React bails out of re-rendering the subtree,
// so a data change would look like it had been ignored. Same type in the same position is still a
// re-render, not a remount.
function tree(entries: string[]) {
  return (
    <Provider store={store}>
    <MemoryRouter initialEntries={entries}>
      <Where />
      <GoBack />
      <Routes>
        <Route path="/projects/:projectId/show" element={<ShowPage />} />
        <Route path="/projects/:projectId/show/stacks/:stackId" element={<ShowPage />} />
        <Route path="/elsewhere" element={<div>elsewhere</div>} />
      </Routes>
    </MemoryRouter>
    </Provider>
  )
}

function draw(entries: string[]) {
  return render(tree(entries))
}

beforeEach(() => {
  stacksState.data = [mkStack(), mkStack({ id: 11, name: 'Act 2', sortOrder: 1 })]
  stacksState.isLoading = false
  stacksState.isFetching = false
  programState.data = { activeStackId: null, canEdit: true }
  blindState.blind = false
  narrow.value = false
  store.dispatch(lockRequested())
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe('ShowPage URL contract', () => {
  it('derives the drilled stack from the path', () => {
    draw(['/projects/1/show/stacks/11'])
    expect(probe.drillStackId).toBe(11)
  })

  it('derives the expanded cue from ?cue=', () => {
    draw(['/projects/1/show/stacks/10?cue=42'])
    expect(probe.openedCueId).toBe(42)
    expect(probe.isExpanded(42)).toBe(true)
    expect(probe.isExpanded(41)).toBe(false)
  })

  it('expands the live cue without claiming the URL', () => {
    // Changed in session 2b. Drilling in used to *write* `?cue=<live>`, which put "what is on
    // stage" and "what I am reading" in one slot — so the next GO overwrote the operator's card.
    // The live card is derived now, and the URL stays theirs.
    programState.data = { activeStackId: 10, canEdit: true }
    stacksState.data = [mkStack({ activeCueId: 5 })]
    draw(['/projects/1/show/stacks/10'])

    expect(where()).toBe('/projects/1/show/stacks/10')
    expect(probe.isExpanded(5)).toBe(true)
  })

  it('keeps both the operator’s card and the live one open', () => {
    // The reason the live card is derived. A GO changes what is live and never touches `?cue=`.
    programState.data = { activeStackId: 10, canEdit: true }
    stacksState.data = [mkStack({ activeCueId: 5 })]
    draw(['/projects/1/show/stacks/10?cue=8'])

    expect(probe.isExpanded(8)).toBe(true)
    expect(probe.isExpanded(5)).toBe(true)
  })

  it('expands nothing on its own in a stack that is not live', () => {
    programState.data = { activeStackId: 11, canEdit: true }
    stacksState.data = [mkStack({ activeCueId: 5 }), mkStack({ id: 11, sortOrder: 1 })]
    draw(['/projects/1/show/stacks/10'])
    expect(probe.isExpanded(5)).toBe(false)
  })

  it('addresses one cue at a time', () => {
    // The single scalar in the URL is the one-at-a-time rule — for the *addressed* card. The live
    // one sits alongside it rather than competing for the slot.
    draw(['/projects/1/show/stacks/10'])
    act(() => probe.toggle(1))
    expect(where()).toBe('/projects/1/show/stacks/10?cue=1')
    act(() => probe.toggle(2))
    expect(where()).toBe('/projects/1/show/stacks/10?cue=2')
  })

  it('clears ?cue= rather than leaving it empty', () => {
    draw(['/projects/1/show/stacks/10?cue=1'])
    act(() => probe.toggle(1))
    expect(where()).toBe('/projects/1/show/stacks/10')
  })

  it('closes the live card without addressing it', () => {
    // Collapsing a derived card cannot be a deletion from a set, so it is remembered separately —
    // and it must not write the URL, or dismissing the live card would address it.
    programState.data = { activeStackId: 10, canEdit: true }
    stacksState.data = [mkStack({ activeCueId: 5 })]
    draw(['/projects/1/show/stacks/10'])
    expect(probe.isExpanded(5)).toBe(true)

    act(() => probe.toggle(5))
    expect(probe.isExpanded(5)).toBe(false)
    expect(where()).toBe('/projects/1/show/stacks/10')
  })

  it('re-opens the live card on the next cue after one is dismissed', () => {
    // Dismissal must not be sticky beyond that cue, or the operator silently stops being shown
    // what is outputting for the rest of the show.
    programState.data = { activeStackId: 10, canEdit: true }
    stacksState.data = [mkStack({ activeCueId: 5 })]
    draw(['/projects/1/show/stacks/10'])
    act(() => probe.toggle(5))
    expect(probe.isExpanded(5)).toBe(false)

    stacksState.data = [mkStack({ activeCueId: 6 })]
    act(() => probe.drill(10))
    expect(probe.isExpanded(6)).toBe(true)
  })

  it('does not grow history when the addressed cue changes', () => {
    // `{ replace: true }` on the `?cue=` write. Without it, every chevron is a history entry and
    // Back walks the operator through their own expansions instead of leaving the view.
    draw(['/elsewhere', '/projects/1/show/stacks/10'])
    act(() => probe.toggle(1))
    act(() => probe.toggle(2))
    expect(where()).toBe('/projects/1/show/stacks/10?cue=2')

    act(() => screen.getByText('back').click())
    expect(where()).toBe('/elsewhere')
  })

  it('drills into the live stack on arrival, once', () => {
    programState.data = { activeStackId: 11, canEdit: true }
    draw(['/projects/1/show'])
    expect(screen.getByTestId('where')).toHaveTextContent('/projects/1/show/stacks/11')
  })

  it('the Stacks button leaves a stack while the show runs, and stays left', () => {
    // The auto-drill above used to fire again on the way back: `/show` and `/show/stacks/:id` are
    // sibling routes with an `element` each, so leaving a stack REMOUNTS the page and resets the
    // ref that was meant to make it fire once. The signal has to ride the location.
    programState.data = { activeStackId: 11, canEdit: true }
    draw(['/projects/1/show/stacks/11'])
    act(() => {
      probe.drill(null)
    })
    expect(screen.getByTestId('where')).toHaveTextContent('/projects/1/show')
    expect(screen.getByTestId('where')).not.toHaveTextContent('/stacks/')
  })

  it('redirects out of a stack that has vanished', () => {
    draw(['/projects/1/show/stacks/404'])
    expect(where()).toBe('/projects/1/show')
  })

  it('waits for the list to settle before calling a stack stale', () => {
    // The create-then-refetch bounce: during the refetch that follows creating a stack, the list
    // briefly lacks the new stack. Redirecting then throws the operator straight back out of the
    // stack they just made.
    stacksState.isFetching = true
    draw(['/projects/1/show/stacks/404'])
    expect(where()).toBe('/projects/1/show/stacks/404')
  })

  it('passes the server active cue down, not the fade cursor', () => {
    // The marker must not jitter mid-fade, which is why this is the server's value. 2b keeps this
    // reading AND adds the optimistic one alongside it for the fade chrome — two props, no mode.
    programState.data = { activeStackId: 10 }
    stacksState.data = [mkStack({ activeCueId: 5 })]
    draw(['/projects/1/show/stacks/10'])
    expect(probe.activeCueId).toBe(5)
  })

  it('reports no active cue for a stopped show', () => {
    draw(['/projects/1/show/stacks/10'])
    expect(probe.activeCueId).toBeNull()
  })

  it('shows the bar whether or not the show is running', () => {
    // Changed in 2b. The bar carries blackout, the speed masters and the programmer chip — all
    // meaningful with the show down — and gating it was what once made Blind's *location* depend
    // on the show's state. `goDisabled` already mutes BACK/GO.
    draw(['/projects/1/show/stacks/10'])
    expect(screen.getByTestId('show-bar')).toBeTruthy()

    cleanup()
    programState.data = { activeStackId: 10, canEdit: true }
    draw(['/projects/1/show/stacks/10'])
    expect(screen.getByTestId('show-bar')).toBeTruthy()
  })

  it('redirects to the current project when addressed with another one', () => {
    draw(['/projects/7/show'])
    expect(where()).toBe('/projects/1/show')
  })
})

describe('ShowPage as the merged run/edit surface', () => {
  it('offers no lock while the show is stopped', () => {
    // A stopped show is simply editable — there is nothing to protect and so nothing to warn
    // about. A permanently-open padlock would be chrome describing nothing.
    draw(['/projects/1/show/stacks/10'])
    expect(screen.queryByTestId('lock')).toBeNull()
    expect(probe.locked).toBe(false)
  })

  it('locks itself as soon as the show is running', () => {
    programState.data = { activeStackId: 10, canEdit: true }
    draw(['/projects/1/show/stacks/10'])
    expect(screen.getByTestId('lock').getAttribute('data-locked')).toBe('true')
    expect(probe.locked).toBe(true)
  })

  it('hands the rows over to editing when unlocked', () => {
    programState.data = { activeStackId: 10, canEdit: true }
    draw(['/projects/1/show/stacks/10'])
    act(() => screen.getByTestId('lock').click())
    expect(probe.locked).toBe(false)
  })

  it('stays locked where the backend would refuse the edit', () => {
    // `canEdit` is the project being current, not a role. Offering an unlock that can only 4xx
    // would be worse than not offering one.
    programState.data = { activeStackId: 10, canEdit: false }
    draw(['/projects/1/show/stacks/10'])
    expect(screen.queryByTestId('lock')).toBeNull()
    expect(probe.locked).toBe(true)
  })

  it('takes the bar whole from the shared hook, and adds no Blind of its own', () => {
    // The bar is identical on all three live views because everything but `showShortcuts` comes
    // from `useShowBarProps`, so nothing can be present on one view and missing on another — which
    // is what happened when each host wired the bar itself. Blind is the case that proves it from
    // the other side: the hook supplies no press for ANY host (`PD-BLIND-ON-PROGRAMMER` made it the
    // programmer's action bar's, reported elsewhere by `ProgrammerIndicator`), and this page must
    // not hand-wire one back in.
    programState.data = { activeStackId: 10, canEdit: true }
    draw(['/projects/1/show/stacks/10'])
    expect(barProps.onBlind).toBeUndefined()
    expect(barProps.stackName).toBe('Act 1')
  })

  it('advertises the transport keys only where they act', () => {
    programState.data = { activeStackId: 10, canEdit: true }
    draw(['/projects/1/show/stacks/10'])
    expect(barProps.showShortcuts).toBe(true)

    act(() => screen.getByTestId('lock').click())
    expect(barProps.showShortcuts).toBe(false)
  })

  it('fires GO from the keyboard while locked, and not while unlocked', () => {
    // Unlocked, the row's cue numbers, names and fades are all live text fields, so Space is a
    // space. `L` stays bound either way — see `useTransportKeys`.
    programState.data = { activeStackId: 10, canEdit: true }
    draw(['/projects/1/show/stacks/10'])
    fireEvent.keyDown(window, { code: 'Space' })
    expect(transportSpies.go).toHaveBeenCalledTimes(1)

    act(() => screen.getByTestId('lock').click())
    fireEvent.keyDown(window, { code: 'Space' })
    expect(transportSpies.go).toHaveBeenCalledTimes(1)
  })

  it('shows the stack switcher only inside a stack', () => {
    // The stack *list* is its own switcher, so the strip has nothing to say there. Asserted with
    // the show stopped, because a running show auto-drills off the overview on first mount.
    draw(['/projects/1/show'])
    expect(screen.queryByTestId('tab-strip')).toBeNull()

    cleanup()
    programState.data = { activeStackId: 10, canEdit: true }
    draw(['/projects/1/show/stacks/10'])
    const strip = screen.getByTestId('tab-strip')
    expect(strip.getAttribute('data-selected')).toBe('10')
    expect(strip.getAttribute('data-live')).toBe('10')
  })

  it('says where the show is when reading a stack that is not playing', () => {
    programState.data = { activeStackId: 10, canEdit: true }
    draw(['/projects/1/show/stacks/11'])
    expect(screen.getByTestId('off-playhead').textContent).toBe('Act 1')
  })

  it('says nothing when reading the stack that is playing', () => {
    programState.data = { activeStackId: 10, canEdit: true }
    draw(['/projects/1/show/stacks/10'])
    expect(screen.queryByTestId('off-playhead')).toBeNull()
  })

  it('gives the rows a fade source only in the live stack', () => {
    // A stack being read has no fade of its own, and the running stack's runner says nothing about
    // these rows.
    programState.data = { activeStackId: 10, canEdit: true }
    draw(['/projects/1/show/stacks/11'])
    expect(probe.drillStackId).toBe(11)
    expect(screen.getByTestId('program-view')).toBeTruthy()
  })

  it('is the phone runner below the narrow threshold, and cannot be unlocked there', () => {
    // Mobile is a running surface with no room for editing chrome, so there is nothing an unlocked
    // state could reveal.
    programState.data = { activeStackId: 10, canEdit: true }
    narrow.value = true
    draw(['/projects/1/show/stacks/10'])
    expect(screen.getByTestId('mobile')).toBeTruthy()
    expect(screen.queryByTestId('program-view')).toBeNull()
    expect(screen.queryByTestId('lock')).toBeNull()
    expect(screen.queryByTestId('show-bar')).toBeNull()
  })
})

describe('ShowPage following the playhead', () => {
  it('follows a boundary GO while standing on the playhead', () => {
    // Otherwise a boundary GO leaves the operator reading the act that just finished.
    programState.data = { activeStackId: 10, canEdit: true }
    const { rerender } = draw(['/projects/1/show/stacks/10'])
    expect(where()).toBe('/projects/1/show/stacks/10')

    programState.data = { activeStackId: 11, canEdit: true }
    act(() => rerender(tree(['/projects/1/show/stacks/10'])))
    expect(probe.drillStackId).toBe(11)
  })

  it('does not drag a browsing operator along', () => {
    // The condition that makes the follow safe: it fires only for someone who *was* watching the
    // playhead. Reading Act 2 while Act 1 runs and then hearing a GO must not move the view.
    programState.data = { activeStackId: 10, canEdit: true }
    const { rerender } = draw(['/projects/1/show/stacks/11'])
    expect(probe.drillStackId).toBe(11)

    programState.data = { activeStackId: 10, canEdit: true }
    stacksState.data = [mkStack({ activeCueId: 3 }), mkStack({ id: 11, sortOrder: 1 })]
    act(() => rerender(tree(['/projects/1/show/stacks/11'])))
    expect(probe.drillStackId).toBe(11)
  })
})

describe('ShowPage browsing versus arming', () => {
  it('selecting a stack navigates and touches no server state', () => {
    // The heart of the split. This used to run `deactivate(old) → goToStack → deactivate(target)`,
    // so one unconfirmed press took the live cue off stage and repositioned every other client.
    programState.data = { activeStackId: 10, canEdit: true }
    draw(['/projects/1/show/stacks/10'])
    act(() => stripProbe.onSelectStack!(stacksState.data![1]))

    expect(where()).toBe('/projects/1/show/stacks/11')
    expect(goToStack).not.toHaveBeenCalled()
    expect(deactivateCueStack).not.toHaveBeenCalled()
  })

  it('jumping to live is navigation, not a server call', () => {
    programState.data = { activeStackId: 10, canEdit: true }
    draw(['/projects/1/show/stacks/11'])
    act(() => bannerProbe.onJumpToLive!())

    expect(where()).toBe('/projects/1/show/stacks/10')
    expect(goToStack).not.toHaveBeenCalled()
  })

  it('moving the playhead is an explicit act, and only that', () => {
    // The one path that still calls `go-to`. No client-side deactivate of the stack being left —
    // `POST /show/go-to` already does that server-side.
    programState.data = { activeStackId: 10, canEdit: true }
    draw(['/projects/1/show/stacks/11'])
    act(() => bannerProbe.onMakeLive!())

    expect(goToStack).toHaveBeenCalledWith({ projectId: 1, stackId: 11 })
    expect(deactivateCueStack).not.toHaveBeenCalledWith({ projectId: 1, stackId: 10 })
  })
})

describe('ShowPage unlocked warning', () => {
  it('washes the header while a running show is unlocked', () => {
    // The state worth being unmistakable about, and the same signal the Prompt Book gives: believing
    // you are locked when you are not is how a show gets edited by accident.
    programState.data = { activeStackId: 10, canEdit: true }
    draw(['/projects/1/show/stacks/10'])
    expect(screen.getByTestId('header').getAttribute('data-warn')).toBe('false')

    act(() => screen.getByTestId('lock').click())
    expect(screen.getByTestId('header').getAttribute('data-warn')).toBe('true')
  })

  it('does not wash a stopped show, which is simply editable', () => {
    // Nothing to be wrong about: there is no lock, so there is nothing to warn about.
    draw(['/projects/1/show/stacks/10'])
    expect(screen.getByTestId('header').getAttribute('data-warn')).toBe('false')
    expect(barProps.unlockedWarning).toBe(false)
    expect(screen.getByTestId('tab-strip').getAttribute('data-warn')).toBe('false')
    expect(probe.unlockedWarning).toBe(false)
  })

  it('tints the whole chrome band together, not just the header', () => {
    // The load-bearing one. The band is four siblings — header, show bar, tab strip, navigation
    // row — and tinting only some of them reads as stripes rather than as one state. That is
    // exactly what a header-only wash looked like.
    programState.data = { activeStackId: 10, canEdit: true }
    draw(['/projects/1/show/stacks/10'])
    act(() => screen.getByTestId('lock').click())

    expect(screen.getByTestId('header').getAttribute('data-warn')).toBe('true')
    expect(barProps.unlockedWarning).toBe(true)
    expect(screen.getByTestId('tab-strip').getAttribute('data-warn')).toBe('true')
    expect(probe.unlockedWarning).toBe(true)
  })
})

describe('ShowPage lock feedback', () => {
  it('ends a fix-it session on GO', () => {
    // `onBeforeGo` is how the lock hears about GO. Without it, unlocking mid-show and pressing GO
    // left the show running *and* editable — and the Prompt Book, which shares the same slice, did
    // re-lock, so the two disagreed about one piece of state.
    programState.data = { activeStackId: 10, canEdit: true }
    draw(['/projects/1/show/stacks/10'])
    act(() => screen.getByTestId('lock').click())
    expect(screen.getByTestId('lock').getAttribute('data-locked')).toBe('false')

    expect(barPropsOpts.onBeforeGo).toBeTypeOf('function')
    act(() => barPropsOpts.onBeforeGo!())
    expect(screen.getByTestId('lock').getAttribute('data-locked')).toBe('true')
  })

  it('an interaction with the body defers the idle re-lock', () => {
    // The countdown is armed the moment you unlock, so without a `noteEdit` call site it fired
    // regardless of activity — two minutes of renaming cues and it re-locks mid-keystroke.
    programState.data = { activeStackId: 10, canEdit: true }
    draw(['/projects/1/show/stacks/10'])
    act(() => screen.getByTestId('lock').click())

    // Capture-phase on the body wrapper, so one boundary covers every affordance below it.
    const body = screen.getByTestId('program-view').parentElement!
    act(() => fireEvent.pointerDown(body))
    expect(screen.getByTestId('lock').getAttribute('data-locked')).toBe('false')
  })
})
