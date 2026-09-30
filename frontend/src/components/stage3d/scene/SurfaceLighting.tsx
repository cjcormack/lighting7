import { createContext, useContext, useEffect, useMemo, useState } from 'react'
import type { ShaderMaterial } from 'three'
import { makeLightTexture, makeSurfaceMaterial, makeSurfaceUniforms, type SurfaceMaterialOptions, type SurfaceUniforms } from './surfaceShader'
import type { PartFinish } from './sceneParts'

/**
 * One canvas's surface lighting: the light texture, the uniforms every receiver shares, and the
 * float array `StageEmitters` packs the light table into. **Per canvas, never per module**: the
 * Positions panel's plan and the Stage route's view are two canvases — two WebGL contexts — mounted
 * at once, and one texture written by both rigs would light each with the other's frame.
 */
export interface SurfaceLighting {
  uniforms: SurfaceUniforms
  /** The texture's backing array: `StageEmitters` packs into it and flags the texture. */
  data: Float32Array
}

const SurfaceLightingContext = createContext<SurfaceLighting | null>(null)

export function SurfaceLightingProvider({ children }: { children: React.ReactNode }) {
  const [lighting] = useState<SurfaceLighting>(() => {
    const { texture, data } = makeLightTexture()
    return { uniforms: makeSurfaceUniforms(texture), data }
  })
  useEffect(() => () => lighting.uniforms.uLights.value.dispose(), [lighting])
  return <SurfaceLightingContext.Provider value={lighting}>{children}</SurfaceLightingContext.Provider>
}

/** This canvas's surface lighting. Throws outside a `SurfaceLightingProvider`: a surface with no lights is a bug. */
export function useSurfaceLighting(): SurfaceLighting {
  const lighting = useContext(SurfaceLightingContext)
  if (lighting == null) throw new Error('useSurfaceLighting outside a SurfaceLightingProvider')
  return lighting
}

/** A receiver material for [finish] on this canvas, rebuilt when the finish changes, disposed on unmount. */
export function useSurfaceMaterial(finish: PartFinish, options: SurfaceMaterialOptions = {}): ShaderMaterial {
  const { uniforms } = useSurfaceLighting()
  const { colour, pattern, emissive } = finish
  const { doubleSided, opacity, catchOnly } = options
  const material = useMemo(
    () => makeSurfaceMaterial(uniforms, { colour, pattern, emissive }, { doubleSided, opacity, catchOnly }),
    [uniforms, colour, pattern, emissive, doubleSided, opacity, catchOnly],
  )
  useEffect(() => () => material.dispose(), [material])
  return material
}
