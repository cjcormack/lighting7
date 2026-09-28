// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import {
  edgeDragRef,
  edgeSideFor,
  handTargetAt,
  isEdgeDragRelease,
  pointInViewport,
  readWindowFrame,
  screenToClient,
  type WindowFrame,
} from './edgeDrag'

/**
 * The same-machine edge drag's pure half (multi-screen plan §5 session 4).
 *
 * The whole reason this file exists is that the gesture itself needs two browser windows on two
 * monitors, which no test has. Every rule that can be stated as arithmetic is stated here, against
 * a **two-monitor desktop written out as data** — including the left-hand monitor's negative
 * coordinates, which is what Windows does and what a naive `Math.abs` or a clamp at zero would
 * quietly get wrong.
 */

/** The right-hand monitor's window: 1920 wide at the virtual desktop's origin. */
const right: WindowFrame = {
  screenX: 0,
  screenY: 0,
  outerWidth: 1920,
  outerHeight: 1080,
  innerWidth: 1920,
  innerHeight: 993,
}

/**
 * The left-hand monitor's window. On Windows the desktop's origin is the *primary* screen's
 * top-left, so a monitor to its left has negative coordinates throughout.
 */
const left: WindowFrame = { ...right, screenX: -1920 }

describe('edgeSideFor — has the pointer left this window', () => {
  it('answers null while the pointer is inside', () => {
    expect(edgeSideFor({ x: 960, y: 500 }, right)).toBeNull()
    expect(edgeSideFor({ x: 0, y: 0 }, right)).toBeNull()
    expect(edgeSideFor({ x: 1919, y: 1079 }, right)).toBeNull()
  })

  it('answers right at the far edge and beyond', () => {
    expect(edgeSideFor({ x: 1920, y: 500 }, right)).toBe('right')
    expect(edgeSideFor({ x: 2400, y: 500 }, right)).toBe('right')
  })

  it('answers left before the near edge', () => {
    expect(edgeSideFor({ x: -1, y: 500 }, right)).toBe('left')
  })

  it('is horizontal only — off the top or the bottom is not a hand-off', () => {
    // The gesture is "the screen to its right". A pointer dragged over the OS's own furniture, or
    // over some other application's window above or below, must not give the record away.
    expect(edgeSideFor({ x: 960, y: -300 }, right)).toBeNull()
    expect(edgeSideFor({ x: 960, y: 4000 }, right)).toBeNull()
  })

  it('crosses out of a corner, because vertical position is not consulted', () => {
    expect(edgeSideFor({ x: 2000, y: -40 }, right)).toBe('right')
  })

  it('reads a window at negative screen coordinates the same way', () => {
    // The whole of the left-hand monitor is negative; its right edge is the desktop origin.
    expect(edgeSideFor({ x: -960, y: 500 }, left)).toBeNull()
    expect(edgeSideFor({ x: -1920, y: 500 }, left)).toBeNull()
    expect(edgeSideFor({ x: -1921, y: 500 }, left)).toBe('left')
    // x = 0 is the first pixel of the right-hand monitor, and is past this window's right edge.
    expect(edgeSideFor({ x: 0, y: 500 }, left)).toBe('right')
  })
})

describe('screenToClient — a posted point in the receiving window', () => {
  it('subtracts the window origin and the chrome above the viewport', () => {
    // 1080 - 993 = 87px of chrome, all at the top; no side borders.
    expect(screenToClient({ x: 300, y: 500 }, right)).toEqual({ x: 300, y: 413 })
  })

  it('works across the desktop origin, so a left-hand monitor is not a special case', () => {
    expect(screenToClient({ x: -1620, y: 500 }, left)).toEqual({ x: 300, y: 413 })
  })

  it('splits symmetric side borders between the two edges', () => {
    const bordered: WindowFrame = { ...right, outerWidth: 1930, innerWidth: 1920 }
    expect(screenToClient({ x: 305, y: 500 }, bordered).x).toBe(300)
  })

  it('never shifts the point left when innerWidth exceeds outerWidth', () => {
    // A side-docked devtools panel reports this. Clamped at zero rather than inverted.
    const odd: WindowFrame = { ...right, outerWidth: 1000, innerWidth: 1920 }
    expect(screenToClient({ x: 300, y: 500 }, odd).x).toBe(300)
  })
})

describe('readWindowFrame', () => {
  it('reads the six live values off a window', () => {
    expect(readWindowFrame(window)).toEqual({
      screenX: window.screenX,
      screenY: window.screenY,
      outerWidth: window.outerWidth,
      outerHeight: window.outerHeight,
      innerWidth: window.innerWidth,
      innerHeight: window.innerHeight,
    })
  })
})

describe('edgeDragRef — which drags carry a record', () => {
  it('reads all three kinds off a library palette row', () => {
    expect(
      edgeDragRef({ type: 'busk-palette', record: { kind: 'TEMPLATE', template: { id: 7 } } }),
    ).toEqual({ kind: 'TEMPLATE', id: 7 })
    expect(
      edgeDragRef({ type: 'busk-palette', record: { kind: 'LOOK', look: { id: 8 } } }),
    ).toEqual({ kind: 'LOOK', id: 8 })
    expect(edgeDragRef({ type: 'busk-palette', record: { kind: 'CUE', cue: { id: 9 } } })).toEqual({
      kind: 'CUE',
      id: 9,
    })
  })

  it('answers null for every drag that is a rearrangement rather than a record', () => {
    // A pad and a bank move the page; a slot item moves the cue-slot overlay. None of them names a
    // record id at all, which is why the edge drag is the palette's alone for now.
    expect(edgeDragRef({ type: 'busk-pad', at: { row: 0 } })).toBeNull()
    expect(edgeDragRef({ type: 'busk-bank', at: { row: 0 } })).toBeNull()
    expect(edgeDragRef({ type: 'slot-item', page: 0, slotIndex: 1 })).toBeNull()
    expect(edgeDragRef(undefined)).toBeNull()
    expect(edgeDragRef(null)).toBeNull()
  })
})

describe('handTargetAt — the element under a posted point', () => {
  it('walks up from the topmost hit to the registered target', () => {
    document.body.innerHTML = `
      <button data-hand-target="bank" id="band"><span id="label">Place here</span></button>
    `
    const label = document.getElementById('label')!
    const doc = {
      elementsFromPoint: () => [label, document.body],
    } as unknown as Document
    expect(handTargetAt({ x: 10, y: 10 }, doc)?.id).toBe('band')
  })

  it('takes the topmost registered target', () => {
    document.body.innerHTML = `
      <button data-hand-target="slot" id="over"></button>
      <button data-hand-target="bank" id="under"></button>
    `
    const over = document.getElementById('over')!
    const under = document.getElementById('under')!
    const doc = { elementsFromPoint: () => [over, under] } as unknown as Document
    expect(handTargetAt({ x: 10, y: 10 }, doc)?.id).toBe('over')
  })

  it('does NOT burrow past a covering overlay to a target underneath', () => {
    // A real click there hits the overlay and does nothing. An open dialog or sheet leaves the page
    // beneath it mounted, so a stack walk would place a record on a band the operator cannot see.
    document.body.innerHTML = `
      <div id="overlay"></div>
      <button data-hand-target="bank" id="covered"></button>
    `
    const overlay = document.getElementById('overlay')!
    const covered = document.getElementById('covered')!
    const doc = { elementsFromPoint: () => [overlay, covered] } as unknown as Document
    expect(handTargetAt({ x: 10, y: 10 }, doc)).toBeNull()
  })

  it('answers null when nothing under the point is a target', () => {
    document.body.innerHTML = `<div id="plain"></div>`
    const plain = document.getElementById('plain')!
    const doc = { elementsFromPoint: () => [plain] } as unknown as Document
    expect(handTargetAt({ x: 10, y: 10 }, doc)).toBeNull()
  })

  it('answers null where the document cannot hit-test at all', () => {
    // jsdom has no `elementsFromPoint`, so this guard is load-bearing for every test above it.
    expect(handTargetAt({ x: 10, y: 10 }, {} as unknown as Document)).toBeNull()
  })
})

describe('isEdgeDragRelease', () => {
  const record = { kind: 'TEMPLATE', id: 7 }
  const screen = { x: 1, y: 2 }

  it('accepts a release that names its point and its record', () => {
    expect(isEdgeDragRelease({ type: 'edge-drag-release', screen, record })).toBe(true)
  })

  it('rejects a release naming no record', () => {
    // The record is what the receiving window waits for the hand to hold. A release without one
    // would be a release the receiver could only act on blindly.
    expect(isEdgeDragRelease({ type: 'edge-drag-release', screen })).toBe(false)
    expect(isEdgeDragRelease({ type: 'edge-drag-release', screen, record: { id: 7 } })).toBe(false)
  })

  it('rejects anything else on a shared-origin channel', () => {
    expect(isEdgeDragRelease({ type: 'edge-drag-release', record })).toBe(false)
    expect(isEdgeDragRelease({ type: 'edge-drag-hello' })).toBe(false)
    expect(isEdgeDragRelease(null)).toBe(false)
    expect(isEdgeDragRelease('release')).toBe(false)
  })
})

describe('pointInViewport', () => {
  it('accepts a point on the window and refuses one beyond it', () => {
    expect(pointInViewport({ x: 0, y: 0 }, right)).toBe(true)
    expect(pointInViewport({ x: 1919, y: 992 }, right)).toBe(true)
    expect(pointInViewport({ x: -1, y: 10 }, right)).toBe(false)
    expect(pointInViewport({ x: 10, y: 993 }, right)).toBe(false)
    expect(pointInViewport({ x: 1920, y: 10 }, right)).toBe(false)
  })
})
