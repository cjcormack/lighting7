// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * The programmer's dock (tables-colours plan D4): **Colour · Spread**, collapsible, at the same
 * 260 floor and 300 default, listing nothing.
 *
 * jsdom evaluates no container query, so which arm is on screen is a browser check; what is pinned
 * is everything that is JavaScript — the tab fact and where it rests, that each door opens its tab
 * in its own arm and writes only that arm's flag, that the strip and the handle carry no counts and
 * no `+`, and that only a docked, expanded dock claims a grid gesture.
 */
vi.mock('./RailColourTab', () => ({ RailColourTab: () => <div data-testid="colour-tab" /> }))
vi.mock('./RailSpreadTab', () => ({ RailSpreadTab: () => <div data-testid="spread-tab" /> }))
vi.mock('./ScopedEditorContext', () => ({
  ScopedEditorContextProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}))

import { ProgrammerRail, RAIL_TABS } from './ProgrammerRail'
import { ProgrammerWorkspace, RAIL_DEFAULT_WIDTH, RAIL_MIN_WIDTH } from './ProgrammerWorkspace'
import { useRailTabClaim } from './railTab'
import { SPREAD_COMPACT_WORD_CLASS } from '@/components/editor/SpreadPanel'
import { CHROME_ROW_CLASS } from '@/components/sheet/sheetFrame'
import { resetSidePanelModeStore, setSidePanelMode } from '@/lib/sidePanelMode'

const COLLAPSED_KEY = 'programmer.rail.collapsed'

beforeEach(() => {
  window.localStorage.setItem(COLLAPSED_KEY, JSON.stringify(false))
})

afterEach(() => {
  cleanup()
  window.localStorage.clear()
  window.sessionStorage.clear()
  resetSidePanelModeStore()
  vi.unstubAllGlobals()
})

/** The grid's half of the claim, as the grid reads it. */
function ClaimProbe() {
  return <div data-testid="claim">{useRailTabClaim()?.tab ?? 'none'}</div>
}

function draw() {
  return render(<ProgrammerWorkspace grid={<ClaimProbe />} rail={<ProgrammerRail projectId={6} />} />)
}

const tab = (name: string) => within(screen.getByRole('tablist', { name: 'Dock' })).getByRole('tab', { name })
const claim = () => screen.getByTestId('claim').textContent

describe('the dock', () => {
  it('is Colour and Spread, resting on Colour, and lists nothing', () => {
    draw()
    expect(RAIL_TABS.map((t) => t.label)).toEqual(['Colour', 'Spread'])
    expect(tab('Colour')).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByTestId('colour-tab')).toBeInTheDocument()
    // No counts, no doors onto the stack: those are row B's tables now.
    expect(screen.queryByText(/^Layers$/)).toBeNull()
    expect(screen.queryByRole('button', { name: /Add a/ })).toBeNull()
    expect(screen.queryByText('Per-fixture FX')).toBeNull()
  })

  it('swaps its panel on a tab press, the claim following the drawn tab', () => {
    draw()
    expect(claim()).toBe('colour')
    fireEvent.click(tab('Spread'))
    expect(screen.getByTestId('spread-tab')).toBeInTheDocument()
    expect(screen.queryByTestId('colour-tab')).toBeNull()
    expect(claim()).toBe('spread')
  })

  it('is not persisted: every arrival rests on Colour', () => {
    const first = draw()
    fireEvent.click(tab('Spread'))
    first.unmount()
    draw()
    expect(tab('Colour')).toHaveAttribute('aria-selected', 'true')
  })

  it('keeps its tab across a collapse, claiming nothing while collapsed', () => {
    draw()
    fireEvent.click(tab('Spread'))
    fireEvent.click(screen.getByRole('button', { name: 'Collapse the dock' }))
    expect(screen.queryByTestId('spread-tab')).toBeNull()
    expect(claim()).toBe('none')
    expect(window.localStorage.getItem(COLLAPSED_KEY)).toBe('true')
    fireEvent.click(screen.getByRole('button', { name: 'Expand the dock' }))
    expect(screen.getByTestId('spread-tab')).toBeInTheDocument()
    expect(claim()).toBe('spread')
  })

  it('expands a collapsed dock onto a tab from the strip’s docked door, writing the stored preference', () => {
    window.localStorage.setItem(COLLAPSED_KEY, JSON.stringify(true))
    draw()
    const door = screen.getByRole('button', { name: 'Expand the dock on Spread' })
    expect(door.className).toContain('@max-[1200px]:hidden')
    fireEvent.click(door)
    expect(screen.getByTestId('spread-tab')).toBeInTheDocument()
    expect(window.localStorage.getItem(COLLAPSED_KEY)).toBe('false')
  })

  it('opens the overlay on a tab from the strip’s narrow door, leaving the preference alone', () => {
    window.localStorage.setItem(COLLAPSED_KEY, JSON.stringify(true))
    draw()
    const door = screen.getByRole('button', { name: 'Open the dock on Spread' })
    expect(door.className).toContain('@min-[1200px]:hidden')
    fireEvent.click(door)
    expect(screen.getByTestId('spread-tab')).toBeInTheDocument()
    expect(window.localStorage.getItem(COLLAPSED_KEY)).toBe('true')
  })

  it('opens the phone’s sheet on a tab from the handle, the panel in one place', () => {
    draw()
    fireEvent.click(screen.getByRole('button', { name: 'Show Spread' }))
    expect(screen.getAllByTestId('spread-tab')).toHaveLength(1)
    expect(screen.queryByRole('complementary', { name: 'Colour and spread' })).toBeNull()
    expect(within(screen.getByRole('dialog')).getByRole('tablist', { name: 'Dock' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Open Colour and Spread', hidden: true })).toHaveAttribute('aria-expanded', 'true')
  })

  it('draws its tabs in overlay mode too, one chevron and one door per tab', () => {
    setSidePanelMode('overlay')
    draw()
    expect(screen.getAllByRole('button', { name: 'Open the dock on Colour' })).toHaveLength(1)
    expect(screen.queryByRole('button', { name: 'Expand the dock on Colour' })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Open the dock on Spread' }))
    expect(tab('Spread')).toHaveAttribute('aria-selected', 'true')
    // Overlay mode floats the panel over the grid, which shuts on a press there: it claims nothing.
    expect(claim()).toBe('none')
  })

  it('claims nothing where push mode does not dock it', () => {
    // The docked-arm probe is drawn only where the dock docks; a zero box is the narrow arm.
    let report: () => void = () => {}
    vi.stubGlobal(
      'ResizeObserver',
      class {
        constructor(cb: () => void) {
          report = cb
        }
        observe() {}
        disconnect() {}
        unobserve() {}
      },
    )
    draw()
    expect(claim()).toBe('colour')
    const probe = document.querySelector('span[aria-hidden].size-px')!
    expect(probe.className).toContain('@max-[1200px]:hidden')
    act(() => report())
    expect(claim()).toBe('none')
  })

  it('keeps its header level with row B at 40px', () => {
    draw()
    const header = screen.getByRole('tablist', { name: 'Dock' }).parentElement!
    expect(header.className).toBe(CHROME_ROW_CLASS)
    expect(screen.getByRole('button', { name: 'Collapse the dock' }).className).toContain('size-6')
  })

  it('starts the Spread panel’s compact curve row between the dock’s floor and its default', () => {
    // The docked panel is the dock less its 1px edge, and `@max-[n]` is a strict `<`: compact
    // below 300 of dock, worded at the 300 default, compact at the 260 floor.
    const below = Number(/@max-\[(\d+)px\]/.exec(SPREAD_COMPACT_WORD_CLASS)![1])
    expect(RAIL_MIN_WIDTH - 1).toBeLessThan(below)
    expect(RAIL_DEFAULT_WIDTH - 1).toBeGreaterThanOrEqual(below)
  })
})
