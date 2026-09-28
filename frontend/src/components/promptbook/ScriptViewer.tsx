import {
  memo,
  useCallback,
  useEffect,
  useImperativeHandle,
  useMemo,
  useRef,
  useState,
  forwardRef,
  type PointerEvent as ReactPointerEvent,
} from 'react'
import { Document, Page, pdfjs } from 'react-pdf'
import 'react-pdf/dist/Page/TextLayer.css'
import { Loader2 } from 'lucide-react'
import { clamp, cn } from '@/lib/utils'
import { useMountedRef } from '@/hooks/useMountedRef'
import type { AnnotationDto, AnnotationKind, CueAnchorDto, Rect, Region } from '../../api/promptBooksApi'
import {
  clientPointToNormalized,
  cornersToRect,
  groupByPage,
  MARKER_LANE_X,
  moveRegionVertically,
  rangeToRegion,
  rectToStyle,
  verticalBounds,
} from '../../lib/promptBook/geometry'
import { scriptPosition } from '../../lib/promptBook/desync'
import { CueWash, CueMarginMarker, type CueRunStatus } from './AnchorOverlay'
import {
  CueNoteCallout,
  CueNoteInline,
  CutOverlay,
  CutMarginMarker,
  FreetextOverlay,
  NoteCallout,
  NoteInline,
} from './AnnotationOverlay'
import { FloatingSelectionToolbar } from './FloatingSelectionToolbar'
import type { PromptBookTool } from './ToolPalette'

// Vite worker wiring per react-pdf v10 docs — react-pdf pins the matching
// pdfjs-dist, so API and worker versions can't skew.
pdfjs.GlobalWorkerOptions.workerSrc = new URL(
  'pdfjs-dist/build/pdf.worker.min.mjs',
  import.meta.url,
).toString()

/** Default anchor band placed on a click: full text width, ~2.5% page height.
 *  Used only on the scanned-PDF fallback (a page with no selectable text). */
const PLACED_ANCHOR_RECT = { x: 0.04, w: 0.92, h: 0.025 }

/** Below this item count a page is treated as image-only (scanned) — some scans
 *  carry a few junk OCR items, so a bare `> 0` would misfire. */
const MIN_TEXT_ITEMS = 5

/**
 * How far outside the scroller a page is still kept rendered, as a fraction of the
 * scroller's own height — one viewport above and below, so a page is drawn well before
 * it is scrolled to and a fast flick lands on paper rather than on a blank sheet.
 *
 * This is the whole reason the render window exists, so it is worth stating what it buys:
 * a `<Page>` canvas is sized at `devicePixelRatio`, so at a 900px sheet on a retina screen
 * each page holds an **18 MB** backing store. An 84-page panto script rendered whole is
 * ~1.5 GB of canvas, which is slow to build, slow to scroll, and — the reported symptom —
 * takes seconds to hand back when the operator navigates away and the browser has to
 * reclaim all of it at once. Windowed, the same book holds five or six pages.
 */
const RENDER_MARGIN = '100% 0px'

/** Normalized gap kept between the text's left edge and the marker rail. */
const TEXT_EDGE_GAP = 0.006
/** Cap on the marker lane's x — a centered/indented page must not push the rail
 *  into the middle of the text; it stays within the left margin. */
const MAX_MARKER_LANE_X = 0.15
/** Normalized gap between the text's right edge and the note tail (a touch wider). */
const NOTE_EDGE_GAP = 0.016
/** Px kept between a note's right edge and the sheet's right edge. */
const NOTE_RIGHT_MARGIN = 8
/** Normalized vertical clearance between two bubbles sharing the right gutter lane. */
const GUTTER_MIN_GAP = 0.055

/**
 * One bubble in the right gutter lane: either a script annotation or a cue's own note. They are
 * laid out together so the two kinds can't land on top of each other. `top` is mutated during
 * the stacking pass.
 */
type GutterNote =
  | { key: string; top: number; annotation: AnnotationDto }
  | { key: string; top: number; cueId: number; label: string; notes: string | null }

/**
 * Measure the normalized left/right edge of a rendered page's text block from its
 * text-layer spans. Returns null if the text layer isn't ready / has no text.
 */
function measureTextBounds(pageEl: HTMLElement): { left: number; right: number } | null {
  const box = pageEl.getBoundingClientRect()
  if (box.width === 0) return null
  let minL = 1
  let maxR = 0
  let found = false
  pageEl.querySelectorAll('.textLayer span').forEach((span) => {
    if (!span.textContent?.trim()) return
    const r = span.getBoundingClientRect()
    if (r.width < 1) return
    minL = Math.min(minL, (r.left - box.left) / box.width)
    maxR = Math.max(maxR, (r.right - box.left) / box.width)
    found = true
  })
  return found ? { left: minL, right: maxR } : null
}

/**
 * The PDF page canvas + text layer, memoized so overlay/marker/bounds state changes
 * in the parent don't re-render it — a text-layer re-render would re-fire
 * `onRenderTextLayerSuccess` and, via the bounds setState, loop endlessly. Props are
 * intentionally minimal and stable (callbacks are useCallback'd in the parent).
 */
const PdfPage = memo(function PdfPage({
  index,
  width,
  onHasText,
  onTextLayerRendered,
}: {
  index: number
  width: number
  onHasText: (index: number, hasText: boolean) => void
  onTextLayerRendered: (index: number) => void
}) {
  return (
    <Page
      pageIndex={index}
      width={width}
      renderTextLayer
      renderAnnotationLayer={false}
      onGetTextSuccess={(tc) => onHasText(index, (tc.items?.length ?? 0) >= MIN_TEXT_ITEMS)}
      onRenderTextLayerSuccess={() => onTextLayerRendered(index)}
    />
  )
})

/** pdf.js's document proxy, taken from react-pdf's own pinned copy rather than by importing
 *  `pdfjs-dist` directly — the two can't skew, and this repo has no direct dependency on it. */
type PdfDocumentProxy = Awaited<ReturnType<typeof pdfjs.getDocument>['promise']>

export interface ScriptViewerHandle {
  /** Smooth-scroll so the region's reading position sits ~40% down the viewport. */
  scrollToRegion(region: Region): void
}

interface ScriptViewerProps {
  fileUrl: string
  anchors: CueAnchorDto[]
  annotations: AnnotationDto[]
  statusOf: (cueId: number) => CueRunStatus
  /** Live cue labels from the cue stack, keyed by cueId — the anchor's cached
   *  label can go stale when a cue number is edited, so this wins when present. */
  cueLabels: Map<number, string>
  /** Cue notes keyed by cueId — rendered in the right gutter beside the cue's anchor. */
  cueNotes: Map<number, string>
  /** Set or clear an anchored cue's note from the gutter (unlocked only). */
  onRenoteCue: (cueId: number, notes: string | null) => void
  warningCueIds: Set<number>
  locked: boolean
  tool: PromptBookTool
  /** Cue armed for click-to-place on a scanned page (no text layer to select). */
  placingCueId: number | null
  /**
   * Commit a completed anchor drag (nudge). `prevRegion` is the region before the
   * drag, for the caller's single-step undo snapshot.
   */
  onMoveAnchor: (cueId: number, region: Region, prevRegion: Region) => void
  /** Place the armed cue's anchor at a clicked point (scanned-page fallback). */
  onPlaceAnchor: (region: Region) => void
  /** Open the cue picker to anchor the given selection region to a chosen cue. */
  onAnchorRequest: (region: Region) => void
  /** Create a free annotation over a selected/drawn region. */
  onCreateAnnotation: (kind: AnnotationKind, region: Region) => void
  onAnnotationClick: (annotation: AnnotationDto) => void
  onDocumentError: () => void
  /**
   * The sheets are laid out, so `scrollToRegion` can be honoured from here on. Fires **once per
   * mounted document** — the caller is expected to move the viewport, which must not happen again
   * behind an operator reading ahead.
   */
  onPagesReady: () => void
}

/**
 * The script pane: PDF pages rendered fit-width with a selectable text layer and
 * an absolutely-positioned overlay layer per page. All overlay geometry is
 * normalized [0..1] and rendered as CSS percentages, so zoom/resize is free; only
 * pointer/selection interactions convert through the page element's current box.
 *
 * Annotation creation is selection-driven on text pages (select script text → a
 * floating toolbar), falling back to drag-a-box / click-a-band on scanned pages
 * that have no text layer.
 */
// Memoized: the Prompt Book page re-renders on every fade-progress frame during a GO;
// with all props referentially stable through a fade, memo spares the whole PDF + overlay
// subtree from re-reconciling ~60×/s.
export const ScriptViewer = memo(forwardRef<ScriptViewerHandle, ScriptViewerProps>(function ScriptViewer(
  {
    fileUrl,
    anchors,
    annotations,
    statusOf,
    cueLabels,
    cueNotes,
    onRenoteCue,
    warningCueIds,
    locked,
    tool,
    placingCueId,
    onMoveAnchor,
    onPlaceAnchor,
    onAnchorRequest,
    onCreateAnnotation,
    onAnnotationClick,
    onDocumentError,
    onPagesReady,
  },
  ref,
) {
  const containerRef = useRef<HTMLDivElement>(null)
  const pageElsRef = useRef(new Map<number, HTMLDivElement>())
  // Height/width of every page, read from the PDF up front — one entry per page, so its
  // length is also the page count. Every sheet is therefore laid out at its true height
  // whether or not its canvas is currently mounted, which is what keeps `scrollToRegion`
  // exact and stops the render window shifting the page under the operator.
  const [pageRatios, setPageRatios] = useState<number[]>([])
  // The pages whose canvas + text layer are currently mounted — see RENDER_MARGIN.
  const [rendered, setRendered] = useState<Set<number>>(new Set())
  const renderWindowRef = useRef<IntersectionObserver | null>(null)
  const [containerWidth, setContainerWidth] = useState<number>(0)
  // Which pages have a usable text layer (vs scanned image). Drives the
  // selection-vs-box gesture and is reactive so the cursor updates on load.
  const [hasTextByPage, setHasTextByPage] = useState<Map<number, boolean>>(new Map())
  // Normalized left/right edge of each page's text block, measured from the text
  // layer once it renders. Anchors the margin rail tight to the text (not out in
  // the PDF's own margin) and the note tails tight to the text's right edge.
  const [textBoundsByPage, setTextBoundsByPage] = useState<Map<number, { left: number; right: number }>>(
    new Map(),
  )
  // Pages whose text bounds we've already captured — measured once (bounds are
  // normalized, so width-invariant), which also breaks any re-measure feedback.
  const measuredPagesRef = useRef(new Set<number>())

  useEffect(() => {
    const el = containerRef.current
    if (!el) return
    const observer = new ResizeObserver(() => setContainerWidth(el.clientWidth))
    observer.observe(el)
    return () => observer.disconnect()
  }, [])

  // Stable callbacks for the memoized PdfPage (identity must not change per render).
  const handleHasText = useCallback((index: number, hasText: boolean) => {
    setHasTextByPage((m) => (m.get(index) === hasText ? m : new Map(m).set(index, hasText)))
  }, [])
  const handleTextLayerRendered = useCallback((index: number) => {
    if (measuredPagesRef.current.has(index)) return
    const el = pageElsRef.current.get(index)
    const bounds = el && measureTextBounds(el)
    if (bounds) {
      measuredPagesRef.current.add(index)
      setTextBoundsByPage((m) => new Map(m).set(index, bounds))
    }
  }, [])

  // Measure every page's aspect up front rather than assuming the first page speaks for the
  // rest — a script with a landscape plan or an A3 fold-out would otherwise lay out at the
  // wrong height on the sheets that aren't mounted, and the scroll would jump as they came
  // in. It costs one `getPage` per page against the already-parsed document (~7ms for an
  // 84-page script), which is why it can be paid on load rather than lazily.
  const loadSeqRef = useRef(0)
  // Both async walks below check this after every `await`; see the hook for why writing it on
  // mount as well as clearing it on unmount is load-bearing rather than belt-and-braces.
  // `ScriptViewer.test.tsx` renders the classification walk under `StrictMode` for that reason.
  const mountedRef = useMountedRef()

  /**
   * Classify every page as text-bearing or scanned in the background, once the sheets are up.
   *
   * `<Page>`'s own `onGetTextSuccess` gives the same answer, but only for a page inside the render
   * window — so with the window in place a page's classification settles when you *scroll* to it
   * rather than at load, which reopens a gap `onPagePointerDown` has to guess in. It guesses "has
   * text", so a click on a **scanned** page in that gap waits for a text selection that can never
   * come: the click is swallowed and both scanned-page fallbacks — click to place an armed cue,
   * drag a note/cut box — go with it.
   *
   * The DOM cannot answer it at gesture time either, tempting as that looks: react-pdf builds the
   * text-layer spans *from* the very content this call resolves, so for as long as the answer is
   * unknown there is provably nothing to count. And flipping the default is worse than the bug,
   * because it puts a real anchor on a text page rather than doing nothing.
   *
   * So classification goes back to being settled at load, as it was when every page mounted at
   * once — without the canvases that cost. This is text content only: a few small objects per
   * page, parsed on the pdf.js worker and dropped again, not the ~18 MB backing store the render
   * window exists to avoid. Per page rather than all-or-nothing, unlike the aspect walk above,
   * because it is advisory: a page that will not yield its text is simply left unknown, and
   * `<Page>` is still the backstop when it mounts.
   */
  const classifyPages = useCallback(
    async (pdf: PdfDocumentProxy, seq: number) => {
      for (let n = 1; n <= pdf.numPages; n += 1) {
        try {
          const text = await (await pdf.getPage(n)).getTextContent()
          if (!mountedRef.current || seq !== loadSeqRef.current) return
          handleHasText(n - 1, (text.items?.length ?? 0) >= MIN_TEXT_ITEMS)
        } catch {
          // An unmount rejects every request still in flight, and there is no one left to tell;
          // a single unreadable page just stays unknown. Either way, stop walking a dead document.
          if (!mountedRef.current || seq !== loadSeqRef.current) return
        }
      }
    },
    // `mountedRef` is a ref object, so listing it costs nothing — but it comes from a hook
    // rather than a local `useRef`, which is the only reason the rule can't see that itself.
    [handleHasText, mountedRef],
  )

  const onDocumentLoad = useCallback(
    async (pdf: PdfDocumentProxy) => {
      const seq = ++loadSeqRef.current
      const ratios: number[] = []
      try {
        for (let n = 1; n <= pdf.numPages; n += 1) {
          const viewport = (await pdf.getPage(n)).getViewport({ scale: 1 })
          ratios.push(viewport.height / viewport.width)
        }
      } catch {
        // Two failures arrive here and they are not the same. **Unmounting** destroys the
        // loading task, which rejects every page request still in flight — nothing is wrong
        // and there is no one left to tell. A **genuine** failure (one unreadable page object
        // in an otherwise valid script) has to be reported, because this walk is all-or-nothing:
        // one bad page leaves `pageRatios` empty and the pane blank, where the whole-book
        // render it replaced would still have drawn the other eighty-three.
        if (mountedRef.current && seq === loadSeqRef.current) onDocumentError()
        return
      }
      // A second document may have loaded while we walked the first one's pages.
      if (seq !== loadSeqRef.current) return
      setPageRatios(ratios)
      // Deliberately not awaited: the sheets are usable now, and this only fills in the
      // text-vs-scanned classification sitting behind them.
      void classifyPages(pdf, seq)
    },
    [onDocumentError, classifyPages, mountedRef],
  )

  // The render window. Pages are mounted by proximity to the scroller rather than all at
  // once; the sheet, its overlays and its measured text bounds all stay put either way, so
  // only the canvas and text layer come and go.
  useEffect(() => {
    const root = containerRef.current
    if (!root) return
    const observer = new IntersectionObserver(
      (entries) => {
        setRendered((prev) => {
          let next: Set<number> | null = null
          for (const entry of entries) {
            const index = Number((entry.target as HTMLElement).dataset.pageIndex)
            if (entry.isIntersecting === prev.has(index)) continue
            next ??= new Set(prev)
            if (entry.isIntersecting) next.add(index)
            else next.delete(index)
          }
          // Null means no entry changed the answer — a repeat callback writes no state.
          return next ?? prev
        })
      },
      { root, rootMargin: RENDER_MARGIN },
    )
    renderWindowRef.current = observer
    // Sheets that mounted before this effect ran (the observer is created on mount, and the
    // pages only exist once the document has loaded, but don't rely on that ordering).
    for (const el of pageElsRef.current.values()) observer.observe(el)
    return () => {
      observer.disconnect()
      renderWindowRef.current = null
    }
  }, [])

  /**
   * Track a sheet and put it in the render window — **one identity for the life of the
   * component**, which is the whole point of it.
   *
   * An inline arrow in the render loop gets a fresh identity every render, and React answers a
   * changed ref identity by detaching and re-attaching the ref on the *same* node. That was free
   * while the body was two `Map` writes; with an observe/unobserve pair in it, it is one observer
   * round-trip per sheet per render — ~84 of them at frame rate through an anchor drag, which
   * `setDragOverride` drives from this component's own state, so the `memo` above does not stop
   * it. The index is read off the element rather than closed over, which is what lets a single
   * callback serve every page; the returned cleanup is React 19's form, so React runs it on a
   * genuine detach instead of calling this with `null`.
   */
  const registerPageEl = useCallback((el: HTMLDivElement | null) => {
    // React never passes null once a cleanup is returned; this satisfies `RefCallback`'s type.
    if (!el) return
    const index = Number(el.dataset.pageIndex)
    pageElsRef.current.set(index, el)
    renderWindowRef.current?.observe(el)
    return () => {
      pageElsRef.current.delete(index)
      renderWindowRef.current?.unobserve(el)
    }
  }, [])

  // Cue/cut markers hug the left edge of the highlighted text and overflow into
  // the left paper gutter; notes get the right gutter. When the pane is narrow
  // both gutters collapse and notes fall inline under their line. The page fills
  // what's left, clamped so a huge monitor doesn't render a canvas wall.
  const narrow = containerWidth > 0 && containerWidth < 720
  const leftGutter = narrow ? 0 : 56
  const rightGutter = narrow ? 0 : 200
  const pageWidth = clamp(containerWidth - leftGutter - rightGutter - 48, 280, 1000)

  const scrollToRegion = useCallback((region: Region) => {
    const container = containerRef.current
    if (!container || region.length === 0) return
    const pos = scriptPosition(region)
    const pageEl = pageElsRef.current.get(pos.page)
    if (!pageEl) return
    const target = pageEl.offsetTop + pos.y * pageEl.clientHeight - container.clientHeight * 0.4
    container.scrollTo({ top: Math.max(0, target), behavior: 'smooth' })
  }, [])

  useImperativeHandle(ref, () => ({ scrollToRegion }), [scrollToRegion])

  /**
   * Announce that `scrollToRegion` can now be honoured — the sheets exist, so every page has an
   * element with a real offset, whether or not its canvas is inside the render window.
   *
   * The caller this exists for is the Prompt Book's jump-to-live: opening the book mid-show, the
   * playhead is known well before the PDF is, so the effect that jumps to it fires into an empty
   * pane and nothing re-runs it — `scrollToCue` holds one identity for the session on purpose, so
   * that a book refetch can't yank the viewport out from under an operator reading ahead. Which is
   * also why this is **one shot per mounted document** rather than a state the caller can poll: a
   * jump that repeated on every refetch would be exactly the yank that identity prevents. The
   * viewer is remounted per document (`key={scriptHash:retryNonce}`), so the latch is fresh where
   * it needs to be.
   */
  const pagesReadyRef = useRef(false)
  useEffect(() => {
    if (pagesReadyRef.current || pageRatios.length === 0 || containerWidth === 0) return
    pagesReadyRef.current = true
    onPagesReady()
  }, [pageRatios, containerWidth, onPagesReady])

  // ── Text-selection capture (edit mode, text pages) ──
  // The selection Region is captured into state on gesture end — eagerly, because
  // a book refetch (WS echo) can remount pages and drop the live DOM selection.
  const [selection, setSelection] = useState<{ region: Region; anchor: { x: number; y: number } } | null>(null)

  const clearSelection = useCallback(() => {
    window.getSelection()?.removeAllRanges()
    setSelection(null)
  }, [])

  const captureSelection = useCallback(() => {
    if (locked) return
    const sel = window.getSelection()
    const container = containerRef.current
    if (!sel || sel.isCollapsed || sel.rangeCount === 0 || !container) {
      setSelection(null)
      return
    }
    const range = sel.getRangeAt(0)
    if (!container.contains(range.commonAncestorContainer)) {
      setSelection(null)
      return
    }
    const region = rangeToRegion(range, pageElsRef.current)
    if (region.length === 0) {
      // No text under the selection (e.g. a scanned page) — nothing to anchor to.
      setSelection(null)
      return
    }
    const b = range.getBoundingClientRect()
    setSelection({ region, anchor: { x: b.left + b.width / 2, y: b.top } })
  }, [locked])

  // Hide the toolbar when the selection collapses (click elsewhere / post-commit).
  useEffect(() => {
    const onSel = () => {
      const s = window.getSelection()
      if (!s || s.isCollapsed) setSelection(null)
    }
    document.addEventListener('selectionchange', onSel)
    return () => document.removeEventListener('selectionchange', onSel)
  }, [])

  // Locking drops any pending selection toolbar and clears the DOM selection so no
  // stray blue highlight lingers after an (auto-)relock.
  useEffect(() => {
    if (locked) {
      window.getSelection()?.removeAllRanges()
      setSelection(null)
    }
  }, [locked])

  // ── Anchor drag (move) ──
  // The ref carries the authoritative in-flight geometry (mutations commit from it
  // on pointer-up — never from inside a setState updater, which React may re-invoke);
  // the state mirror only drives rendering, coalesced to one update per frame.
  const dragRef = useRef<{
    cueId: number
    startY: number
    origRegion: Region
    lastRegion: Region
    pageEl: HTMLDivElement
    /** Set once the pointer moves past a threshold — a drag (nudge) vs a click (re-select). */
    moved: boolean
  } | null>(null)
  const [dragOverride, setDragOverride] = useState<{ cueId: number; region: Region } | null>(null)
  const rafRef = useRef<number | null>(null)

  const scheduleOverlayFrame = useCallback((apply: () => void) => {
    if (rafRef.current != null) return
    rafRef.current = requestAnimationFrame(() => {
      rafRef.current = null
      apply()
    })
  }, [])

  useEffect(() => () => {
    if (rafRef.current != null) cancelAnimationFrame(rafRef.current)
  }, [])

  const onAnchorPointerDown = useCallback(
    (e: ReactPointerEvent<HTMLDivElement>, anchor: CueAnchorDto, page: number) => {
      if (locked || tool !== 'move') return
      const pageEl = pageElsRef.current.get(page)
      if (!pageEl) return
      e.stopPropagation()
      e.preventDefault()
      ;(e.currentTarget as HTMLElement).setPointerCapture(e.pointerId)
      dragRef.current = {
        cueId: anchor.cueId,
        startY: e.clientY,
        origRegion: anchor.region,
        lastRegion: anchor.region,
        pageEl,
        moved: false,
      }
      setDragOverride({ cueId: anchor.cueId, region: anchor.region })
    },
    [locked, tool],
  )

  // ── Annotation draw (box) — scanned-page fallback + freetext ──
  const drawRef = useRef<{
    page: number
    start: { x: number; y: number }
    lastRect: Rect
    pageEl: HTMLDivElement
    /** Set once the pointer drags past a threshold — a real box vs a stray click. */
    moved: boolean
  } | null>(null)
  const [draftRect, setDraftRect] = useState<Rect | null>(null)

  const onPagePointerDown = useCallback(
    (e: ReactPointerEvent<HTMLDivElement>, page: number) => {
      if (locked) return
      const pageEl = pageElsRef.current.get(page)
      if (!pageEl) return
      // Unknown (text layer not loaded yet) → assume text, let selection drive.
      const textPage = hasTextByPage.get(page) !== false

      if (placingCueId != null) {
        if (textPage) return // selecting the line will anchor the cue
        // Scanned fallback: click a point → full-width band.
        const point = clientPointToNormalized(e.clientX, e.clientY, pageEl)
        onPlaceAnchor([placedAnchorRect(page, point.y)])
        return
      }

      // Box-draw: freetext is always placed (works on any page); cut/note fall back
      // to a box only when the page has no selectable text.
      const boxDraw = tool === 'freetext' || (tool !== 'move' && !textPage)
      if (!boxDraw) return
      e.preventDefault()
      ;(e.currentTarget as HTMLElement).setPointerCapture(e.pointerId)
      const start = clientPointToNormalized(e.clientX, e.clientY, pageEl)
      const rect = cornersToRect(page, start, start)
      drawRef.current = { page, start, lastRect: rect, pageEl, moved: false }
      setDraftRect(rect)
    },
    [locked, tool, placingCueId, hasTextByPage, onPlaceAnchor],
  )

  const onPointerMove = useCallback((e: ReactPointerEvent<HTMLDivElement>) => {
    const drag = dragRef.current
    if (drag) {
      if (Math.abs(e.clientY - drag.startY) > 4) drag.moved = true
      const dy = (e.clientY - drag.startY) / drag.pageEl.clientHeight
      drag.lastRegion = moveRegionVertically(drag.origRegion, dy)
      // Coalesce to one render per frame — a raw 120Hz pointer stream would
      // otherwise reconcile every <Page> on every event.
      scheduleOverlayFrame(() => {
        const current = dragRef.current
        if (current) setDragOverride({ cueId: current.cueId, region: current.lastRegion })
      })
      return
    }
    const draw = drawRef.current
    if (draw) {
      const point = clientPointToNormalized(e.clientX, e.clientY, draw.pageEl)
      if (Math.abs(point.x - draw.start.x) > 0.008 || Math.abs(point.y - draw.start.y) > 0.008) {
        draw.moved = true
      }
      draw.lastRect = cornersToRect(draw.page, draw.start, point)
      scheduleOverlayFrame(() => {
        const current = drawRef.current
        if (current) setDraftRect(current.lastRect)
      })
    }
  }, [scheduleOverlayFrame])

  const onPointerUp = useCallback(() => {
    const drag = dragRef.current
    if (drag) {
      dragRef.current = null
      setDragOverride(null)
      // Commit a nudge only if the pointer moved AND the region actually changed
      // (a stray click, or a drag returning to its origin, is ignored — re-anchoring
      // is done by selecting new text → "Anchor cue").
      const changed = drag.lastRegion.some((r, i) => r.y !== drag.origRegion[i]?.y)
      if (drag.moved && changed) onMoveAnchor(drag.cueId, drag.lastRegion, drag.origRegion)
      return
    }
    const draw = drawRef.current
    if (draw) {
      const { moved, lastRect } = draw
      drawRef.current = null
      setDraftRect(null)
      // A plain click (no drag) leaves a min-size rect — don't persist it.
      if (moved && tool !== 'move') {
        onCreateAnnotation(
          tool === 'note' ? 'NOTE' : tool === 'strikethrough' ? 'STRIKETHROUGH' : 'FREETEXT',
          [lastRect],
        )
      }
      return
    }
    // No drag/draw in progress → a text selection may have just ended.
    captureSelection()
  }, [tool, onMoveAnchor, onCreateAnnotation, captureSelection])

  // A cancelled pointer (touch interruption, OS gesture, lost capture) aborts the
  // in-flight gesture — it must NOT commit a nudge or annotation like pointer-up.
  const onPointerCancel = useCallback(() => {
    dragRef.current = null
    drawRef.current = null
    setDragOverride(null)
    setDraftRect(null)
  }, [])

  // Merge the in-flight drag geometry over the cache data. The WS-echo refetch
  // triggered by our own edits can never fight a live drag because the override
  // wins until pointer-up commits.
  const effectiveAnchors = useMemo(() => {
    if (!dragOverride) return anchors
    return anchors.map((a) => (a.cueId === dragOverride.cueId ? { ...a, region: dragOverride.region } : a))
  }, [anchors, dragOverride])

  /** Shown for the document's own load and again while its pages are being measured. */
  const spinner = (
    <div className="flex items-center justify-center p-16">
      <Loader2 className="size-6 animate-spin text-muted-foreground" />
    </div>
  )

  const anchorsByPage = useMemo(() => groupByPage(effectiveAnchors, (a) => a.region), [effectiveAnchors])
  const annotationsByPage = useMemo(() => groupByPage(annotations, (n) => n.region), [annotations])
  // Cue run-status keyed by cueId. Memoized on `anchors` (not effectiveAnchors) so
  // the per-page render loop doesn't rebuild a status Map on every drag frame.
  const statusByCue = useMemo(
    () => new Map(anchors.map((a) => [a.cueId, statusOf(a.cueId)])),
    [anchors, statusOf],
  )

  return (
    <div
      ref={containerRef}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerUp}
      onPointerCancel={onPointerCancel}
      onDoubleClick={captureSelection}
      // Only drop the toolbar if the selection is actually gone — a programmatic
      // auto-scroll (live cue advancing) must not wipe a pending annotation.
      onScroll={() => {
        const sel = window.getSelection()
        if (!sel || sel.isCollapsed) setSelection(null)
      }}
      className="relative flex-1 overflow-y-auto min-h-0 bg-muted/30"
    >
      <Document
        file={fileUrl}
        onLoadSuccess={onDocumentLoad}
        onLoadError={onDocumentError}
        loading={spinner}
      >
        <div className="flex flex-col items-center gap-4 px-6 py-6">
          {/* `Document` treats `onLoadSuccess` as fire-and-forget: it swaps this spinner in for
              its own the instant the callback is invoked, without awaiting the promise
              `onDocumentLoad` returns. Without this the measuring pass — however short — is a
              pane with neither a spinner nor a sheet in it. */}
          {pageRatios.length === 0 && spinner}
          {containerWidth > 0 &&
            pageRatios.map((ratio, i) => {
              const cues = anchorsByPage.get(i) ?? []
              const anns = annotationsByPage.get(i) ?? []
              const cuts = anns.filter((a) => a.item.kind === 'STRIKETHROUGH')
              const notes = anns.filter((a) => a.item.kind === 'NOTE')
              const freetexts = anns.filter((a) => a.item.kind === 'FREETEXT')
              const annLocked = locked || tool !== 'move'
              const textPage = hasTextByPage.get(i) !== false
              // Crosshair only where a box-draw gesture is possible; text pages
              // keep the text layer's I-beam to invite selection.
              const boxCursor =
                !locked && (tool === 'freetext' || (!textPage && (placingCueId != null || tool !== 'move')))
              // Anchor the margin rail just left of the text block and the note
              // tails just right of it — measured from the text layer, falling back
              // to a fixed lane / the gutter edge on scanned pages. laneX is clamped
              // so a centered/indented page can't push the rail over the text.
              const bounds = textBoundsByPage.get(i)
              const laneX = bounds ? clamp(bounds.left - TEXT_EDGE_GAP, 0.006, MAX_MARKER_LANE_X) : MARKER_LANE_X
              const noteLeftNorm = bounds ? Math.min(bounds.right + NOTE_EDGE_GAP, 0.98) : 1
              // Hold desktop notes until the page is classified (bounds measured, or
              // known scanned) so they don't flash at the far-right edge then jump in.
              const notesReady = bounds != null || hasTextByPage.get(i) === false
              // Fill from the text's right edge to a small margin before the sheet edge.
              const noteWidthPx = clamp(
                (1 - noteLeftNorm) * pageWidth + rightGutter - NOTE_RIGHT_MARGIN,
                150,
                250,
              )
              // Annotation notes and cue notes share one gutter lane, so they are placed
              // together: sorted by the line they belong to, then pushed down so each clears
              // the one above. GUTTER_MIN_GAP is a floor, not a measurement — a bubble's height
              // depends on its text — but it stops the common case of two notes anchored to the
              // same line rendering exactly on top of each other.
              // Desktop hangs a bubble off the TOP of its region (tail against that line);
              // narrow drops it BELOW the region, inline under the line. Both then go through
              // the same de-collision pass, so the two kinds can't overlap in either layout.
              const anchorY = (rects: Rect[]) => {
                const b = verticalBounds(rects)
                return narrow ? b.top + b.height : b.top
              }
              const gutterNotes: GutterNote[] = [
                ...notes.map(({ item, rects }) => ({
                  key: `ann-${item.id}`,
                  top: anchorY(rects),
                  annotation: item,
                })),
                ...cues
                  .filter(({ item }) => cueNotes.has(item.cueId))
                  .map(({ item, rects }) => ({
                    key: `cue-${item.cueId}`,
                    top: anchorY(rects),
                    cueId: item.cueId,
                    // Falls back to the bubble's generic tag rather than rendering an empty chip.
                    label: cueLabels.get(item.cueId) ?? item.label ?? 'Cue',
                    notes: cueNotes.get(item.cueId) ?? null,
                  })),
              ].sort((a, b) => a.top - b.top)
              // Clamped at the foot of the sheet: the page does not clip its overlay, so a
              // dense page could otherwise push the last bubbles off the bottom and leave them
              // floating in the grey gap between pages, pointing at nothing. Once the lane is
              // full the remaining bubbles pile up on the last line rather than escaping it.
              let lastGutterTop = -Infinity
              for (const note of gutterNotes) {
                note.top = Math.min(
                  Math.max(note.top, lastGutterTop + GUTTER_MIN_GAP),
                  1 - GUTTER_MIN_GAP,
                )
                lastGutterTop = note.top
              }
              return (
                <div
                  key={i}
                  className="flex items-stretch rounded-sm bg-white shadow-lg"
                  style={{ width: pageWidth + leftGutter + rightGutter }}
                >
                  {/* Left paper gutter — the cue chip / cut tag overflow into it. */}
                  {leftGutter > 0 && <div className="shrink-0" style={{ width: leftGutter }} />}

                  {/* Page + on-page overlays (washes, cuts, markers, notes-when-narrow). */}
                  <div
                    ref={registerPageEl}
                    data-page-index={i}
                    onPointerDown={(e) => onPagePointerDown(e, i)}
                    className={cn('relative shrink-0', boxCursor && 'cursor-crosshair')}
                    // The height is the sheet's, not the canvas's: it holds whether or not this
                    // page is inside the render window, so the scroll height is right from the
                    // first frame and every normalized overlay resolves against the same box.
                    // Floored the way react-pdf floors its own canvas height, so a mounted page
                    // sits flush in it.
                    style={{ width: pageWidth, height: Math.floor(pageWidth * ratio) }}
                  >
                    {rendered.has(i) && (
                      <PdfPage
                        index={i}
                        width={pageWidth}
                        onHasText={handleHasText}
                        onTextLayerRendered={handleTextLayerRendered}
                      />
                    )}
                    {/* Overlay sits ABOVE the text layer (z-index 2) but stays
                        click-through, so native text selection still reaches the
                        text layer; only the markers/bubbles capture pointers. */}
                    <div className="pointer-events-none absolute inset-0 z-[3]">
                      {cuts.map(({ item, rects }) => (
                        <CutOverlay
                          key={item.id}
                          rects={rects}
                          locked={annLocked}
                          onClick={() => onAnnotationClick(item)}
                        />
                      ))}
                      {freetexts.map(({ item, rects }) => (
                        <FreetextOverlay
                          key={item.id}
                          annotation={item}
                          rects={rects}
                          locked={annLocked}
                          onClick={() => onAnnotationClick(item)}
                        />
                      ))}
                      {cues.map(({ item: anchor, rects }) => {
                        const status = statusByCue.get(anchor.cueId)!
                        return (
                          <CueWash key={anchor.cueId} rects={rects} status={status} isLive={status === 'live'} />
                        )
                      })}
                      {/* Margin markers — cue/cut labels + accent bands, tight to the text. */}
                      {cuts.map(({ item, rects }) => (
                        <CutMarginMarker
                          key={item.id}
                          rects={rects}
                          locked={annLocked}
                          laneX={laneX}
                          onClick={() => onAnnotationClick(item)}
                        />
                      ))}
                      {cues.map(({ item: anchor, rects }) => (
                        <CueMarginMarker
                          key={anchor.cueId}
                          anchor={anchor}
                          label={cueLabels.get(anchor.cueId) ?? anchor.label}
                          rects={rects}
                          status={statusByCue.get(anchor.cueId)!}
                          hasWarning={warningCueIds.has(anchor.cueId)}
                          locked={locked || tool !== 'move' || placingCueId != null}
                          dragging={dragOverride?.cueId === anchor.cueId}
                          laneX={laneX}
                          onPointerDown={(e) => onAnchorPointerDown(e, anchor, i)}
                        />
                      ))}
                      {/* Notes — desktop: tail anchored to the text's right edge, bubble
                          extends into the paper gutter; narrow: inline under the line.
                          Script annotations and cue notes share the lane, hence one list. */}
                      {!narrow &&
                        notesReady &&
                        gutterNotes.map((note) =>
                          'annotation' in note ? (
                            <NoteCallout
                              key={note.key}
                              annotation={note.annotation}
                              topPct={note.top * 100}
                              locked={annLocked}
                              leftPct={noteLeftNorm * 100}
                              widthPx={noteWidthPx}
                              onClick={() => onAnnotationClick(note.annotation)}
                            />
                          ) : (
                            <CueNoteCallout
                              key={note.key}
                              label={note.label}
                              notes={note.notes}
                              topPct={note.top * 100}
                              // `annLocked`, not `locked`: with a drawing tool armed every
                              // overlay has to go inert so a box-drag that starts over it
                              // reaches the page instead of being swallowed.
                              locked={annLocked}
                              leftPct={noteLeftNorm * 100}
                              widthPx={noteWidthPx}
                              onCommit={(next) => onRenoteCue(note.cueId, next)}
                            />
                          ),
                        )}
                      {narrow &&
                        gutterNotes.map((note) =>
                          'annotation' in note ? (
                            <NoteInline
                              key={note.key}
                              annotation={note.annotation}
                              topPct={note.top * 100}
                              locked={annLocked}
                              onClick={() => onAnnotationClick(note.annotation)}
                            />
                          ) : (
                            <CueNoteInline
                              key={note.key}
                              label={note.label}
                              notes={note.notes}
                              topPct={note.top * 100}
                              locked={annLocked}
                              onCommit={(next) => onRenoteCue(note.cueId, next)}
                            />
                          ),
                        )}
                      {draftRect && draftRect.page === i && (
                        <div
                          style={rectToStyle(draftRect)}
                          className="rounded-sm border border-dashed border-amber-400 bg-amber-400/10"
                        />
                      )}
                    </div>
                  </div>

                  {/* Right paper gutter — the note bubbles overflow into it. */}
                  {rightGutter > 0 && <div className="shrink-0" style={{ width: rightGutter }} />}
                </div>
              )
            })}
        </div>
      </Document>

      {selection && !locked && (
        <FloatingSelectionToolbar
          anchor={selection.anchor}
          onAnchor={() => {
            onAnchorRequest(selection.region)
            clearSelection()
          }}
          onCut={() => {
            onCreateAnnotation('STRIKETHROUGH', selection.region)
            clearSelection()
          }}
          onNote={() => {
            onCreateAnnotation('NOTE', selection.region)
            clearSelection()
          }}
        />
      )}
    </div>
  )
}))

/** Compute the default band rect for a click-placed anchor (scanned fallback). */
function placedAnchorRect(page: number, y: number): Rect {
  const h = PLACED_ANCHOR_RECT.h
  return { page, x: PLACED_ANCHOR_RECT.x, y: clamp(y - h / 2, 0, 1 - h), w: PLACED_ANCHOR_RECT.w, h }
}
