// @vitest-environment jsdom
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { useRef, useState } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import {
  CELL_EDITOR_SHEET_QUERY,
  EditorSurface,
  editorIsOpen,
  resetEditorSurfaceMedia,
} from './EditorSurface'
import { openCellEditorTarget } from '../sheet/cellEntry'
import { useEditorOpen } from './useEditorOpen'

/**
 * Which of the three shapes a cell editor takes, and the listeners behind the decision.
 *
 * Three things here can fail silently on a desk and only show up on a phone: a query string
 * `matchMedia` cannot parse (it answers `false` rather than throwing, so the fold simply never
 * happens — the trap `shortViewport.test.ts` guards for the rest of the short-viewport arm), the
 * precedence between the two queries (a landscape phone matches *both*, and answering it with the
 * bottom sheet gives it the one shape that needs the height it has not got), and the media
 * subscription quietly becoming per-cell, which costs a listener per visible cell on a rig of any
 * size and is invisible in every rendering of the grid.
 */

/** A `matchMedia` this file controls the answers of, counting the listeners hung off it. */
function stubMatchMedia({ narrow, short }: { narrow: boolean; short: boolean }): {
  listenerCount: () => number
} {
  let count = 0
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: query.includes('max-width') ? narrow : query.includes('max-height') ? short : false,
    media: query,
    onchange: null,
    addEventListener: () => {
      count += 1
    },
    removeEventListener: () => {
      count -= 1
    },
    addListener: () => {},
    removeListener: () => {},
    dispatchEvent: () => false,
  }))
  return { listenerCount: () => count }
}

function Surface({ title = 'Dimmer' }: { title?: string }) {
  return (
    <EditorSurface
      open
      onOpenChange={() => {}}
      title={title}
      contentClassName="w-64"
      trigger={<button type="button">cell</button>}
    >
      <p>editor body</p>
    </EditorSurface>
  )
}

/** What the mounted editor is drawing itself as, from the attribute the surface stamps. */
function form(): string | null {
  return document.querySelector('[data-cell-editor-surface]')?.getAttribute('data-cell-editor-surface') ?? null
}

beforeEach(() => {
  resetEditorSurfaceMedia()
})

afterEach(() => {
  vi.unstubAllGlobals()
  resetEditorSurfaceMedia()
})

/**
 * A cell whose click behaves the programmer's way: the trigger selects and never opens, and the
 * editor — when something else opens it — is anchored at the button passed in rather than at the
 * cell. `openFromOutside` stands in for the selection bar's Set.
 */
function SelectOnlySurface({ withAnchor = true }: { withAnchor?: boolean }) {
  // **The real hook, not a `useState` standing in for it.** Whether the panel is anchored at the
  // Set button is `useEditorOpen`'s latched `atButton`, and the cells forward the ref only
  // while it holds — so a harness that kept its own boolean would put every test on the
  // Set-anchored branch, including the ones about gestures that never take it, and would go on
  // passing if the latch itself regressed. `autoOpen` is the one-shot the table raises for Set.
  const [setPressed, setSetPressed] = useState(false)
  const anchorRef = useRef<HTMLButtonElement | null>(null)
  const { isOpen, setOpen, atButton } = useEditorOpen({
    autoOpen: setPressed,
    anchorAtButton: true,
  })
  return (
    <>
      <button type="button" ref={anchorRef} onClick={() => setSetPressed(true)}>
        Set
      </button>
      <EditorSurface
        open={isOpen}
        onOpenChange={setOpen}
        title="Dimmer"
        contentClassName="w-64"
        triggerOpens={false}
        // Production's own expression, verbatim — see `SliderCell`.
        anchorRef={withAnchor && atButton ? anchorRef : undefined}
        trigger={
          <button type="button" onClick={() => selected()}>
            cell
          </button>
        }
      >
        <p>editor body</p>
      </EditorSurface>
    </>
  )
}

const selected = vi.fn()

/** A cell the way the two plain list routes mount it: a single click is the way into the editor. */
function ClickOpensSurface() {
  const [open, setOpen] = useState(false)
  return (
    <EditorSurface
      open={open}
      onOpenChange={setOpen}
      title="Dimmer"
      contentClassName="w-64"
      trigger={<button type="button">cell</button>}
    >
      <p>editor body</p>
    </EditorSurface>
  )
}

describe('EditorSurface', () => {
  it('floats a popover at desk sizes', () => {
    stubMatchMedia({ narrow: false, short: false })
    render(<Surface />)

    expect(form()).toBe('popover')
    expect(document.querySelector('[data-slot="sheet-content"]')).toBeNull()
    expect(screen.getByText('editor body')).toBeVisible()
  })

  it('drops a bottom sheet on an upright phone, titled by the column', () => {
    stubMatchMedia({ narrow: true, short: false })
    render(<Surface title="Colour" />)

    expect(form()).toBe('bottom-sheet')
    expect(document.querySelector('[data-slot="popover-content"]')).toBeNull()
    // A sheet is a Radix dialog, and a dialog with no accessible name is unreadable. The title is
    // also the only thing on screen saying which column the editor belongs to, since a sheet is
    // anchored to the screen rather than to the cell.
    expect(screen.getByText('Colour')).toBeVisible()
    expect(screen.getByText('editor body')).toBeVisible()
  })

  it('uses the right-hand sheet where the viewport is short but not narrow', () => {
    stubMatchMedia({ narrow: false, short: true })
    render(<Surface />)

    expect(form()).toBe('side-sheet')
  })

  it('prefers the side sheet when the viewport is short AND narrow', () => {
    // A landscape phone matches both queries. The bottom sheet is the one shape that needs the
    // vertical room a short viewport has not got, so short has to win — this is the assertion that
    // fails if the two arms are ever reordered.
    stubMatchMedia({ narrow: true, short: true })
    render(<Surface />)

    expect(form()).toBe('side-sheet')
  })

  it('shares one media listener per query, however many cells are mounted', () => {
    const media = stubMatchMedia({ narrow: false, short: false })
    render(
      <>
        <Surface title="Dimmer" />
        <Surface title="Colour" />
        <Surface title="Position" />
      </>,
    )

    // Two queries, three editors. Not "at most six": the point is that the count does not track
    // the number of editors, which the grid mounts one of per visible cell.
    expect(media.listenerCount()).toBe(2)
  })

  it('asks a width question `matchMedia` can parse, one pixel below the `sm` breakpoint', () => {
    // `matchMedia` answers `false` for a string it cannot parse rather than throwing, so a typo
    // here is a fold that silently never happens on the only devices that want it — and jsdom
    // implements no `matchMedia` to parse it with, so the shape is pinned instead. The height
    // query's spelling is pinned beside its four siblings in `shortViewport.test.ts`.
    const match = /^\(max-width: (\d+)px\)$/.exec(CELL_EDITOR_SHEET_QUERY)
    expect(match, `${CELL_EDITOR_SHEET_QUERY} is not a max-width query`).not.toBeNull()
    // The sheet primitive's own `sm:max-w-sm` is the other half of this decision: below the width
    // at which a sheet stops filling the screen is exactly where a popover stops having room.
    expect(Number(match?.[1])).toBe(639)
  })
})

describe('EditorSurface click behaviour', () => {
  beforeEach(() => {
    selected.mockClear()
    stubMatchMedia({ narrow: false, short: false })
  })

  it('a click on the trigger selects instead of opening, where `triggerOpens` is false', () => {
    // The whole of item 3: on the programmer's grid a click says *what* to edit, and Set says
    // *edit it*. The trigger is a `PopoverAnchor` there rather than a `PopoverTrigger`, so Radix
    // has nothing to toggle and the button's own `onClick` is all a click does.
    render(<SelectOnlySurface />)
    fireEvent.click(screen.getByText('cell'))
    expect(selected).toHaveBeenCalledTimes(1)
    expect(screen.queryByText('editor body')).toBeNull()
  })

  it('a double click on the trigger opens the editor, where a single click only selects', () => {
    // The second gesture made with the pointer alone. A real browser sends both clicks and then
    // `dblclick`, so the test does too: the clicks must still select — the double click composes
    // onto the cell's own `onClick` rather than replacing it — and the editor must end up open.
    render(<SelectOnlySurface />)
    const cell = screen.getByText('cell')
    fireEvent.click(cell)
    expect(screen.queryByText('editor body')).toBeNull()
    fireEvent.click(cell)
    fireEvent.doubleClick(cell)

    expect(selected).toHaveBeenCalledTimes(2)
    expect(screen.getByText('editor body')).toBeVisible()
  })

  it('leaves an already-open editor alone — a double click opens, it does not re-open', () => {
    // The bug the guard exists for: a second `onOpenChange(true)` would re-run the cell's own open
    // reset (`SliderCell`'s `draft.reset`) over what the operator had half-typed, and re-latch the
    // anchor to the cell, swinging a Set-anchored panel across the screen and remounting the cell's
    // button under the pointer.
    //
    // **This reaches the second open only because `fireEvent` does not synthesise a gesture.** It
    // dispatches exactly the one event it names, so no `pointerdown` precedes the `dblclick` and
    // `DismissableLayer`'s `pointerdown` listener — the thing that closes an open editor when the
    // press lands outside it — never runs. At the desk that listener does run and the editor is
    // already shut by the time `dblclick` arrives, so this is the guard's narrow case rather than
    // its everyday one. Not `triggerRef`: `PopoverContent` suppresses an outside press only where
    // it lands on a real `PopoverTrigger`, and this grid renders none in either environment.
    const opens: boolean[] = []
    function Harness() {
      const [open, setOpen] = useState(false)
      return (
        <EditorSurface
          open={open}
          onOpenChange={(next) => {
            opens.push(next)
            setOpen(next)
          }}
          title="Dimmer"
          contentClassName="w-64"
          triggerOpens={false}
          trigger={<button type="button">cell</button>}
        >
          <p>editor body</p>
        </EditorSurface>
      )
    }
    render(<Harness />)
    const cell = screen.getByText('cell')
    fireEvent.doubleClick(cell)
    expect(opens).toEqual([true])

    // Still open, and the gesture repeated on it changes nothing.
    fireEvent.doubleClick(cell)
    expect(opens).toEqual([true])
    expect(screen.getByText('editor body')).toBeVisible()
  })

  it('does not remount the cell under the pointer when a Set-anchored editor is double clicked', () => {
    // The visible half of the same bug. Re-opening re-latches `atButton` to false, which swaps the
    // rendered branch from `TriggerState` to `PopoverAnchor` — different component types at one
    // JSX slot, so React tears the subtree down and builds it again and the operator's own button
    // is replaced under their finger, mid-gesture. Node identity is the assertion because it is
    // the half jsdom can see: it lays nothing out, so the anchor swap itself is unobservable.
    render(<SelectOnlySurface />)
    fireEvent.click(screen.getByText('Set'))
    const cell = screen.getByText('cell')
    expect(screen.getByText('editor body')).toBeVisible()

    fireEvent.doubleClick(cell)

    expect(screen.getByText('editor body')).toBeVisible()
    expect(screen.getByText('cell')).toBe(cell)
  })

  it('wires no double click where a single click already opens', () => {
    // Two clicks there toggle the editor shut again, and a double click that re-opened it would
    // make the second click of the gesture do nothing visible. `dblclick` alone is the proof: it
    // is the only event this would have to be listening to.
    render(<ClickOpensSurface />)
    fireEvent.doubleClick(screen.getByText('cell'))

    expect(screen.queryByText('editor body')).toBeNull()
  })

  it('opens at the anchor it is given, not at the cell', async () => {
    // floating-ui measures whatever Radix hands it as the reference element, so the proof that the
    // Set button is the anchor is that the Set button is what gets measured — and that the cell is
    // not. Nothing else about the position is observable in jsdom, which lays nothing out.
    render(<SelectOnlySurface />)
    const setButton = screen.getByText('Set')
    const cell = screen.getByText('cell')
    const setRect = vi.spyOn(setButton, 'getBoundingClientRect')
    const cellRect = vi.spyOn(cell, 'getBoundingClientRect')

    fireEvent.click(setButton)

    expect(screen.getByText('editor body')).toBeVisible()
    // floating-ui measures from an effect and resolves a promise, so the read is a tick behind
    // the open.
    await waitFor(() => expect(setRect).toHaveBeenCalled())
    expect(cellRect).not.toHaveBeenCalled()
  })

  it('opens beside the cell when the opener passes no anchor — the keyboard\'s case', async () => {
    // Enter and a typed character are gestures made *at the selection*, with the operator's eye on
    // the grid, so their editor belongs beside the cell. The cells express that by withholding the
    // anchor (`atButton ? editorAnchorRef : undefined`) rather than by a second prop here.
    render(<SelectOnlySurface withAnchor={false} />)
    const cell = screen.getByText('cell')
    const setButton = screen.getByText('Set')
    const cellRect = vi.spyOn(cell, 'getBoundingClientRect')
    const setRect = vi.spyOn(setButton, 'getBoundingClientRect')

    fireEvent.click(setButton)

    await waitFor(() => expect(cellRect).toHaveBeenCalled())
    expect(setRect).not.toHaveBeenCalled()
  })

  it('falls back to the cell when there is no anchor to open at', async () => {
    // A `virtualRef` whose `current` is null would set Radix's anchor to null and the content
    // would never be positioned at all, so "no Set button" has to mean the cell rather than
    // nothing. The two plain list routes pass no anchor and are the case this protects.
    render(<SelectOnlySurface withAnchor={false} />)
    const cell = screen.getByText('cell')
    const cellRect = vi.spyOn(cell, 'getBoundingClientRect')

    fireEvent.click(screen.getByText('Set'))

    expect(screen.getByText('editor body')).toBeVisible()
    await waitFor(() => expect(cellRect).toHaveBeenCalled())
  })
})

describe('editorIsOpen', () => {
  // The grid's window-level Escape asks this instead of asking where the key was pressed, because
  // Radix closes an open editor from a document listener wherever focus is — so the two questions
  // disagree exactly when focus has been left somewhere unexpected, which is what pressing Set
  // twice does. Answering it from the DOM is what keeps one bit from becoming a subscription per
  // cell.
  it('is false with nothing open and true while an editor is on screen', () => {
    stubMatchMedia({ narrow: false, short: false })
    expect(editorIsOpen()).toBe(false)

    const { unmount } = render(<SelectOnlySurface />)
    expect(editorIsOpen()).toBe(false)

    fireEvent.click(screen.getByText('Set'))
    expect(editorIsOpen()).toBe(true)

    unmount()
    expect(editorIsOpen()).toBe(false)
  })

  it('sees a sheet as readily as a popover — Escape must behave in all three forms', () => {
    stubMatchMedia({ narrow: true, short: false })
    render(<SelectOnlySurface />)
    fireEvent.click(screen.getByText('Set'))
    expect(form()).toBe('bottom-sheet')
    expect(editorIsOpen()).toBe(true)
  })
})

/**
 * `data-state` on the trigger is the grid's addressing contract: it is what says *which cell* the
 * open editor belongs to once the panel itself is anchored somewhere else (the Set button) or
 * portalled to the screen edge (either sheet). The selection bar's Set reads it to decide which
 * editor to shut, so it has to be there in **all three** forms, not just the popover.
 */
describe('EditorSurface trigger state', () => {
  function cellGrid(children: React.ReactNode) {
    return (
      <div data-row-id="fixture:a">
        <div data-cell="dimmer">{children}</div>
      </div>
    )
  }

  function SurfaceInCell({ withAnchor = true }: { withAnchor?: boolean }) {
    const anchorRef = useRef<HTMLButtonElement | null>(null)
    return cellGrid(
      <>
        <button type="button" ref={anchorRef}>
          Set
        </button>
        <EditorSurface
          open
          onOpenChange={() => {}}
          title="Dimmer"
          contentClassName="w-64"
          triggerOpens={false}
          anchorRef={withAnchor ? anchorRef : undefined}
          trigger={<button type="button">cell</button>}
        >
          <p>editor body</p>
        </EditorSurface>
      </>,
    )
  }

  it('marks the cell in every form, so Set can find the editor it opened', () => {
    // The sheet forms are what this caught: a sheet is portalled to `body` exactly as a popover is,
    // so with the marker left off nothing inside `[data-cell]` said an editor was open and Set's
    // second press could never close one on a phone or a short viewport.
    for (const media of [
      { narrow: false, short: false },
      { narrow: true, short: false },
      { narrow: false, short: true },
    ]) {
      resetEditorSurfaceMedia()
      stubMatchMedia(media)
      const { unmount } = render(<SurfaceInCell />)
      expect(openCellEditorTarget()).toEqual({ rowId: 'fixture:a', col: 'dimmer' })
      unmount()
      vi.unstubAllGlobals()
    }
  })

})
