import { describe, expect, it, vi } from 'vitest'
import {
  createSceneMaskCache,
  MASK_ATLAS_LAYERS,
  MASK_GRACE_MS,
  MASK_SIZE,
  maskHole,
  maskImagesOf,
  maskLayer,
  type DecodedMask,
  type SceneMaskLoader,
} from './sceneMasks'

const hash = (i: number) => i.toString(16).padStart(64, '0')
const url = (h: string) => `/api/rest/projects/7/scene-images/${h}?variant=mask`
const flush = () => new Promise<void>((r) => setTimeout(r, 0))

/** A mask [w] × [h], rows top first: holes ([hole] true) in its left half. */
function mask(w: number, h: number, hole = true): DecodedMask {
  const data = new Uint8Array(w * h).fill(255)
  if (hole) for (let y = 0; y < h; y++) for (let x = 0; x < w / 2; x++) data[y * w + x] = 0
  return { width: w, height: h, data }
}

/** A loader whose loads finish when the test says, by URL. */
function controlledLoader() {
  const pending = new Map<string, (v: DecodedMask | null) => void>()
  const fail = new Map<string, (e: unknown) => void>()
  const load = vi.fn<SceneMaskLoader>(
    (u) =>
      new Promise((resolve, reject) => {
        pending.set(u, resolve)
        fail.set(u, reject)
      }),
  )
  return {
    load,
    finish: (u: string, m: DecodedMask | null) => pending.get(u)!(m),
    fail: (u: string) => fail.get(u)!(new Error('network')),
  }
}

function manualTimers() {
  let now = 0
  const timers = new Map<number, { at: number; fn: () => void }>()
  let next = 1
  return {
    setTimer: (fn: () => void, ms: number) => {
      const id = next++
      timers.set(id, { at: now + ms, fn })
      return id
    },
    clearTimer: (id: unknown) => {
      timers.delete(id as number)
    },
    advance(ms: number) {
      now += ms
      for (const [id, t] of [...timers]) {
        if (t.at <= now) {
          timers.delete(id)
          t.fn()
        }
      }
    },
  }
}

describe('a mask layer (scrim plan D5)', () => {
  it('resamples onto 256 × 256 with its bottom row first, and needs none without a hole', () => {
    // A 4 × 2 image: its top row cloth, its bottom row a hole in its left half.
    const m: DecodedMask = { width: 4, height: 2, data: new Uint8Array([255, 255, 255, 255, 0, 0, 255, 255]) }
    const layer = maskLayer(m)!
    expect(layer.length).toBe(MASK_SIZE * MASK_SIZE)
    // v = 0 is the image's bottom: row 0 here.
    expect(layer[0]).toBe(0)
    expect(layer[MASK_SIZE - 1]).toBe(255)
    expect(layer[(MASK_SIZE - 1) * MASK_SIZE]).toBe(255)
    expect(maskLayer(mask(256, 128, false))).toBeNull()
    // A JPEG's mask is opaque white: no hole, no layer.
    expect(maskLayer({ width: 2, height: 2, data: new Uint8Array([255, 255, 255, 255]) })).toBeNull()
  })

  it('reads a hole below half opacity, clamped to the layer as the shader is', () => {
    const atlas = new Uint8Array(2 * MASK_SIZE * MASK_SIZE).fill(255)
    atlas[MASK_SIZE * MASK_SIZE + 3] = 127
    atlas[MASK_SIZE * MASK_SIZE + 4] = 128
    expect(maskHole(atlas, 1, 3.5 / MASK_SIZE, 0)).toBe(true)
    expect(maskHole(atlas, 1, 4.5 / MASK_SIZE, 0)).toBe(false)
    expect(maskHole(atlas, 1, -1, -1)).toBe(false)
    expect(maskHole(atlas, -1, 3.5 / MASK_SIZE, 0)).toBe(false)
  })

  it('names each mask image once, in order', () => {
    expect(maskImagesOf([null, { kind: 'angle' }, { kind: 'mask', image: 'a' }, { kind: 'mask', image: 'b' }, { kind: 'mask', image: 'a' }])).toEqual(['a', 'b'])
  })
})

describe('the mask cache', () => {
  it('loads each mask once, however many hold it, and gives one with holes a layer', async () => {
    const loader = controlledLoader()
    const cache = createSceneMaskCache({ load: loader.load })
    const heard = vi.fn()
    cache.subscribe(heard)
    const a = cache.acquire(7, hash(1))
    const b = cache.acquire(7, hash(1))
    expect(loader.load).toHaveBeenCalledTimes(1)
    expect(loader.load).toHaveBeenCalledWith(url(hash(1)), expect.any(AbortSignal))
    expect(cache.state(hash(1))).toBe('loading')
    // Loading counts as solid.
    expect(cache.layerOf(hash(1))).toBe(-1)
    expect(cache.holeAt(hash(1), 0.1, 0.5)).toBe(false)
    let settled = false
    void cache.settled().then(() => (settled = true))
    loader.finish(url(hash(1)), mask(256, 128))
    await flush()
    expect(settled).toBe(true)
    expect(heard).toHaveBeenCalled()
    expect(cache.state(hash(1))).toBe('ready')
    expect(cache.layerOf(hash(1))).toBe(0)
    expect(cache.holeAt(hash(1), 0.1, 0.5)).toBe(true)
    expect(cache.holeAt(hash(1), 0.9, 0.5)).toBe(false)
    expect(cache.takeDirtyLayers()).toEqual([0])
    a()
    b()
  })

  it('counts a missing mask, a failed load and a holeless one as solid, with no layer', async () => {
    const loader = controlledLoader()
    const cache = createSceneMaskCache({ load: loader.load })
    cache.acquire(7, hash(1))
    cache.acquire(7, hash(2))
    cache.acquire(7, hash(3))
    loader.finish(url(hash(1)), null)
    loader.fail(url(hash(2)))
    loader.finish(url(hash(3)), mask(64, 64, false))
    await flush()
    expect(cache.state(hash(1))).toBe('missing')
    expect(cache.state(hash(2))).toBe('missing')
    expect(cache.state(hash(3))).toBe('ready')
    for (const h of [hash(1), hash(2), hash(3)]) {
      expect(cache.layerOf(h)).toBe(-1)
      expect(cache.holeAt(h, 0.1, 0.5)).toBe(false)
    }
    expect(cache.overCap()).toBe(0)
  })

  it('disposes a mask nothing holds after the grace, freeing its layer, and keeps one re-held in time', async () => {
    const loader = controlledLoader()
    const timers = manualTimers()
    const cache = createSceneMaskCache({ load: loader.load, setTimer: timers.setTimer, clearTimer: timers.clearTimer })
    const release = cache.acquire(7, hash(1))
    loader.finish(url(hash(1)), mask(32, 32))
    await flush()
    release()
    timers.advance(MASK_GRACE_MS - 1)
    const again = cache.acquire(7, hash(1))
    timers.advance(10)
    expect(cache.size()).toBe(1)
    expect(loader.load).toHaveBeenCalledTimes(1)
    again()
    again()
    timers.advance(MASK_GRACE_MS)
    expect(cache.size()).toBe(0)
    expect(cache.layerOf(hash(1))).toBe(-1)
    // Asked for afresh, it loads again.
    cache.acquire(7, hash(1))
    expect(loader.load).toHaveBeenCalledTimes(2)
  })

  it('falls back to solid for the 33rd mask with holes, names it, and gives it the first layer that frees', async () => {
    const loader = controlledLoader()
    const timers = manualTimers()
    const cache = createSceneMaskCache({ load: loader.load, setTimer: timers.setTimer, clearTimer: timers.clearTimer })
    expect(MASK_ATLAS_LAYERS).toBe(32)
    const releases = Array.from({ length: 33 }, (_, i) => cache.acquire(7, hash(i + 1)))
    for (let i = 0; i < 33; i++) loader.finish(url(hash(i + 1)), mask(16, 16))
    await flush()
    for (let i = 0; i < 32; i++) expect(cache.layerOf(hash(i + 1))).toBe(i)
    expect(cache.layerOf(hash(33))).toBe(-1)
    expect(cache.holeAt(hash(33), 0.1, 0.5)).toBe(false)
    expect(cache.overCap()).toBe(1)
    // The fifth leaves the scene: its layer goes to the one waiting.
    releases[4]()
    timers.advance(MASK_GRACE_MS)
    expect(cache.layerOf(hash(33))).toBe(4)
    expect(cache.holeAt(hash(33), 0.1, 0.5)).toBe(true)
    expect(cache.overCap()).toBe(0)
  })
})
