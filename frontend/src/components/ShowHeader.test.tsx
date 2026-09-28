// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'

vi.mock('./SaveStatusIndicator', () => ({ SaveStatusIndicator: () => null }))
const desk = { connected: true }
vi.mock('@/store/status', () => ({ useIsDeskConnected: () => desk.connected }))
const media = { desktop: true }
vi.mock('@/hooks/useMediaQuery', () => ({
  useMediaQuery: () => media.desktop,
  MD_BREAKPOINT: '(min-width: 768px)',
  SM_BREAKPOINT: '(min-width: 640px)',
}))

import { ShowHeader } from './ShowHeader'
import { MobileDrawerContext } from './mobileDrawerContext'
import { isImmersive, resetImmersiveStore, setImmersive } from '@/lib/immersive'
import type { ShowView } from './ViewSwitcher'

/**
 * The amber wash is the lock's loudest signal, and it moved here from the Prompt Book's own toolbar
 * in session 2b so both lock-bearing views give it in the same place.
 */
function draw(over: Partial<React.ComponentProps<typeof ShowHeader>> = {}) {
  return render(
    <MemoryRouter>
      <ShowHeader
        view="show"
        projectId={1}
        projectName="Hamlet"
        isShowActive
        canStart={false}
        onStart={vi.fn()}
        onStop={vi.fn()}
        {...over}
      />
    </MemoryRouter>,
  )
}

const root = (c: HTMLElement) => c.firstElementChild!.className
const VIEWS: ShowView[] = ['programmer', 'show', 'prompt-book', 'busk']

beforeEach(() => {
  desk.connected = true
  media.desktop = true
})

afterEach(() => {
  cleanup()
  resetImmersiveStore()
  window.sessionStorage.clear()
})

describe('ShowHeader', () => {
  it('washes amber when a running show is unlocked', () => {
    const { container } = draw({ unlockedWarning: true })
    expect(root(container)).toContain('bg-amber-400/15')
    expect(root(container)).toContain('border-amber-500/50')
  })

  it('stays quiet otherwise', () => {
    const { container } = draw({ unlockedWarning: false })
    expect(root(container)).not.toContain('amber')
  })

  it('reserves the border in both states so the layout cannot shift', () => {
    // Colouring a border that isn't there would move the whole page down a pixel as the lock flips.
    const quiet = draw()
    expect(root(quiet.container)).toContain('border-b')
    expect(root(quiet.container)).toContain('border-transparent')
    quiet.unmount()

    const warned = draw({ unlockedWarning: true })
    expect(root(warned.container)).toContain('border-b')
  })

  // No test for "the breadcrumb names only the view": there is no `extra` prop to pass any more, so
  // that is a compile-time guarantee. A DOM assertion would only be brittle — "Show" appears in the
  // switcher pill as well as the trail.

  it('renders the actions slot, where the lock control lives', () => {
    draw({ actions: <button>lock</button> })
    expect(screen.getByText('lock')).toBeTruthy()
  })

  it('is the shell\u2019s 40px chrome row on the 12px gutter at every height (D12)', () => {
    // `CHROME_ROW_CLASS`: `h-10 px-3`, 40 on the box with its border, holding 32px controls like
    // every other chrome row on these views. It was `py-2` (48) after `p-4`; the header is the row
    // every other programmer row's left edge is read against, and it is shared, so all four live
    // views take it. A `py-*`, a `p-4` or a height media arm coming back here would put it out of
    // step with the rows under it again — the plan's own `py-1` measured 41 with the border.
    const { container } = draw()
    expect(root(container)).toContain('h-10')
    expect(root(container)).toContain('px-3')
    expect(root(container)).toContain('border-b')
    expect(root(container)).not.toMatch(/(^|\s)py-\d(\s|$)/)
    expect(root(container)).not.toMatch(/(^|\s)p-4(\s|$)/)
    expect(root(container)).not.toContain('max-height')
  })

  describe('immersive (busk-chrome plan D7, D10, D11)', () => {
    it('draws the expand glyph on all four hosts, after the actions and before the switcher, with no per-host wiring', () => {
      for (const view of VIEWS) {
        const { unmount } = draw({ view, actions: <button>lock</button> })
        const glyph = screen.getByRole('button', { name: 'Expand over the app' })
        expect(glyph).toHaveAttribute('aria-pressed', 'false')
        // Order: the host's actions, then the glyph, then the switcher's nav (the last nav in the
        // row — the breadcrumbs are one too).
        const lock = screen.getByText('lock')
        const switcher = screen.getAllByRole('navigation').at(-1)!
        expect(lock.compareDocumentPosition(glyph) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
        expect(glyph.compareDocumentPosition(switcher) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
        unmount()
      }
    })

    it('inverts with the state, and the same control brings the app back', () => {
      draw()
      fireEvent.click(screen.getByRole('button', { name: 'Expand over the app' }))
      expect(isImmersive()).toBe(true)
      const back = screen.getByRole('button', { name: 'Show the app' })
      expect(back).toHaveAttribute('aria-pressed', 'true')
      expect(back).toHaveAttribute('data-immersive', 'on')
      fireEvent.click(back)
      expect(isImmersive()).toBe(false)
      expect(screen.getByRole('button', { name: 'Expand over the app' })).toHaveAttribute('data-immersive', 'off')
    })

    it('draws the Offline chip only while immersive and the socket is down', () => {
      // Connected, app drawn: nothing.
      const a = draw()
      expect(screen.queryByText('Offline')).toBeNull()
      a.unmount()
      // Offline, app drawn: the app header's own pill says it.
      desk.connected = false
      const b = draw()
      expect(screen.queryByText('Offline')).toBeNull()
      b.unmount()
      // Offline and immersive: the one state that matters, back where it is read.
      setImmersive(true)
      const c = draw()
      expect(screen.getByText('Offline')).toBeInTheDocument()
      c.unmount()
      // Connected and immersive: a chip reading Connected all night is what immersive removes.
      desk.connected = true
      draw()
      expect(screen.queryByText('Offline')).toBeNull()
    })

    it('draws the mobile drawer’s button at its left edge below md while immersive, and only then', () => {
      const open = vi.fn()
      const withDrawer = (over: Partial<React.ComponentProps<typeof ShowHeader>> = {}) =>
        render(
          <MobileDrawerContext.Provider value={open}>
            <MemoryRouter>
              <ShowHeader view="busk" projectId={1} projectName="Hamlet" isShowActive canStart={false} onStart={vi.fn()} onStop={vi.fn()} {...over} />
            </MemoryRouter>
          </MobileDrawerContext.Provider>,
        )

      // Desk width, immersive: the sidebar is a click on the glyph away, so no hamburger.
      setImmersive(true)
      const a = withDrawer()
      expect(screen.queryByRole('button', { name: 'Open navigation' })).toBeNull()
      a.unmount()

      // Below md, immersive: the app header that held the hamburger is gone, so it is here…
      media.desktop = false
      const b = withDrawer()
      const button = screen.getByRole('button', { name: 'Open navigation' })
      // …first in the row, before the breadcrumbs.
      expect(b.container.firstElementChild!.firstElementChild).toBe(button)
      fireEvent.click(button)
      expect(open).toHaveBeenCalledTimes(1)
      b.unmount()

      // Below md with the app drawn: the app header has the hamburger.
      setImmersive(false)
      const c = withDrawer()
      expect(screen.queryByRole('button', { name: 'Open navigation' })).toBeNull()
      c.unmount()

      // And never where no drawer is lent — a header outside Layout has nothing to open.
      setImmersive(true)
      draw({ view: 'busk' })
      expect(screen.queryByRole('button', { name: 'Open navigation' })).toBeNull()
    })
  })
})
