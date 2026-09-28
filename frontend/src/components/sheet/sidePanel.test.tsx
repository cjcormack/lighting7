// @vitest-environment jsdom
import { StrictMode, useState } from 'react'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import {
  getSidePanelMode,
  resetSidePanelModeStore,
  setSidePanelMode,
  toggleSidePanelMode,
} from '@/lib/sidePanelMode'
import * as sidePanel from './sidePanel'
import { CHROME_ROW_CLASS } from './sheetFrame'
import {
  clampPanelWidth,
  SIDE_PANEL_BODY_CLASS,
  SIDE_PANEL_ENTER_CLASS,
  SIDE_PANEL_MAX_WIDTH,
  SIDE_PANEL_MIN_WIDTH,
  SIDE_PANEL_OVERLAY_CLASS,
  SIDE_PANEL_STRIP_CELL_CLASS,
  SIDE_PANEL_STRIP_CLASS,
  usePanelEnter,
  useSidePanelResize,
} from './sidePanel'

/**
 * The shared docked-panel chrome, and the one piece of it that is code.
 *
 * The class strings are pinned the way `ProgrammerWorkspace.test.tsx` pins the frames': jsdom
 * lays nothing out, so what a test can hold is the contract — the measurements the chrome system
 * settles, so a change to one of them is a change to this file and not a silent drift in one of
 * the two surfaces. `usePanelEnter` is pinned as behaviour, because its two rules (never on the
 * first render, latched while open) are each a real defect if they go.
 */

afterEach(() => {
  cleanup()
  resetSidePanelModeStore()
  window.sessionStorage.clear()
  // The resize commits a width to `localStorage` on release; without this the next case starts
  // from the last one's drag rather than from its own fallback.
  window.localStorage.clear()
})

describe('the side-panel mode', () => {
  it('defaults to push — the behaviour both panels already had', () => {
    expect(getSidePanelMode()).toBe('push')
  })

  it('toggles, and is one fact for both panels rather than one each', () => {
    toggleSidePanelMode()
    expect(getSidePanelMode()).toBe('overlay')
    toggleSidePanelMode()
    expect(getSidePanelMode()).toBe('push')
  })

  it('is per tab, in sessionStorage — two desk screens are two windows of one profile', () => {
    setSidePanelMode('overlay')
    expect(window.sessionStorage.getItem('desk.sidePanel.mode')).toBe('"overlay"')
    expect(window.localStorage.getItem('desk.sidePanel.mode')).toBeNull()
  })

  it('takes the fallback for a value a later build wrote, rather than passing it through', () => {
    window.sessionStorage.setItem('desk.sidePanel.mode', '"floating"')
    resetSidePanelModeStore()
    expect(getSidePanelMode()).toBe('push')
  })
})

describe('the shared side-panel chrome', () => {
  it('states the strip’s measurements once: 40px wide, on a 40px cell', () => {
    expect(SIDE_PANEL_STRIP_CLASS).toContain('w-10')
    expect(SIDE_PANEL_STRIP_CLASS).toContain('border-l')
    expect(SIDE_PANEL_STRIP_CELL_CLASS).toContain('h-10')
    expect(SIDE_PANEL_STRIP_CELL_CLASS).toContain('w-10')
  })

  it('does not restate the 40px chrome row — a panel header is `sheetFrame.ts`’s', () => {
    // The whole point of the module is one measurement in one place, so a header constant here
    // would be the drift it exists to close: `CHROME_ROW_CLASS` already says 40px on a 12px
    // gutter, and both panels import it directly.
    expect(CHROME_ROW_CLASS).toBe('flex h-10 shrink-0 items-center gap-2 border-b px-3')
    expect(Object.keys(sidePanel)).not.toContain('SIDE_PANEL_HEADER_CLASS')
    for (const value of Object.values(sidePanel)) {
      if (typeof value === 'string') expect(value).not.toBe(CHROME_ROW_CLASS)
    }
  })

  it('fills the body opaquely — both panels have an overlay arm over live content', () => {
    expect(SIDE_PANEL_BODY_CLASS).toContain('color-mix')
    expect(SIDE_PANEL_BODY_CLASS).toContain('border-l')
    // A translucent fill is the thing the mix exists to avoid; catch a revert to it by name.
    expect(SIDE_PANEL_BODY_CLASS).not.toContain('bg-card/40')
  })

  it('slides in its whole width, not a token 16px, and declares no exit', () => {
    // `slide-in-from-right-4` is what shipped first and the desk's verdict was that neither
    // panel read as animating at all: 16px of travel is smaller than the content reflow beside
    // it, so the eye takes the jump and never sees the slide. Bare `slide-in-from-right` is the
    // full width, which is what the app's own `Sheet` primitive uses.
    expect(SIDE_PANEL_ENTER_CLASS).toContain('animate-in')
    expect(SIDE_PANEL_ENTER_CLASS).toContain('slide-in-from-right')
    expect(SIDE_PANEL_ENTER_CLASS).not.toMatch(/slide-in-from-right-\d/)
    expect(SIDE_PANEL_ENTER_CLASS).not.toContain('animate-out')
  })

  it('sets the animation’s duration and easing without touching transitions', () => {
    // `duration-300` sets `animation-duration` (via tailwindcss-animate) AND
    // `transition-duration` (via Tailwind core), and CSS's initial `transition-property` is
    // `all` — so with the class latched for as long as the panel is open, every property
    // transitioned over 300ms. What that broke was the resize: each new width was eased toward
    // rather than followed. Arbitrary animation properties cannot leak that way.
    expect(SIDE_PANEL_ENTER_CLASS).toContain('[animation-duration:300ms]')
    expect(SIDE_PANEL_ENTER_CLASS).toMatch(/\[animation-timing-function:/)
    expect(SIDE_PANEL_ENTER_CLASS).not.toMatch(/(^|\s)duration-/)
    expect(SIDE_PANEL_ENTER_CLASS).not.toMatch(/(^|\s)ease-/)
  })

  it('floats an overlay panel flush to the edge, with no scrim', () => {
    // Flush `right-0` and not inset by a strip width, because both surfaces hide their strip
    // while the panel is up. No scrim: the rail overlays a grid that goes on being clicked and
    // the sheet overlays pads that go on being pressed.
    expect(SIDE_PANEL_OVERLAY_CLASS).toContain('absolute')
    expect(SIDE_PANEL_OVERLAY_CLASS).toContain('right-0')
    expect(SIDE_PANEL_OVERLAY_CLASS).not.toContain('right-10')
    expect(SIDE_PANEL_OVERLAY_CLASS).not.toContain('bg-black')
  })
})

/** A stand-in panel: the flag, a toggle for it, and the class the hook answers with. */
function Panel({ initial }: { initial: boolean }) {
  const [open, setOpen] = useState(initial)
  // A counter rather than `setOpen(open)`: React bails out of a state write that changes nothing,
  // so re-rendering has to be asked for with something that actually moves.
  const [, setTick] = useState(0)
  const enter = usePanelEnter(open)
  return (
    <>
      <button onClick={() => setOpen((previous) => !previous)}>toggle</button>
      <button onClick={() => setTick((previous) => previous + 1)}>re-render</button>
      <div data-testid="panel" data-enter={enter ? 'yes' : 'no'} />
    </>
  )
}

function enterState() {
  return screen.getByTestId('panel').getAttribute('data-enter')
}

describe('usePanelEnter', () => {
  it('does not animate a panel that is already open on the first render', () => {
    render(<Panel initial />)
    expect(enterState()).toBe('no')
  })

  it('animates when the panel opens, and stops offering it once it closes again', () => {
    render(<Panel initial={false} />)
    expect(enterState()).toBe('no')
    fireEvent.click(screen.getByText('toggle'))
    expect(enterState()).toBe('yes')
    fireEvent.click(screen.getByText('toggle'))
    expect(enterState()).toBe('no')
  })

  it('latches the class while the panel stays open — a re-render must not cut the animation off', () => {
    render(<Panel initial={false} />)
    fireEvent.click(screen.getByText('toggle'))
    expect(enterState()).toBe('yes')
    // Both panels re-render freely while open: a layer arrives, a marquee moves, a tempo ticks.
    fireEvent.click(screen.getByText('re-render'))
    fireEvent.click(screen.getByText('re-render'))
    expect(enterState()).toBe('yes')
  })

  it('is safe under StrictMode’s double render — the flag is state, not a mutated ref', () => {
    render(
      <StrictMode>
        <Panel initial={false} />
      </StrictMode>,
    )
    expect(enterState()).toBe('no')
    fireEvent.click(screen.getByText('toggle'))
    expect(enterState()).toBe('yes')
  })
})

/** A stand-in panel: the handle, and the width the hook answers with. */
function Resizable({
  storageKey = 'test.panel.width',
  min,
}: {
  storageKey?: string
  min?: number
}) {
  const { width, resizing, onResizeStart } = useSidePanelResize({
    storageKey,
    fallback: min == null ? 300 : min + 20,
    min,
  })
  return (
    <div data-testid="panel" data-width={width} data-resizing={resizing ? 'yes' : 'no'}>
      <div data-testid="handle" onPointerDown={onResizeStart} />
    </div>
  )
}

const panelWidth = () => Number(screen.getByTestId('panel').getAttribute('data-width'))
const isResizing = () => screen.getByTestId('panel').getAttribute('data-resizing') === 'yes'

/** dnd is not involved: the hook listens on `window` after its own `pointerdown`. */
function drag(toClientX: number[], { end = 'pointerup' }: { end?: 'pointerup' | 'pointercancel' } = {}) {
  fireEvent.pointerDown(screen.getByTestId('handle'), { button: 0, clientX: 1000 })
  for (const x of toClientX) fireEvent.pointerMove(window, { clientX: x })
  fireEvent[end === 'pointerup' ? 'pointerUp' : 'pointerCancel'](window)
}

describe('useSidePanelResize', () => {
  it('grows as the handle is dragged left — both panels sit against the right edge', () => {
    render(<Resizable />)
    expect(panelWidth()).toBe(300)
    fireEvent.pointerDown(screen.getByTestId('handle'), { button: 0, clientX: 1000 })
    fireEvent.pointerMove(window, { clientX: 940 })
    expect(panelWidth()).toBe(360)
    expect(isResizing()).toBe(true)
    fireEvent.pointerMove(window, { clientX: 1040 })
    expect(panelWidth()).toBe(260)
    fireEvent.pointerUp(window)
    expect(isResizing()).toBe(false)
  })

  it('clamps to the shared range, whatever the pointer does', () => {
    render(<Resizable />)
    drag([0])
    expect(panelWidth()).toBe(SIDE_PANEL_MAX_WIDTH)
    drag([9999])
    expect(panelWidth()).toBe(SIDE_PANEL_MIN_WIDTH)
  })

  it('commits to storage once, on release, not on every move', () => {
    render(<Resizable />)
    fireEvent.pointerDown(screen.getByTestId('handle'), { button: 0, clientX: 1000 })
    fireEvent.pointerMove(window, { clientX: 960 })
    // Mid-drag the panel is already 340 wide, but nothing has been written: `usePersistentState`
    // writes storage on every change, and a drag is sixty of them a second.
    expect(panelWidth()).toBe(340)
    expect(window.localStorage.getItem('test.panel.width')).not.toBe('340')
    fireEvent.pointerUp(window)
    expect(window.localStorage.getItem('test.panel.width')).toBe('340')
  })

  it('ends on pointercancel as surely as on pointerup', () => {
    // A touch drag the browser reclaims as a pan ends with no release at all; a panel left
    // `resizing` keeps its window listeners and writes a width on the next pointer movement
    // anywhere on the page with nothing held down.
    render(<Resizable />)
    drag([950], { end: 'pointercancel' })
    expect(isResizing()).toBe(false)
    const settled = panelWidth()
    fireEvent.pointerMove(window, { clientX: 400 })
    expect(panelWidth()).toBe(settled)
  })

  it('ignores a non-primary button', () => {
    render(<Resizable />)
    fireEvent.pointerDown(screen.getByTestId('handle'), { button: 2, clientX: 1000 })
    expect(isResizing()).toBe(false)
  })

  it('clamps a stored value from an older build rather than trusting it', () => {
    expect(clampPanelWidth(9999, 300)).toBe(SIDE_PANEL_MAX_WIDTH)
    expect(clampPanelWidth(-5, 300)).toBe(SIDE_PANEL_MIN_WIDTH)
    expect(clampPanelWidth('wide', 300)).toBe(300)
    expect(clampPanelWidth(Number.NaN, 300)).toBe(300)
  })

  it('lifts a stored width up to a panel’s own floor, and never below it', () => {
    // A panel whose chrome needs more than the shared 260 passes its own `min`; a desk that
    // stored the narrower value before that floor existed is lifted on read rather than left
    // rendering a clipped header.
    expect(clampPanelWidth(260, 320, 320)).toBe(320)
    expect(clampPanelWidth(400, 320, 320)).toBe(400)
    // Even a fallback below the floor cannot get under it.
    expect(clampPanelWidth('junk', 288, 320)).toBe(320)
  })

  it('honours a panel’s own floor through a whole drag, not just on read', () => {
    render(<Resizable storageKey="test.floor.width" min={320} />)
    expect(panelWidth()).toBe(340)
    fireEvent.pointerDown(screen.getByTestId('handle'), { button: 0, clientX: 1000 })
    fireEvent.pointerMove(window, { clientX: 1200 })
    expect(panelWidth()).toBe(320)
    fireEvent.pointerUp(window)
    expect(window.localStorage.getItem('test.floor.width')).toBe('320')
  })
})
