// @vitest-environment jsdom
import { StrictMode, type ComponentProps } from 'react'
import { act, fireEvent, render, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { CueAnchorDto } from '../../api/promptBooksApi'

/**
 * The render window, which is most of what this file is about.
 *
 * A `<Page>` canvas is sized at `devicePixelRatio`, so an 84-page panto script rendered whole
 * held ~1.5 GB of canvas — slow to build, and seconds to hand back when the operator navigated
 * away and the browser had to reclaim all of it at once. Pages are therefore mounted by
 * proximity to the scroller.
 *
 * Two halves have to hold together, and each is silently wrong on its own:
 *  • only the windowed pages mount a `<Page>` — the saving;
 *  • *every* sheet is laid out at its true height regardless — what keeps the scroll height
 *    honest, `scrollToRegion` exact, and the normalized overlays resolving against the same
 *    box on a page whose canvas is not currently there.
 * Testing the first alone passes just as happily with a book that collapses to nothing.
 *
 * The last two cases are what the window then took *away* from the rest of the component, and
 * had to be given back: a page's text-vs-scanned classification, which used to settle at load
 * because every page mounted at load, and the fact that a scroll is answerable at all.
 */

// ── react-pdf, stubbed ─────────────────────────────────────────────────────────
// jsdom has no canvas, and the document under test is the layout rather than the raster.
const PAGE_COUNT = 84
const PDF_W = 596
const PDF_H = 842
/** Comfortably over the component's `MIN_TEXT_ITEMS` floor for "this page has real text". */
const TEXT_ITEMS = 120

vi.mock('react-pdf', () => ({
  pdfjs: { GlobalWorkerOptions: {} },
  Document: ({
    onLoadSuccess,
    children,
  }: {
    onLoadSuccess: (pdf: unknown) => void
    children: React.ReactNode
  }) => {
    loadDocument = () => onLoadSuccess({ numPages: PAGE_COUNT, getPage })
    return <div>{children}</div>
  },
  Page: ({ pageIndex }: { pageIndex: number }) => <div data-testid={`pdf-page-${pageIndex}`} />,
}))

let loadDocument: (() => void) | null = null
/** Page *indices* the fake document has no selectable text on — i.e. scans. */
let scannedPages = new Set<number>()

type FakePage = {
  getViewport: () => { width: number; height: number }
  getTextContent: () => Promise<{ items: unknown[] }>
}
/** Swapped by the failure test; every other test measures every page happily. */
let getPage: (n: number) => Promise<FakePage>

// ── Observers ──────────────────────────────────────────────────────────────────
/** Every render-window observer built by a render, newest last. */
const windows: { root: Element | null; rootMargin: string; targets: Set<Element> }[] = []

class FakeIntersectionObserver {
  root: Element | null
  rootMargin: string
  targets = new Set<Element>()
  /** Call counts, because re-registering a target React never detached is the bug below. */
  observeCalls = 0
  unobserveCalls = 0
  constructor(
    private cb: (entries: { target: Element; isIntersecting: boolean }[]) => void,
    options?: { root?: Element | null; rootMargin?: string },
  ) {
    this.root = options?.root ?? null
    this.rootMargin = options?.rootMargin ?? '0px'
    windows.push(this)
  }
  observe(el: Element) {
    this.observeCalls += 1
    this.targets.add(el)
  }
  unobserve(el: Element) {
    this.unobserveCalls += 1
    this.targets.delete(el)
  }
  disconnect() {
    this.targets.clear()
  }
  /** Report a set of page indices as inside the window and the rest as outside. */
  report(inside: number[]) {
    const wanted = new Set(inside)
    this.cb(
      [...this.targets].map((target) => ({
        target,
        isIntersecting: wanted.has(Number((target as HTMLElement).dataset.pageIndex)),
      })),
    )
  }
}

const CONTAINER_WIDTH = 1200
/** Mirrors the component: width less both paper gutters and the sheet padding. */
const PAGE_WIDTH = CONTAINER_WIDTH - 56 - 200 - 48
const SHEET_HEIGHT = Math.floor(PAGE_WIDTH * (PDF_H / PDF_W))

/** The document the fixture describes: every page A4, and text-bearing unless `scannedPages` says. */
const wholeBook = async (n: number): Promise<FakePage> => ({
  getViewport: () => ({ width: PDF_W, height: PDF_H }),
  // pdf.js numbers pages from 1; every index in this file is 0-based, as the component's are.
  getTextContent: async () => ({ items: new Array(scannedPages.has(n - 1) ? 1 : TEXT_ITEMS).fill(null) }),
})

beforeEach(() => {
  windows.length = 0
  loadDocument = null
  scannedPages = new Set()
  getPage = wholeBook
  vi.stubGlobal('IntersectionObserver', FakeIntersectionObserver)
  // jsdom lays nothing out, so the width the sheets are sized from has to be supplied.
  vi.stubGlobal(
    'ResizeObserver',
    class {
      constructor(private cb: () => void) {}
      observe() {
        this.cb()
      }
      disconnect() {}
    },
  )
  Object.defineProperty(HTMLElement.prototype, 'clientWidth', {
    configurable: true,
    value: CONTAINER_WIDTH,
  })
})

const anchors: CueAnchorDto[] = [
  { cueId: 1, label: 'Q1', region: [{ page: 2, x: 0.1, y: 0.4, w: 0.6, h: 0.02 }] },
]

type ViewerProps = ComponentProps<typeof import('./ScriptViewer').ScriptViewer>

const noop = () => {}
const baseProps: ViewerProps = {
  fileUrl: '/script.pdf',
  anchors,
  annotations: [],
  statusOf: () => 'done',
  cueLabels: new Map([[1, 'Q1']]),
  cueNotes: new Map<number, string>(),
  onRenoteCue: noop,
  warningCueIds: new Set<number>(),
  locked: true,
  tool: 'move',
  placingCueId: null,
  onMoveAnchor: noop,
  onPlaceAnchor: noop,
  onAnchorRequest: noop,
  onCreateAnnotation: noop,
  onAnnotationClick: noop,
  onDocumentError: noop,
  onPagesReady: noop,
}

async function renderViewer(overrides: Partial<ViewerProps> = {}, { strict = false } = {}) {
  const { ScriptViewer } = await import('./ScriptViewer')
  const props = { ...baseProps, ...overrides }
  const view = render(<ScriptViewer {...props} />, strict ? { wrapper: StrictMode } : undefined)
  // The document's page dimensions are read asynchronously; the sheets appear with them.
  await act(async () => {
    loadDocument?.()
  })
  const observer = windows.at(-1) as FakeIntersectionObserver | undefined
  const sheets = () => [...view.container.querySelectorAll<HTMLElement>('[data-page-index]')]
  const mounted = () =>
    [...view.container.querySelectorAll('[data-testid^="pdf-page-"]')].map((el) =>
      Number(el.getAttribute('data-testid')!.replace('pdf-page-', '')),
    )
  // Re-render through a prop the component actually uses, so the memo does not swallow it.
  // Internal state (`setDragOverride` at frame rate through a drag) re-renders it the same way.
  const rerender = async () => {
    await act(async () => {
      view.rerender(<ScriptViewer {...props} tool="note" />)
    })
  }
  return { view, observer: observer!, sheets, mounted, rerender }
}

describe('ScriptViewer render window', () => {
  it('lays out every sheet at its true height, with no page mounted until the window reports', async () => {
    const { sheets, mounted } = await renderViewer()

    expect(sheets()).toHaveLength(PAGE_COUNT)
    for (const sheet of sheets()) {
      expect(sheet.style.width).toBe(`${PAGE_WIDTH}px`)
      // The sheet's own height, not the canvas's — it holds while the page is unmounted.
      expect(sheet.style.height).toBe(`${SHEET_HEIGHT}px`)
    }
    expect(mounted()).toEqual([])
  })

  it('watches every sheet from the scroller, with a buffer beyond it', async () => {
    const { observer, sheets } = await renderViewer()

    expect(observer.targets.size).toBe(PAGE_COUNT)
    expect(observer.root).toBe(sheets()[0].closest('.overflow-y-auto'))
    // A page is drawn before it is scrolled to, or a flick lands on a blank sheet.
    expect(observer.rootMargin).toBe('100% 0px')
  })

  it('mounts only the reported pages, and drops them again when they leave', async () => {
    const { observer, mounted, sheets } = await renderViewer()

    await act(async () => observer.report([0, 1, 2]))
    expect(mounted().sort((a, b) => a - b)).toEqual([0, 1, 2])
    // The sheets themselves never went anywhere.
    expect(sheets()).toHaveLength(PAGE_COUNT)

    await act(async () => observer.report([40, 41]))
    expect(mounted().sort((a, b) => a - b)).toEqual([40, 41])
  })

  it('re-registers nothing with the observer when the component merely re-renders', async () => {
    // An inline ref callback in the render loop gets a fresh identity every render, and React
    // answers that by detaching and re-attaching the ref on the *same* node — so an
    // observe/unobserve pair in its body runs once per sheet per render. `setDragOverride`
    // re-renders this component at frame rate through an anchor nudge, which would make that
    // ~84 observer round-trips a frame for no change in what is on screen. The sheets stay put
    // across a re-render, so the correct number of new registrations is zero.
    const { observer, rerender, sheets } = await renderViewer()
    await act(async () => observer.report([0, 1, 2]))

    const before = { observe: observer.observeCalls, unobserve: observer.unobserveCalls }
    await rerender()
    await rerender()

    expect(observer.observeCalls).toBe(before.observe)
    expect(observer.unobserveCalls).toBe(before.unobserve)
    // …and the sheets really are still there and still watched, so this isn't passing by
    // having stopped tracking them.
    expect(sheets()).toHaveLength(PAGE_COUNT)
    expect(observer.targets.size).toBe(PAGE_COUNT)
  })

  it('reports a page that will not measure, rather than showing an empty book', async () => {
    // The measuring walk is all-or-nothing, so one unreadable page object leaves `pageRatios`
    // empty and the pane blank — where the whole-book render this replaced would still have
    // drawn the other eighty-three. It has to reach `onDocumentError`, which is what puts the
    // Retry card on screen.
    const onDocumentError = vi.fn()
    const onPagesReady = vi.fn()
    getPage = async (n: number) => {
      if (n === 43) throw new Error('corrupt page object')
      return wholeBook(n)
    }
    const { sheets } = await renderViewer({ onDocumentError, onPagesReady })

    expect(onDocumentError).toHaveBeenCalledTimes(1)
    expect(sheets()).toHaveLength(0)
    // …and with no sheets, nothing has anywhere to scroll to: the readiness signal below must
    // not fire off the back of a book that failed to lay out.
    expect(onPagesReady).not.toHaveBeenCalled()
  })

  it('draws a page’s cue markers whether or not its canvas is mounted', async () => {
    // The overlays are normalized against the sheet, so an anchor on an unrendered page still
    // has somewhere correct to sit — which is what makes the marker rail continuous.
    const { view, observer } = await renderViewer()

    await act(async () => observer.report([0]))
    expect(view.queryByTestId('pdf-page-2')).toBeNull()
    expect(view.getByText('Q1')).toBeVisible()
  })

  it('classifies every page at load, so a scanned page answers a gesture before it is windowed', async () => {
    // `<Page>`'s `onGetTextSuccess` only fires for a page inside the window, so left to it a page's
    // text-vs-scanned classification settles when you *scroll* to it rather than at load. An
    // unclassified page defaults to "has text", and on a **scanned** page that default waits for a
    // text selection that can never come: the click is swallowed, taking both of the scanned-page
    // fallbacks with it — placing an armed cue, and dragging a note/cut box.
    scannedPages = new Set([3])
    const onPlaceAnchor = vi.fn()
    const { sheets, mounted } = await renderViewer({
      locked: false,
      placingCueId: 1,
      onPlaceAnchor,
    })

    // Nothing has ever been reported inside the window, so no `<Page>` has classified anything —
    // which is the whole point: the answer has to come from the document itself.
    expect(mounted()).toEqual([])
    await waitFor(() => expect(sheets()[3].className).toContain('cursor-crosshair'))

    fireEvent.pointerDown(sheets()[3], { clientX: 100, clientY: 200 })
    expect(onPlaceAnchor).toHaveBeenCalledTimes(1)

    // …and a text page still routes the same gesture to a text selection rather than dropping a
    // band on it, which is the failure that flipping the default would have bought instead.
    expect(sheets()[4].className).not.toContain('cursor-crosshair')
    fireEvent.pointerDown(sheets()[4], { clientX: 100, clientY: 200 })
    expect(onPlaceAnchor).toHaveBeenCalledTimes(1)
  })

  it('classifies under StrictMode too, whose remount latches the mounted flag off', async () => {
    // The walk stops when the component has gone away, and "has it gone away" was a ref written
    // only by the unmount cleanup — so StrictMode's mount/teardown/remount left it `false` for the
    // whole life of the component on screen and the walk gave up after its first page. Caught on
    // the desk, against the real 84-page book, and invisible to a plain `render()`: this same case
    // passes without the fix if it is not wrapped. (It also silently disabled the `onDocumentError`
    // report that the failed-measure test above covers.)
    scannedPages = new Set([3, 71])
    const { sheets } = await renderViewer({ locked: false, placingCueId: 1 }, { strict: true })

    // Both, and the far one is what bites: a walk that gives up part-way still classifies the
    // pages it reached before it did.
    await waitFor(() => expect(sheets()[71].className).toContain('cursor-crosshair'))
    expect(sheets()[3].className).toContain('cursor-crosshair')
    expect(sheets()[40].className).not.toContain('cursor-crosshair')
  })

  it('says once, when its sheets exist, that a scroll can be honoured', async () => {
    // Opening the book mid-show, the playhead is known long before the PDF is, so the effect that
    // jumps to the live cue fires into an empty pane and nothing re-runs it. Hence this signal —
    // and hence *once*: a jump that repeated on every book refetch is exactly the viewport yank
    // that `scrollToCue`'s stable identity exists to prevent.
    const onPagesReady = vi.fn()
    const { observer, rerender } = await renderViewer({ onPagesReady })

    expect(onPagesReady).toHaveBeenCalledTimes(1)

    await act(async () => observer.report([0, 1, 2]))
    await rerender()
    expect(onPagesReady).toHaveBeenCalledTimes(1)
  })
})
