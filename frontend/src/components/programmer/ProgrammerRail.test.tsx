// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * `PD-SHEET-ICONS-OPEN`: the two counts the collapsed arms draw are doors, not readouts.
 *
 * jsdom lays nothing out, so which arm is *on screen* cannot be asserted here — that is a browser
 * check, and so is where the scroll lands. What is pinned is everything that is JavaScript: that
 * each glyph is a control at all, that the strip's pair write the two arm flags apart (the docked
 * one the stored preference, the overlay one not), that the handle's open the sheet, and that the
 * band a press asks for is the one the body scrolls to. The arm-splitting is the same contract
 * `ProgrammerWorkspace.test.tsx` states from the frames' side.
 */
vi.mock('react-router', () => ({ useParams: () => ({ projectId: '6' }) }))
vi.mock('@/store/programmer', () => ({
  useProgrammerLayersQuery: () => ({ data: [] }),
}))
vi.mock('@/store/fixtureFx', () => ({ useActiveEffectsQuery: () => ({ data: [] }) }))
vi.mock('./FxSheet', () => ({ FxSheet: () => null }))
vi.mock('./ProgrammerAddEffect', () => ({
  ProgrammerAddEffectSheet: () => null,
  useProgrammerAddEffect: () => ({ canAdd: true, reason: '', add: () => {} }),
}))
vi.mock('./ProgrammerAddLayerSheet', () => ({ ProgrammerAddLayerSheet: () => null }))
vi.mock('./ProgrammerFxList', () => ({ ProgrammerFxList: () => null }))
vi.mock('./ProgrammerLookStack', () => ({ ProgrammerLookStack: () => null }))
// The hand's place target in the footer. Mocked for the reason every store-touching child here
// is: it reads the desk selection and the hand through RTK Query, and this suite renders the
// rail without a Provider.
vi.mock('@/components/hand/HandLayerTargets', () => ({ HandProgrammerLayerStrip: () => null }))
vi.mock('./ProgrammerScope', () => ({
  useProgrammerScope: () => null,
  useProgrammerScopeActions: () => ({ focusLocal: () => {} }),
}))
vi.mock('./ProgrammerSheets', () => ({
  useProgrammerSheets: () => ({ openMakeLayer: () => {} }),
}))
vi.mock('./useLocalFamilyCounts', () => ({ useLocalValueCount: () => 0 }))
// The Scenery band has a suite of its own (`RailSceneryBand.test.tsx`); here it is a landmark with
// a ref, and the held count is a value the test sets.
vi.mock('./RailSceneryBand', async () => {
  const { forwardRef } = await import('react')
  return {
    RailSceneryBand: forwardRef<HTMLDivElement>(function Band(_, ref) {
      return <div ref={ref} data-testid="scenery-band" />
    }),
  }
})
vi.mock('./ProgrammerSceneryList', () => ({ useHeldSceneryCount: () => sceneryHeld.count }))
const sceneryHeld = vi.hoisted(() => ({ count: 0 }))

import { ProgrammerRail } from './ProgrammerRail'
import { ProgrammerWorkspace } from './ProgrammerWorkspace'

const COLLAPSED_KEY = 'programmer.rail.collapsed'

/** What `scrollIntoView` was called on, since the element itself is the assertion. */
let scrolledTo: Element[] = []
let realScrollIntoView: Element['scrollIntoView']

beforeEach(() => {
  scrolledTo = []
  window.localStorage.setItem(COLLAPSED_KEY, JSON.stringify(true))
  // `src/test/setup.ts` already stubs `scrollIntoView` to a no-op for every jsdom test, since
  // jsdom implements none. Replacing it with a recorder is what makes the band request
  // observable — the same swap `StackDetail.test.tsx` makes, and restored below so it cannot
  // follow this file into another.
  realScrollIntoView = Element.prototype.scrollIntoView
  Element.prototype.scrollIntoView = function scrollIntoView(this: Element) {
    scrolledTo.push(this)
  }
})

afterEach(() => {
  cleanup()
  window.localStorage.clear()
  Element.prototype.scrollIntoView = realScrollIntoView
})

function draw() {
  render(
    <ProgrammerWorkspace grid={<div />} rail={<ProgrammerRail />} />,
  )
}

/** The frame a child is drawn in — the element carrying the arms' container-query classes. */
function frameOf(child: Element): Element {
  const frame = child.closest('div[class*="flex"]')
  if (frame == null) throw new Error('no frame')
  return frame
}

describe('ProgrammerRail — the collapsed arms', () => {
  it('draws each count as a control, with a name per arm', () => {
    // Both arms are in the DOM at once (they are hidden by container queries, not by JS), so the
    // four strip buttons and the two handle buttons must not be announced by the same words —
    // the rule the two chevrons already keep.
    draw()
    const names = [
      'Expand the rail at the layers',
      'Expand the rail at the effects',
      'Open the rail at the layers',
      'Open the rail at the effects',
      'Show the layers',
      'Show the effects',
      // Scenery's doors (scenery-programmer plan D17): the strip's pair and the handle's one.
      'Expand the rail at the scenery',
      'Open the rail at the scenery',
      'Show the scenery',
    ]
    for (const name of names) {
      expect(screen.getAllByRole('button', { name })).toHaveLength(1)
    }
  })

  it("the strip's docked count expands the rail, writing the stored preference", () => {
    draw()
    fireEvent.click(screen.getByRole('button', { name: 'Expand the rail at the layers' }))
    // `collapsed` is what the strip frame hides itself on at ≥1200, and it is persisted — the
    // observable difference from the overlay arm below.
    expect(window.localStorage.getItem(COLLAPSED_KEY)).toBe('false')
  })

  it("the strip's overlay count opens the overlay and leaves the preference alone", () => {
    draw()
    fireEvent.click(screen.getByRole('button', { name: 'Open the rail at the effects' }))
    // The body is up either way; what must not happen is an iPad's press writing "expanded" into
    // the preference a wide desk reads tomorrow.
    expect(window.localStorage.getItem(COLLAPSED_KEY)).toBe('true')
    expect(screen.getByText('Values above beat effects below')).toBeTruthy()
  })

  it("the handle's counts open the sheet", () => {
    draw()
    fireEvent.click(screen.getByRole('button', { name: 'Show the effects' }))
    expect(window.localStorage.getItem(COLLAPSED_KEY)).toBe('true')
    expect(screen.getByText('Values above beat effects below')).toBeTruthy()
  })

  it('scrolls to the band the press named, and to the rule above the effects', () => {
    draw()
    fireEvent.click(screen.getByRole('button', { name: 'Show the effects' }))
    expect(scrolledTo).toHaveLength(1)
    // The anchor is the amber boundary rather than the `Effects` label below it: the bar is what
    // introduces the half, so landing on the label would open the band with the one rule that
    // explains it just above the fold.
    expect(scrolledTo[0].textContent).toContain('Values above beat effects below')

    cleanup()
    scrolledTo = []
    draw()
    fireEvent.click(screen.getByRole('button', { name: 'Show the layers' }))
    expect(scrolledTo).toHaveLength(1)
    expect(scrolledTo[0].textContent).toContain('top wins')
  })

  it('opens the rail at the Scenery band from the handle, the band being the top of the body', () => {
    sceneryHeld.count = 2
    draw()
    const handle = screen.getByRole('button', { name: 'Show the scenery' })
    expect(handle.textContent).toBe('2')
    fireEvent.click(handle)
    expect(scrolledTo).toHaveLength(1)
    expect(scrolledTo[0].getAttribute('data-testid')).toBe('scenery-band')
    // Above the values, as the top tier: the band comes before the Values label in the body.
    const band = screen.getByTestId('scenery-band')
    const values = screen.getByText('top wins')
    expect(band.compareDocumentPosition(values) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    // …and the sheet's title counts it beside the layers and the effects.
    expect(screen.getByRole('dialog').textContent).toContain('Scenery2')
    sceneryHeld.count = 0
  })

  it('counts held scenery on the strip, which opens the rail at the band', () => {
    sceneryHeld.count = 1
    draw()
    const strip = screen.getByRole('button', { name: 'Open the rail at the scenery' })
    expect(strip.textContent).toBe('1')
    expect(strip.getAttribute('title')).toBe('1 held piece of scenery')
    fireEvent.click(strip)
    expect(scrolledTo[0].getAttribute('data-testid')).toBe('scenery-band')
    sceneryHeld.count = 0
  })

  it('honours a band request once, and again on the next press', () => {
    // A one-shot request, not a stored position: were it kept, the operator's own scrolling
    // afterwards would be undone by the next unrelated re-render of the body — and were it not
    // released, a second press on an already-open rail would do nothing. Driven from the strip
    // rather than the handle because the sheet is a Radix modal, which `aria-hidden`s the handle
    // behind it: pressing it twice is not a gesture that exists on that arm.
    draw()
    fireEvent.click(screen.getByRole('button', { name: 'Open the rail at the effects' }))
    expect(scrolledTo).toHaveLength(1)
    expect(scrolledTo[0].textContent).toContain('Values above beat effects below')
    fireEvent.click(screen.getByRole('button', { name: 'Open the rail at the layers' }))
    expect(scrolledTo).toHaveLength(2)
    expect(scrolledTo[1].textContent).toContain('top wins')
  })

  it('keeps the strip and the handle in separate frames', () => {
    // Sanity on the mount points the arms' CSS is written against, so a refactor that folded the
    // two into one frame would fail here rather than on a phone.
    draw()
    const strip = frameOf(screen.getByRole('button', { name: 'Expand the rail at the layers' }))
    const handle = frameOf(screen.getByRole('button', { name: 'Show the layers' }))
    expect(strip).not.toBe(handle)
  })

  it('keeps the rail header and the strip chevrons level with row B at 40px', () => {
    // The chrome system: row B is `h-10` on a 12px gutter, and this header's bottom border meets
    // that row's across the seam. `h-9 px-2.5` was four pixels short and two in. Expanded, since
    // the docked frame is what draws the header.
    window.localStorage.setItem(COLLAPSED_KEY, JSON.stringify(false))
    draw()
    const expand = screen.getByRole('button', { name: 'Expand the rail' })
    expect(expand.className).toContain('h-10')
    // **And the `+` at the foot of the same column.** It was left at `h-9` by the chrome tidy-up
    // and only the chevron was pinned, so the one control that had missed the system was also the
    // one the test could not see. Every fixed-height cell of the strip is asserted here now.
    const add = screen.getByRole('button', { name: 'Add a layer or an effect' })
    expect(add.className).toContain('h-10')
    // The header is the one 40px row with the gutter; `0 layers` is also the strip count's title.
    const header = document.querySelector('div.h-10.border-b.px-3')!
    expect(header).not.toBeNull()
    expect(header.textContent).toContain('Layers')
    expect(header.className).toContain('px-3')
    expect(header.className).toContain('gap-2')
  })
})
