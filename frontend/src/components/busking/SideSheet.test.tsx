// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { LIVE_SHEET_TABS, getBuskSheet, resetBuskWindowStores, setBuskSheet } from '@/lib/buskWindow'
import { resetEditorSurfaceMedia } from '@/components/editor/EditorSurface'
import { SIDE_PANEL_BODY_CLASS, SIDE_PANEL_STRIP_CLASS } from '@/components/sheet/sidePanel'
import { CHROME_ROW_CLASS } from '@/components/sheet/sheetFrame'
import { resetSidePanelModeStore, setSidePanelMode } from '@/lib/sidePanelMode'
import type { BuskingTarget } from './buskingTypes'

/**
 * The side sheet (busk-further plan D7): five live tabs — Speed, Colour (session 5), Spread
 * (session 6), Show (the busk-chrome plan's session A) and Effects (fixture-fx-sheets plan D20,
 * between Spread and Show) — and the fold; the fold keeps the beat, master 1's tempo, the tab
 * glyphs, the live cue number under the Show glyph and the selection's colour; off the desk board
 * the sheet is an overlay carrying Colour, Spread, Effects and Show and no Speed tab. The sheet is one fact — `busk.sheet`, `none` for the fold — and the Colour tab's
 * *Spread to a second colour…* button opens Spread with *From* set through the host's seed.
 */

vi.mock('./BuskSpeedRail', () => ({ BuskSpeedRail: () => <div data-testid="speed-rail" /> }))
vi.mock('./ColourSheet', () => ({
  ColourSheet: ({ compact, onSpread }: { compact?: boolean; onSpread?: (from: { r: number; g: number; b: number }) => void }) => (
    <div data-testid="colour-sheet" data-compact={compact ? 'true' : 'false'}>
      <button type="button" onClick={() => onSpread?.({ r: 245, g: 179, b: 66 })}>Second colour</button>
    </div>
  ),
}))
vi.mock('./SpreadSheet', () => ({
  SpreadSheet: ({ compact, seed, onSeedConsumed }: { compact?: boolean; seed?: { from: { r: number; g: number; b: number }; key: number } | null; onSeedConsumed?: () => void }) => (
    <div data-testid="spread-sheet" data-compact={compact ? 'true' : 'false'} data-seed={seed == null ? '' : JSON.stringify(seed.from)}>
      <button type="button" onClick={() => onSeedConsumed?.()}>consume</button>
    </div>
  ),
}))
vi.mock('./EffectsSheet', () => ({ EffectsSheet: () => <div data-testid="effects-sheet" /> }))
vi.mock('./ShowTab', () => ({
  ShowTab: ({ show }: { show: { transport: { serverActiveCueId: number | null } } }) => (
    <div data-testid="show-tab" data-live={String(show.transport.serverActiveCueId)} />
  ),
}))
vi.mock('@/components/BeatIndicator', () => ({
  BeatIndicator: ({ master }: { master?: { index: number } }) => <span data-testid="beat" data-master={master?.index} />,
}))
vi.mock('@/store/speedMasters', () => ({
  useSpeedMasterLiveQuery: () => ({ data: [{ uuid: 'm1', index: 1, name: 'Global', bpm: 120.04 }] }),
}))
const programmer = { blind: false }
vi.mock('@/hooks/useProgrammerBlind', () => ({ useProgrammerBlind: () => programmer.blind }))
vi.mock('@/store/patches', () => ({
  usePatchListQuery: () => ({ data: [{ id: 1, key: 'par-1', displayName: 'PAR 1' }] }),
}))
const par = { key: 'par-1', name: 'PAR 1', typeKey: 'par', groups: ['Front wash'] }
vi.mock('@/hooks/useFixtureLookup', () => ({
  useFixtureLookup: () => ({ fixtures: [par], fixtureByKey: new Map([['par-1', par]]), typeByKey: new Map() }),
}))
vi.mock('@/components/fixtures/fixtureAppearance', () => ({
  FixtureAppearanceSource: ({ children }: { children: (a: { color: string; intensity: number }) => React.ReactNode }) =>
    children({ color: '#ff0000', intensity: 1 }),
}))

import { SIDE_SHEET_TABS, SideSheet, SideSheetOverlay, sideSheetTabs, tabWordClass } from './SideSheet'
import type { ShowTabSource } from './ShowTab'

function surface({ narrow = false, short = false } = {}) {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: query.includes('max-width') ? narrow : query.includes('500px') ? short : query.startsWith('(min-width'),
    media: query,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    onchange: null,
    dispatchEvent: () => false,
  }))
}

const selection = new Map<string, BuskingTarget>([
  ['group:Front wash', { type: 'group', name: 'Front wash', group: { name: 'Front wash', memberCount: 6 } } as BuskingTarget],
  ['fixture:par-1', { type: 'fixture', key: 'par-1', fixture: par } as unknown as BuskingTarget],
])
/** The route's bar state, as `routes/Busk.tsx` hands it down: cue 12 on stage, cue 13 armed. */
function showSource(overrides: Partial<ShowTabSource['transport']> = {}): ShowTabSource {
  const cues = [
    { id: 12, cueNumber: '12', name: 'Verse 2' },
    { id: 13, cueNumber: null, name: 'Chorus' },
  ]
  const activeStack = { id: 1, name: 'Main Show', type: 'STACK', cues, activeCueId: 12 }
  return {
    transport: {
      activeStack,
      serverActiveCueId: 12,
      activeCueId: 12,
      standbyCueId: 13,
      completedCueIds: [],
      go: vi.fn(),
      back: vi.fn(),
      setStandby: vi.fn(),
      cancelAnimations: vi.fn(),
      ...overrides,
    },
    showBarProps: { dbo: false, onDbo: vi.fn() },
    activeCue: cues[0],
    standbyCue: cues[1],
    nextStack: null,
  } as unknown as ShowTabSource
}
const props = { projectId: 1, selectedTargets: selection, families: null, show: showSource() }

beforeEach(() => surface())

afterEach(() => {
  cleanup()
  window.sessionStorage.clear()
  resetBuskWindowStores()
  resetEditorSurfaceMedia()
  resetSidePanelModeStore()
  vi.unstubAllGlobals()
})

describe('docked, on the desk board', () => {
  it('offers Speed, Colour, Spread, Effects and Show', () => {
    setBuskSheet('speed')
    render(<SideSheet {...props} />)
    const tabs = within(screen.getByRole('tablist', { name: 'Side sheet' })).getAllByRole('tab')
    expect(tabs.map((t) => t.textContent)).toEqual(['Speed', 'Colour', 'Spread', 'Effects', 'Show'])
    expect(tabs[0]).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByTestId('speed-rail')).toBeInTheDocument()
    expect(sideSheetTabs('docked').map((t) => t.id)).toEqual(['speed', 'colour', 'spread', 'effects', 'show'])
  })

  it('mounts the Show tab — the phone runner over the route’s transport — when the fact names it (busk-chrome D1)', () => {
    setBuskSheet('show')
    render(<SideSheet {...props} />)
    expect(screen.getByRole('tab', { name: 'Show' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByTestId('show-tab')).toHaveAttribute('data-live', '12')
    expect(screen.queryByTestId('speed-rail')).toBeNull()
  })

  it('folds the tab words to glyphs below 460px of sheet, keeping only the open tab’s word — the strip is the container (D3)', () => {
    setBuskSheet('colour')
    render(<SideSheet {...props} />)
    const strip = screen.getByRole('tablist', { name: 'Side sheet' })
    // The container is the strip's unpadded wrapper, never the `px-3` row: a size query measures
    // the content box, and on the row the fold would fire 24px early.
    expect(strip.className).not.toContain('@container')
    expect(strip.parentElement!.className).toContain('@container')
    expect(strip.parentElement!.className).not.toMatch(/\bp[xlr]?-\d/)
    for (const tab of within(strip).getAllByRole('tab')) {
      expect(tab.querySelector('svg')).not.toBeNull()
      const word = [...tab.querySelectorAll('span')].find((el) => el.textContent === tab.textContent)!
      if (tab.getAttribute('aria-selected') === 'true') {
        expect(word.className).not.toContain('hidden')
      } else {
        expect(word.className).toContain('hidden')
        // 460, not D3's 400: five worded tabs measure 455 of sheet with the chrome.
        expect(word.className).toContain('@[460px]:inline')
      }
    }
    expect(tabWordClass(true)).not.toContain('hidden')
    expect(tabWordClass(false)).toBe('hidden @[400px]:inline')
    expect(tabWordClass(false, 460)).toBe('hidden @[460px]:inline')
  })

  it('mounts the Spread tab in the panel when the fact names it', () => {
    setBuskSheet('spread')
    render(<SideSheet {...props} />)
    expect(screen.getByRole('tab', { name: 'Spread' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByTestId('spread-sheet')).toHaveAttribute('data-compact', 'false')
    expect(screen.getByTestId('spread-sheet')).toHaveAttribute('data-seed', '')
    expect(screen.queryByTestId('colour-sheet')).toBeNull()
  })

  it('opens Spread with From set from the Colour tab’s Second colour switch, and drops the seed once it is read', () => {
    setBuskSheet('colour')
    render(<SideSheet {...props} />)
    fireEvent.click(screen.getByRole('button', { name: 'Second colour' }))
    expect(getBuskSheet()).toBe('spread')
    expect(screen.getByTestId('spread-sheet')).toHaveAttribute('data-seed', JSON.stringify({ r: 245, g: 179, b: 66 }))
    // Read once: the tab asks the host to drop it, so a later visit by another door is not re-seeded.
    fireEvent.click(screen.getByRole('button', { name: 'consume' }))
    expect(screen.getByTestId('spread-sheet')).toHaveAttribute('data-seed', '')
  })

  it('mounts the Colour tab in the panel, full layout, when the fact names it', () => {
    setBuskSheet('colour')
    render(<SideSheet {...props} />)
    expect(screen.getByRole('tab', { name: 'Colour' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByTestId('colour-sheet')).toHaveAttribute('data-compact', 'false')
    expect(screen.queryByTestId('speed-rail')).toBeNull()
    fireEvent.click(screen.getByRole('tab', { name: 'Speed' }))
    expect(getBuskSheet()).toBe('speed')
  })

  it('folds to the strip on the chevron, writing none — the sheet is one fact', () => {
    setBuskSheet('speed')
    render(<SideSheet {...props} />)
    fireEvent.click(screen.getByRole('button', { name: 'Fold the side sheet' }))
    expect(getBuskSheet()).toBe('none')
    expect(document.querySelector('[data-side-sheet="none"]')).not.toBeNull()
    expect(screen.queryByTestId('speed-rail')).toBeNull()
    expect(Object.keys(window.sessionStorage).some((k) => /open/i.test(k))).toBe(false)
  })

  it('keeps the beat, master 1’s tempo, a glyph per live tab, the selection’s colour and its head count on the fold', () => {
    setBuskSheet('none')
    render(<SideSheet {...props} />)
    expect(screen.getByTestId('beat')).toHaveAttribute('data-master', '1')
    expect(document.querySelector('[data-fold-tempo]')).toHaveTextContent('120')
    expect(document.querySelector('[data-fold-heads]')).toHaveTextContent('7')
    expect((document.querySelector('[data-fold-colour]') as HTMLElement).style.background).toBe('rgb(255, 0, 0)')
    // One glyph per live tab; a tap unfolds onto it.
    expect(screen.getByRole('button', { name: 'Open the Spread tab' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Open the Show tab' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Open the Colour tab' }))
    expect(getBuskSheet()).toBe('colour')
  })

  it('draws the live cue number under the Show glyph from the server cursor, green; an em-dash with nothing on stage (D4)', () => {
    setBuskSheet('none')
    const { rerender } = render(<SideSheet {...props} />)
    const cue = document.querySelector('[data-fold-cue]') as HTMLElement
    expect(cue).toHaveTextContent('12')
    expect(cue.className).toContain('text-green-500')
    // It sits under the Show glyph, not under any other.
    expect(cue.parentElement!.querySelector('button')).toHaveAttribute('aria-label', 'Open the Show tab')

    // The *server* cursor, not the animating one: mid-fade the fold holds the outgoing cue.
    rerender(<SideSheet {...props} show={showSource({ serverActiveCueId: 12, activeCueId: 13 })} />)
    expect(document.querySelector('[data-fold-cue]')).toHaveTextContent('12')
    // A cue with no number is named.
    rerender(<SideSheet {...props} show={showSource({ serverActiveCueId: 13 })} />)
    expect(document.querySelector('[data-fold-cue]')).toHaveTextContent('Chorus')
    // Nothing on stage.
    rerender(<SideSheet {...props} show={showSource({ serverActiveCueId: null })} />)
    const dark = document.querySelector('[data-fold-cue]') as HTMLElement
    expect(dark).toHaveTextContent('—')
    expect(dark.className).not.toContain('text-green-500')
  })

  it('marks the Show tab’s glyph with an amber dot only while the programmer is blind — in the strip and on the fold', () => {
    setBuskSheet('speed')
    render(<SideSheet {...props} />)
    expect(document.querySelector('[data-busk-blind-dot]')).toBeNull()
    cleanup()

    programmer.blind = true
    try {
      render(<SideSheet {...props} />)
      const tabs = within(screen.getByRole('tablist', { name: 'Side sheet' })).getAllByRole('tab')
      // On Show's glyph and no other tab's; the dot is aria-hidden and the word is the tab's
      // name — never an sr-only span, which was silent on the fold and *was* the name below 400.
      const show = screen.getByRole('tab', { name: 'Show — programmer blind' })
      expect(show.querySelector('[data-busk-blind-dot]')).not.toBeNull()
      expect(show.querySelector('[data-busk-blind-dot]')).toHaveAttribute('aria-hidden')
      expect(show.textContent).toBe('Show')
      expect(tabs.filter((t) => t.querySelector('[data-busk-blind-dot]') != null)).toHaveLength(1)
      expect(tabs.map((t) => t.getAttribute('aria-label'))).toEqual(['Speed', 'Colour', 'Spread', 'Effects', 'Show — programmer blind'])
      // A mark, not a control: the dot is inert and the tab still opens Show.
      fireEvent.click(show)
      expect(getBuskSheet()).toBe('show')
      cleanup()

      setBuskSheet('none')
      render(<SideSheet {...props} />)
      const glyph = screen.getByRole('button', { name: 'Open the Show tab — programmer blind' })
      expect(glyph.querySelector('[data-busk-blind-dot]')).not.toBeNull()
      expect(screen.getByRole('button', { name: 'Open the Colour tab' }).querySelector('[data-busk-blind-dot]')).toBeNull()
      // The cue number under the glyph is untouched.
      expect(document.querySelector('[data-fold-cue]')).toHaveTextContent('12')
    } finally {
      programmer.blind = false
    }
  })

  it('unfolds onto a tab from the chevron', () => {
    setBuskSheet('none')
    render(<SideSheet {...props} />)
    fireEvent.click(screen.getByRole('button', { name: 'Unfold the side sheet' }))
    expect(getBuskSheet()).toBe('speed')
  })

  it('unfolds onto the tab that was last open — the memory a MIDI toggle reads — not always onto Speed', () => {
    setBuskSheet('colour')
    setBuskSheet('none')
    render(<SideSheet {...props} />)
    fireEvent.click(screen.getByRole('button', { name: 'Unfold the side sheet' }))
    expect(getBuskSheet()).toBe('colour')
    expect(screen.getByTestId('colour-sheet')).toBeInTheDocument()
  })

  it('wears the programmer rail’s chrome: the shared body, header and strip, and the enter animation', () => {
    setBuskSheet('speed')
    const { rerender } = render(<SideSheet {...props} />)
    const panel = document.querySelector('[data-side-sheet="speed"]') as HTMLElement
    // The body's fill and its one left edge come from the shared module, not from this file.
    for (const cls of SIDE_PANEL_BODY_CLASS.split(' ')) expect(panel.className).toContain(cls)
    expect(panel).toHaveAttribute('role', 'complementary')
    // The header IS the 40px chrome row — `sheetFrame.ts`'s own, not a copy of it. Every class
    // of it survives except `gap-2`, which the tab row deliberately tightens to `gap-0.5`; that
    // one exception is asserted rather than skipped, so a second divergence cannot creep in.
    const header = screen.getByRole('tablist')
    for (const cls of CHROME_ROW_CLASS.split(' ')) {
      if (cls === 'gap-2') continue
      expect(header.className).toContain(cls)
    }
    expect(header.className).toContain('gap-0.5')
    expect(header.className).not.toContain('gap-2')
    // Already open on the first render is not "opening": no animation on arrival at the route.
    expect(panel.className).not.toContain('animate-in')

    // Folded, the strip is the rail's 40px one — not the phone handle's 44.
    setBuskSheet('none')
    rerender(<SideSheet {...props} />)
    const strip = document.querySelector('[data-side-sheet="none"]') as HTMLElement
    for (const cls of SIDE_PANEL_STRIP_CLASS.split(' ')) expect(strip.className).toContain(cls)
    expect(strip.className).not.toContain('w-11')

    // Unfolding is an opening, so this one animates.
    fireEvent.click(screen.getByRole('button', { name: 'Unfold the side sheet' }))
    rerender(<SideSheet {...props} />)
    expect((document.querySelector('[data-side-sheet="speed"]') as HTMLElement).className).toContain(
      'animate-in',
    )
  })

  it('keeps the fold\u2019s 40px in the row while floating, so opening the sheet does not reflow the page', () => {
    // The overlay is absolute and takes no room: swapping the fold for it alone gave the fold's
    // width back to the page, and every bank beside it reflowed on each open and close.
    setBuskSheet('colour')
    setSidePanelMode('overlay')
    const { rerender } = render(<SideSheet {...props} />)
    const panel = document.querySelector('[data-side-sheet="colour"]') as HTMLElement
    expect(panel).toHaveAttribute('data-sheet-mode', 'overlay')
    const spacer = document.querySelector('[data-side-sheet-spacer]') as HTMLElement
    expect(spacer).not.toBeNull()
    // The fold's own width, in the flow, drawing nothing and read by nothing.
    expect(spacer.className).toContain('w-10')
    expect(spacer.className).toContain('shrink-0')
    expect(spacer).toHaveAttribute('aria-hidden')
    expect(spacer.childElementCount).toBe(0)
    // It is not the fold: the fold and the panel are still never both drawn.
    expect(document.querySelector('[data-side-sheet="none"]')).toBeNull()

    // Pushed beside the page, the panel is in the flow itself and there is nothing to hold.
    const tab = screen.getByTestId('colour-sheet')
    setSidePanelMode('push')
    rerender(<SideSheet {...props} />)
    expect(document.querySelector('[data-side-sheet-spacer]')).toBeNull()
    expect(document.querySelector('[data-side-sheet="colour"]')).toHaveAttribute('data-sheet-mode', 'push')
    // The mode toggle is in the panel's own header, so flipping it must not remount the tab under
    // the operator's hand.
    expect(screen.getByTestId('colour-sheet')).toBe(tab)
    setSidePanelMode('overlay')
    rerender(<SideSheet {...props} />)
    expect(screen.getByTestId('colour-sheet')).toBe(tab)
  })

  it('opens no narrower than its header needs, lifting a width stored below that floor', () => {
    // The row is three labelled tabs plus the mode toggle and the fold chevron inside the chrome
    // row's 12px gutters — 304px measured — so at the shared 260 both buttons were pushed clean
    // outside the panel. A desk that stored the old width is lifted on read.
    window.localStorage.setItem('busk.sheet.width', JSON.stringify(260))
    setBuskSheet('speed')
    render(<SideSheet {...props} />)
    const panel = document.querySelector('[data-side-sheet="speed"]') as HTMLElement
    expect(Number.parseInt(panel.style.getPropertyValue('--sheet-w'), 10)).toBeGreaterThanOrEqual(320)
  })

  it('lets the tabs give before the header’s buttons do', () => {
    // Insurance rather than a live case: `SHEET_MIN_WIDTH` keeps the row fitting today. It is
    // here so the next control added to that row degrades — clipping the end of the tab strip —
    // instead of pushing the toggle and the chevron outside the panel, which is exactly what
    // adding the mode toggle did.
    setBuskSheet('speed')
    render(<SideSheet {...props} />)
    const tablist = screen.getByRole('tablist')
    const group = tablist.querySelector('div') as HTMLElement
    expect(group.className).toContain('min-w-0')
    expect(group.className).toContain('overflow-hidden')
    expect(group.className).toContain('flex-1')
    for (const name of ['Fold the side sheet', 'Show the panel over the content']) {
      expect(screen.getByRole('button', { name }).className).toContain('shrink-0')
    }
  })

  it('draws the fold for a fact naming a tab that has not landed — none today, so the gate is pinned on the list', () => {
    // Every tab has landed; what remains is the mechanism a fifth tab would land through. The
    // strip draws exactly the live list, and nothing outside `LIVE_SHEET_TABS` reaches it.
    expect(sideSheetTabs('docked').every((tab) => LIVE_SHEET_TABS.includes(tab.id))).toBe(true)
    expect(SIDE_SHEET_TABS.map((t) => t.id)).toEqual([...LIVE_SHEET_TABS])
  })
})

describe('the overlay, off the desk board', () => {
  it('carries Colour, Spread, Effects and Show and no Speed tab in any overlay form — the Show strip has the tempo chip (D6, D7)', () => {
    expect(sideSheetTabs('bottom-sheet').map((t) => t.id)).toEqual(['colour', 'spread', 'effects', 'show'])
    expect(sideSheetTabs('side-sheet').map((t) => t.id)).toEqual(['colour', 'spread', 'effects', 'show'])
    // `popover` is what a 640–767px window answers, where the rail is still not drawn: an overlay
    // with a Speed tab there would open onto nothing.
    expect(sideSheetTabs('popover').map((t) => t.id)).toEqual(['colour', 'spread', 'effects', 'show'])
  })

  it('marks the overlay strip’s Show tab the same way while blind', () => {
    surface({ narrow: true })
    setBuskSheet('colour')
    programmer.blind = true
    try {
      render(<SideSheetOverlay {...props} />)
      const tabs = screen.getAllByRole('tab')
      const show = screen.getByRole('tab', { name: 'Show — programmer blind' })
      expect(show.querySelector('[data-busk-blind-dot]')).not.toBeNull()
      expect(tabs.filter((t) => t.querySelector('[data-busk-blind-dot]') != null)).toHaveLength(1)
      // The other tabs keep their plain names, and every tab is named whatever the width.
      expect(tabs.map((t) => t.getAttribute('aria-label'))).toEqual(['Colour', 'Spread', 'Effects', 'Show — programmer blind'])
    } finally {
      programmer.blind = false
    }
  })

  it('opens onto Show as a bottom sheet below md, over the route’s transport', () => {
    surface({ narrow: true })
    setBuskSheet('show')
    render(<SideSheetOverlay {...props} />)
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getAllByRole('tab').map((t) => t.textContent)).toEqual(['Colour', 'Spread', 'Effects', 'Show'])
    expect(within(dialog).getByRole('tab', { name: 'Show' })).toHaveAttribute('aria-selected', 'true')
    expect(within(dialog).getByTestId('show-tab')).toHaveAttribute('data-live', '12')
  })

  it('folds the overlay strip’s words like the docked one, on an unpadded container, clear of the close cross', () => {
    // Three worded tabs are ~291px, which overran the right-hand form while it was 288 and ran
    // under the sheet primitive's close cross: the strip takes D3's fold, its wrapper is the
    // container, and the tab group clips its own end rather than pushing anything out.
    surface({ short: true })
    setBuskSheet('show')
    render(<SideSheetOverlay {...props} />)
    const strip = within(screen.getByRole('dialog')).getByRole('tablist', { name: 'Side sheet' })
    expect(strip.parentElement!.className).toContain('@container')
    expect(strip.className).toContain('pr-12')
    const group = strip.firstElementChild as HTMLElement
    expect(group.className).toContain('overflow-hidden')
    expect(group.className).toContain('min-w-0')
    for (const tab of within(strip).getAllByRole('tab')) {
      expect(tab.className).toContain('shrink-0')
      const word = [...tab.querySelectorAll('span')].find((el) => el.textContent === tab.textContent)!
      expect(word.className).toBe(tabWordClass(tab.getAttribute('aria-selected') === 'true', 460))
    }
  })

  it('opens onto Spread as a right-hand sheet on the short board, compact, and Second colour crosses over to it', () => {
    surface({ short: true })
    setBuskSheet('colour')
    render(<SideSheetOverlay {...props} />)
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Second colour' }))
    expect(getBuskSheet()).toBe('spread')
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByRole('tab', { name: 'Spread' })).toHaveAttribute('aria-selected', 'true')
    expect(within(dialog).getByTestId('spread-sheet')).toHaveAttribute('data-compact', 'true')
    expect(within(dialog).getByTestId('spread-sheet')).toHaveAttribute('data-seed', JSON.stringify({ r: 245, g: 179, b: 66 }))
  })

  it('opens onto Colour as a bottom sheet below md, full layout, and closing writes none', () => {
    surface({ narrow: true })
    setBuskSheet('colour')
    render(<SideSheetOverlay {...props} />)
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByRole('tab', { name: 'Colour' })).toHaveAttribute('aria-selected', 'true')
    expect(within(dialog).queryByRole('tab', { name: 'Speed' })).toBeNull()
    expect(within(dialog).getByTestId('colour-sheet')).toHaveAttribute('data-compact', 'false')
    fireEvent.keyDown(dialog, { key: 'Escape' })
    expect(getBuskSheet()).toBe('none')
  })

  it('opens onto Colour as a right-hand sheet on the short board, in the compact layout', () => {
    surface({ short: true })
    setBuskSheet('colour')
    render(<SideSheetOverlay {...props} />)
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByTestId('colour-sheet')).toHaveAttribute('data-compact', 'true')
    expect(within(dialog).queryByRole('tab', { name: 'Speed' })).toBeNull()
  })

  it('never opens on speed off the desk board, having no Speed tab to open onto', () => {
    surface({ narrow: true })
    setBuskSheet('speed')
    render(<SideSheetOverlay {...props} />)
    expect(screen.queryByRole('dialog')).toBeNull()
  })
})

describe('the Effects tab (fixture-fx-sheets plan D20) is in every place LIVE_SHEET_TABS reaches', () => {
  it('is live, between Spread and Show', () => {
    expect(LIVE_SHEET_TABS).toEqual(['speed', 'colour', 'spread', 'effects', 'show'])
  })

  it('is a tab on the docked strip, folding to its glyph below 460px of sheet, and mounts the tab', () => {
    setBuskSheet('effects')
    render(<SideSheet {...props} />)
    const tab = screen.getByRole('tab', { name: 'Effects' })
    expect(tab).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByTestId('effects-sheet')).toBeInTheDocument()
    // Open, its word stays at every width; another tab's is the strip's container fold.
    expect(tab.querySelector('span:last-child')?.className).toBe(tabWordClass(true, 460))
    expect(screen.getByRole('tab', { name: 'Spread' }).querySelector('span:last-child')?.className).toBe(tabWordClass(false, 460))
  })

  it('is a tab on the overlay strip, and mounts the tab there', () => {
    surface({ narrow: true })
    setBuskSheet('effects')
    render(<SideSheetOverlay {...props} />)
    expect(screen.getByRole('tab', { name: 'Effects' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByTestId('effects-sheet')).toBeInTheDocument()
  })

  it('is a glyph on the fold, which opens it', () => {
    setBuskSheet('none')
    render(<SideSheet {...props} />)
    fireEvent.click(screen.getByRole('button', { name: 'Open the Effects tab' }))
    expect(getBuskSheet()).toBe('effects')
  })
})
