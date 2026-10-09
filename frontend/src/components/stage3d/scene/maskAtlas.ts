import { DataArrayTexture, NearestFilter, RedFormat, UnsignedByteType } from 'three'
import { MASK_SIZE, sceneMasks, type SceneMaskCache } from './sceneMasks'

/**
 * **The mask atlas** (scrim plan D8): [SceneMaskCache.atlas] as an `R8` `DataArrayTexture`, 256 ×
 * 256 × 32, which the surface shader's occlusion test reads with `texelFetch` where a segment
 * crosses a painted cloth (`occlusion.ts`'s `OCCLUSION_GLSL`). The bytes are the cache's own — the
 * texture wraps the same array, so beam reach on the CPU and a shadow on the GPU read one copy.
 *
 * A texture belongs to no renderer, so one serves every canvas, each uploading it to its own
 * context, as the paint textures do. A layer written by the cache is marked for upload
 * ([syncMaskAtlas]) before the cache's listeners hear of it, so the frame they ask for has it;
 * landing a mask is a texture write and a uniform, never a recompile.
 */
export function makeMaskAtlasTexture(cache: SceneMaskCache): DataArrayTexture {
  const texture = new DataArrayTexture(cache.atlas, MASK_SIZE, MASK_SIZE, cache.atlas.length / (MASK_SIZE * MASK_SIZE))
  texture.format = RedFormat
  texture.type = UnsignedByteType
  texture.magFilter = NearestFilter
  texture.minFilter = NearestFilter
  texture.generateMipmaps = false
  texture.unpackAlignment = 1
  texture.needsUpdate = true
  return texture
}

/**
 * Mark the atlas for upload when [cache] has written a layer since the last sync; false when it has
 * not. The whole atlas goes up (2 MB), never three's per-layer `addLayerUpdate`: a canvas whose first
 * upload of the texture lands while layer updates are pending uploads those layers alone into
 * zeroed storage, and a zero is a hole — a second Stage view opened as a mask arrived would let light
 * through every other cloth. A mask arrives a handful of times a session.
 */
export function syncMaskAtlas(cache: SceneMaskCache, texture: DataArrayTexture): boolean {
  if (cache.takeDirtyLayers().length === 0) return false
  texture.needsUpdate = true
  return true
}

let shared: DataArrayTexture | null = null

/**
 * The one atlas the scene's surfaces sample, over [sceneMasks]. Made on first use, and synced on
 * every change the cache announces — subscribed first, so ahead of any canvas's listener.
 */
export function getMaskAtlasTexture(): DataArrayTexture {
  if (shared == null) {
    const texture = makeMaskAtlasTexture(sceneMasks)
    shared = texture
    sceneMasks.takeDirtyLayers()
    sceneMasks.subscribe(() => {
      syncMaskAtlas(sceneMasks, texture)
    })
  }
  return shared
}
