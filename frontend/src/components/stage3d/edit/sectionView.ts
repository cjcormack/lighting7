import type { Extent, ScreenPoint } from '../../../lib/stageProjection'

/**
 * Where an orthographic section's camera is looking, in the section's own screen metres
 * (`lib/stageProjection.ts`'s `{h, v}`, v screen-down): the point at the canvas's centre, the zoom
 * in pixels per metre, and the canvas's size in pixels. The section camera's screen axes are the
 * projection's (`stageCameras.ts`'s `orthoSection`), so this is all it takes to turn a pointer into
 * a point on the section and back — which is what lets the edit layer over the canvas hit-test,
 * snap and draw its handles in metres, exactly as the SVG plot it replaced did.
 */
export interface SectionView {
  h: number
  v: number
  /** Pixels per metre: the orthographic camera's zoom, its frustum being the canvas in pixels. */
  zoom: number
  width: number
  height: number
}

/** The section's pan and zoom, which the edit layer drives while it holds the pointer. */
export interface SectionControls {
  /** Slide the section by a pointer's travel in pixels — the content follows the pointer. */
  panBy(dxPx: number, dyPx: number): void
  /** Zoom by [factor] about a point on the canvas, in pixels from its top left. */
  zoomAt(factor: number, xPx: number, yPx: number): void
  /** Fit the rig again, and keep refitting until the operator moves the section. */
  fit(): void
}

/** A tiny store the section camera writes and the DOM layer over the canvas reads. */
export interface SectionViewStore {
  get(): SectionView | null
  set(view: SectionView): void
  /** Forget the view: the section it described is no longer being edited, or no longer shown. */
  clear(): void
  subscribe(listener: () => void): () => void
}

export function createSectionViewStore(): SectionViewStore {
  let current: SectionView | null = null
  const listeners = new Set<() => void>()
  return {
    get: () => current,
    set(view) {
      if (
        current != null &&
        current.h === view.h &&
        current.v === view.v &&
        current.zoom === view.zoom &&
        current.width === view.width &&
        current.height === view.height
      ) {
        return
      }
      current = view
      for (const listener of [...listeners]) listener()
    },
    clear() {
      if (current == null) return
      current = null
      for (const listener of [...listeners]) listener()
    },
    subscribe(listener) {
      listeners.add(listener)
      return () => {
        listeners.delete(listener)
      }
    },
  }
}

/** Metres per CSS pixel — what a constant-pixel handle, tolerance or hit radius is multiplied by. */
export function metresPerPixel(view: SectionView): number {
  return view.zoom > 0 ? 1 / view.zoom : 1
}

/** The part of the section on screen, in screen metres. */
export function visibleSection(view: SectionView): Extent {
  const mpp = metresPerPixel(view)
  const halfW = (view.width / 2) * mpp
  const halfH = (view.height / 2) * mpp
  return { hMin: view.h - halfW, hMax: view.h + halfW, vMin: view.v - halfH, vMax: view.v + halfH }
}

/** A point on the canvas, in pixels from its top left, as a point on the section. */
export function pixelToSection(view: SectionView, xPx: number, yPx: number): ScreenPoint {
  return offsetToSection(view, xPx - view.width / 2, yPx - view.height / 2)
}

/**
 * A point on the canvas, in pixels from its **centre**, as a point on the section. What a pointer
 * is resolved with: the section camera keeps its centre and its zoom when the canvas resizes, so an
 * offset from the canvas's centre as it is now is right at once — while the canvas's size as the
 * camera last reported it can be a frame or two behind (a side panel folding away widens the
 * canvas, and a click in that moment must still land where it was made).
 */
export function offsetToSection(view: SectionView, dxPx: number, dyPx: number): ScreenPoint {
  const mpp = metresPerPixel(view)
  return { h: view.h + dxPx * mpp, v: view.v + dyPx * mpp }
}

/** The inverse of [pixelToSection]. */
export function sectionToPixel(view: SectionView, p: ScreenPoint): { x: number; y: number } {
  return { x: view.width / 2 + (p.h - view.h) * view.zoom, y: view.height / 2 + (p.v - view.v) * view.zoom }
}
