import { afterEach, describe, expect, it, vi } from 'vitest'
import { LinearMipmapLinearFilter, SRGBColorSpace } from 'three'
import { createPaintSlot, createPaintTextureCache, PAINT_ANISOTROPY, type PaintImageLoader } from './paintTextures'

const HASH = 'c'.repeat(64)
const OTHER = 'd'.repeat(64)

/** A stand-in image: a canvas-like object a `Texture` will hold, with a `close` like a bitmap's. */
function fakeImage(label: string) {
  return { width: 2048, height: 1024, label, close: vi.fn() } as unknown as TexImageSource & { close: () => void; label: string }
}

/** A loader whose loads finish when the test says, by URL. */
function controlledLoader() {
  const pending = new Map<string, { resolve: (v: TexImageSource | null) => void; reject: (e: unknown) => void }>()
  const load = vi.fn<PaintImageLoader>(
    (url) =>
      new Promise((resolve, reject) => {
        pending.set(url, { resolve, reject })
      }),
  )
  return {
    load,
    finish(url: string, image: TexImageSource | null) {
      pending.get(url)!.resolve(image)
    },
    fail(url: string) {
      pending.get(url)!.reject(new Error('network'))
    },
  }
}

const url = (hash: string, variant: string) => `/api/rest/projects/7/scene-images/${hash}?variant=${variant}`
const flush = () => new Promise<void>((r) => setTimeout(r, 0))

/** A clock the test turns by hand: the cache's grace period runs on it. */
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

afterEach(() => vi.restoreAllMocks())

describe('the paint texture cache (scrim plan D4, D12)', () => {
  it('loads each image and variant once, however many hold it, from the desk’s derived copy', async () => {
    const loader = controlledLoader()
    const cache = createPaintTextureCache({ load: loader.load })
    const a = vi.fn()
    const b = vi.fn()
    cache.acquire(7, HASH, 'display', a)
    cache.acquire(7, HASH, 'display', b)
    cache.acquire(7, HASH, 'detail')
    expect(loader.load).toHaveBeenCalledTimes(2)
    expect(loader.load.mock.calls.map(([u]) => u)).toEqual([url(HASH, 'display'), url(HASH, 'detail')])
    expect(cache.state(HASH, 'display')).toBe('loading')
    expect(cache.texture(HASH, 'display')).toBeNull()

    const image = fakeImage('display')
    loader.finish(url(HASH, 'display'), image)
    await flush()
    const texture = cache.texture(HASH, 'display')!
    expect(texture.image).toBe(image)
    expect(a).toHaveBeenCalledTimes(1)
    expect(b).toHaveBeenCalledTimes(1)
    // Mipmapped, anisotropic, sRGB, rows top first (the shader flips v).
    expect(texture.generateMipmaps).toBe(true)
    expect(texture.minFilter).toBe(LinearMipmapLinearFilter)
    expect(texture.anisotropy).toBe(PAINT_ANISOTROPY)
    expect(texture.colorSpace).toBe(SRGBColorSpace)
    expect(texture.flipY).toBe(false)
    // A later hold gets the same texture, with no second load.
    cache.acquire(7, HASH, 'display')
    expect(loader.load).toHaveBeenCalledTimes(2)
  })

  it('disposes an image nothing holds once its grace has passed, and keeps one held again within it', async () => {
    const loader = controlledLoader()
    const timers = manualTimers()
    const cache = createPaintTextureCache({ load: loader.load, graceMs: 1000, ...timers })
    const release = cache.acquire(7, HASH, 'display')
    const image = fakeImage('display')
    loader.finish(url(HASH, 'display'), image)
    await flush()
    const texture = cache.texture(HASH, 'display')!
    const disposed = vi.fn()
    texture.addEventListener('dispose', disposed)

    // Dropped and held again within the grace: a remount, kept.
    release()
    release()
    timers.advance(500)
    const again = cache.acquire(7, HASH, 'display')
    timers.advance(5000)
    expect(cache.texture(HASH, 'display')).toBe(texture)
    expect(disposed).not.toHaveBeenCalled()

    // Dropped for good: disposed, its bitmap closed, forgotten — and a new hold loads afresh.
    again()
    timers.advance(999)
    expect(disposed).not.toHaveBeenCalled()
    timers.advance(1)
    expect(disposed).toHaveBeenCalledTimes(1)
    expect(image.close).toHaveBeenCalled()
    expect(cache.size()).toBe(0)
    expect(cache.texture(HASH, 'display')).toBeNull()
    cache.acquire(7, HASH, 'display')
    expect(loader.load).toHaveBeenCalledTimes(2)
  })

  it('settles a missing image, or one that fails, as missing with no texture — never an error', async () => {
    const loader = controlledLoader()
    const cache = createPaintTextureCache({ load: loader.load })
    const changed = vi.fn()
    cache.acquire(7, HASH, 'display', changed)
    cache.acquire(7, OTHER, 'display', changed)
    let settled = false
    void cache.settled().then(() => {
      settled = true
    })
    loader.finish(url(HASH, 'display'), null)
    await flush()
    expect(cache.state(HASH, 'display')).toBe('missing')
    expect(settled).toBe(false)
    loader.fail(url(OTHER, 'display'))
    await flush()
    expect(cache.state(OTHER, 'display')).toBe('missing')
    expect(cache.texture(OTHER, 'display')).toBeNull()
    expect(changed).toHaveBeenCalledTimes(2)
    expect(settled).toBe(true)
  })

  it('answers settled at once with nothing loading, and once the last load is in otherwise', async () => {
    const loader = controlledLoader()
    const cache = createPaintTextureCache({ load: loader.load })
    await cache.settled()
    cache.acquire(7, HASH, 'display')
    let settled = false
    void cache.settled().then(() => {
      settled = true
    })
    await flush()
    expect(settled).toBe(false)
    loader.finish(url(HASH, 'display'), fakeImage('x'))
    await flush()
    expect(settled).toBe(true)
  })

  it('drops a load nothing holds any more, and settles without it', async () => {
    const loader = controlledLoader()
    const timers = manualTimers()
    const cache = createPaintTextureCache({ load: loader.load, graceMs: 10, ...timers })
    cache.acquire(7, HASH, 'display')()
    let settled = false
    void cache.settled().then(() => {
      settled = true
    })
    timers.advance(10)
    await flush()
    expect(settled).toBe(true)
    expect(loader.load.mock.calls[0][1].aborted).toBe(true)
    // The late answer is closed, not turned into a texture.
    const late = fakeImage('late')
    loader.finish(url(HASH, 'display'), late)
    await flush()
    expect(late.close).toHaveBeenCalled()
    expect(cache.size()).toBe(0)
  })
})

describe('a painted face’s slot', () => {
  it('swaps to the detail copy when Full detail is on, drawing the display copy until it is in', async () => {
    const loader = controlledLoader()
    const timers = manualTimers()
    const cache = createPaintTextureCache({ load: loader.load, graceMs: 1000, ...timers })
    const changed = vi.fn()
    const slot = createPaintSlot(cache, changed)
    slot.set(7, HASH, 'display')
    loader.finish(url(HASH, 'display'), fakeImage('display'))
    await flush()
    const display = cache.texture(HASH, 'display')!
    expect(slot.texture()).toBe(display)

    slot.set(7, HASH, 'detail')
    expect(loader.load).toHaveBeenLastCalledWith(url(HASH, 'detail'), expect.anything())
    // Still the display copy while the detail one downloads, and still held.
    expect(slot.texture()).toBe(display)
    timers.advance(5000)
    expect(cache.texture(HASH, 'display')).toBe(display)

    loader.finish(url(HASH, 'detail'), fakeImage('detail'))
    await flush()
    expect(slot.texture()).toBe(cache.texture(HASH, 'detail'))
    expect(changed).toHaveBeenCalled()
    // The display copy is let go once the detail is in, and disposed after its grace.
    timers.advance(1000)
    expect(cache.texture(HASH, 'display')).toBeNull()

    // Back to display: loaded afresh (it was dropped), the detail copy kept meanwhile.
    slot.set(7, HASH, 'display')
    expect(slot.texture()).toBe(cache.texture(HASH, 'detail'))
    slot.dispose()
    timers.advance(1000)
    expect(cache.size()).toBe(0)
  })

  it('draws nothing for an image that is missing, rather than the copy it replaced', async () => {
    const loader = controlledLoader()
    const cache = createPaintTextureCache({ load: loader.load })
    const slot = createPaintSlot(cache, () => {})
    slot.set(7, HASH, 'display')
    loader.finish(url(HASH, 'display'), fakeImage('display'))
    await flush()
    slot.set(7, HASH, 'detail')
    loader.finish(url(HASH, 'detail'), null)
    await flush()
    expect(slot.texture()).toBeNull()
  })

  it('lets a replaced image go at once, and holds nothing with no image', async () => {
    const loader = controlledLoader()
    const timers = manualTimers()
    const cache = createPaintTextureCache({ load: loader.load, graceMs: 1, ...timers })
    const slot = createPaintSlot(cache, () => {})
    slot.set(7, HASH, 'display')
    loader.finish(url(HASH, 'display'), fakeImage('a'))
    await flush()
    slot.set(7, OTHER, 'display')
    expect(slot.texture()).toBeNull()
    timers.advance(1)
    expect(cache.state(HASH, 'display')).toBeNull()
    slot.set(7, undefined, 'display')
    timers.advance(1)
    expect(cache.size()).toBe(0)
  })
})
