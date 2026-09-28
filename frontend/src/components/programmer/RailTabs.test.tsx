// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * The programmer rail's header as a tab strip — **Stack · Colour · Spread** (editor-kit plan
 * session 4, `RailTabs.dc.html`).
 *
 * The fold is pinned **as an ordering, not as pixels**: jsdom evaluates no container query, so what
 * can be asserted is that each word carries the shared rule's class, and that the numbers those
 * classes name sit on the right sides of the rail's own — the strip at the rail's ceiling is
 * narrower than the fold, so a closed tab is its glyph at every width the rail has; the compact
 * curve row starts between the rail's floor and its default. The rest is the tab fact's behaviour:
 * it rests on Stack, a strip glyph expands onto its tab, a collapse forgets it, and overlay mode
 * draws no tabs at all.
 */
vi.mock('react-router', () => ({ useParams: () => ({ projectId: '6' }) }))
vi.mock('@/store/programmer', () => ({
  useProgrammerLayersQuery: () => ({ data: [1, 2, 3].map((id) => ({ id, source: { name: `Layer ${id}` } })) }),
}))
vi.mock('@/store/fixtureFx', () => ({ useActiveEffectsQuery: () => ({ data: [{ id: 1 }, { id: 2 }] }) }))
vi.mock('./FxSheet', () => ({ FxSheet: () => null }))
vi.mock('./ProgrammerAddEffect', () => ({
  ProgrammerAddEffectSheet: () => null,
  useProgrammerAddEffect: () => ({ canAdd: true, reason: '', add: () => {} }),
}))
vi.mock('./ProgrammerAddLayerSheet', () => ({ ProgrammerAddLayerSheet: () => null }))
vi.mock('./ProgrammerFxList', () => ({ ProgrammerFxList: () => <div data-testid="fx" /> }))
vi.mock('./ProgrammerLookStack', () => ({ ProgrammerLookStack: () => <div data-testid="layers" /> }))
vi.mock('@/components/hand/HandLayerTargets', () => ({ HandProgrammerLayerStrip: () => null }))
vi.mock('./ProgrammerScope', () => ({
  useProgrammerScope: () => null,
  useProgrammerScopeActions: () => ({ focusLocal: () => {} }),
}))
vi.mock('./ProgrammerSheets', () => ({ useProgrammerSheets: () => ({ openMakeLayer: () => {} }) }))
vi.mock('./useLocalFamilyCounts', () => ({ useLocalValueCount: () => 0 }))
// The two tabs have suites of their own; here they only have to be the thing that mounts.
vi.mock('./RailColourTab', () => ({ RailColourTab: () => <div data-testid="colour-tab" /> }))
vi.mock('./RailSpreadTab', () => ({ RailSpreadTab: () => <div data-testid="spread-tab" /> }))
vi.mock('./ScopedEditorContext', () => ({
  ScopedEditorContextProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}))

import { ProgrammerRail, RAIL_TABS } from './ProgrammerRail'
import { ProgrammerWorkspace, RAIL_DEFAULT_WIDTH, RAIL_MAX_WIDTH, RAIL_MIN_WIDTH } from './ProgrammerWorkspace'
import { SPREAD_COMPACT_WORD_CLASS } from '@/components/editor/SpreadPanel'
import { CHROME_ROW_CLASS } from '@/components/sheet/sheetFrame'
import { SIDE_PANEL_HEADER_BUTTON_CLASS, tabWordClass } from '@/components/sheet/sidePanel'
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
})

function draw() {
  return render(<ProgrammerWorkspace grid={<div />} rail={<ProgrammerRail />} />)
}

const tab = (name: string) => screen.getByRole('tab', { name })
/** The first number a Tailwind class names, as pixels — `px-3` → 12, `size-6` → 24, `@[400px]` → 400. */
function px(classes: string, pattern: RegExp, scale = 1): number {
  const match = pattern.exec(classes)
  if (match == null) throw new Error(`${pattern} not in "${classes}"`)
  return Number(match[1]) * scale
}

describe('the fold, as an ordering', () => {
  it('keeps the open tab’s word and folds every other tab to its glyph by the shared rule', () => {
    draw()
    for (const { label } of RAIL_TABS) {
      const button = tab(label)
      const open = button.getAttribute('aria-selected') === 'true'
      const words = [...button.querySelectorAll('span')].filter((span) => span.closest('[data-slot="badge"]') == null)
      expect(words.length).toBeGreaterThan(0)
      for (const word of words) expect(word.className).toContain(tabWordClass(open))
      expect(button.querySelector('svg')).not.toBeNull()
    }
    expect(tabWordClass(true)).not.toContain('hidden')
  })

  it('folds the Stack tab to the strip’s glyph-and-count pairs, never losing the counts', () => {
    draw()
    fireEvent.click(tab('Colour'))
    const stack = tab('Stack')
    // Two glyphs and two counts outside any word that folds.
    expect(stack.querySelectorAll('svg')).toHaveLength(2)
    const counts = [...stack.querySelectorAll('[data-slot="badge"]')]
    expect(counts.map((badge) => badge.textContent)).toEqual(['3', '2'])
    // …and neither count sits inside a word that folds.
    for (const badge of counts) expect(badge.closest('[class*="hidden @["]')).toBeNull()
  })

  it('folds at every width the rail has: the strip at the rail’s ceiling is narrower than the fold', () => {
    // The strip is the row less its gutters, the mode toggle, the chevron and the two gaps
    // between them — read off the classes the row is drawn with.
    const gutter = px(CHROME_ROW_CLASS, /\bpx-(\d+)\b/, 4)
    const gap = px(CHROME_ROW_CLASS, /\bgap-(\d+)\b/, 4)
    const button = px(SIDE_PANEL_HEADER_BUTTON_CLASS, /\bsize-(\d+)\b/, 4)
    const widestStrip = RAIL_MAX_WIDTH - 2 * gutter - 2 * button - 2 * gap
    const fold = px(tabWordClass(false), /@\[(\d+)px\]/)
    expect(widestStrip).toBeLessThan(fold)
  })

  it('starts the Spread panel’s compact curve row between the rail’s floor and its default', () => {
    // The docked panel is the rail less its 1px edge, and `@max-[n]` is a strict `<`: compact
    // below 300 of rail (call 12), worded at the 300 default, compact at the 260 floor.
    const below = px(SPREAD_COMPACT_WORD_CLASS, /@max-\[(\d+)px\]/)
    expect(RAIL_MIN_WIDTH - 1).toBeLessThan(below)
    expect(RAIL_DEFAULT_WIDTH - 1).toBeGreaterThanOrEqual(below)
  })

  it('is its own container, and the docked arm’s alone', () => {
    draw()
    const strip = screen.getByRole('tablist', { name: 'Rail' }).closest('[data-rail-tabs]')!
    expect(strip.className).toContain('@container')
    expect(strip.className).toContain('@max-[1200px]:hidden')
  })
})

describe('the tab fact', () => {
  it('rests on Stack, and a tab press swaps the body: Stack is the layers and the effects', () => {
    draw()
    expect(tab('Stack').getAttribute('aria-selected')).toBe('true')
    expect(screen.getByTestId('layers')).toBeInTheDocument()
    expect(screen.getByTestId('fx')).toBeInTheDocument()

    fireEvent.click(tab('Colour'))
    expect(tab('Colour').getAttribute('aria-selected')).toBe('true')
    expect(screen.getByTestId('colour-tab')).toBeInTheDocument()
    expect(screen.queryByTestId('layers')).toBeNull()

    fireEvent.click(tab('Spread'))
    expect(screen.getByTestId('spread-tab')).toBeInTheDocument()
  })

  it('is not persisted: every arrival rests on Stack (call 10)', () => {
    const first = draw()
    fireEvent.click(tab('Colour'))
    first.unmount()
    draw()
    expect(tab('Stack').getAttribute('aria-selected')).toBe('true')
    const stored = [...Object.keys(window.localStorage), ...Object.keys(window.sessionStorage)]
    expect(stored.some((key) => /tab/i.test(key))).toBe(false)
  })

  it('expands a collapsed rail onto a tab from the strip’s glyph', () => {
    window.localStorage.setItem(COLLAPSED_KEY, JSON.stringify(true))
    draw()
    expect(screen.queryByRole('tablist')).toBeNull()
    fireEvent.click(screen.getByLabelText('Expand the rail on the Colour tab'))
    expect(tab('Colour').getAttribute('aria-selected')).toBe('true')
    expect(screen.getByTestId('colour-tab')).toBeInTheDocument()
    expect(window.localStorage.getItem(COLLAPSED_KEY)).toBe('false')
  })

  it('forgets the tab on a collapse, and a count on the strip opens the Stack tab', () => {
    draw()
    fireEvent.click(tab('Spread'))
    fireEvent.click(screen.getByLabelText('Collapse the rail'))
    fireEvent.click(screen.getByLabelText('Expand the rail at the effects'))
    expect(tab('Stack').getAttribute('aria-selected')).toBe('true')
    expect(screen.getByTestId('fx')).toBeInTheDocument()
  })

  it('draws the strip’s tab glyphs in the docked arm only', () => {
    window.localStorage.setItem(COLLAPSED_KEY, JSON.stringify(true))
    draw()
    const palette = screen.getByLabelText('Expand the rail on the Colour tab')
    expect(palette.className).toContain('@max-[1200px]:hidden')
    expect(screen.getByLabelText('Expand the rail on the Spread tab').className).toContain('@max-[1200px]:hidden')
  })

  it('draws no tabs in overlay mode, where the panel shuts on the next press outside it', () => {
    draw()
    fireEvent.click(tab('Colour'))
    act(() => setSidePanelMode('overlay'))
    expect(screen.queryByRole('tablist')).toBeNull()
    expect(screen.queryByLabelText('Expand the rail on the Colour tab')).toBeNull()
    // The fact reads Stack while it floats; the body is the layers and the effects.
    fireEvent.click(screen.getByLabelText('Open the rail'))
    const body = screen.getByRole('complementary', { name: 'Layers and effects' })
    expect(within(body).getByTestId('layers')).toBeInTheDocument()
  })
})
