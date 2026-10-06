import { createContext, useContext, useEffect, useMemo, useState } from 'react'
import type { ShaderMaterial } from 'three'
import {
  makeLightTexture,
  makeOcclusionTextures,
  makeSurfaceMaterial,
  makeSurfaceUniforms,
  type OcclusionTextures,
  type SurfaceMaterialOptions,
  type SurfaceUniforms,
} from './surfaceShader'
import type { PartFinish } from './sceneParts'

/**
 * One canvas's surface lighting: the light texture, the uniforms every receiver shares, the float
 * array `StageEmitters` packs the light table into, and the colliders and per-light lists the
 * surfaces' shadows are tested against. **Per canvas, never per module**: the Positions panel's plan
 * and the Stage route's view are two canvases — two WebGL contexts — mounted at once, and one
 * texture written by both rigs would light each with the other's frame.
 */
export interface SurfaceLighting {
  uniforms: SurfaceUniforms
  /** The texture's backing array: `StageEmitters` packs into it and flags the texture. */
  data: Float32Array
  /** The colliders and each light's list of them (`occlusion.ts`), which `StageEmitters` fills too. */
  occlusion: OcclusionTextures
}

const SurfaceLightingContext = createContext<SurfaceLighting | null>(null)

export function SurfaceLightingProvider({ children }: { children: React.ReactNode }) {
  const [lighting] = useState<SurfaceLighting>(() => {
    const { texture, data } = makeLightTexture()
    const occlusion = makeOcclusionTextures()
    return { uniforms: makeSurfaceUniforms(texture, occlusion), data, occlusion }
  })
  useEffect(
    () => () => {
      lighting.uniforms.uLights.value.dispose()
      lighting.occlusion.colliders.dispose()
      lighting.occlusion.lists.dispose()
    },
    [lighting],
  )
  return <SurfaceLightingContext.Provider value={lighting}>{children}</SurfaceLightingContext.Provider>
}

/** This canvas's surface lighting. Throws outside a `SurfaceLightingProvider`: a surface with no lights is a bug. */
export function useSurfaceLighting(): SurfaceLighting {
  const lighting = useContext(SurfaceLightingContext)
  if (lighting == null) throw new Error('useSurfaceLighting outside a SurfaceLightingProvider')
  return lighting
}

/**
 * A receiver material for [finish] on this canvas, rebuilt when the finish changes, disposed on
 * unmount. A `pleat` and the finish's `lobes` are compared by identity, so a caller holds them
 * stable across builds (`FINISH_LOBES`' presets are).
 */
export function useSurfaceMaterial(finish: PartFinish, options: SurfaceMaterialOptions = {}): ShaderMaterial {
  const { uniforms } = useSurfaceLighting()
  const { colour, pattern, emissive, lobes } = finish
  const { doubleSided, opacity, fill, catchOnly, behind, pleat } = options
  const material = useMemo(
    () => makeSurfaceMaterial(uniforms, { colour, pattern, emissive, lobes }, { doubleSided, opacity, fill, catchOnly, behind, pleat }),
    [uniforms, colour, pattern, emissive, lobes, doubleSided, opacity, fill, catchOnly, behind, pleat],
  )
  useEffect(() => () => material.dispose(), [material])
  return material
}
