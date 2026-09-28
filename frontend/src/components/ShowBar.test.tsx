// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

// Store-connected children (speed masters, the programmer indicator) are mocked to nothing: this
// suite is about the BAR's own structure, and mounting them would drag in the real WebSocket.
vi.mock('./SpeedMasters', () => ({
  SpeedMasters: () => <div data-testid="speed-masters" />,
  SpeedMastersChip: () => <div />,
}))
vi.mock('./ProgrammerIndicator', () => ({ ProgrammerIndicator: () => null }))

import { ShowBar } from './ShowBar'

afterEach(cleanup)

/**
 * The bar itself, which is the wrapper's only child rather than the render root.
 *
 * That wrapper is what lets the bar's `@[440px]:` padding and gap classes match at all: a
 * container query resolves against the nearest *ancestor* container, never the element declaring
 * one, so while `@container` and those classes shared an element they matched nothing. Reaching
 * past it here rather than asserting on `container.firstElementChild` keeps these tests about the
 * bar and not about how many elements deep it happens to sit.
 */
const bar = (container: HTMLElement) => container.firstElementChild!.firstElementChild as HTMLElement

const PROPS = {
  stackName: 'Act 1',
  dbo: false,
  onDbo: () => {},
  activeNumber: 'Q4',
  activeName: 'Warm Wash',
  standbyNumber: 'Q5',
  standbyName: 'Sunset Fade',
  fade: null,
  onGo: () => {},
  onBack: () => {},
}

/**
 * jsdom applies no CSS, so container queries are invisible here and none of the *rungs* can be
 * tested. What can be tested is the thing the rungs were introduced to guarantee: that nothing is
 * removed from the DOM at any width. The bar used to gate its whole live-state block behind
 * `@[560px]`, and that class is exactly what these assertions fence against coming back.
 */
describe('ShowBar', () => {
  it('never hides the live-state block', () => {
    const { container } = render(<ShowBar {...PROPS} />)
    const live = container.querySelector('.flex-1')
    expect(live).toBeTruthy()
    // The regression fence for the 560px collision. Cue numbers must survive every width; only
    // NAMES are allowed a `hidden …:block` gate.
    expect(live!.className).not.toMatch(/(^|\s)hidden(\s|$)/)
    expect(screen.getByText('Q4')).toBeTruthy()
    expect(screen.getByText('Q5')).toBeTruthy()
  })

  it('keeps the transport on the surface, with GO widening as the bar narrows', () => {
    render(<ShowBar {...PROPS} />)
    const go = screen.getByRole('button', { name: 'GO' })
    expect(screen.getByRole('button', { name: 'Back' })).toBeTruthy()
    // `flex-1` in the 440–700 band and `flex-none` above it — GO gets bigger as room runs out,
    // which is the right way round for a control pressed in the dark. Below 440 it stops growing
    // and takes a fixed 84px instead, because that rung is one row and the live block needs the
    // rest of it; see the rung test below.
    expect(go.className).toContain('@[440px]:flex-1')
    expect(go.className).toContain('@[700px]:flex-none')
  })

  it('gives the narrowest rung one row: an 84×44 GO and a transport that does not break the line', () => {
    // Space plan D8. The bar used to spend two lines and 118px below 440px — the 440–700 arm plus
    // a 52px GO — which on an 852px phone is a seventh of the screen before a fixture. jsdom lays
    // nothing out, so what is pinned is the contract the container queries are written against:
    // GO's fixed size at the bottom rung, and that the `basis-full` transport line still arrives
    // at 440 rather than having been deleted from the ladder.
    render(<ShowBar {...PROPS} />)
    const go = screen.getByRole('button', { name: 'GO' })
    expect(go.className).toContain('w-[84px]')
    expect(go.className).toContain('h-11')
    expect(go.className).toMatch(/(^|\s)flex-none(\s|$)/)

    const transport = go.parentElement as HTMLElement
    expect(transport.className).toMatch(/(^|\s)basis-auto(\s|$)/)
    // The fallback the ladder's own history says must never be removed from the middle band.
    expect(transport.className).toContain('@[440px]:basis-full')
    expect(transport.className).toContain('@[700px]:basis-auto')
  })

  it('renders the speed masters rather than a BPM tile of its own', () => {
    // Master 1 used to be a read-only tile here AND absent from the strip beside it. That split is
    // what made the 560-900px band unwinnable; one owner is the fix.
    render(<ShowBar {...PROPS} />)
    expect(screen.getByTestId('speed-masters')).toBeTruthy()
    expect(screen.queryByText('TAP')).toBeNull()
    expect(screen.queryByText(/M1 · BPM/)).toBeNull()
  })

  it('counts the FADING badge down from the descriptor, not from a per-frame prop', () => {
    // The bar takes the fade's write-once span and runs the countdown itself — the prop must stay
    // identity-stable for the whole fade or the memo wrapping the bar buys nothing.
    render(<ShowBar {...PROPS} fade={{ startMs: performance.now(), durationMs: 5000 }} />)
    expect(screen.getByText(/FADING/)).toBeTruthy()

    cleanup()
    // A span that has already run out is not a fade.
    render(<ShowBar {...PROPS} fade={{ startMs: performance.now() - 6000, durationMs: 5000 }} />)
    expect(screen.queryByText(/FADING/)).toBeNull()
  })

  it('toggles blackout', () => {
    const onDbo = vi.fn()
    render(<ShowBar {...PROPS} onDbo={onDbo} />)
    const dbo = screen.getByTitle('Toggle blackout')
    expect(dbo.getAttribute('aria-pressed')).toBe('false')
    fireEvent.click(dbo)
    expect(onDbo).toHaveBeenCalled()
  })
})

describe('ShowBar unlocked warning', () => {
  it('washes amber with the rest of the chrome band', () => {
    // Every bar in the band takes the same flag and the same class, or the header tints and the
    // rows below it do not — which reads as stripes rather than as one state.
    const { container } = render(<ShowBar {...PROPS} unlockedWarning />)
    expect(bar(container).className).toContain('bg-amber-400/15')
    expect(bar(container).className).toContain('border-amber-500/50')
  })

  it('stays quiet by default', () => {
    const { container } = render(<ShowBar {...PROPS} />)
    expect(bar(container).className).not.toContain('amber')
  })
})
