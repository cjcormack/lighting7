// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { RouterProvider } from 'react-router/dom'
import { createMemoryRouter } from 'react-router'

/**
 * `Layout`'s one immersive boolean (busk-chrome plan D7, D10): the window's fact *and* a live
 * view. Everything the shell mounts around the routed page is mocked to a marker — this is a test
 * of what `Layout` draws and skips, not of the forty things it hosts. The sidebar's one state
 * (venue-visit plan D4) is driven here too, through its own toggle and the router, because the
 * defect it removes only showed on a navigation; `useSidebarOpen.test.tsx` pins the storage half.
 */

const media = { desktop: true }
vi.mock('@/hooks/useMediaQuery', () => ({
  useMediaQuery: () => media.desktop,
  MD_BREAKPOINT: '(min-width: 768px)',
  SM_BREAKPOINT: '(min-width: 640px)',
}))

// Each marker is written out inside its factory: `vi.mock` is hoisted above every declaration in
// this file, so a shared helper would be read before it exists.
vi.mock('./connection', () => ({ ConnectionStatus: () => <div data-testid="connection" /> }))
vi.mock('./components/ProgrammerIndicator', () => ({ ProgrammerIndicator: () => null }))
vi.mock('./ProjectSwitcher', () => ({ default: () => <div data-testid="project-switcher" />, useViewedProject: () => undefined }))
vi.mock('./components/venue/VenueBand', () => ({ VenueBand: () => <div data-testid="venue-band" /> }))
vi.mock('./components/venue/VenueBehindBanner', () => ({ VenueBehindBanner: () => <div data-testid="venue-behind" /> }))
vi.mock('./components/rigSafety/RigSafetyBanner', () => ({ RigSafetyBanner: () => <div data-testid="rig-safety" /> }))
vi.mock('./components/auth/UserMenu', () => ({ UserMenu: () => null }))
vi.mock('./components/FixtureOverviewPanel', () => ({ FixtureOverviewPanel: () => <div data-testid="panel-fixtures" /> }))
vi.mock('./components/positions/PositionsPanel', () => ({ PositionsPanel: () => <div data-testid="panel-stage" /> }))
vi.mock('./components/SpeedMasterOverviewPanel', () => ({ SpeedMasterOverviewPanel: () => <div data-testid="panel-speed" /> }))
vi.mock('./components/CueSlotOverviewPanel', () => ({ CueSlotOverviewPanel: () => <div data-testid="panel-slots" /> }))
vi.mock('./components/groups/FixtureDetailModal', () => ({ FixtureDetailModal: () => null }))
vi.mock('./components/overviewPanels', () => {
  const panel = (id: string) => ({ id, label: id, noun: id, icon: () => null, storageKey: id, isVisible: false, toggle: () => {} })
  const panels = ['stage', 'fixtures', 'speedMasters', 'grandMaster', 'cueSlots'].map(panel)
  return {
    useOverviewPanels: () => ({ panels, byId: Object.fromEntries(panels.map((p) => [p.id, p])) }),
    OverviewToggle: () => null,
  }
})
vi.mock('./components/dnd/DeskDndProvider', () => ({ DeskDndProvider: ({ children }: { children: React.ReactNode }) => <>{children}</> }))
// The palette's toggles are recorded so the View group can be asserted without rendering cmdk.
vi.mock('./components/CommandPalette', () => ({
  default: (props: { toggles?: { label: string }[] }) => (
    <div data-testid="palette-toggles">{(props.toggles ?? []).map((t) => t.label).join('|')}</div>
  ),
}))
vi.mock('./components/fx/FxAddSheet', () => ({ FxAddSheet: () => null }))
vi.mock('./components/ChannelValueDialog', () => ({ ChannelValueDialog: () => null }))
vi.mock('./components/cloudSync/SyncNotifications', () => ({ SyncNotifications: () => null }))
vi.mock('./components/cloudSync/SyncReauthBanner', () => ({ SyncReauthBanner: () => <div data-testid="banner-reauth" /> }))
vi.mock('./components/screens/ReturnToFullscreenBanner', () => ({ ReturnToFullscreenBanner: () => null }))
vi.mock('./components/hand/HandChip', () => ({ HandChip: () => <div data-testid="hand-chip" /> }))
vi.mock('./components/effects/ArmedChip', () => ({ ArmedChip: () => null }))
vi.mock('./components/GrandMasterOverviewPanel', () => ({ GrandMasterOverviewPanel: () => <div data-testid="panel-grand-master" /> }))
vi.mock('./components/effects/EffectsAnnouncer', () => ({ EffectsAnnouncer: () => null }))
vi.mock('./components/screens/ScreensSheet', () => ({ ScreensSheet: () => null }))
vi.mock('./components/stageRender/StageRenderHost', () => ({ StageRenderHost: () => null }))
vi.mock('./components/screens/useWindowsBridge', () => ({ useWindowsBridge: () => {} }))
// The cue action set's dialogs, held by `Layout` — here they say only which cue they are open on.
vi.mock('./components/programmer/RecordSheet', () => ({
  RecordSheet: (p: { targetCueId: number }) => <div data-testid="record-sheet">{p.targetCueId}</div>,
}))
vi.mock('./components/cues/CueDeleteDialog', () => ({ CueDeleteDialog: () => null }))
vi.mock('./components/appBar/AppBarSwitcher', () => ({ AppBarSwitcher: () => <div data-testid="app-switcher" /> }))
vi.mock('./components/appBar/DeskName', () => ({ DeskName: () => null, useDocumentTitle: () => {} }))

import Layout from './Layout'
import { resetImmersiveStore, setImmersive } from './lib/immersive'
import { useOpenMobileDrawer } from './components/mobileDrawerContext'
import { useCueActionDialogs } from './components/runner/cueActionDialogs'

/** The routed page: what the `ShowHeader` does below `md` while immersive, reduced to its opener. */
function Page() {
  const openDrawer = useOpenMobileDrawer()
  const dialogs = useCueActionDialogs()
  return (
    <div data-testid="page">
      <button onClick={() => openDrawer?.()}>open drawer</button>
      <button onClick={() => dialogs.openRecord(1, 12, 'Storm')}>record</button>
      {dialogs.local}
    </div>
  )
}

function draw(path: string) {
  const router = createMemoryRouter([{ path: '/', element: <Layout />, children: [{ path: '*', element: <Page /> }] }], {
    initialEntries: [path],
  })
  const view = render(<RouterProvider router={router} />)
  return { ...view, router }
}

const LIVE = ['/projects/1/programmer', '/projects/1/show', '/projects/1/prompt-book', '/projects/1/busk']

/** The desktop sidebar is the one `<aside>` on a desk; below `md` the mobile drawer is one too. */
const sidebar = (c: HTMLElement) => c.querySelector('aside')
const appHeader = (c: HTMLElement) => c.querySelector('header')
const content = (c: HTMLElement) => c.querySelector('main')!.parentElement as HTMLElement

beforeEach(() => {
  media.desktop = true
})

afterEach(() => {
  cleanup()
  resetImmersiveStore()
  window.sessionStorage.clear()
  window.localStorage.clear()
})

describe('the app around a live view', () => {
  it('is drawn on every live view while the fact is off', async () => {
    for (const path of LIVE) {
      const { container, unmount } = draw(path)
      expect(await screen.findByTestId('page')).toBeInTheDocument()
      expect(sidebar(container)).not.toBeNull()
      expect(appHeader(container)).not.toBeNull()
      // The grand master, summoned from the header like the others (venue-visit plan S5).
      expect(screen.getByTestId('panel-grand-master')).toBeInTheDocument()
      expect(screen.getByTestId('panel-stage')).toBeInTheDocument()
      expect(screen.getByTestId('panel-slots')).toBeInTheDocument()
      // Open, as everywhere else: there is one sidebar state, not a live-view rail (D4).
      expect(content(container).style.marginLeft).toBe('240px')
      unmount()
    }
  })

  it('is hidden on each of the four live paths with the fact on — sidebar, header, panels, margin', async () => {
    setImmersive(true)
    for (const path of LIVE) {
      const { container, unmount } = draw(path)
      expect(await screen.findByTestId('page')).toBeInTheDocument()
      expect(sidebar(container)).toBeNull()
      expect(appHeader(container)).toBeNull()
      for (const id of ['panel-stage', 'panel-fixtures', 'panel-speed', 'panel-slots']) {
        expect(screen.queryByTestId(id)).toBeNull()
      }
      expect(content(container).style.marginLeft).toBe('0px')
      // What is untouched (D7): the banners and the hand.
      expect(screen.getByTestId('banner-reauth')).toBeInTheDocument()
      expect(screen.getByTestId('hand-chip')).toBeInTheDocument()
      unmount()
    }
  })

  it('withholds the five panel toggles from the palette while immersive', async () => {
    const { unmount } = draw('/projects/1/busk')
    await screen.findByTestId('page')
    expect(screen.getByTestId('palette-toggles')).toHaveTextContent(/^stage\|fixtures\|speedMasters\|grandMaster\|cueSlots$/)
    act(() => setImmersive(true))
    // A row reading "On" for a panel that is not mounted would be a control reporting a state
    // it is not in.
    expect(screen.getByTestId('palette-toggles')).toBeEmptyDOMElement()
    unmount()
  })

  it('is drawn on /fixtures with the fact on — the list is navigated from the sidebar', async () => {
    setImmersive(true)
    const { container } = draw('/projects/1/fixtures')
    expect(await screen.findByTestId('page')).toBeInTheDocument()
    expect(sidebar(container)).not.toBeNull()
    expect(appHeader(container)).not.toBeNull()
    expect(screen.getByTestId('panel-stage')).toBeInTheDocument()
    // Open there by default, so the full width; the fact does not touch the margin either.
    expect(content(container).style.marginLeft).toBe('240px')
  })

  it('never lets a prefix answer: /program is not the programmer, /fx-library is not busk', async () => {
    setImmersive(true)
    for (const path of ['/projects/1/program', '/projects/1/fx-library']) {
      const { container, unmount } = draw(path)
      expect(await screen.findByTestId('page')).toBeInTheDocument()
      expect(appHeader(container)).not.toBeNull()
      unmount()
    }
  })

  it('comes and goes with the fact, live, without a remount of the page', async () => {
    const { container } = draw('/projects/1/busk')
    const page = await screen.findByTestId('page')
    act(() => setImmersive(true))
    expect(appHeader(container)).toBeNull()
    act(() => setImmersive(false))
    expect(appHeader(container)).not.toBeNull()
    expect(screen.getByTestId('page')).toBe(page)
  })

  it('returns when the window leaves a live view for the app and is waiting when it comes back', async () => {
    setImmersive(true)
    const { container, router } = draw('/projects/1/busk')
    await screen.findByTestId('page')
    expect(appHeader(container)).toBeNull()
    await act(async () => {
      await router.navigate('/projects/1/fixtures')
    })
    expect(appHeader(container)).not.toBeNull()
    await act(async () => {
      await router.navigate('/projects/1/show')
    })
    expect(appHeader(container)).toBeNull()
  })
})

/**
 * Venue-visit plan D4: one sidebar state across every view. Driven through the gesture — the
 * sidebar's own toggle button, then the router moving between a live view and the lists — because
 * the defect it removes only showed on a navigation: the sidebar changed shape as the route did.
 */
describe('the sidebar’s one state', () => {
  const toggleButton = (c: HTMLElement) => sidebar(c)!.querySelector('button')!
  const margin = (c: HTMLElement) => content(c).style.marginLeft

  it('keeps the shape it was given when the window moves between a live view and the lists', async () => {
    const { container, router } = draw('/projects/1/programmer')
    await screen.findByTestId('page')
    expect(margin(container)).toBe('240px')

    fireEvent.click(toggleButton(container))
    expect(margin(container)).toBe('64px')
    for (const path of ['/projects/1/fixtures', '/projects/1/show', '/install/diagnostics', '/projects/1/busk']) {
      await act(async () => {
        await router.navigate(path)
      })
      expect(margin(container)).toBe('64px')
    }

    // …and a toggle on the lists reaches the live views too.
    await act(async () => {
      await router.navigate('/projects/1/fixtures')
    })
    fireEvent.click(toggleButton(container))
    expect(margin(container)).toBe('240px')
    await act(async () => {
      await router.navigate('/projects/1/prompt-book')
    })
    expect(margin(container)).toBe('240px')
  })

  it('is hidden by immersive, not collapsed, and comes back as it was', async () => {
    const { container, router } = draw('/projects/1/fixtures')
    await screen.findByTestId('page')
    fireEvent.click(toggleButton(container))
    expect(margin(container)).toBe('64px')

    await act(async () => {
      await router.navigate('/projects/1/busk')
    })
    act(() => setImmersive(true))
    expect(sidebar(container)).toBeNull()
    expect(margin(container)).toBe('0px')
    // The preference is not written while immersive holds.
    expect(window.localStorage.getItem('layout.sidebarOpen')).toBe('false')

    act(() => setImmersive(false))
    expect(sidebar(container)).not.toBeNull()
    expect(margin(container)).toBe('64px')
  })

  it('comes back as it was on the lists too, with immersive still waiting on the live view', async () => {
    const { container, router } = draw('/projects/1/busk')
    await screen.findByTestId('page')
    act(() => setImmersive(true))
    expect(sidebar(container)).toBeNull()

    await act(async () => {
      await router.navigate('/projects/1/groups')
    })
    // Open by default, and immersive left it alone.
    expect(margin(container)).toBe('240px')
  })

  it('starts from a desk’s pre-D4 answer for the lists, on a live view too', async () => {
    window.localStorage.setItem('layout.sidebarOpen.other', 'false')
    window.localStorage.setItem('layout.sidebarOpen.live', 'true')
    const { container } = draw('/projects/1/show')
    await screen.findByTestId('page')
    expect(margin(container)).toBe('64px')
  })
})

describe('below md', () => {
  beforeEach(() => {
    media.desktop = false
  })

  it('keeps the drawer and opens it from the header’s lent button while immersive', async () => {
    setImmersive(true)
    const { container } = draw('/projects/1/busk')
    await screen.findByTestId('page')
    // The app header with its hamburger is gone…
    expect(appHeader(container)).toBeNull()
    // …the drawer is still here, shut…
    const drawer = container.querySelector('aside')!
    expect(drawer.style.transform).toBe('translateX(-100%)')
    // …and the opener the ShowHeader draws below `md` opens it.
    fireEvent.click(screen.getByRole('button', { name: 'open drawer' }))
    expect(drawer.style.transform).toBe('translateX(0)')
  })

  it('still draws the app header with its hamburger while the fact is off', async () => {
    const { container } = draw('/projects/1/busk')
    await screen.findByTestId('page')
    expect(appHeader(container)).not.toBeNull()
  })
})

describe('the cue action set’s dialogs', () => {
  it('outlive a GO that carries the Show view into the next stack — a new path', async () => {
    const { router } = draw('/projects/1/show/stacks/1')
    await screen.findByTestId('page')
    fireEvent.click(screen.getByRole('button', { name: 'record' }))
    expect(screen.getByTestId('record-sheet').textContent).toBe('12')
    // The Show view follows the playhead with a `replace` navigation to the new stack's path,
    // which remounts everything under the path-keyed error boundary.
    await act(() => router.navigate('/projects/1/show/stacks/2', { replace: true }))
    expect(screen.getByTestId('record-sheet').textContent).toBe('12')
  })
})
