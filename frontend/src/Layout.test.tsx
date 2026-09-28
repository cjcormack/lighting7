// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { RouterProvider } from 'react-router/dom'
import { createMemoryRouter } from 'react-router'

/**
 * `Layout`'s one immersive boolean (busk-chrome plan D7, D10): the window's fact *and* a live
 * view. Everything the shell mounts around the routed page is mocked to a marker — this is a test
 * of what `Layout` draws and skips, not of the forty things it hosts (`useSidebarOpen.test.tsx`
 * argues the same about the sidebar preference and tests the hook instead; here the boolean has
 * no hook of its own to test, because the skipping *is* the behaviour).
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
vi.mock('./ProjectSwitcher', () => ({ default: () => <div data-testid="project-switcher" /> }))
vi.mock('./components/auth/UserMenu', () => ({ UserMenu: () => null }))
vi.mock('./components/FixtureOverviewPanel', () => ({ FixtureOverviewPanel: () => <div data-testid="panel-fixtures" /> }))
vi.mock('./components/StageOverviewPanel', () => ({ StageOverviewPanel: () => <div data-testid="panel-stage" /> }))
vi.mock('./components/SpeedMasterOverviewPanel', () => ({ SpeedMasterOverviewPanel: () => <div data-testid="panel-speed" /> }))
vi.mock('./components/CueSlotOverviewPanel', () => ({ CueSlotOverviewPanel: () => <div data-testid="panel-slots" /> }))
vi.mock('./components/groups/FixtureDetailModal', () => ({ FixtureDetailModal: () => null }))
vi.mock('./components/overviewPanels', () => {
  const panel = (id: string) => ({ id, label: id, noun: id, icon: () => null, storageKey: id, isVisible: false, toggle: () => {} })
  const panels = ['stage', 'fixtures', 'speedMasters', 'cueSlots'].map(panel)
  return {
    useOverviewPanels: () => ({ panels, byId: Object.fromEntries(panels.map((p) => [p.id, p])) }),
    OverviewToggle: () => null,
  }
})
vi.mock('./components/ai/AiChatToggle', () => ({ AiChatToggle: () => null }))
vi.mock('./components/dnd/DeskDndProvider', () => ({ DeskDndProvider: ({ children }: { children: React.ReactNode }) => <>{children}</> }))
// The palette's toggles are recorded so the View group can be asserted without rendering cmdk.
vi.mock('./components/CommandPalette', () => ({
  default: (props: { toggles?: { label: string }[] }) => (
    <div data-testid="palette-toggles">{(props.toggles ?? []).map((t) => t.label).join('|')}</div>
  ),
}))
vi.mock('./components/fx/AddEditFxSheet', () => ({ AddEditFxSheet: () => null }))
vi.mock('./components/ChannelValueDialog', () => ({ ChannelValueDialog: () => null }))
vi.mock('./components/cloudSync/SyncNotifications', () => ({ SyncNotifications: () => null }))
vi.mock('./components/cloudSync/SyncReauthBanner', () => ({ SyncReauthBanner: () => <div data-testid="banner-reauth" /> }))
vi.mock('./components/screens/ReturnToFullscreenBanner', () => ({ ReturnToFullscreenBanner: () => null }))
vi.mock('./components/hand/HandChip', () => ({ HandChip: () => <div data-testid="hand-chip" /> }))
vi.mock('./components/screens/ScreensSheet', () => ({ ScreensSheet: () => null }))
vi.mock('./components/screens/useWindowsBridge', () => ({ useWindowsBridge: () => {} }))

import Layout from './Layout'
import { resetImmersiveStore, setImmersive } from './lib/immersive'
import { useOpenMobileDrawer } from './components/mobileDrawerContext'

/** The routed page: what the `ShowHeader` does below `md` while immersive, reduced to its opener. */
function Page() {
  const openDrawer = useOpenMobileDrawer()
  return (
    <div data-testid="page">
      <button onClick={() => openDrawer?.()}>open drawer</button>
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
      expect(screen.getByTestId('panel-stage')).toBeInTheDocument()
      expect(screen.getByTestId('panel-slots')).toBeInTheDocument()
      // The 64px rail: the sidebar starts collapsed on a live view.
      expect(content(container).style.marginLeft).toBe('64px')
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

  it('withholds the four panel toggles from the palette while immersive, keeping Lux', async () => {
    const { unmount } = draw('/projects/1/busk')
    await screen.findByTestId('page')
    expect(screen.getByTestId('palette-toggles')).toHaveTextContent('stage|fixtures|speedMasters|cueSlots|Lux (AI Chat)')
    act(() => setImmersive(true))
    // A row reading "On" for a panel that is not mounted would be a control reporting a state
    // it is not in; the AI panel is outside the gate and stays offered.
    expect(screen.getByTestId('palette-toggles')).toHaveTextContent(/^Lux \(AI Chat\)$/)
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
