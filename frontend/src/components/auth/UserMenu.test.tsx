// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { DeskWindow } from '@/api/windowsApi'

// Store-connected; the menu *contents* are Radix dropdown children that need pointer-event
// polyfills in jsdom, so the avatar tests stay on the always-rendered trigger, and the item tests
// open the menu with the keyboard, which Radix handles without a pointer. The factory is hoisted
// above everything else in this file.
let statusResult: { data?: { setupRequired: boolean; authenticated: boolean; user?: unknown } } = {}

vi.mock('@/store/auth', () => ({
  useAuthStatusQuery: () => statusResult,
  useLogoutMutation: () => [() => ({ unwrap: () => Promise.resolve() }), { isLoading: false }],
  // ProfileSheet is mounted alongside the trigger, so its hooks have to exist too.
  useChangePasswordMutation: () => [() => ({ unwrap: () => Promise.resolve() }), { isLoading: false }],
  useUpdateProfileMutation: () => [() => ({ unwrap: () => Promise.resolve() }), { isLoading: false }],
  useRevokeOtherSessionsMutation: () => [() => ({ unwrap: () => Promise.resolve() }), { isLoading: false }],
  useSessionsQuery: () => ({ data: [] }),
  useConnectedAppsQuery: () => ({ data: [] }),
  useRevokeConnectedAppMutation: () => [() => ({ unwrap: () => Promise.resolve() }), { isLoading: false }],
  useCreateDeviceLoginMutation: () => [() => ({ unwrap: () => Promise.resolve() }), { isLoading: false }],
  useCancelDeviceLoginMutation: () => [() => ({ unwrap: () => Promise.resolve() }), { isLoading: false }],
  useDeviceLoginStatusQuery: () => ({}),
}))

const registry: { windows: DeskWindow[] } = { windows: [] }
vi.mock('@/store/windows', () => ({ useDeskWindows: () => registry.windows }))
vi.mock('@/lib/windowIdentity', () => ({ useWindowName: () => 'Screen 1' }))

const fullscreen = { active: false, can: true, enter: vi.fn(), exit: vi.fn() }
vi.mock('@/lib/fullscreen', () => ({
  useFullscreenState: () => ({ active: fullscreen.active, wanted: false }),
  canFullscreen: () => fullscreen.can,
  enterFullscreen: () => fullscreen.enter(),
  exitFullscreen: () => fullscreen.exit(),
}))

const screensSheet = { open: vi.fn() }
vi.mock('@/components/screens/screensSheetState', () => ({ openScreensSheet: () => screensSheet.open() }))

import { UserMenu } from './UserMenu'

// jsdom has no `matchMedia`. The menu renders `ThemeMenuItem` on both branches, whose
// `getInitialTheme` falls back to the OS preference when nothing is stored — so without this the
// render throws `not a function` and reads as a component bug rather than a missing browser API.
// Per-file rather than in `test/setup.ts`, which is the convention the other callers follow
// (`ProgrammerPage.test.tsx`, `BuskingView.test.tsx`) and which `lib/theme.test.ts` depends on,
// since it stubs `window` wholesale.
vi.stubGlobal('matchMedia', (query: string) => ({
  matches: false,
  media: query,
  addEventListener: () => {},
  removeEventListener: () => {},
}))

function signedInAs(displayName: string) {
  statusResult = {
    data: {
      setupRequired: false,
      authenticated: true,
      user: { uuid: 'u-1', username: 'admin', displayName, role: 'ADMIN' },
    },
  }
}

const row = (id: string, name: string): DeskWindow => ({
  id,
  windowId: `w-${id}`,
  name,
  view: '/projects/1/programmer',
  fullscreen: false,
  follows: true,
  user: null,
  viewOptions: null,
})

/** Open the menu the keyboard way: Radix opens on Enter at the trigger with no pointer needed. */
function openMenu() {
  const trigger = screen.getByRole('button', { name: /^Signed in as/ })
  fireEvent.keyDown(trigger, { key: 'Enter' })
  return screen.getByRole('menu')
}

afterEach(() => {
  cleanup()
  statusResult = {}
  registry.windows = []
  fullscreen.active = false
  fullscreen.can = true
  vi.clearAllMocks()
})

describe('UserMenu', () => {
  it('keeps the per-viewer items reachable on a bootstrap-open desk — theme, full screen, Screens…', () => {
    // No users exist yet, so there is no identity to show; the setup screen asks for one. The menu
    // still opens, because three of its items are about this viewer and not about an account. It
    // once returned a bare theme button here, which lost the other two the day they were added.
    statusResult = { data: { setupRequired: true, authenticated: false } }
    registry.windows = [row('s-1', 'Screen 1')]
    render(<UserMenu />)

    expect(screen.queryByRole('button', { name: /^Signed in as/ })).toBeNull()
    fireEvent.keyDown(screen.getByRole('button', { name: 'Menu' }), { key: 'Enter' })
    const items = screen.getAllByRole('menuitem').map((item) => item.textContent)
    expect(items.some((t) => t?.startsWith('Switch to'))).toBe(true)
    expect(items.some((t) => t?.startsWith('Full screen'))).toBe(true)
    expect(items.some((t) => t?.startsWith('Screens…'))).toBe(true)
    expect(items.some((t) => t === 'Log out' || t === 'Profile…')).toBe(false)
    expect(screen.getByRole('menu')).toHaveTextContent('this window is Screen 1')
  })

  describe('the avatar initials', () => {
    const initials = (displayName: string) => {
      signedInAs(displayName)
      render(<UserMenu />)
      return screen.getByRole('button', { name: `Signed in as ${displayName}` }).textContent
    }

    it('takes the first and last word', () => {
      expect(initials('Chris Cormack')).toBe('CC')
    })

    it('skips a parenthesised qualifier rather than showing punctuation', () => {
      // "Chris C (desk)" — a desk, room or role in brackets is the commonest shape here, and
      // taking the first character of the last word blindly rendered "C(".
      expect(initials('Chris C (desk)')).toBe('CC')
    })

    it('uses two letters when there is only one word', () => {
      expect(initials('Cher')).toBe('CH')
    })

    it('falls back to a question mark when no word starts with a letter or digit', () => {
      expect(initials('!!! ???')).toBe('?')
    })
  })

  describe('the screen items (multi-screen plan §4, Screens.dc.html §1)', () => {
    it('says which window this is, and lists Full screen ⇧F and Screens… with the window count', () => {
      signedInAs('Chris Cormack')
      registry.windows = [row('s-1', 'Screen 1'), row('s-2', 'Screen 2'), row('s-3', 'Chris’s iPad')]
      render(<UserMenu />)
      const menu = openMenu()

      expect(menu).toHaveTextContent('this window is Screen 1')
      const items = screen.getAllByRole('menuitem').map((item) => item.textContent)
      // Between the theme item and Log out, in that order.
      const theme = items.findIndex((t) => t?.startsWith('Switch to'))
      const full = items.findIndex((t) => t?.startsWith('Full screen'))
      const screens = items.findIndex((t) => t?.startsWith('Screens…'))
      const logout = items.findIndex((t) => t === 'Log out')
      expect([theme, full, screens, logout].every((i) => i >= 0)).toBe(true)
      expect(theme < full && full < screens && screens < logout).toBe(true)
      expect(items[full]).toContain('⇧F')
      expect(items[screens]).toContain('3 windows')
      // Not full screen: no exit glyph anywhere in the label.
      expect(screen.queryByRole('button', { name: 'Exit full screen' })).toBeNull()
    })

    it('opens the Screens sheet and enters full screen from the two items', () => {
      signedInAs('Chris Cormack')
      registry.windows = [row('s-1', 'Screen 1')]
      render(<UserMenu />)
      openMenu()
      fireEvent.click(screen.getByRole('menuitem', { name: /^Screens…/ }))
      expect(screensSheet.open).toHaveBeenCalledTimes(1)

      openMenu()
      fireEvent.click(screen.getByRole('menuitem', { name: /^Full screen/ }))
      expect(fullscreen.enter).toHaveBeenCalledTimes(1)
    })

    it('draws the exit glyph on the label line only while full screen, and flips the item', () => {
      signedInAs('Chris Cormack')
      fullscreen.active = true
      render(<UserMenu />)
      openMenu()

      const glyph = screen.getByRole('button', { name: 'Exit full screen' })
      fireEvent.click(glyph)
      expect(fullscreen.exit).toHaveBeenCalledTimes(1)
      expect(screen.getByRole('menuitem', { name: /^Exit full screen/ })).toBeInTheDocument()
      expect(screen.queryByRole('menuitem', { name: /^Full screen/ })).toBeNull()
    })

    it('draws no Full screen item at all where the browser has no Fullscreen API (D13)', () => {
      signedInAs('Chris Cormack')
      fullscreen.can = false
      render(<UserMenu />)
      openMenu()
      expect(screen.queryByRole('menuitem', { name: /full screen/i })).toBeNull()
      expect(screen.getByRole('menuitem', { name: /^Screens…/ })).toBeInTheDocument()
    })

    it('counts one window in the singular', () => {
      signedInAs('Chris Cormack')
      registry.windows = [row('s-1', 'Screen 1')]
      render(<UserMenu />)
      openMenu()
      expect(screen.getByRole('menuitem', { name: /^Screens…/ })).toHaveTextContent('1 window')
    })
  })
})
