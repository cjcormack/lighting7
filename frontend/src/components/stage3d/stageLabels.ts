import { Vector3, type Camera, type Object3D } from 'three'

/**
 * The Stage view's labels: one DOM layer over the canvas, positioned once per frame and
 * decluttered in screen space.
 *
 * It replaced a drei `<Html>` per label — a React root and a `backdrop-blur` each, sixty-odd
 * over a WebGL canvas, and one of the memory findings in the stage-view design record. Nothing
 * here is React: the scene registers an anchor (`useStageLabel`), this store owns one `<div>`
 * per label, and `layout` projects, decides and writes transforms. See
 * `docs/stage-vis-engineering.md` §"The label layer".
 */

/**
 * What the layer shows. *Positions* — the rigging and the regions — always, and a fixture only
 * while it is hovered or selected; *All* every fixture that fits as well; *None* nothing at all.
 * Under *All*, positions still outrank fixtures, and a hovered or selected fixture outranks both.
 */
export type StageLabelMode = 'positions' | 'all' | 'none'

export const STAGE_LABEL_MODES: ReadonlyArray<StageLabelMode> = ['positions', 'all', 'none']

export const STAGE_LABEL_MODE_LABELS: Record<StageLabelMode, string> = {
  positions: 'Positions',
  all: 'All fixtures',
  none: 'None',
}

export function isStageLabelMode(v: unknown): v is StageLabelMode {
  return v === 'positions' || v === 'all' || v === 'none'
}

/**
 * A stored preference narrowed to a mode. The flag was a boolean until the label layer, and a
 * desk's `localStorage` still holds one: `true` was "labels on", which is *Positions* now, and
 * `false` was "off".
 */
export function toStageLabelMode(v: unknown): StageLabelMode {
  if (isStageLabelMode(v)) return v
  if (v === false) return 'none'
  return 'positions'
}

/** `position` is a rigging or a region; `fixture` a lantern. */
export type StageLabelKind = 'position' | 'fixture'

/** Rank in the declutter: the first placed wins the space. */
const PRIORITY_EMPHASISED = 3
const PRIORITY_POSITION = 2
const PRIORITY_FIXTURE = 1

/** Horizontal and vertical breathing room between two labels, in px. */
const GAP_X = 2
const GAP_Y = 1

export interface ScreenRect {
  x: number
  y: number
  w: number
  h: number
}

/** Whether `r` collides with anything already placed, gaps included. */
export function overlapsAny(r: ScreenRect, placed: ReadonlyArray<ScreenRect>, count = placed.length): boolean {
  for (let i = 0; i < count; i++) {
    const q = placed[i]
    if (r.x < q.x + q.w + GAP_X && r.x + r.w + GAP_X > q.x && r.y < q.y + q.h + GAP_Y && r.y + r.h + GAP_Y > q.y) {
      return true
    }
  }
  return false
}

/** Whether a label of this kind is a candidate at all under `mode`. */
export function wantsLabel(mode: StageLabelMode, kind: StageLabelKind, emphasised: boolean): boolean {
  if (mode === 'none') return false
  if (kind === 'position') return true
  return mode === 'all' || emphasised
}

export function labelPriority(kind: StageLabelKind, emphasised: boolean): number {
  if (emphasised) return PRIORITY_EMPHASISED
  return kind === 'position' ? PRIORITY_POSITION : PRIORITY_FIXTURE
}

const BASE_CLASS =
  'pointer-events-none absolute left-0 top-0 whitespace-nowrap rounded border px-1.5 py-px shadow-sm will-change-transform'
const KIND_CLASS: Record<StageLabelKind, string> = {
  position:
    'border-amber-500/40 bg-background/85 font-mono text-[10px] font-semibold tracking-wider text-amber-600 dark:text-amber-400',
  fixture: 'border-border/60 bg-background/85 text-[11px] font-medium text-foreground',
}
const EMPHASISED_CLASS = 'border-primary bg-primary/15'

export interface StageLabelEntry {
  readonly el: HTMLDivElement
  anchor: Object3D | null
  kind: StageLabelKind
  text: string
  emphasised: boolean
  /** Measured box; 0 until the label has been laid out while visible in a sized container. */
  w: number
  h: number
  /** Last transform written, so an unmoved label costs no DOM write. */
  lastX: number
  lastY: number
  shown: boolean
}

const SCRATCH = new Vector3()

/**
 * One per Stage3D. `invalidate` is the canvas's, set by the driver inside the canvas, so a label
 * change on a `demand` frameloop draws a frame; until then it is a no-op.
 */
export class StageLabelStore {
  private container: HTMLElement | null = null
  private readonly entries: StageLabelEntry[] = []
  // Reused per frame: the layout runs on every rendered frame and allocates nothing.
  private readonly order: StageLabelEntry[] = []
  private readonly placed: ScreenRect[] = []
  mode: StageLabelMode = 'positions'
  invalidate: () => void = () => {}

  setContainer(el: HTMLElement | null): void {
    if (el === this.container) return
    this.container = el
    for (const e of this.entries) {
      e.el.remove()
      e.w = 0
      if (el) el.appendChild(e.el)
    }
    this.invalidate()
  }

  setMode(mode: StageLabelMode): void {
    if (mode === this.mode) return
    this.mode = mode
    this.invalidate()
  }

  add(kind: StageLabelKind, text: string, emphasised: boolean): StageLabelEntry {
    const el = document.createElement('div')
    const entry: StageLabelEntry = {
      el,
      anchor: null,
      kind,
      text,
      emphasised,
      w: 0,
      h: 0,
      lastX: NaN,
      lastY: NaN,
      shown: false,
    }
    el.textContent = text
    el.style.display = 'none'
    applyClass(entry)
    this.entries.push(entry)
    this.container?.appendChild(el)
    this.invalidate()
    return entry
  }

  update(entry: StageLabelEntry, kind: StageLabelKind, text: string, emphasised: boolean): void {
    let changed = false
    if (entry.text !== text) {
      entry.text = text
      entry.el.textContent = text
      entry.w = 0
      changed = true
    }
    if (entry.kind !== kind || entry.emphasised !== emphasised) {
      entry.kind = kind
      entry.emphasised = emphasised
      applyClass(entry)
      // The emphasised style has its own border weight; re-measure rather than trust the old box.
      entry.w = 0
      changed = true
    }
    if (changed) this.invalidate()
  }

  remove(entry: StageLabelEntry): void {
    const i = this.entries.indexOf(entry)
    if (i >= 0) this.entries.splice(i, 1)
    entry.el.remove()
    this.invalidate()
  }

  /** Every entry, for tests. */
  get size(): number {
    return this.entries.length
  }

  /**
   * Project every candidate, place them greedily by priority, and show only those that fit.
   *
   * `camera`'s world matrix must be current, and each anchor's is brought up to date here —
   * the layout runs before the renderer's own matrix walk, and on a `demand` frameloop a label
   * one frame stale stays stale until something else asks for a frame.
   */
  layout(camera: Camera, width: number, height: number): void {
    const order = this.order
    order.length = 0
    for (const e of this.entries) {
      if (e.anchor && wantsLabel(this.mode, e.kind, e.emphasised)) order.push(e)
    }
    // Stable, so equal ranks keep registration order and the winner of a tie doesn't flicker.
    order.sort((a, b) => labelPriority(b.kind, b.emphasised) - labelPriority(a.kind, a.emphasised))

    let placedCount = 0
    const placed = this.placed
    for (const e of order) {
      const anchor = e.anchor!
      anchor.updateWorldMatrix(true, false)
      SCRATCH.setFromMatrixPosition(anchor.matrixWorld).project(camera)
      let fits = SCRATCH.z >= -1 && SCRATCH.z <= 1
      if (fits) {
        if (e.w === 0) measure(e)
        const w = e.w || estimatedWidth(e)
        const h = e.h || ESTIMATED_HEIGHT
        const x = (SCRATCH.x * 0.5 + 0.5) * width - w / 2
        const y = (-SCRATCH.y * 0.5 + 0.5) * height - h / 2
        fits = x > -w && y > -h && x < width && y < height
        if (fits) {
          const rect = placed[placedCount] ?? (placed[placedCount] = { x: 0, y: 0, w: 0, h: 0 })
          rect.x = x
          rect.y = y
          rect.w = w
          rect.h = h
          fits = !overlapsAny(rect, placed, placedCount)
          if (fits) {
            placedCount++
            const rx = Math.round(x)
            const ry = Math.round(y)
            if (rx !== e.lastX || ry !== e.lastY) {
              e.el.style.transform = `translate(${rx}px, ${ry}px)`
              e.lastX = rx
              e.lastY = ry
            }
          }
        }
      }
      setShown(e, fits)
    }
    // Candidates were decided above; everything else is hidden. `order` is small (the visible
    // rig), so a membership scan per entry is cheaper than a Set allocated every frame.
    for (const e of this.entries) {
      if (!order.includes(e)) setShown(e, false)
    }
  }

  /** Hide every label — the canvas has lost its context, so there is nothing to label. */
  hideAll(): void {
    for (const e of this.entries) setShown(e, false)
  }
}

function applyClass(e: StageLabelEntry): void {
  e.el.className = `${BASE_CLASS} ${KIND_CLASS[e.kind]}${e.emphasised ? ` ${EMPHASISED_CLASS}` : ''}`
}

function setShown(e: StageLabelEntry, shown: boolean): void {
  if (e.shown === shown) return
  e.shown = shown
  e.el.style.display = shown ? '' : 'none'
}

/**
 * Size a label from the DOM. It has to be displayed to have a box, so this shows it; a label that
 * then fails to place is hidden again in the same layout pass, before the browser paints. A zero
 * box (the container is hidden or unsized) leaves it unmeasured, so the next layout in a sized
 * container measures it properly, and the layout uses an estimate meanwhile.
 */
function measure(e: StageLabelEntry): void {
  setShown(e, true)
  const w = e.el.offsetWidth
  const h = e.el.offsetHeight
  if (w > 0 && h > 0) {
    e.w = w
    e.h = h
  }
}

/** A stand-in box for a label that could not be measured: ~6px a character at 11px. */
function estimatedWidth(e: StageLabelEntry): number {
  return e.text.length * 6 + 12
}
const ESTIMATED_HEIGHT = 16
