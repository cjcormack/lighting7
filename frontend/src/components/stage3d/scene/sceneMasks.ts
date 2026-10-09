import { sceneImageUrl } from '../../../api/sceneImageApi'

/**
 * **The masks a painted cloth's holes are cut by** (scrim plan D5, D8): one per image, the desk's
 * `?variant=mask` — the image's alpha at 256 px on its longest side, one byte a pixel, opaque white
 * for a JPEG — loaded once and shared by every canvas, every beam cast and every `render_view`
 * capture. `paintTextures.ts`'s pattern, and for its reasons:
 *
 * - **Keyed by hash.** An image is content-addressed, so a hash names its mask in any project.
 * - **Released, then disposed.** Every user holds a reference ([SceneMaskCache.acquire]); one nothing
 *   has held for [SceneMaskCacheOptions.graceMs] is forgotten, so a rebuild does not download it again.
 * - **Missing is not an error.** A mask the desk will not serve settles `missing`, and counts as solid.
 * - **Module-level, never a React context**: a capture's root bridges only the channel source.
 *
 * **One copy for the CPU and the GPU.** A mask with a hole in it is resampled, nearest, onto a
 * 256 × 256 **layer** of [SceneMaskCache.atlas] — row 0 at the image's bottom (v = 0), column 0 at
 * its left (u = 0) — which `maskAtlas.ts` hands the GPU as an `R8` `DataArrayTexture` and beam reach
 * and the TypeScript twin of the occlusion test read directly ([maskHole]), so a beam's end and a
 * shadow's edge are decided by the same bytes. A byte below [MASK_HOLE_BELOW] is a hole.
 *
 * **At most [MASK_ATLAS_LAYERS] masks hold a layer.** A mask with no hole (a JPEG, an opaque PNG)
 * takes none — it is solid everywhere, so nothing needs to sample it. A further mask with holes waits
 * for a layer, counted by [SceneMaskCache.overCap] (the Stage stats name it), and counts as solid
 * until one frees. So does a mask still loading or missing: [SceneMaskCache.layerOf] is −1.
 *
 * The loader and the clock are arguments, so the node test drives the whole lifecycle.
 */

/** A layer's side, in texels: the desk's mask is 256 px on its longest side. */
export const MASK_SIZE = 256
/** The atlas's layers: the most masks with holes the scene can cut light by at once (D8). */
export const MASK_ATLAS_LAYERS = 32
/** A mask byte below this is a hole (D5: below half opacity). */
export const MASK_HOLE_BELOW = 128
/** How long a mask nothing holds is kept: long enough to outlive a remount, as the paint's. */
export const MASK_GRACE_MS = 5000

const LAYER_BYTES = MASK_SIZE * MASK_SIZE

export type SceneMaskState = 'loading' | 'ready' | 'missing'

/** A decoded mask: [width] × [height] bytes, rows top first, as the PNG holds them. */
export interface DecodedMask {
  width: number
  height: number
  data: Uint8Array
}

/** Loads one mask, or answers null when the desk does not have it. Rejecting is treated as null. */
export type SceneMaskLoader = (url: string, signal: AbortSignal) => Promise<DecodedMask | null>

export interface SceneMaskCacheOptions {
  load?: SceneMaskLoader
  graceMs?: number
  setTimer?: (fn: () => void, ms: number) => unknown
  clearTimer?: (timer: unknown) => void
  layers?: number
}

export interface SceneMaskCache {
  /**
   * Hold [hash]'s mask, loading it if nothing has yet. Answers the release, which is safe to call
   * more than once. Every settle and every layer moving notifies [subscribe]'s listeners.
   */
  acquire(projectId: number, hash: string): () => void
  state(hash: string): SceneMaskState | null
  /** The atlas layer [hash]'s mask holds, or −1: loading, missing, no hole, over the cap, or not held. */
  layerOf(hash: string): number
  /** Whether [hash]'s mask is a hole at ([u], [v]) on its image — false wherever [layerOf] is −1. */
  holeAt(hash: string, u: number, v: number): boolean
  /** Masks with holes that hold no layer, the atlas being full: drawn and lit solid. */
  overCap(): number
  /** The layers' bytes, [MASK_ATLAS_LAYERS] × 256 × 256 — what `maskAtlas.ts` uploads. */
  readonly atlas: Uint8Array
  /** The layers written since the last call, emptied by it — what the atlas uploads. */
  takeDirtyLayers(): number[]
  /** Moves on every change a listener should hear: a settle, a layer given or taken. */
  version(): number
  subscribe(listener: () => void): () => void
  /** Resolves once nothing is loading — what a `render_view` capture waits for before it draws. */
  settled(): Promise<void>
  /** How many masks are held or kept, for tests. */
  size(): number
}

interface Entry {
  state: SceneMaskState
  /** The 256 × 256 layer, bottom row first, once ready with a hole; null otherwise. */
  layer: Uint8Array | null
  slot: number
  refs: number
  abort: AbortController
  evict: unknown
  /** When it was first asked for: a freed layer goes to the longest-waiting mask. */
  order: number
}

/**
 * [mask] resampled, nearest, onto a 256 × 256 layer, row 0 at the image's bottom; null when no byte
 * of it is a hole, which needs no layer.
 */
export function maskLayer(mask: DecodedMask): Uint8Array | null {
  const { width, height, data } = mask
  if (!(width > 0 && height > 0) || data.length < width * height) return null
  const out = new Uint8Array(LAYER_BYTES)
  let hole = false
  for (let j = 0; j < MASK_SIZE; j++) {
    // v at the texel's centre; the image's rows run top first.
    const sy = Math.min(height - 1, Math.floor((1 - (j + 0.5) / MASK_SIZE) * height))
    for (let i = 0; i < MASK_SIZE; i++) {
      const sx = Math.min(width - 1, Math.floor(((i + 0.5) / MASK_SIZE) * width))
      const b = data[sy * width + sx]
      out[j * MASK_SIZE + i] = b
      if (b < MASK_HOLE_BELOW) hole = true
    }
  }
  return hole ? out : null
}

/** The texel of a layer ([u], [v]) falls in: clamped to the layer, as the shader's `texelFetch` is. */
export function maskTexel(u: number, v: number): number {
  const i = Math.min(MASK_SIZE - 1, Math.max(0, Math.floor(u * MASK_SIZE)))
  const j = Math.min(MASK_SIZE - 1, Math.max(0, Math.floor(v * MASK_SIZE)))
  return j * MASK_SIZE + i
}

/** Whether [atlas]'s layer [layer] is a hole at ([u], [v]): the twin of the shader's sample. */
export function maskHole(atlas: Uint8Array, layer: number, u: number, v: number): boolean {
  if (layer < 0) return false
  return atlas[layer * LAYER_BYTES + maskTexel(u, v)] < MASK_HOLE_BELOW
}

/**
 * `fetch` the mask and read its bytes: decoded off the main thread, drawn to an offscreen canvas
 * with no colour conversion, and the red channel kept (a grey PNG's grey). A 404 or a failed decode
 * is missing.
 */
export const fetchSceneMask: SceneMaskLoader = async (url, signal) => {
  const response = await fetch(url, { signal, credentials: 'same-origin' })
  if (!response.ok) return null
  const blob = await response.blob()
  const bitmap = await createImageBitmap(blob, { premultiplyAlpha: 'none', colorSpaceConversion: 'none' })
  try {
    const canvas = new OffscreenCanvas(bitmap.width, bitmap.height)
    const context = canvas.getContext('2d')
    if (context == null) return null
    context.drawImage(bitmap, 0, 0)
    const rgba = context.getImageData(0, 0, bitmap.width, bitmap.height).data
    const data = new Uint8Array(bitmap.width * bitmap.height)
    for (let p = 0; p < data.length; p++) data[p] = rgba[p * 4]
    return { width: bitmap.width, height: bitmap.height, data }
  } finally {
    bitmap.close()
  }
}

export function createSceneMaskCache(options: SceneMaskCacheOptions = {}): SceneMaskCache {
  const load = options.load ?? fetchSceneMask
  const graceMs = options.graceMs ?? MASK_GRACE_MS
  const setTimer = options.setTimer ?? ((fn, ms) => setTimeout(fn, ms))
  const clearTimer = options.clearTimer ?? ((t) => clearTimeout(t as ReturnType<typeof setTimeout>))
  const layers = options.layers ?? MASK_ATLAS_LAYERS
  const atlas = new Uint8Array(layers * LAYER_BYTES)
  const owners: (Entry | null)[] = new Array(layers).fill(null)
  const entries = new Map<string, Entry>()
  const listeners = new Set<() => void>()
  let dirty: number[] = []
  let waiting: (() => void)[] = []
  let changes = 0
  let asked = 0

  const notify = () => {
    changes++
    for (const l of [...listeners]) l()
  }

  const loading = () => {
    for (const e of entries.values()) if (e.state === 'loading') return true
    return false
  }

  const wake = () => {
    if (loading()) return
    const resolve = waiting
    waiting = []
    resolve.forEach((r) => r())
  }

  const give = (entry: Entry, slot: number) => {
    entry.slot = slot
    owners[slot] = entry
    atlas.set(entry.layer!, slot * LAYER_BYTES)
    if (!dirty.includes(slot)) dirty.push(slot)
  }

  /** A free layer to the longest-waiting mask with holes that has none. */
  const refill = (slot: number) => {
    let next: Entry | null = null
    for (const e of entries.values()) {
      if (e.layer != null && e.slot < 0 && (next == null || e.order < next.order)) next = e
    }
    if (next != null) give(next, slot)
  }

  const drop = (hash: string, entry: Entry) => {
    if (entries.get(hash) !== entry) return
    entries.delete(hash)
    entry.abort.abort()
    if (entry.slot >= 0) {
      const slot = entry.slot
      owners[slot] = null
      entry.slot = -1
      refill(slot)
    }
    entry.layer = null
    notify()
    wake()
  }

  const settle = (entry: Entry, mask: DecodedMask | null) => {
    if (entry.abort.signal.aborted) return
    entry.state = mask != null ? 'ready' : 'missing'
    entry.layer = mask != null ? maskLayer(mask) : null
    if (entry.layer != null) {
      const free = owners.indexOf(null)
      if (free >= 0) give(entry, free)
    }
    notify()
    wake()
  }

  return {
    acquire(projectId, hash) {
      let entry = entries.get(hash)
      if (entry == null) {
        const created: Entry = {
          state: 'loading',
          layer: null,
          slot: -1,
          refs: 0,
          abort: new AbortController(),
          evict: null,
          order: asked++,
        }
        entry = created
        entries.set(hash, created)
        load(sceneImageUrl(projectId, hash, 'mask'), created.abort.signal).then(
          (mask) => settle(created, mask),
          () => settle(created, null),
        )
      }
      const held = entry
      if (held.evict != null) {
        clearTimer(held.evict)
        held.evict = null
      }
      held.refs++
      let released = false
      return () => {
        if (released) return
        released = true
        held.refs--
        if (held.refs > 0 || entries.get(hash) !== held) return
        held.evict = setTimer(() => {
          held.evict = null
          if (held.refs === 0) drop(hash, held)
        }, graceMs)
      }
    },
    state(hash) {
      return entries.get(hash)?.state ?? null
    },
    layerOf(hash) {
      return entries.get(hash)?.slot ?? -1
    },
    holeAt(hash, u, v) {
      return maskHole(atlas, entries.get(hash)?.slot ?? -1, u, v)
    },
    overCap() {
      let n = 0
      for (const e of entries.values()) if (e.layer != null && e.slot < 0) n++
      return n
    },
    atlas,
    takeDirtyLayers() {
      const out = dirty
      dirty = []
      return out
    },
    version: () => changes,
    subscribe(listener) {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
    settled() {
      if (!loading()) return Promise.resolve()
      return new Promise((resolve) => waiting.push(resolve))
    },
    size() {
      return entries.size
    },
  }
}

/** What beam reach and the occlusion twin ask of the masks: whether a point of one is a hole. */
export interface MaskSampler {
  holeAt(hash: string, u: number, v: number): boolean
}

/** The one cache the scene cuts light by. */
export const sceneMasks: SceneMaskCache = createSceneMaskCache()

/** Every mask image the [colliders]' transmitting parts name, once each, in order. */
export function maskImagesOf(transmits: Iterable<{ kind: string; image?: string } | null | undefined>): string[] {
  const out: string[] = []
  const seen = new Set<string>()
  for (const t of transmits) {
    if (t?.kind !== 'mask' || t.image == null || seen.has(t.image)) continue
    seen.add(t.image)
    out.push(t.image)
  }
  return out
}
