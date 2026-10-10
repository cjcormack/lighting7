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
import { useStageInvalidate } from '../stageInvalidate'
import { applyWorkLights, DEFAULT_WORK_LIGHTS, WORK_LIGHT_LEVELS, type WorkLightLevels, type WorkLights } from './workLights'

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
const WorkLightsContext = createContext<WorkLightLevels>(WORK_LIGHT_LEVELS[DEFAULT_WORK_LIGHTS])

export function SurfaceLightingProvider({
  workLights = DEFAULT_WORK_LIGHTS,
  children,
}: {
  /**
   * This canvas's work lights (`workLights.ts`): the window's for the Stage view and the Positions
   * plan, the request's for a `render_view` capture.
   */
  workLights?: WorkLights
  children: React.ReactNode
}) {
  const levels = WORK_LIGHT_LEVELS[workLights]
  const [lighting] = useState<SurfaceLighting>(() => {
    const { texture, data } = makeLightTexture()
    const occlusion = makeOcclusionTextures()
    const uniforms = makeSurfaceUniforms(texture, occlusion)
    // Written before the first frame, so a canvas mounted with work lights on never draws one off.
    applyWorkLights(uniforms, levels)
    return { uniforms, data, occlusion }
  })
  // A switch is a uniform write, not an R3F prop: nothing recompiles, and the demand canvas has to
  // be asked for the frame that shows it.
  const invalidate = useStageInvalidate()
  useEffect(() => {
    applyWorkLights(lighting.uniforms, levels)
    invalidate()
  }, [lighting, levels, invalidate])
  useEffect(
    () => () => {
      lighting.uniforms.uLights.value.dispose()
      lighting.occlusion.colliders.dispose()
      lighting.occlusion.lists.dispose()
    },
    [lighting],
  )
  return (
    <SurfaceLightingContext.Provider value={lighting}>
      <WorkLightsContext.Provider value={levels}>{children}</WorkLightsContext.Provider>
    </SurfaceLightingContext.Provider>
  )
}

/** This canvas's work-light levels: what the housings' own fill follows (`bodies/StageBodies.tsx`). */
export function useWorkLightLevels(): WorkLightLevels {
  return useContext(WorkLightsContext)
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
 * stable across builds (`FINISH_LOBES`' presets are). The finish's `paint` does not key it: whether
 * the part is painted is `options.painted`, and its images are bound by `setPaintTextures`. Nor does
 * its `translucent` τ or a net's thread share and gather: how it is seen through is `options.draw`, a
 * define, and those numbers are uniforms (`setTranslucency`, `setScrimNet`).
 */
export function useSurfaceMaterial(finish: PartFinish, options: SurfaceMaterialOptions = {}): ShaderMaterial {
  const { uniforms } = useSurfaceLighting()
  const { colour, pattern, emissive, lobes } = finish
  const { doubleSided, opacity, fill, catchOnly, behind, pleat, painted, draw } = options
  const material = useMemo(
    () => makeSurfaceMaterial(uniforms, { colour, pattern, emissive, lobes }, { doubleSided, opacity, fill, catchOnly, behind, pleat, painted, draw }),
    [uniforms, colour, pattern, emissive, lobes, doubleSided, opacity, fill, catchOnly, behind, pleat, painted, draw],
  )
  useEffect(() => () => material.dispose(), [material])
  return material
}
