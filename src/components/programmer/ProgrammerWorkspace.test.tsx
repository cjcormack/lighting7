// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import {
  ProgrammerWorkspace,
  RAIL_DEFAULT_WIDTH,
  RAIL_MAX_WIDTH,
  RAIL_MIN_WIDTH,
  RailBodyFrame,
  RailHandleFrame,
  RailStripFrame,
  useRailArm,
} from './ProgrammerWorkspace'
import { SIDE_PANEL_BODY_CLASS, usePanelEnter } from '@/components/sheet/sidePanel'
import {
  resetSidePanelModeStore,
  setSidePanelMode,
  useSidePanelMode,
} from '@/lib/sidePanelMode'

/**
 * The rail's three arms and the state behind them, driven through a stand-in rail that reads
 * `useRailArm` the way `ProgrammerRail` does.
 *
 * jsdom lays nothing out, so the arms cannot be pinned by *measuring*: what is pinned instead is
 * the contract the container queries are written against — which classes the frames carry in
 * each state, on the child of the `@container` wrapper — plus everything that is JavaScript: the
 * three flags, what writes each, the drag's arithmetic and its two endings, and that the stored
 * width survives a remount. The widths themselves are measured in a browser (space plan §6).
 */
let railRenders = 0

function TestRail() {
  const arm = useRailArm()
  railRenders += 1
  // `ProgrammerRail` computes the enter flag here, above the conditional mount, for the reason
  // the assertions below pin: a hook inside the frame sees every appearance as a first render.
  // One latch per arm, as the real rail does — the overlay arm's body is mounted the whole time,
  // so `!collapsed` alone can never see it open.
  const overlay = useSidePanelMode() === 'overlay'
  const expandEnter = usePanelEnter(!arm.collapsed)
  const overlayEnter = usePanelEnter(arm.overlayOpen)
  const enter = overlay ? overlayEnter : expandEnter || overlayEnter
  // The real rail's mount condition: one flag in overlay mode, both in push mode.
  const bodyShown = overlay ? arm.overlayOpen : !arm.collapsed || arm.overlayOpen
  return (
    <>
      {/* The real rail's ternary: the sheet and the docked body are one body in two places. */}
      {arm.sheetOpen ? (
        <div data-testid="sheet">
          <button onClick={arm.closeSheet}>close sheet</button>
        </div>
      ) : (
        bodyShown && (
          <RailBodyFrame enter={enter}>
            <button onClick={arm.collapse}>collapse</button>
            <button onClick={arm.closeOverlay}>close</button>
          </RailBodyFrame>
        )
      )}
      <RailStripFrame>
        <button onClick={arm.expand}>expand</button>
        <button onClick={arm.openOverlay}>open</button>
      </RailStripFrame>
      <RailHandleFrame>
        <button onClick={arm.openSheet}>open sheet</button>
      </RailHandleFrame>
    </>
  )
}

function draw() {
  return render(
    <ProgrammerWorkspace grid={<div data-testid="grid">grid</div>} rail={<TestRail />} />,
  )
}

const body = () => screen.getByRole('complementary', { name: 'Layers and effects' })
const strip = () => screen.getByText('expand').parentElement as HTMLElement
const bottomHandle = () => screen.getByText('open sheet').parentElement as HTMLElement
const handle = () => screen.getByRole('separator', { name: 'Resize the rail' })
/** The row carries `select-none` only while a drag runs — the drag's one visible trace. */
const resizing = () => body().parentElement!.className.includes('select-none')

afterEach(() => {
  cleanup()
  window.localStorage.clear()
  window.sessionStorage.clear()
  resetSidePanelModeStore()
  railRenders = 0
})

describe('ProgrammerWorkspace', () => {
  it('declares the container on a wrapper and queries it from the children', () => {
    // The trap the doc comment records: a container query never matches the element that
    // declares the container. Every arm class has to sit at least one level down.
    const { container } = draw()
    const wrapper = container.firstElementChild as HTMLElement
    expect(wrapper.className).toContain('@container')
    expect(body().className).not.toContain('@container')
    expect(body().parentElement).toBe(wrapper.firstElementChild)
  })

  it('docks at the default width, with the strip hidden in the wide arm', () => {
    draw()
    expect(body().style.getPropertyValue('--rail-w')).toBe(`${RAIL_DEFAULT_WIDTH}px`)
    expect(body().className).toContain('@min-[1200px]:w-[var(--rail-w)]')
    expect(body().className).not.toContain('@min-[1200px]:hidden')
    // Narrow, the same element is the overlay — shut until opened.
    expect(body().className).toContain('@max-[1200px]:absolute')
    expect(body().className).toContain('@max-[1200px]:hidden')
    expect(strip().className).toContain('@min-[1200px]:hidden')
  })

  it('collapses to the strip in the wide arm, and remembers it', () => {
    draw()
    fireEvent.click(screen.getByText('collapse'))
    expect(screen.queryByRole('complementary')).toBeNull()
    expect(strip().className).not.toContain('@min-[1200px]:hidden')
    expect(JSON.parse(window.localStorage.getItem('programmer.rail.collapsed') ?? 'null')).toBe(
      true,
    )

    cleanup()
    draw()
    expect(screen.queryByRole('complementary')).toBeNull()
    fireEvent.click(screen.getByText('expand'))
    expect(body().className).not.toContain('@min-[1200px]:hidden')
  })

  it('opens as an overlay in the narrow arm without touching the docked preference', () => {
    draw()
    fireEvent.click(screen.getByText('collapse'))
    fireEvent.click(screen.getByText('open'))
    // Mounted, and unhidden for the narrow arm only: the wide arm still reads `collapsed`.
    expect(body().className).not.toContain('@max-[1200px]:hidden')
    expect(body().className).toContain('@min-[1200px]:hidden')
    // Flush to the edge: the strip is no longer beside it to be inset past.
    expect(body().className).toContain('@max-[1200px]:right-0')
    expect(body().className).toContain('@max-[1200px]:w-[300px]')

    fireEvent.click(screen.getByText('close'))
    expect(screen.queryByRole('complementary')).toBeNull()
    expect(JSON.parse(window.localStorage.getItem('programmer.rail.collapsed') ?? 'null')).toBe(
      true,
    )
  })

  it('closes the overlay on Escape, unless something above it took the key', () => {
    draw()
    fireEvent.click(screen.getByText('open'))
    expect(body().className).not.toContain('@max-[1200px]:hidden')

    // A sheet or popover open over the rail prevents the default on the Escape it handles; one
    // press must close that and not the rail as well.
    const taken = new KeyboardEvent('keydown', { key: 'Escape', cancelable: true })
    taken.preventDefault()
    window.dispatchEvent(taken)
    expect(body().className).not.toContain('@max-[1200px]:hidden')

    fireEvent.keyDown(window, { key: 'Escape' })
    expect(body().className).toContain('@max-[1200px]:hidden')
  })

  it('closes the overlay on a press anywhere on the grid', () => {
    draw()
    fireEvent.click(screen.getByText('open'))
    fireEvent.pointerDown(screen.getByTestId('grid'))
    expect(body().className).toContain('@max-[1200px]:hidden')
  })

  it('sets the docked width from the handle, leftwards to grow, clamped to the bounds', () => {
    draw()
    const rendersBefore = railRenders
    fireEvent.pointerDown(handle(), { button: 0, clientX: 1000 })
    expect(resizing()).toBe(true)

    fireEvent.pointerMove(window, { clientX: 900 })
    expect(body().style.getPropertyValue('--rail-w')).toBe('400px')
    // The width rides its own context, read by the frame alone: nothing that reads the arm —
    // which is the rail, above every layer and FX row — re-renders per pointer move.
    expect(railRenders).toBe(rendersBefore)

    fireEvent.pointerMove(window, { clientX: 100 })
    expect(body().style.getPropertyValue('--rail-w')).toBe(`${RAIL_MAX_WIDTH}px`)
    fireEvent.pointerMove(window, { clientX: 1900 })
    expect(body().style.getPropertyValue('--rail-w')).toBe(`${RAIL_MIN_WIDTH}px`)

    // Nothing is stored until the release, and the release stores the last value shown.
    expect(window.localStorage.getItem('programmer.rail.width')).toBe(String(RAIL_DEFAULT_WIDTH))
    fireEvent.pointerMove(window, { clientX: 950 })
    fireEvent.pointerUp(window)
    expect(resizing()).toBe(false)
    expect(railRenders).toBe(rendersBefore)
    expect(window.localStorage.getItem('programmer.rail.width')).toBe('350')
  })

  it('ends a drag on pointercancel exactly as on pointerup', () => {
    // A touchscreen pan reclaimed by the browser sends no pointerup. Left `resizing`, the frame
    // would keep its window listeners and write a width on the next movement with nothing held.
    draw()
    fireEvent.pointerDown(handle(), { button: 0, clientX: 1000 })
    fireEvent.pointerMove(window, { clientX: 940 })
    fireEvent.pointerCancel(window)
    expect(resizing()).toBe(false)
    expect(window.localStorage.getItem('programmer.rail.width')).toBe('360')

    fireEvent.pointerMove(window, { clientX: 500 })
    expect(body().style.getPropertyValue('--rail-w')).toBe('360px')
  })

  it('keeps the stored width across a remount, and clamps one it does not trust', () => {
    window.localStorage.setItem('programmer.rail.width', '440')
    draw()
    expect(body().style.getPropertyValue('--rail-w')).toBe('440px')
    cleanup()

    window.localStorage.setItem('programmer.rail.width', '9000')
    draw()
    expect(body().style.getPropertyValue('--rail-w')).toBe(`${RAIL_MAX_WIDTH}px`)
    cleanup()

    window.localStorage.setItem('programmer.rail.width', '"wide"')
    draw()
    expect(body().style.getPropertyValue('--rail-w')).toBe(`${RAIL_DEFAULT_WIDTH}px`)
  })

  it('turns the row into a column below 704px, with a bottom handle instead of the strip', () => {
    // Space plan D8's third arm. 704 of workspace is a 768px viewport with the sidebar on its
    // 64px rail — Tailwind's `md` — and below `md` the sidebar is off-canvas so the workspace is
    // the viewport. `flex-col` is the whole of the layout change: the same two children of the
    // same row, stacked, so the strip frame's sibling lands under the grid rather than beside it.
    draw()
    expect(body().parentElement!.className).toContain('@max-[704px]:flex-col')
    // The two right-hand frames are gone at that width; the handle is gone at every other.
    expect(strip().className).toContain('@max-[704px]:hidden')
    expect(body().className).toContain('@max-[704px]:hidden')
    expect(bottomHandle().className).toContain('@min-[704px]:hidden')
    expect(bottomHandle().className).toContain('h-11')
  })

  it('opens the bottom sheet from the handle, and mounts the body in exactly one place', () => {
    draw()
    // Docked to start with: the body is in its frame and there is no sheet.
    expect(screen.queryByTestId('sheet')).toBeNull()
    expect(screen.queryByRole('complementary')).not.toBeNull()

    fireEvent.click(screen.getByText('open sheet'))
    expect(screen.getByTestId('sheet')).toBeTruthy()
    // The one that matters: two bodies would be two layer lists, two FX lists and two of every
    // subscription under them.
    expect(screen.queryByRole('complementary')).toBeNull()

    fireEvent.click(screen.getByText('close sheet'))
    expect(screen.queryByTestId('sheet')).toBeNull()
    expect(screen.queryByRole('complementary')).not.toBeNull()
  })

  it('writes no preference from the bottom sheet', () => {
    // The phone arm is transient, like the overlay: closing a sheet on a phone must not collapse
    // the rail on the desk that shares this desk's localStorage.
    draw()
    fireEvent.click(screen.getByText('open sheet'))
    fireEvent.click(screen.getByText('close sheet'))
    // `usePersistentState` seeds its key on mount, so the assertion is on the VALUE: the phone's
    // gesture must never write `true` into the preference a wide desk reads tomorrow.
    expect(JSON.parse(window.localStorage.getItem('programmer.rail.collapsed') ?? 'null')).toBe(
      false,
    )
    expect(window.localStorage.getItem('programmer.rail.width')).toBe(String(RAIL_DEFAULT_WIDTH))
  })

  it('shares the busk side sheet’s body chrome, and animates the body in only when it opens', () => {
    draw()
    // The fill and the left edge are the shared module's — `components/sheet/sidePanel.ts`.
    for (const cls of SIDE_PANEL_BODY_CLASS.split(' ')) expect(body().className).toContain(cls)
    // Expanded on the first render is the stored default, and is not an opening.
    expect(body().className).not.toContain('animate-in')
    fireEvent.click(screen.getByText('collapse'))
    fireEvent.click(screen.getByText('expand'))
    expect(body().className).toContain('animate-in')
  })

  it('animates the overlay arm too — its body is mounted throughout, so `!collapsed` cannot see it open', () => {
    // The defect this pins: `collapsed` rests at false, so a single `!collapsed || overlayOpen`
    // is already true on the first render and opening the overlay transitions nothing. jsdom
    // draws no arms, but the flag is JavaScript and is the whole of what the arm animates on.
    draw()
    expect(body().className).not.toContain('animate-in')
    fireEvent.click(screen.getByText('open'))
    expect(body().className).toContain('animate-in')
    fireEvent.click(screen.getByText('close'))
    expect(body().className).not.toContain('animate-in')
  })

  it('never draws the strip and the body at once, in either mode', () => {
    // The busk sheet's reading, which the rail was brought onto: the fold IS the closed state of
    // the panel, so the two are one control in two shapes. The rail used to stand its strip
    // beside the open overlay at 704–1200, which is what looked wrong on the desk.
    draw()
    // Push mode, docked arm: open body, strip hidden by the wide query.
    expect(body().className).not.toContain('@min-[1200px]:hidden')
    expect(strip().className).toContain('@min-[1200px]:hidden')
    // Push mode, narrow arm: opening the overlay makes the strip invisible for that arm too —
    // invisible and not hidden, so its 40px stays in the row and the grid does not reflow.
    fireEvent.click(screen.getByText('collapse'))
    expect(strip().className).not.toContain('@max-[1200px]:invisible')
    fireEvent.click(screen.getByText('open'))
    expect(strip().className).toContain('@max-[1200px]:invisible')
    expect(strip().className).not.toContain('@max-[1200px]:hidden')
  })

  it('takes one arm at every width in overlay mode, and keeps the strip\u2019s room when open', () => {
    setSidePanelMode('overlay')
    draw()
    // `collapsed` says nothing here: the only flag is `overlayOpen`, so nothing is mounted yet.
    expect(screen.queryByRole('complementary')).toBeNull()
    // The bare class, as a token: `@max-[704px]:hidden` is always there and contains the word.
    const hiddenOutright = (el: HTMLElement) => el.className.split(' ').includes('hidden')
    expect(hiddenOutright(strip())).toBe(false)

    fireEvent.click(screen.getByText('open'))
    const panel = body()
    expect(panel).toHaveAttribute('data-rail-mode', 'overlay')
    // One arm: absolute and flush at every width, with no 1200px pair to switch between.
    expect(panel.className).toContain('absolute')
    expect(panel.className).toContain('right-0')
    expect(panel.className).not.toContain('@min-[1200px]:relative')
    expect(panel.className).not.toContain('@max-[1200px]:absolute')
    // The stored width applies here too — the operator chose to float it, so the drag they set
    // still governs — and the handle is drawn, unqualified by any container query, because at
    // every width in this mode the width on screen is the stored one.
    expect(panel.className).toContain('w-[var(--rail-w)]')
    expect(handle().className).not.toContain('@max-[1200px]:hidden')
    // The strip is invisible under the open overlay, never taken out of the row: the overlay is
    // absolute and takes no room, so a strip that left the flow as it opened handed its 40px to
    // the grid and every column reflowed on each open and close.
    expect(hiddenOutright(strip())).toBe(false)
    expect(strip().className.split(' ')).toContain('invisible')

    fireEvent.click(screen.getByText('close'))
    expect(strip().className.split(' ')).not.toContain('invisible')
  })

  it('draws the header in the grid\u2019s column, beside the rail and outside the overlay\u2019s close', () => {
    // The rail stands beside the view's menus and under nothing but the ShowHeader, as the busk
    // sheet stands beside the rig band — so row A is the column's, not the page's.
    render(
      <ProgrammerWorkspace
        header={<div data-testid="header">row A</div>}
        grid={<div data-testid="grid">grid</div>}
        rail={<TestRail />}
      />,
    )
    const header = screen.getByTestId('header')
    const column = header.parentElement as HTMLElement
    expect(column.contains(screen.getByTestId('grid'))).toBe(true)
    expect(column.contains(body())).toBe(false)
    // The column and the rail are siblings in the one row.
    expect(column.parentElement).toBe(body().parentElement)
    // Before the grid, in the column's flow.
    expect(column.firstElementChild).toBe(header)

    // A press on row A is not a press on the grid: its verbs are about the programmer the rail
    // shows, and closing the rail under them was never the rule.
    fireEvent.click(screen.getByText('open'))
    fireEvent.pointerDown(header)
    expect(body().className).not.toContain('@max-[1200px]:hidden')
    fireEvent.pointerDown(screen.getByTestId('grid'))
    expect(body().className).toContain('@max-[1200px]:hidden')
  })

  it('renders the grid exactly once, and never inside the rail', () => {
    draw()
    expect(screen.getAllByTestId('grid')).toHaveLength(1)
    expect(body().contains(screen.getByTestId('grid'))).toBe(false)
  })
})
