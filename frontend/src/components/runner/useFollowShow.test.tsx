// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render } from '@testing-library/react'
import { useRef } from 'react'
import { FOLLOW_PAUSE_MS, useFollowShow } from './useFollowShow'

/**
 * The Show view's follow (venue-rig-fixes plan D10, D11), on a harness list: a 100px scroller over
 * ten 36px rows, rows 1 and 2 inside it and the rest below. Each host's wiring is its own test's.
 */

const ROW_HEIGHT = 36
const VISIBLE = new Set([1, 2])
const centred = vi.fn()

interface HarnessProps {
  stackId?: number
  active: number | null
  standby?: number | null
  linked?: number | null
}

function Harness({ stackId = 1, active, standby = null, linked = null }: HarnessProps) {
  const scroller = useRef<HTMLDivElement>(null)
  useFollowShow({
    stackId,
    activeEntryId: active,
    standbyEntryId: standby,
    linkedEntryId: linked,
    getScroller: () => scroller.current,
    getRow: (id) => scroller.current?.querySelector(`[data-entry-row="${id}"]`) ?? null,
    scrollTo: centred,
  })
  return (
    <div ref={scroller} data-testid="scroller" tabIndex={-1}>
      {Array.from({ length: 10 }, (_, i) => (
        <button type="button" key={i} data-entry-row={i + 1}>
          Q{i + 1}
        </button>
      ))}
    </div>
  )
}

function rect(top: number, height: number): DOMRect {
  return { top, bottom: top + height, left: 0, right: 100, width: 100, height, x: 0, y: top, toJSON: () => ({}) }
}

let clientHeight = 100

beforeEach(() => {
  clientHeight = 100
  vi.useFakeTimers()
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(function (this: Element) {
    if (this.getAttribute('data-testid') === 'scroller') return rect(0, 100)
    const id = Number(this.getAttribute('data-entry-row'))
    // Rows 1 and 2 sit inside the scroller; every other row is below it.
    return VISIBLE.has(id) ? rect((id - 1) * ROW_HEIGHT, ROW_HEIGHT) : rect(200 + id * ROW_HEIGHT, ROW_HEIGHT)
  })
  vi.spyOn(Element.prototype, 'clientHeight', 'get').mockImplementation(function (this: Element) {
    return this.getAttribute('data-testid') === 'scroller' ? clientHeight : 0
  })
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  vi.useRealTimers()
  centred.mockReset()
})

const scroller = () => document.querySelector<HTMLElement>('[data-testid="scroller"]')!

describe('useFollowShow', () => {
  it('centres the live entry on entering a running stack', () => {
    render(<Harness active={7} />)
    expect(centred).toHaveBeenCalledExactlyOnceWith(7)
  })

  it('centres nothing on entering a stack that is not running', () => {
    render(<Harness active={null} standby={4} />)
    expect(centred).not.toHaveBeenCalled()
  })

  it('a GO to a row outside the scroller centres it, and one to a row in view does not', () => {
    const view = render(<Harness active={1} />)
    centred.mockReset()
    view.rerender(<Harness active={2} />)
    expect(centred).not.toHaveBeenCalled()
    view.rerender(<Harness active={6} />)
    expect(centred).toHaveBeenCalledExactlyOnceWith(6)
  })

  it('arming follows as a GO does', () => {
    const view = render(<Harness active={1} standby={2} />)
    centred.mockReset()
    view.rerender(<Harness active={1} standby={2} />)
    view.rerender(<Harness active={1} standby={1} />)
    expect(centred).not.toHaveBeenCalled()
    view.rerender(<Harness active={1} standby={8} />)
    expect(centred).toHaveBeenCalledExactlyOnceWith(8)
  })

  it('a GO moving both entries follows the live one', () => {
    const view = render(<Harness active={1} standby={2} />)
    centred.mockReset()
    view.rerender(<Harness active={5} standby={6} />)
    expect(centred).toHaveBeenCalledExactlyOnceWith(5)
  })

  it('entering another stack centres again', () => {
    const view = render(<Harness active={1} />)
    centred.mockReset()
    view.rerender(<Harness stackId={2} active={2} />)
    expect(centred).toHaveBeenCalledExactlyOnceWith(2)
  })

  it('a wheel over the list holds following off for 10 s', () => {
    const view = render(<Harness active={1} />)
    centred.mockReset()
    fireEvent.wheel(document.querySelector('[data-entry-row="1"]')!)
    vi.advanceTimersByTime(FOLLOW_PAUSE_MS - 100)
    view.rerender(<Harness active={6} />)
    expect(centred).not.toHaveBeenCalled()
    vi.advanceTimersByTime(200)
    view.rerender(<Harness active={7} />)
    expect(centred).toHaveBeenCalledExactlyOnceWith(7)
  })

  it('a touch move and a press on the scroller itself hold it off too', () => {
    const view = render(<Harness active={1} />)
    centred.mockReset()
    fireEvent.touchMove(document.querySelector('[data-entry-row="2"]')!)
    view.rerender(<Harness active={6} />)
    expect(centred).not.toHaveBeenCalled()
    vi.advanceTimersByTime(FOLLOW_PAUSE_MS + 1)
    fireEvent.pointerDown(scroller())
    view.rerender(<Harness active={7} />)
    expect(centred).not.toHaveBeenCalled()
  })

  it('a scrolling key in the list holds it off, unless something took the key', () => {
    const view = render(<Harness active={1} />)
    centred.mockReset()
    const first = document.querySelector<HTMLElement>('[data-entry-row="1"]')!
    first.focus()
    // The sheet's cursor takes its arrows: that is the sheet's own scroll.
    first.addEventListener('keydown', (e) => e.key === 'ArrowDown' && e.preventDefault(), { once: true })
    fireEvent.keyDown(first, { key: 'ArrowDown' })
    vi.advanceTimersByTime(1)
    view.rerender(<Harness active={6} />)
    expect(centred).toHaveBeenCalledExactlyOnceWith(6)
    centred.mockReset()
    fireEvent.keyDown(first, { key: 'PageDown' })
    vi.advanceTimersByTime(1)
    view.rerender(<Harness active={7} />)
    expect(centred).not.toHaveBeenCalled()
  })

  it('Space on a focused button presses it and does not pause; on the list itself it does', () => {
    const view = render(<Harness active={1} />)
    centred.mockReset()
    const first = document.querySelector<HTMLElement>('[data-entry-row="1"]')!
    first.focus()
    fireEvent.keyDown(first, { key: ' ' })
    vi.advanceTimersByTime(1)
    view.rerender(<Harness active={6} />)
    expect(centred).toHaveBeenCalledExactlyOnceWith(6)
    centred.mockReset()
    scroller().focus()
    fireEvent.keyDown(scroller(), { key: ' ' })
    vi.advanceTimersByTime(1)
    view.rerender(<Harness active={7} />)
    expect(centred).not.toHaveBeenCalled()
  })

  it('a row under a horizontal scrollbar is out of view', () => {
    vi.mocked(Element.prototype.getBoundingClientRect).mockImplementation(function (this: Element) {
      if (this.getAttribute('data-testid') === 'scroller') return rect(0, 100)
      // Row 2 ends at 72, inside the box but under a 30px scrollbar below the 64px client area.
      return rect((Number(this.getAttribute('data-entry-row')) - 1) * ROW_HEIGHT, ROW_HEIGHT)
    })
    clientHeight = 64
    const view = render(<Harness active={1} />)
    centred.mockReset()
    view.rerender(<Harness active={2} />)
    expect(centred).toHaveBeenCalledExactlyOnceWith(2)
  })

  it('a programmatic scroll, such as the pane row kept in view, does not pause it', () => {
    const view = render(<Harness active={1} />)
    centred.mockReset()
    fireEvent.scroll(scroller())
    view.rerender(<Harness active={6} />)
    expect(centred).toHaveBeenCalledExactlyOnceWith(6)
  })

  it('a click on a row never scrolls and never pauses', () => {
    const view = render(<Harness active={1} />)
    centred.mockReset()
    const row = document.querySelector<HTMLElement>('[data-entry-row="2"]')!
    fireEvent.pointerDown(row)
    fireEvent.click(row)
    expect(centred).not.toHaveBeenCalled()
    view.rerender(<Harness active={6} />)
    expect(centred).toHaveBeenCalledExactlyOnceWith(6)
  })

  it('a ?cue= link wins over the entry centring, and following starts from it', () => {
    const view = render(<Harness active={7} linked={3} />)
    expect(centred).not.toHaveBeenCalled()
    view.rerender(<Harness active={8} linked={3} />)
    expect(centred).toHaveBeenCalledExactlyOnceWith(8)
  })

  it('a link arriving with a GO is honoured, and the GO is not followed past it', () => {
    const view = render(<Harness active={1} />)
    centred.mockReset()
    view.rerender(<Harness active={6} linked={9} />)
    expect(centred).not.toHaveBeenCalled()
  })
})
