import { useFrame } from '@react-three/fiber'

/**
 * The frame at which the scene is drawn: after every director (0), the bodies' flush and the
 * emitters' flush, which upload what this frame draws.
 */
export const STAGE_RENDER_PRIORITY = 1

/**
 * Draws the scene straight to the canvas — R3F stops drawing on its own once a frame subscriber has
 * a positive priority, as the two flushes do. No post-processing: every fragment encodes itself, so
 * additive beams and overlapping pools blend in display space.
 */
export function StageRender() {
  useFrame(({ gl, scene, camera }) => gl.render(scene, camera), STAGE_RENDER_PRIORITY)
  return null
}
