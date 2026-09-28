// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { canChooseDisplay, isLoopbackHost, listDisplays, newWindowUrl, nextScreenName, openWindowOn, windowSetupUrl } from './screens'

/**
 * Opening a new desk window (multi-screen plan D10, D13): the `?window=` URL with `%20` for the
 * space — the launcher's spelling (`DeskScreens.screenUrl`) — the next free *Screen N*, and the
 * Chrome-only display list behind a feature check.
 */
afterEach(() => {
  delete (window as { getScreenDetails?: unknown }).getScreenDetails
  vi.restoreAllMocks()
})

describe('newWindowUrl', () => {
  it('spells the space as %20, never +, so a tray-named and a sheet-named window agree', () => {
    expect(newWindowUrl('Screen 2', '/', 'http://localhost:8413')).toBe('http://localhost:8413/?window=Screen%202')
    expect(newWindowUrl('Chris’s iPad', '/', 'http://desk.local:8413')).toBe(
      'http://desk.local:8413/?window=Chris%E2%80%99s%20iPad',
    )
  })

  it('carries a view path when one is given, and trims the name', () => {
    expect(newWindowUrl(' Screen 2 ', '/projects/1/busk', 'http://localhost:8413')).toBe(
      'http://localhost:8413/projects/1/busk?window=Screen%202',
    )
  })
})

describe('windowSetupUrl', () => {
  it('carries page, focus and sheet in that order, each only when the row has it', () => {
    expect(windowSetupUrl('Screen 2', '/projects/1/busk', { sheet: 'show', focus: 'pads', page: '3' }, 'http://desk')).toBe(
      'http://desk/projects/1/busk?window=Screen%202&page=3&focus=pads&sheet=show',
    )
    expect(windowSetupUrl('Screen 2', '/projects/1/show', null, 'http://desk')).toBe('http://desk/projects/1/show?window=Screen%202')
  })

  it('carries immersive only while it is on — off is what every window boots with (busk-chrome D9)', () => {
    expect(windowSetupUrl('Screen 2', '/projects/1/show', { immersive: 'on' }, 'http://desk')).toBe(
      'http://desk/projects/1/show?window=Screen%202&immersive=on',
    )
    expect(windowSetupUrl('Screen 2', '/projects/1/show', { immersive: 'off' }, 'http://desk')).toBe('http://desk/projects/1/show?window=Screen%202')
    expect(windowSetupUrl('Screen 2', '/projects/1/busk', { focus: 'rig', immersive: 'on' }, 'http://desk')).toBe(
      'http://desk/projects/1/busk?window=Screen%202&focus=rig&immersive=on',
    )
  })
})

describe('nextScreenName', () => {
  it('takes the smallest free Screen N, case-insensitively', () => {
    expect(nextScreenName([])).toBe('Screen 1')
    expect(nextScreenName(['Screen 1'])).toBe('Screen 2')
    expect(nextScreenName(['screen 1', 'Screen 3', 'iPad'])).toBe('Screen 2')
  })
})

describe('isLoopbackHost', () => {
  it('knows the spellings of this machine', () => {
    for (const host of ['localhost', 'LOCALHOST', '127.0.0.1', '127.1.2.3', '[::1]', '::1', '0.0.0.0']) {
      expect(isLoopbackHost(host)).toBe(true)
    }
    expect(isLoopbackHost('lighting7-desk.local')).toBe(false)
    expect(isLoopbackHost('192.168.1.20')).toBe(false)
  })
})

describe('the display list', () => {
  it('is absent without getScreenDetails, and lists the screens with the current one marked', async () => {
    expect(canChooseDisplay()).toBe(false)
    const current = { availLeft: 0, availTop: 0, availWidth: 1440, availHeight: 900, width: 1440, height: 900 }
    const other = { availLeft: 1440, availTop: 0, availWidth: 1920, availHeight: 1080, width: 1920, height: 1080 }
    ;(window as { getScreenDetails?: unknown }).getScreenDetails = async () => ({ screens: [current, other], currentScreen: current })
    expect(canChooseDisplay()).toBe(true)
    expect(await listDisplays()).toEqual([
      { label: 'Display 1 · 1440×900', left: 0, top: 0, width: 1440, height: 900, isCurrent: true },
      { label: 'Display 2 · 1920×1080', left: 1440, top: 0, width: 1920, height: 1080, isCurrent: false },
    ])
  })

  it('opens the window at the display’s bounds with noopener, so no sessionStorage is inherited', () => {
    const open = vi.spyOn(window, 'open').mockImplementation(() => null)
    openWindowOn({ label: 'Display 2 · 1920×1080', left: 1440, top: 0, width: 1920, height: 1080, isCurrent: false }, 'http://localhost:8413/?window=Screen%202')
    expect(open).toHaveBeenCalledWith(
      'http://localhost:8413/?window=Screen%202',
      '_blank',
      'left=1440,top=0,width=1920,height=1080,noopener',
    )
  })
})
