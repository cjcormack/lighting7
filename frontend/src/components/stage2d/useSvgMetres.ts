import { useCallback, useLayoutEffect, useMemo, useRef, useState } from 'react'
import {
  padExtent,
  visibleExtent,
  type Extent,
  type ScreenPoint,
} from '../../lib/stageProjection'

/** Breathing room around the stage envelope when fitting the view. */
const FIT_MARGIN_M = 1
const MIN_SPAN_M = 0.5
const MAX_SPAN_M = 400
const ZOOM_PER_WHEEL_NOTCH = 1.15

export interface SvgMetres {
  /** Attach to the `<svg>`. */
  ref: React.RefObject<SVGSVGElement | null>
  /** Feed straight into the `viewBox` attribute. */
  viewBox: string
  /** The viewBox as an extent. */
  view: Extent
  /**
   * Metres per CSS pixel at the current scale. Multiply any pixel dimension by
   * this to get a metre value that renders at a constant on-screen size — handle
   * radii, stroke padding, font sizes, hit-target sizes, snap tolerances.
   */
  mPerPx: number
  /** The region actually visible, which under `meet` exceeds the viewBox. */
  visible: Extent
  /** Convert a pointer event's client coordinates to screen-metres. */
  toMetres: (clientX: number, clientY: number) => ScreenPoint
  /** Reset to the fitted extent. */
  fit: () => void
  zoomBy: (factor: number, centre?: ScreenPoint) => void
  panBy: (dh: number, dv: number) => void
  /** True once the element has been measured; nothing should draw before then. */
  measured: boolean
}

/**
 * Viewport state for an SVG drawn in metre space.
 *
 * The viewBox is in metres and `preserveAspectRatio="xMidYMid meet"` does the
 * fitting, which is what makes the scale isotropic — the previous DOM-percentage
 * stage map had a fixed pixel height and a fluid width, so a metre along X and a
 * metre along Y were different lengths on screen and the map couldn't be measured
 * against.
 *
 * `toMetres` goes through `getScreenCTM().inverse()` rather than recomputing the
 * fit: the CTM already accounts for `preserveAspectRatio`, page zoom, and any
 * ancestor CSS transform, so hand-rolling the arithmetic a second time would
 * only be a second thing to get wrong.
 */
export function useSvgMetres(fitTo: Extent): SvgMetres {
  const ref = useRef<SVGSVGElement | null>(null)
  const [view, setView] = useState<Extent>(() => padExtent(fitTo, FIT_MARGIN_M))
  const [size, setSize] = useState<{ w: number; h: number } | null>(null)

  // Re-fit when the projection or the stage dimensions change — a fitted view of
  // the plan is meaningless once we're looking at the front elevation.
  const fitKey = `${fitTo.hMin},${fitTo.hMax},${fitTo.vMin},${fitTo.vMax}`
  useLayoutEffect(() => {
    setView(padExtent(fitTo, FIT_MARGIN_M))
    // fitKey is the serialised extent; depending on the object identity would
    // re-fit on every render since callers build it inline.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fitKey])

  // useLayoutEffect, not useEffect: the initial measure has to land before the
  // browser paints. `mPerPx` derives from this size, so an unmeasured first
  // commit would paint one frame of glyphs scaled by the whole viewBox span —
  // a 7px fixture dot drawn with a radius of ~90 metres.
  useLayoutEffect(() => {
    const el = ref.current
    if (!el) return
    const observer = new ResizeObserver((entries) => {
      const box = entries[0]?.contentRect
      if (box) setSize({ w: box.width, h: box.height })
    })
    observer.observe(el)
    const rect = el.getBoundingClientRect()
    setSize({ w: rect.width, h: rect.height })
    return () => observer.disconnect()
  }, [])

  const toMetres = useCallback((clientX: number, clientY: number): ScreenPoint => {
    const el = ref.current
    if (!el) return { h: 0, v: 0 }
    const ctm = el.getScreenCTM()
    if (!ctm) return { h: 0, v: 0 }
    const p = new DOMPoint(clientX, clientY).matrixTransform(ctm.inverse())
    return { h: p.x, v: p.y }
  }, [])

  const fit = useCallback(() => setView(padExtent(fitTo, FIT_MARGIN_M)), [fitTo])

  const zoomBy = useCallback((factor: number, centre?: ScreenPoint) => {
    setView((prev) => {
      const w = prev.hMax - prev.hMin
      const clamped = Math.max(MIN_SPAN_M, Math.min(MAX_SPAN_M, w / factor))
      // One scale for both axes, so the viewBox aspect — and therefore the
      // isotropic metre scale — is preserved.
      const scale = clamped / w
      // Keep `centre` pinned under the cursor, so zooming towards a fixture
      // doesn't walk it off screen.
      const cx = centre?.h ?? (prev.hMin + prev.hMax) / 2
      const cy = centre?.v ?? (prev.vMin + prev.vMax) / 2
      return {
        hMin: cx - (cx - prev.hMin) * scale,
        hMax: cx + (prev.hMax - cx) * scale,
        vMin: cy - (cy - prev.vMin) * scale,
        vMax: cy + (prev.vMax - cy) * scale,
      }
    })
  }, [])

  const panBy = useCallback((dh: number, dv: number) => {
    setView((prev) => ({
      hMin: prev.hMin + dh,
      hMax: prev.hMax + dh,
      vMin: prev.vMin + dv,
      vMax: prev.vMax + dv,
    }))
  }, [])

  // One scalar for every constant-pixel dimension.
  //
  // Computed arithmetically from the viewBox and the measured box rather than by
  // reading getScreenCTM() during render: a DOM read here forces a style/layout
  // flush on every render, and drags write the RTK cache every pointermove, so it
  // would flush layout ~60 times a second. It also read the *pre-commit* CTM, so
  // the value lagged a frame behind every zoom.
  //
  // This reproduces `preserveAspectRatio="xMidYMid meet"`: the uniform scale is
  // min(w/spanH, h/spanV), so metres-per-pixel is the max of the two ratios.
  const spanH = view.hMax - view.hMin
  const spanV = view.vMax - view.vMin
  const mPerPx = size
    ? Math.max(spanH / Math.max(1, size.w), spanV / Math.max(1, size.h))
    : spanH

  // Memoised for its *identity*, not the arithmetic. This is `StageGrid`'s only
  // prop that changes, and a fresh object every render defeated the React.memo
  // around it — so the grid re-ran its own useMemo and rebuilt every <line> on
  // each render of the view, which now includes every frame of a drag.
  const visible = useMemo(
    () => (size ? visibleExtent(view, size.w, size.h) : view),
    [view, size],
  )

  return {
    ref,
    viewBox: `${view.hMin} ${view.vMin} ${view.hMax - view.hMin} ${view.vMax - view.vMin}`,
    view,
    mPerPx,
    visible,
    toMetres,
    fit,
    zoomBy,
    panBy,
    measured: size != null,
  }
}

export { ZOOM_PER_WHEEL_NOTCH }
