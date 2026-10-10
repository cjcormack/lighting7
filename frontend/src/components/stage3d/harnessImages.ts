import { useEffect, useSyncExternalStore } from 'react'

/**
 * **The cloth scenes' paint** (scrim plan session 6): the images the profile harness's `=cutcloth`
 * and `=daynight` scenes are dressed with, drawn here pixel by pixel and stored through the desk's
 * own scene-image route, so the cloths they paint go through the real pipeline end to end — the
 * store, its derived 2048 px copy and 256 px mask, the texture and mask caches, the surface shader,
 * the occlusion atlas and the haze — rather than through a stand-in only the harness has.
 *
 * - `foliage` — a border's leaves on a transparent ground: solid where the batten hangs, thinning
 *   towards the bottom edge into the holes the back light's shafts come through.
 * - `dayFront` — a day/night cloth's front: a sky, hills, a house and a sun, painted opaque (the
 *   muslin is opaque to the eye; its front is the dye light from behind comes through).
 * - `nightBack` — its back: black but for the openings — the house's windows, a moon where the sun
 *   is and a field of stars — **drawn as seen from behind**, mirrored, because the shader reads the
 *   back image at `1 − u` (`surfaceShader.ts`'s `paintBack`). Lit from behind, the cloth shows only
 *   those openings, each coloured by the front's dye there (D6).
 *
 * The pixels are pure and deterministic (a seeded generator, no `Math.random`), so a test pins them
 * and the hash a desk answers is the same on every visit from one browser. The upload happens once
 * per project per page load, from the window that first draws the scene; a `render_view` capture in
 * that window reads the hashes it already has. Uploading is idempotent by hash, and an image no
 * element names is pruned by the desk after 7 days, so a harness visit leaves nothing behind for long.
 */

export type HarnessImageKind = 'foliage' | 'dayFront' | 'nightBack'

/** Each image's size in pixels: the aspect of the cloth it is stretched over (`profileHarness.ts`). */
export const HARNESS_IMAGE_SIZE: Readonly<Record<HarnessImageKind, { width: number; height: number }>> = {
  // A 9.6 × 3 m border.
  foliage: { width: 1024, height: 320 },
  // An 8 × 4.5 m cloth.
  dayFront: { width: 1024, height: 576 },
  nightBack: { width: 1024, height: 576 },
}

/** The images a scene is painted with, by the hash the desk answered; a kind still uploading is absent. */
export type HarnessImages = Partial<Record<HarnessImageKind, string>>

/** A seeded generator (mulberry32): the same leaves and stars every time. */
function seeded(seed: number): () => number {
  let a = seed >>> 0
  return () => {
    a = (a + 0x6d2b79f5) >>> 0
    let t = a
    t = Math.imul(t ^ (t >>> 15), t | 1)
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61)
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

type Rgb = readonly [number, number, number]

function hex(colour: string): Rgb {
  const n = Number.parseInt(colour.slice(1), 16)
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255]
}

/** An RGBA image, row 0 at the top — as the shader reads a paint image (`1 − v`). */
export interface HarnessPixels {
  width: number
  height: number
  data: Uint8ClampedArray
}

function canvas(width: number, height: number): HarnessPixels {
  return { width, height, data: new Uint8ClampedArray(width * height * 4) }
}

function put(img: HarnessPixels, x: number, y: number, [r, g, b]: Rgb, a = 255): void {
  if (x < 0 || y < 0 || x >= img.width || y >= img.height) return
  const i = (y * img.width + x) * 4
  img.data[i] = r
  img.data[i + 1] = g
  img.data[i + 2] = b
  img.data[i + 3] = a
}

function rect(img: HarnessPixels, x0: number, y0: number, x1: number, y1: number, colour: Rgb): void {
  for (let y = Math.max(0, Math.floor(y0)); y < Math.min(img.height, Math.ceil(y1)); y++) {
    for (let x = Math.max(0, Math.floor(x0)); x < Math.min(img.width, Math.ceil(x1)); x++) put(img, x, y, colour)
  }
}

/** An ellipse of half-axes [rx], [ry] about ([cx], [cy]), turned by [turn] radians. */
function ellipse(img: HarnessPixels, cx: number, cy: number, rx: number, ry: number, turn: number, colour: Rgb): void {
  const c = Math.cos(turn)
  const s = Math.sin(turn)
  const r = Math.max(rx, ry)
  for (let y = Math.floor(cy - r); y <= Math.ceil(cy + r); y++) {
    for (let x = Math.floor(cx - r); x <= Math.ceil(cx + r); x++) {
      const dx = x - cx
      const dy = y - cy
      const u = (c * dx + s * dy) / rx
      const v = (-s * dx + c * dy) / ry
      if (u * u + v * v <= 1) put(img, x, y, colour)
    }
  }
}

function lerp(a: Rgb, b: Rgb, t: number): Rgb {
  return [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t]
}

/** The border's batten band, as a share of the image's height from the top: solid leaves. */
export const FOLIAGE_SOLID_SHARE = 0.18

function foliage(): HarnessPixels {
  const { width, height } = HARNESS_IMAGE_SIZE.foliage
  const img = canvas(width, height)
  const solid = Math.round(height * FOLIAGE_SOLID_SHARE)
  rect(img, 0, 0, width, solid, hex('#1f3a1e'))
  const greens = ['#2f5a2a', '#3d6e31', '#4f7f3a', '#25472a', '#5e8c3f'].map(hex)
  const rand = seeded(6)
  for (let i = 0; i < 800; i++) {
    // Thick under the batten, thinning to a ragged edge: the further down, the fewer leaves.
    const y = solid * 0.6 + (height - solid * 0.6) * Math.pow(rand(), 2.8)
    const x = rand() * width
    const rx = 9 + rand() * 18
    const ry = 4 + rand() * 8
    ellipse(img, x, y, rx, ry, rand() * Math.PI, greens[Math.floor(rand() * greens.length)])
  }
  return img
}

/** Where the day/night cloth's house windows are, in front-image pixels: the night's openings too. */
export const DAYNIGHT_WINDOWS: ReadonlyArray<readonly [number, number, number, number]> = [
  [640, 330, 672, 368],
  [704, 330, 736, 368],
  [672, 390, 700, 430],
]

/** The day's sun, and the night's moon in its place: centre and radius, front-image pixels. */
export const DAYNIGHT_SUN = { x: 210, y: 112, r: 44 } as const

function dayFront(): HarnessPixels {
  const { width, height } = HARNESS_IMAGE_SIZE.dayFront
  const img = canvas(width, height)
  const top = hex('#8fc0ec')
  const horizon = hex('#e3f1fc')
  const hillFar = hex('#7aa556')
  const hillNear = hex('#5b8a3c')
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const far = 360 + 28 * Math.sin(x / 140) + 14 * Math.sin(x / 47 + 1)
      const near = 430 + 34 * Math.sin(x / 190 + 2) + 10 * Math.sin(x / 61)
      put(img, x, y, y > near ? hillNear : y > far ? hillFar : lerp(top, horizon, y / far))
    }
  }
  for (let y = DAYNIGHT_SUN.y - DAYNIGHT_SUN.r; y <= DAYNIGHT_SUN.y + DAYNIGHT_SUN.r; y++) {
    for (let x = DAYNIGHT_SUN.x - DAYNIGHT_SUN.r; x <= DAYNIGHT_SUN.x + DAYNIGHT_SUN.r; x++) {
      if (Math.hypot(x - DAYNIGHT_SUN.x, y - DAYNIGHT_SUN.y) <= DAYNIGHT_SUN.r) put(img, x, y, hex('#ffe27a'))
    }
  }
  // The house: cream walls, a red roof, pale panes and a door.
  rect(img, 610, 310, 770, 440, hex('#e9dcb8'))
  for (let y = 240; y < 310; y++) {
    const half = ((y - 240) / 70) * 96
    rect(img, 690 - half, y, 690 + half, y + 1, hex('#9c3b2c'))
  }
  for (const [x0, y0, x1, y1] of DAYNIGHT_WINDOWS) rect(img, x0, y0, x1, y1, hex('#f4e9ad'))
  return img
}

function nightBack(): HarnessPixels {
  const { width, height } = HARNESS_IMAGE_SIZE.nightBack
  const img = canvas(width, height)
  rect(img, 0, 0, width, height, [0, 0, 0])
  const white: Rgb = [255, 255, 255]
  // Seen from behind: every opening at the mirror of where it is on the front.
  const mirror = (x: number) => width - 1 - x
  for (const [x0, y0, x1, y1] of DAYNIGHT_WINDOWS) rect(img, mirror(x1 - 1), y0, mirror(x0) + 1, y1, white)
  for (let y = DAYNIGHT_SUN.y - DAYNIGHT_SUN.r; y <= DAYNIGHT_SUN.y + DAYNIGHT_SUN.r; y++) {
    for (let x = DAYNIGHT_SUN.x - DAYNIGHT_SUN.r; x <= DAYNIGHT_SUN.x + DAYNIGHT_SUN.r; x++) {
      // A crescent: the disc less a disc offset towards the house's side.
      const inDisc = Math.hypot(x - DAYNIGHT_SUN.x, y - DAYNIGHT_SUN.y) <= DAYNIGHT_SUN.r
      const inBite = Math.hypot(x - DAYNIGHT_SUN.x - 20, y - DAYNIGHT_SUN.y + 8) <= DAYNIGHT_SUN.r * 0.85
      if (inDisc && !inBite) put(img, mirror(x), y, white)
    }
  }
  const rand = seeded(23)
  for (let i = 0; i < 140; i++) {
    const x = Math.floor(rand() * width)
    const y = Math.floor(rand() * 300)
    // None behind the moon or the house: a star there would come through the roof's dye, red.
    if (Math.hypot(x - DAYNIGHT_SUN.x, y - DAYNIGHT_SUN.y) < DAYNIGHT_SUN.r + 12) continue
    if (x > 580 && x < 800 && y > 225) continue
    rect(img, mirror(x) - 1, y - 1, mirror(x) + 2, y + 2, white)
  }
  return img
}

/** One image's pixels. */
export function harnessImagePixels(kind: HarnessImageKind): HarnessPixels {
  switch (kind) {
    case 'foliage':
      return foliage()
    case 'dayFront':
      return dayFront()
    case 'nightBack':
      return nightBack()
  }
}

/** The images a harness scene is painted with; none for a scene with no paint. */
export function harnessImageKinds(mode: string | null): HarnessImageKind[] {
  if (mode === 'cutcloth') return ['foliage']
  if (mode === 'daynight') return ['dayFront', 'nightBack']
  return []
}

// — the upload ————————————————————————————————————————————————————————————————————

async function encodePng(pixels: HarnessPixels): Promise<Blob> {
  const el = document.createElement('canvas')
  el.width = pixels.width
  el.height = pixels.height
  const ctx = el.getContext('2d')
  if (ctx == null) throw new Error('no 2d context')
  ctx.putImageData(new ImageData(new Uint8ClampedArray(pixels.data), pixels.width, pixels.height), 0, 0)
  return new Promise((resolve, reject) => el.toBlob((b) => (b != null ? resolve(b) : reject(new Error('toBlob failed'))), 'image/png'))
}

async function uploadImage(projectId: number, kind: HarnessImageKind): Promise<string> {
  const body = await encodePng(harnessImagePixels(kind))
  const res = await fetch(`/api/rest/projects/${projectId}/scene-images`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'image/png' },
    body,
  })
  if (!res.ok) throw new Error(`upload answered ${res.status}`)
  const info = (await res.json()) as { hash?: unknown }
  if (typeof info.hash !== 'string') throw new Error('upload answered no hash')
  return info.hash
}

const EMPTY: HarnessImages = {}
const uploaded = new Map<string, HarnessImages>()
const started = new Set<string>()
const listeners = new Set<() => void>()

function keyOf(projectId: number, kind: HarnessImageKind) {
  return `${projectId}:${kind}`
}

function snapshotKey(projectId: number, mode: string | null) {
  return `${projectId}:${mode ?? ''}`
}

function ensureUploaded(projectId: number, mode: string | null): void {
  for (const kind of harnessImageKinds(mode)) {
    const key = keyOf(projectId, kind)
    if (started.has(key)) continue
    started.add(key)
    uploadImage(projectId, kind).then(
      (hash) => {
        const at = snapshotKey(projectId, mode)
        uploaded.set(at, { ...(uploaded.get(at) ?? EMPTY), [kind]: hash })
        listeners.forEach((l) => l())
      },
      (err: unknown) => {
        // The cloth draws unpainted, as one whose image is missing on this machine does.
        console.warn(`profile harness: the ${kind} image was not stored`, err)
      },
    )
  }
}

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

/**
 * The hashes of [mode]'s images in [projectId]'s store, uploading them the first time a scene asks:
 * `{}` until they land, and for a scene with no paint. A stable object per change, so a memo keyed
 * on it rebuilds the scene only when an image arrives.
 */
export function useHarnessImages(projectId: number, mode: string | null): HarnessImages {
  const active = harnessImageKinds(mode).length > 0
  useEffect(() => {
    if (active) ensureUploaded(projectId, mode)
  }, [active, projectId, mode])
  return useSyncExternalStore(subscribe, () => (active ? (uploaded.get(snapshotKey(projectId, mode)) ?? EMPTY) : EMPTY))
}
