import { useLayoutEffect, useRef, useState } from 'react'
import type { Texture } from 'three'
import { createPaintSlot, paintTextures, type PaintSlot, type PaintTextureCache, type PaintVariant } from './paintTextures'

/**
 * One painted face's texture (scrim plan D4, D12): [hash]'s [variant] from the module-level cache
 * (`paintTextures.ts`), null while it loads, when it is missing, or with no image. A switch of
 * variant keeps the old copy until the new one is in. Held in layout effects, so a `render_view`
 * capture's scene has asked for every image by the time it reports ready, and waits for them.
 */
export function usePaintTexture(
  projectId: number | null,
  hash: string | undefined,
  variant: PaintVariant,
  cache: PaintTextureCache = paintTextures,
): Texture | null {
  const [texture, setTexture] = useState<Texture | null>(null)
  const slotRef = useRef<PaintSlot | null>(null)
  useLayoutEffect(() => {
    const slot: PaintSlot = createPaintSlot(cache, () => setTexture(slot.texture()))
    slotRef.current = slot
    return () => {
      slot.dispose()
      slotRef.current = null
    }
  }, [cache])
  useLayoutEffect(() => {
    const slot = slotRef.current
    if (slot == null) return
    slot.set(projectId, hash, variant)
    setTexture(slot.texture())
  }, [cache, projectId, hash, variant])
  return texture
}
