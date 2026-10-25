import type { GraphCtx } from '@/components/fxEditor/fxGraphDraw'

/**
 * A 2D context that paints a pixel grid, for testing what a canvas drawing puts where under jsdom,
 * which has no canvas. Each pixel keeps the style string last painted on it — no blending, no
 * anti-aliasing — so a test reads `pixel(x, y)` and compares it with the colour it expects.
 * Fills (rectangles and paths, even-odd), strokes (with dashes and width), rectangular clips and
 * text (recorded, not drawn) are what it does.
 */
export class PixelCanvas implements GraphCtx {
  fillStyle: string | CanvasGradient | CanvasPattern = '#000'
  strokeStyle: string | CanvasGradient | CanvasPattern = '#000'
  lineWidth = 1
  font = ''
  textBaseline: CanvasTextBaseline = 'alphabetic'
  textAlign: CanvasTextAlign = 'start'
  readonly texts: { text: string; x: number; y: number; fill: string }[] = []

  private readonly pixels: (string | null)[]
  private path: [number, number][][] = []
  private dash: number[] = []
  private clipRect: [number, number, number, number] | null = null
  private readonly stack: { fill: unknown; stroke: unknown; width: number; dash: number[]; clip: PixelCanvas['clipRect'] }[] = []

  constructor(
    readonly width: number,
    readonly height: number,
  ) {
    this.pixels = new Array(width * height).fill(null)
  }

  /** The style last painted at (x, y), or null where nothing was. */
  pixel(x: number, y: number): string | null {
    return this.pixels[Math.floor(y) * this.width + Math.floor(x)] ?? null
  }

  private set(x: number, y: number, style: unknown) {
    const px = Math.floor(x)
    const py = Math.floor(y)
    if (px < 0 || py < 0 || px >= this.width || py >= this.height) return
    const c = this.clipRect
    if (c != null && (px + 0.5 < c[0] || px + 0.5 > c[0] + c[2] || py + 0.5 < c[1] || py + 0.5 > c[1] + c[3])) return
    this.pixels[py * this.width + px] = String(style)
  }

  fillRect(x: number, y: number, w: number, h: number): void {
    for (let py = Math.floor(y); py < Math.ceil(y + h); py++) {
      for (let px = Math.floor(x); px < Math.ceil(x + w); px++) {
        if (px + 0.5 >= x && px + 0.5 <= x + w && py + 0.5 >= y && py + 0.5 <= y + h) this.set(px, py, this.fillStyle)
      }
    }
  }

  clearRect(x: number, y: number, w: number, h: number): void {
    for (let py = Math.max(0, Math.floor(y)); py < Math.min(this.height, Math.ceil(y + h)); py++) {
      for (let px = Math.max(0, Math.floor(x)); px < Math.min(this.width, Math.ceil(x + w)); px++) this.pixels[py * this.width + px] = null
    }
  }

  beginPath(): void {
    this.path = []
  }

  moveTo(x: number, y: number): void {
    this.path.push([[x, y]])
  }

  lineTo(x: number, y: number): void {
    if (this.path.length === 0) this.path.push([])
    this.path[this.path.length - 1].push([x, y])
  }

  closePath(): void {
    const sub = this.path[this.path.length - 1]
    if (sub != null && sub.length > 0) sub.push(sub[0])
  }

  rect(x: number, y: number, w: number, h: number): void {
    this.path.push([
      [x, y],
      [x + w, y],
      [x + w, y + h],
      [x, y + h],
      [x, y],
    ])
  }

  arc(x: number, y: number, r: number): void {
    const sub: [number, number][] = []
    for (let i = 0; i <= 16; i++) sub.push([x + r * Math.cos((i / 16) * Math.PI * 2), y + r * Math.sin((i / 16) * Math.PI * 2)])
    this.path.push(sub)
  }

  fill(): void {
    const points = this.path.flat()
    if (points.length === 0) return
    const xs = points.map((p) => p[0])
    const ys = points.map((p) => p[1])
    for (let py = Math.floor(Math.min(...ys)); py <= Math.ceil(Math.max(...ys)); py++) {
      for (let px = Math.floor(Math.min(...xs)); px <= Math.ceil(Math.max(...xs)); px++) {
        if (this.inside(px + 0.5, py + 0.5)) this.set(px, py, this.fillStyle)
      }
    }
  }

  private inside(x: number, y: number): boolean {
    let inside = false
    for (const sub of this.path) {
      for (let i = 0, j = sub.length - 1; i < sub.length; j = i++) {
        const [xi, yi] = sub[i]
        const [xj, yj] = sub[j]
        if (yi > y !== yj > y && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi) inside = !inside
      }
    }
    return inside
  }

  stroke(): void {
    const period = this.dash.reduce((a, b) => a + b, 0)
    const half = Math.floor(this.lineWidth / 2)
    for (const sub of this.path) {
      let travelled = 0
      for (let i = 1; i < sub.length; i++) {
        const [x0, y0] = sub[i - 1]
        const [x1, y1] = sub[i]
        const length = Math.hypot(x1 - x0, y1 - y0)
        const steps = Math.max(1, Math.ceil(length * 4))
        for (let k = 0; k <= steps; k++) {
          const t = k / steps
          const along = travelled + t * length
          if (period > 0 && along % period >= this.dash[0]) continue
          const x = x0 + (x1 - x0) * t
          const y = y0 + (y1 - y0) * t
          for (let dy = -half; dy <= half; dy++) for (let dx = -half; dx <= half; dx++) this.set(x + dx, y + dy, this.strokeStyle)
        }
        travelled += length
      }
    }
  }

  clip(): void {
    const points = this.path.flat()
    if (points.length === 0) return
    const xs = points.map((p) => p[0])
    const ys = points.map((p) => p[1])
    this.clipRect = [Math.min(...xs), Math.min(...ys), Math.max(...xs) - Math.min(...xs), Math.max(...ys) - Math.min(...ys)]
  }

  save(): void {
    this.stack.push({ fill: this.fillStyle, stroke: this.strokeStyle, width: this.lineWidth, dash: this.dash, clip: this.clipRect })
  }

  restore(): void {
    const top = this.stack.pop()
    if (top == null) return
    this.fillStyle = top.fill as string
    this.strokeStyle = top.stroke as string
    this.lineWidth = top.width
    this.dash = top.dash
    this.clipRect = top.clip
  }

  setLineDash(segments: number[]): void {
    this.dash = [...segments]
  }

  fillText(text: string, x: number, y: number): void {
    this.texts.push({ text, x, y, fill: String(this.fillStyle) })
  }
}
