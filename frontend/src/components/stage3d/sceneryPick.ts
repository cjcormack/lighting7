import type { StageElementDto } from '../../api/stageElementApi'
import { isSceneryPickable } from '../../lib/scenery'
import { elementColliders } from './scene/beamReach'
import { elementBaseZ, type ElementBuild } from './scene/sceneParts'
import { DRAG_PX_THRESHOLD } from './dragThreshold'

/**
 * Clicking a piece of scenery in the Stage view (scenery-programmer plan D11), pure: which presses
 * count, which element a press lands on, and where the popover it opens is anchored. `Stage3D`
 * wires it; `sceneryPick.test.ts` pins it without a canvas.
 *
 * **The order is fixture, then rigging, then element**, and it is the scene's own: a fixture's hit
 * proxy and a rigging bar take R3F clicks (and stop them), the scene's surfaces stay deaf to R3F
 * (`StageSceneElements` — a click meant for a fixture must never land on a wall in front of it), and
 * only a click R3F hands to `onPointerMissed` — nothing with a handler under it — is cast against
 * the drawn elements here. So a fixture behind a closed tab is still the fixture's, as it always
 * was, and the tab is only what is clicked when nothing of the rig is.
 */

/** What a press at the canvas was, by its two ends: a click is one that barely moved. */
export interface ScenePress {
  button: number
  down: { x: number; y: number } | null
  up: { x: number; y: number }
}

/**
 * Whether [press] may pick scenery: the primary button, and released within [DRAG_PX_THRESHOLD] of
 * where it went down — **a drag or a finger pan is never a click**, so orbiting, panning a section
 * or turning the eye cannot open the popover. A release with no recorded press (a pointer that went
 * down outside the canvas) is not a click either.
 */
export function pressPicks(press: ScenePress): boolean {
  if (press.button !== 0 || press.down == null) return false
  return Math.abs(press.up.x - press.down.x) <= DRAG_PX_THRESHOLD && Math.abs(press.up.y - press.down.y) <= DRAG_PX_THRESHOLD
}

/** One surface a press's ray met: the element it belongs to and how far along the ray. */
export interface SceneHit {
  elementUuid: string
  distance: number
}

/**
 * The element a press picks: the **nearest** surface the ray met, if its element opens the popover
 * (`isSceneryPickable`); otherwise none. A wall in front of a piece hides it, as it hides it from the
 * eye — the ray stops at what is drawn first — and only front faces count (the surface materials
 * are single-sided), so a room's inward-facing near wall is seen through from outside, as drawn.
 */
export function pickedScenery(
  hits: readonly SceneHit[],
  elements: ReadonlyMap<string, Pick<StageElementDto, 'kind' | 'params' | 'layer'>>,
): string | null {
  let nearest: SceneHit | null = null
  for (const hit of hits) {
    if (!elements.has(hit.elementUuid)) continue
    if (nearest == null || hit.distance < nearest.distance) nearest = hit
  }
  if (nearest == null) return null
  const element = elements.get(nearest.elementUuid)!
  return isSceneryPickable(element) ? nearest.elementUuid : null
}

/** A piece's box in three.js world space: its centre and its half-extents along x, y and z. */
export interface AnchorBox {
  centre: { x: number; y: number; z: number }
  half: { x: number; y: number; z: number }
}

/**
 * Where a piece is, for the popover's anchor: the box round what is drawn of it, in three.js world
 * space — its parts' boxes together, so a flown piece's anchor flies with it and a drawn tab's sits
 * between its halves. The label layer projects its centre and corners each frame, so the popover
 * sits beside the piece rather than on it. A piece with nothing drawn (hidden, or not built yet)
 * falls back to its own box above its base, so a popover whose piece the operator has just hidden
 * stays where the piece was.
 */
export function elementAnchorBox(
  element: Pick<StageElementDto, 'kind' | 'params' | 'positionX' | 'positionY' | 'positionZ' | 'yawDeg' | 'widthM' | 'depthM' | 'heightM'>,
  build: ElementBuild | null,
): AnchorBox {
  const colliders = build == null ? [] : elementColliders(element, { parts: build.parts.map((p) => ({ ...p, light: 'solid' as const })), seats: [] })
  if (colliders.length === 0) {
    // Lighting (x, y, z) → three (x, z, −y); the box unturned, which is enough for an anchor.
    const reach = Math.hypot(element.widthM, element.depthM) / 2
    return {
      centre: { x: element.positionX, y: elementBaseZ(element) + element.heightM / 2, z: -element.positionY },
      half: { x: reach, y: element.heightM / 2, z: reach },
    }
  }
  let minX = Infinity
  let minY = Infinity
  let minZ = Infinity
  let maxX = -Infinity
  let maxY = -Infinity
  let maxZ = -Infinity
  for (const c of colliders) {
    // The box is turned about y; its bounding circle there is enough for an anchor.
    const r = Math.hypot(c.hx, c.hz)
    minX = Math.min(minX, c.cx - r)
    maxX = Math.max(maxX, c.cx + r)
    minZ = Math.min(minZ, c.cz - r)
    maxZ = Math.max(maxZ, c.cz + r)
    minY = Math.min(minY, c.cy - c.hy)
    maxY = Math.max(maxY, c.cy + c.hy)
  }
  return {
    centre: { x: (minX + maxX) / 2, y: (minY + maxY) / 2, z: (minZ + maxZ) / 2 },
    half: { x: (maxX - minX) / 2, y: (maxY - minY) / 2, z: (maxZ - minZ) / 2 },
  }
}
