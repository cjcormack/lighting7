import {
  ClampToEdgeWrapping,
  LinearFilter,
  LinearMipmapLinearFilter,
  SRGBColorSpace,
  Texture,
} from 'three'
import { sceneImageUrl } from '../../../api/sceneImageApi'

/**
 * **The textures a painted cloth is drawn with** (scrim plan D4, D12): one per image and variant,
 * loaded once and shared by every part, every canvas and every `render_view` capture that draws it.
 *
 * - **Keyed by hash and variant.** An image is content-addressed, so its hash names its pixels in
 *   any project; `display` is the desk's 2048 px copy, `detail` the 4096 px one a cloth with *Full
 *   detail* asks for. Each is fetched from `GET …/scene-images/{hash}?variant=…` once, however many
 *   parts and windows draw it.
 * - **On the GPU with mipmaps and anisotropy**, so a cloth seen at a rake from the stalls is
 *   filtered rather than shimmering. `anisotropy` asks for 8; three clamps it to what the GPU has.
 * - **Released, then disposed.** Every user holds a reference ([PaintTextureCache.acquire]); one
 *   nothing has held for [PaintTextureCacheOptions.graceMs] is disposed and forgotten, so a part
 *   remounted by a rebuild, or a window flipped back and forth between Full detail, does not
 *   download the image again.
 * - **Missing is not an error.** A hash this machine does not hold (a partial import), or one the
 *   desk will not serve, settles as `missing` with no texture, and the cloth draws unpainted.
 *
 * **Module-level, not a React context**: a `render_view` capture's R3F root bridges only the
 * channel source (`CaptureCanvas.tsx`), so anything the scene reads from outside its canvas would
 * read its default there. A `Texture` belongs to no renderer, so one object serves the Stage
 * view's canvas, the Positions plan's and a capture's alike; each uploads it to its own context.
 *
 * The loader and the clock are arguments, so the node test drives the whole lifecycle.
 */

export type PaintVariant = 'display' | 'detail'

export type PaintTextureState = 'loading' | 'ready' | 'missing'

/** Loads one image, or answers null when the desk does not have it. Rejecting is treated as null. */
export type PaintImageLoader = (url: string, signal: AbortSignal) => Promise<TexImageSource | null>

export interface PaintTextureCacheOptions {
  load?: PaintImageLoader
  /** How long an image nothing holds is kept before it is disposed. */
  graceMs?: number
  setTimer?: (fn: () => void, ms: number) => unknown
  clearTimer?: (timer: unknown) => void
}

export interface PaintTextureCache {
  /**
   * Hold [hash]'s [variant], loading it if nothing has yet. [onChange] is called when it settles —
   * ready or missing. Answers the release, which is safe to call more than once.
   */
  acquire(projectId: number, hash: string, variant: PaintVariant, onChange?: () => void): () => void
  /** The texture, once loaded; null while loading, when missing, or when nothing holds it. */
  texture(hash: string, variant: PaintVariant): Texture | null
  state(hash: string, variant: PaintVariant): PaintTextureState | null
  /** Resolves once nothing is loading — what a `render_view` capture waits for before it draws. */
  settled(): Promise<void>
  /** How many images are held or kept, for tests. */
  size(): number
}

/** How long an image nothing holds is kept: long enough to outlive a remount or a variant swap. */
export const PAINT_GRACE_MS = 5000

/** What the GPU is asked to filter a cloth at a rake with; three clamps it to what it has. */
export const PAINT_ANISOTROPY = 8

interface Entry {
  state: PaintTextureState
  texture: Texture | null
  refs: number
  listeners: Set<() => void>
  abort: AbortController
  evict: unknown
}

/** `fetch` the image, then decode it off the main thread: a 404 or a failed decode is missing. */
export const fetchPaintImage: PaintImageLoader = async (url, signal) => {
  const response = await fetch(url, { signal, credentials: 'same-origin' })
  if (!response.ok) return null
  const blob = await response.blob()
  // Unpremultiplied, so a hole's alpha is the image's own; the rows stay top first, which the
  // texture's `flipY = false` and the shader's `1 − v` account for (WebGL ignores flipY for a bitmap).
  return createImageBitmap(blob, { premultiplyAlpha: 'none' })
}

/** A loaded image as the cloth's texture: sRGB, mipmapped, anisotropic, clamped at its edges. */
export function makePaintTexture(image: TexImageSource): Texture {
  const texture = new Texture(image as unknown as HTMLImageElement)
  texture.colorSpace = SRGBColorSpace
  texture.flipY = false
  texture.premultiplyAlpha = false
  texture.generateMipmaps = true
  texture.minFilter = LinearMipmapLinearFilter
  texture.magFilter = LinearFilter
  texture.anisotropy = PAINT_ANISOTROPY
  texture.wrapS = ClampToEdgeWrapping
  texture.wrapT = ClampToEdgeWrapping
  texture.needsUpdate = true
  return texture
}

export function createPaintTextureCache(options: PaintTextureCacheOptions = {}): PaintTextureCache {
  const load = options.load ?? fetchPaintImage
  const graceMs = options.graceMs ?? PAINT_GRACE_MS
  const setTimer = options.setTimer ?? ((fn, ms) => setTimeout(fn, ms))
  const clearTimer = options.clearTimer ?? ((t) => clearTimeout(t as ReturnType<typeof setTimeout>))
  const entries = new Map<string, Entry>()
  let waiting: (() => void)[] = []

  const keyOf = (hash: string, variant: PaintVariant) => `${hash}:${variant}`

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

  const drop = (key: string, entry: Entry) => {
    if (entries.get(key) !== entry) return
    entries.delete(key)
    entry.abort.abort()
    entry.listeners.clear()
    if (entry.texture != null) {
      const image = entry.texture.image as { close?: () => void } | null
      entry.texture.dispose()
      // A bitmap holds its decoded pixels until closed; nothing else will read it now.
      image?.close?.()
      entry.texture = null
    }
    wake()
  }

  const settle = (entry: Entry, image: TexImageSource | null) => {
    if (entry.abort.signal.aborted) {
      ;(image as { close?: () => void } | null)?.close?.()
      return
    }
    entry.state = image != null ? 'ready' : 'missing'
    entry.texture = image != null ? makePaintTexture(image) : null
    const listeners = [...entry.listeners]
    listeners.forEach((l) => l())
    wake()
  }

  return {
    acquire(projectId, hash, variant, onChange) {
      const key = keyOf(hash, variant)
      let entry = entries.get(key)
      if (entry == null) {
        const created: Entry = {
          state: 'loading',
          texture: null,
          refs: 0,
          listeners: new Set(),
          abort: new AbortController(),
          evict: null,
        }
        entry = created
        entries.set(key, created)
        load(sceneImageUrl(projectId, hash, variant), created.abort.signal).then(
          (image) => settle(created, image),
          () => settle(created, null),
        )
      }
      const held = entry
      if (held.evict != null) {
        clearTimer(held.evict)
        held.evict = null
      }
      held.refs++
      const listener = onChange != null ? () => onChange() : null
      if (listener != null) held.listeners.add(listener)
      let released = false
      return () => {
        if (released) return
        released = true
        if (listener != null) held.listeners.delete(listener)
        held.refs--
        if (held.refs > 0 || entries.get(key) !== held) return
        held.evict = setTimer(() => {
          held.evict = null
          if (held.refs === 0) drop(key, held)
        }, graceMs)
      }
    },
    texture(hash, variant) {
      return entries.get(keyOf(hash, variant))?.texture ?? null
    },
    state(hash, variant) {
      return entries.get(keyOf(hash, variant))?.state ?? null
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

/**
 * One painted face's hold on the cache, for a mesh that redraws as it changes: [set] the image it
 * shows, [texture] what to draw now, [dispose] when it goes.
 *
 * A change of variant — *Full detail* switched — keeps drawing the copy it had until the new one has
 * settled, so the cloth never flashes unpainted while the 4096 px copy downloads; only then is the
 * old one released. A change of image, or of none, lets the old go at once.
 */
export interface PaintSlot {
  set(projectId: number | null, hash: string | null | undefined, variant: PaintVariant): void
  texture(): Texture | null
  dispose(): void
}

interface Hold {
  hash: string
  variant: PaintVariant
  release: () => void
}

export function createPaintSlot(cache: PaintTextureCache, onChange: () => void): PaintSlot {
  // Newest last: what [set] asked for, and while it loads the copy it is replacing.
  let holds: Hold[] = []
  let disposed = false

  const prune = () => {
    const newest = holds[holds.length - 1]
    if (newest == null || cache.state(newest.hash, newest.variant) === 'loading') return
    holds.slice(0, -1).forEach((h) => h.release())
    holds = [newest]
  }

  return {
    set(projectId, hash, variant) {
      if (disposed) return
      const newest = holds[holds.length - 1]
      if (newest != null && hash === newest.hash && variant === newest.variant) return
      if (projectId == null || hash == null) {
        holds.forEach((h) => h.release())
        holds = []
        return
      }
      // Another image entirely: nothing of the old is worth showing meanwhile.
      if (newest != null && newest.hash !== hash) {
        holds.forEach((h) => h.release())
        holds = []
      }
      // Back to a variant still held behind the newest: drop what came after it.
      const back = holds.findIndex((h) => h.variant === variant)
      if (back >= 0) {
        holds.slice(back + 1).forEach((h) => h.release())
        holds = holds.slice(0, back + 1)
        return
      }
      const release = cache.acquire(projectId, hash, variant, () => {
        if (disposed) return
        prune()
        onChange()
      })
      holds.push({ hash, variant, release })
      prune()
    },
    texture() {
      for (let i = holds.length - 1; i >= 0; i--) {
        const t = cache.texture(holds[i].hash, holds[i].variant)
        if (t != null) return t
        // The newest settled missing: show nothing, not the copy it replaced.
        if (cache.state(holds[i].hash, holds[i].variant) !== 'loading') return null
      }
      return null
    },
    dispose() {
      disposed = true
      holds.forEach((h) => h.release())
      holds = []
    },
  }
}

/** The one cache the scene draws from. */
export const paintTextures: PaintTextureCache = createPaintTextureCache()
