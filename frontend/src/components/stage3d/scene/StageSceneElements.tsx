import { memo, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { useThree, type ThreeEvent } from '@react-three/fiber'
import {
  BoxGeometry,
  BufferGeometry,
  Color,
  ExtrudeGeometry,
  Float32BufferAttribute,
  CylinderGeometry,
  InstancedMesh,
  LatheGeometry,
  MathUtils,
  Matrix4,
  Path,
  PlaneGeometry,
  Quaternion,
  Shape,
  SphereGeometry,
  Vector2,
  Vector3,
} from 'three'
import type { StageElementDto } from '../../../api/stageElementApi'
import { toThree } from '../../../lib/stageCoords'
import { seatingParams } from '../../../lib/stageSeats'
import { BANQUET_FRAME_COLOUR, chairGeometry } from './chairs'
import { NO_RAYCAST } from '../raycast'
import { useSurfaceMaterial } from './SurfaceLighting'
import {
  PAINT_FACE_ATTRIBUTE,
  setPaintTextures,
  setPleatAmplitude,
  setPleatShift,
  setScrimNet,
  setTranslucency,
} from './surfaceShader'
import { surfaceDrawOf, surfaceRenderOrder } from './seeThrough'
import { pleatOffset, pleatShift, pleatSlope } from './pleat'
import type { PaintVariant } from './paintTextures'
import { usePaintTexture } from './usePaintTexture'
import { useStageInvalidate } from '../stageInvalidate'
import {
  elementBaseZ,
  elementFinish,
  finishLobes,
  type ElementBuild,
  type PartGeometry,
  type PartTexture,
  type PartTransform,
  type PartUv,
  type ScenePart,
} from './sceneParts'

/** The `userData` key an element's group carries its uuid under, for the scenery pick. */
export const SCENE_ELEMENT_UUID = 'sceneElementUuid'

/** One element's build, beside the element it was built from — what the scene draws and casts beams at. */
export interface SceneBuild {
  element: StageElementDto
  build: ElementBuild
}

/** *Sit in a seat…* while it is armed: seats take the pointer, and a click answers the seat. */
export interface SeatPicking {
  onPick: (elementUuid: string, seatId: string) => void
  onHover?: (seat: { elementUuid: string; seatId: string } | null) => void
}

/**
 * The scene document drawn (stage-view plan session 3): each element's parts, placed at its origin
 * and turned by its yaw, every surface on the light-array receiver (`surfaceShader.ts`). Deaf to the
 * pointer — a click meant for a fixture, a region or a rigging must never land on a wall in front of
 * it — except the seats while *Sit in a seat…* is armed. A click on scenery is `Stage3D`'s own
 * cast, made only once R3F has found nothing of the rig under the pointer (`sceneryPick.ts`).
 *
 * Nothing here moves per frame, so nothing here asks for one: a new element list, a layer toggled
 * or a tab drawn is a new prop, and R3F draws it. A painted cloth's image is the exception — it
 * arrives after the part has drawn (`paintTextures.ts`), and its part asks for the frame that shows it.
 */
export const StageSceneElements = memo(function StageSceneElements({
  projectId,
  builds,
  seatPicking,
}: {
  /** The project the elements are this canvas's, whose scene images their paint names. */
  projectId: number | null
  builds: readonly SceneBuild[]
  seatPicking?: SeatPicking | null
}) {
  return (
    <group>
      {builds.map(({ element, build }) =>
        build.parts.length === 0 && build.seats.length === 0 ? null : (
          <group
            key={element.uuid}
            // What `Stage3D`'s scenery pick reads back off a surface its ray met (`ScenePicker`): the
            // meshes stay deaf to R3F, and the pick casts against them itself.
            userData={{ [SCENE_ELEMENT_UUID]: element.uuid }}
            position={toThree(element.positionX, element.positionY, elementBaseZ(element))}
            rotation={[0, MathUtils.degToRad(element.yawDeg), 0]}
          >
            {build.parts.map((part) => (
              <ScenePartMesh
                key={part.key}
                part={part}
                projectId={projectId}
                variant={element.fullDetail === true ? 'detail' : 'display'}
              />
            ))}
          </group>
        ),
      )}
      {builds.map(({ element, build }) =>
        build.seats.length === 0 ? null : (
          <SeatingMesh key={`${element.uuid}:seats`} element={element} build={build} picking={seatPicking ?? null} />
        ),
      )}
    </group>
  )
})

/** Segments a mean pitch: enough that a 2× fold reads as a curve rather than a zig-zag. */
const PLEAT_SEGMENTS = 10

/**
 * A part's geometry in its element's three.js frame (x across, y up, z towards the house), centred
 * on the origin: the part's `at` places it. With [uv] — a painted part's, scrim plan D4 — it carries
 * where each vertex lands on the element's images, and which face it is on
 * ([PAINT_FACE_ATTRIBUTE]): a cloth's one sheet is the downstage face, its back drawn by facing; a
 * box's downstage and upstage faces are painted and its edges are not.
 *
 * A model part ([options]) may carry a [PartTexture] — uvs on every face by its mode, every face
 * painted with the front image ([textureUvs]) — and a [PartTransform], its turn and its fit's scale,
 * baked into the vertices (and the normals, by their own matrix) after the uvs, so a tile is the
 * model's own size and a scaled part's normals stay true.
 */
export function partGeometry(
  geometry: PartGeometry,
  uv?: PartUv,
  options: { texture?: PartTexture; transform?: PartTransform } = {},
): BufferGeometry {
  const g = shapeGeometry(geometry)
  if (uv != null) paintUvs(g, geometry, uv)
  else if (options.texture != null) textureUvs(g, geometry, options.texture)
  if (options.transform != null) g.applyMatrix4(threeTransform(options.transform))
  return g
}

/**
 * [m], a map in the lighting frame (x across, y upstage, z up), as the same map in three's
 * (x across, y up, z towards the house): `B · m · Bᵀ` with B taking lighting (x, y, z) to three's
 * (x, z, −y).
 */
export function threeTransform(m: PartTransform): Matrix4 {
  const [l0, l1, l2, l3, l4, l5, l6, l7, l8] = m
  return new Matrix4().set(
    l0, l2, -l1, 0,
    l6, l8, -l7, 0,
    -l3, -l5, l4, 0,
    0, 0, 0, 1,
  )
}

/**
 * A model part's uvs for its [texture], in its own frame before any turn or scale, and every face
 * painted with the front image. `STRETCH` keeps each shape's own uvs — the whole image on each of a
 * box's faces, round a cylinder, a sphere or a lathe and across each cap — except an outline's,
 * which is laid over its extent as seen from downstage (mirrored on its back, so it reads from
 * behind). `TILE` measures them in the model's units, one repeat every `tileM`: a box's and an
 * outline's faces by the plane they lie in, a round shape's along and around it.
 */
function textureUvs(g: BufferGeometry, geometry: PartGeometry, texture: PartTexture) {
  const pos = g.attributes.position
  const nor = g.attributes.normal
  const uvAttr = g.attributes.uv
  const uvs = new Float32Array(pos.count * 2)
  const tile = texture.mode === 'TILE' ? texture.tileM : null
  g.computeBoundingBox()
  const box = g.boundingBox!
  const bw = Math.max(1e-6, box.max.x - box.min.x)
  const bh = Math.max(1e-6, box.max.y - box.min.y)
  // The arc length up a lathe's profile, for a tiled lathe's v.
  const profileLength =
    geometry.shape === 'lathe'
      ? geometry.profile.reduce((sum, p, i) => (i === 0 ? 0 : sum + Math.hypot(p[0] - geometry.profile[i - 1][0], p[1] - geometry.profile[i - 1][1])), 0)
      : 0
  for (let i = 0; i < pos.count; i++) {
    const x = pos.getX(i)
    const y = pos.getY(i)
    const z = pos.getZ(i)
    const nx = nor.getX(i)
    const ny = nor.getY(i)
    const nz = nor.getZ(i)
    let u = uvAttr != null ? uvAttr.getX(i) : 0
    let v = uvAttr != null ? uvAttr.getY(i) : 0
    switch (geometry.shape) {
      case 'box':
      case 'extrude': {
        const planar = geometry.shape === 'extrude' || tile != null
        if (!planar) break
        // The plane the face lies in, by its normal's largest component.
        const ax = Math.abs(nx), ay = Math.abs(ny), az = Math.abs(nz)
        let a: number, b: number
        if (az >= ax && az >= ay) [a, b] = [nz >= 0 ? x : -x, y]
        else if (ax >= ay) [a, b] = [nx >= 0 ? -z : z, y]
        else [a, b] = [x, ny >= 0 ? -z : z]
        if (tile != null) {
          ;[u, v] = [a / tile, b / tile]
        } else {
          // An outline stretched: over its extent from downstage, mirrored on its back face.
          const across = (x - box.min.x) / bw
          ;[u, v] = [nz < -0.5 ? 1 - across : across, (y - box.min.y) / bh]
        }
        break
      }
      case 'cylinder': {
        if (tile == null) break
        if (Math.abs(ny) > 0.99) {
          // A cap: its own uv is the unit disc, so the radius in metres.
          const r = Math.max(geometry.rTop, geometry.rBottom)
          ;[u, v] = [((u - 0.5) * 2 * r) / tile, ((v - 0.5) * 2 * r) / tile]
        } else {
          ;[u, v] = [(u * Math.PI * (geometry.rTop + geometry.rBottom)) / tile, (v * geometry.h) / tile]
        }
        break
      }
      case 'sphere':
        if (tile != null) [u, v] = [(u * Math.PI * Math.max(geometry.w, geometry.d)) / tile, (v * Math.PI * geometry.h) / 2 / tile]
        break
      case 'lathe':
        if (tile != null) {
          const r = Math.max(...geometry.profile.map((p) => p[0]))
          ;[u, v] = [(u * 2 * Math.PI * r) / tile, (v * profileLength) / tile]
        }
        break
      default:
        break
    }
    uvs[i * 2] = u
    uvs[i * 2 + 1] = v
  }
  g.setAttribute('uv', new Float32BufferAttribute(uvs, 2))
  g.setAttribute(PAINT_FACE_ATTRIBUTE, new Float32BufferAttribute(new Float32Array(pos.count).fill(1), 1))
}

/**
 * A lathe's profile in the order three draws facing out: up its outer side, so the profile closed
 * along the axis runs anticlockwise in (radius, z). A profile written top first is turned round
 * rather than drawn inside out.
 */
export function outwardProfile(profile: readonly (readonly [number, number])[]): (readonly [number, number])[] {
  const closed = [...profile, [0, profile[profile.length - 1][1]] as const, [0, profile[0][1]] as const]
  let area = 0
  for (let i = 0; i < closed.length; i++) {
    const p = closed[i]
    const q = closed[(i + 1) % closed.length]
    area += p[0] * q[1] - q[0] * p[1]
  }
  return area < 0 ? [...profile].reverse() : [...profile]
}

/**
 * Write [uv] onto [g]: each vertex's x across and z up the part, from −w/2…w/2 and −h/2…h/2, onto
 * the image's `u0…u1` and `v0…v1` as seen from downstage. The fold of a pleat moves its vertices out
 * of the plane, never across it, so the image lies on the pleats as it would on the flat cloth.
 */
function paintUvs(g: BufferGeometry, geometry: PartGeometry, uv: PartUv) {
  const size =
    geometry.shape === 'box' || geometry.shape === 'pleat' || geometry.shape === 'sheet' ? { w: geometry.w, h: geometry.h } : null
  const pos = g.attributes.position
  const nor = g.attributes.normal
  const uvs = new Float32Array(pos.count * 2)
  const faces = new Float32Array(pos.count)
  for (let i = 0; i < pos.count; i++) {
    if (size == null) continue
    uvs[i * 2] = uv.u0 + (pos.getX(i) / size.w + 0.5) * (uv.u1 - uv.u0)
    uvs[i * 2 + 1] = uv.v0 + (pos.getY(i) / size.h + 0.5) * (uv.v1 - uv.v0)
    // three's +z is downstage: a box's downstage face is painted with the front image, its upstage
    // face with the back, its edges with neither. A cloth is one sheet facing downstage.
    const nz = nor.getZ(i)
    faces[i] = geometry.shape === 'box' ? (nz > 0.5 ? 1 : nz < -0.5 ? -1 : 0) : 1
  }
  g.setAttribute('uv', new Float32BufferAttribute(uvs, 2))
  g.setAttribute(PAINT_FACE_ATTRIBUTE, new Float32BufferAttribute(faces, 1))
}

function shapeGeometry(geometry: PartGeometry): BufferGeometry {
  switch (geometry.shape) {
    case 'box':
      return new BoxGeometry(geometry.w, geometry.h, geometry.d)
    case 'cylinder':
      return new CylinderGeometry(geometry.rTop, geometry.rBottom, geometry.h, 24)
    case 'disc': {
      const g = new CylinderGeometry(geometry.r, geometry.r, geometry.d, 40)
      g.rotateX(Math.PI / 2)
      return g
    }
    case 'sphere': {
      // three's y is the lighting z (height), its z the lighting y (depth).
      const g = new SphereGeometry(0.5, 32, 16)
      g.scale(geometry.w, geometry.h, geometry.d)
      return g
    }
    case 'extrude': {
      // The outline's (x, z) is three's (x, y); extruded along three's z (towards the house), centred.
      const shape = new Shape(geometry.outline.map(([x, z]) => new Vector2(x, z)))
      for (const hole of geometry.holes) shape.holes.push(new Path(hole.map(([x, z]) => new Vector2(x, z))))
      const g = new ExtrudeGeometry(shape, { depth: geometry.d, bevelEnabled: false })
      g.translate(0, 0, -geometry.d / 2)
      return g
    }
    case 'lathe':
      // Revolved about three's y, which is the lighting z.
      return new LatheGeometry(outwardProfile(geometry.profile).map(([r, z]) => new Vector2(r, z)), geometry.segments)
    case 'pleat': {
      const pleat = geometry.pleat
      const shift = pleatShift(geometry.w, geometry.anchor)
      const segments = Math.max(8, Math.round((geometry.w / pleat.pitchM) * PLEAT_SEGMENTS))
      const g = new PlaneGeometry(geometry.w, geometry.h, segments, 1)
      const pos = g.attributes.position
      const nor = g.attributes.normal
      for (let i = 0; i < pos.count; i++) {
        const x = pos.getX(i) + shift
        pos.setZ(i, pleatOffset(x, pleat))
        // The fold's own normal, not the facets': the surface shader shades with it as well.
        const slope = pleatSlope(x, pleat)
        const len = Math.hypot(slope, 1)
        nor.setXYZ(i, -slope / len, 0, 1 / len)
      }
      return g
    }
    case 'sheet':
      // Flat cloth, facing downstage and drawn from both sides by its material.
      return new PlaneGeometry(geometry.w, geometry.h)
    case 'quad': {
      // A PlaneGeometry faces +z (three) — downstage, lighting −y — before it is turned.
      const g = new PlaneGeometry(geometry.w, geometry.h)
      switch (geometry.facing) {
        case 'downstage':
          break
        case 'upstage':
          g.rotateY(Math.PI)
          break
        case 'up':
          g.rotateX(-Math.PI / 2)
          break
        case 'down':
          g.rotateX(Math.PI / 2)
          break
        case 'left':
          g.rotateY(Math.PI / 2)
          break
        case 'right':
          g.rotateY(-Math.PI / 2)
          break
      }
      return g
    }
  }
}

function geometryKey(g: PartGeometry): string {
  return JSON.stringify(g)
}

function ScenePartMesh({
  part,
  projectId,
  variant,
}: {
  part: ScenePart
  projectId: number | null
  variant: PaintVariant
}) {
  const paint = part.finish.paint
  const uv = paint != null ? part.uv : undefined
  // A model part's texture is drawn as a front paint on every face, its uvs its own (`textureUvs`).
  const texture = uv == null ? part.finish.texture : undefined
  const painted = uv != null || texture != null
  const transform = part.transform
  const key = geometryKey(part.geometry) + JSON.stringify([uv ?? null, texture?.mode ?? null, texture?.tileM ?? null, transform ?? null])
  // Keyed on the shape's numbers, not the part object, which every build makes afresh.
  // eslint-disable-next-line react-hooks/exhaustive-deps -- `key` is `part.geometry`, `uv`, the texture's mode and tile and `transform` serialised whole
  const geometry = useMemo(() => partGeometry(part.geometry, uv, { texture, transform }), [key])
  useEffect(() => () => geometry.dispose(), [geometry])
  // The fold's pitch and wander alone key the material: a drawn half's width moves every frame of a
  // draw, and with it only its shift and — for cloth that hangs flat — its depth, both uniforms.
  const fold = part.geometry.shape === 'pleat' ? part.geometry.pleat : null
  const pleatKey = fold != null ? JSON.stringify([fold.pitchM, fold.warp]) : ''
  // eslint-disable-next-line react-hooks/exhaustive-deps -- `pleatKey` is the fold's pitch and wander serialised
  const pleat = useMemo(() => fold ?? undefined, [pleatKey])
  // How it is seen through (scrim plan session 4): a define, so decided from the part's fabric —
  // its light and its τ — and never switched per frame. The net's numbers and τ are uniforms.
  const draw = surfaceDrawOf(part.light, part.finish.translucent)
  const material = useSurfaceMaterial(part.finish, {
    doubleSided: pleat != null || part.geometry.shape === 'sheet',
    pleat,
    painted,
    tiled: texture?.mode === 'TILE',
    draw,
  })
  const shift = part.geometry.shape === 'pleat' ? pleatShift(part.geometry.w, part.geometry.anchor) : 0
  const amplitude = fold?.amplitudeM ?? 0
  useLayoutEffect(() => {
    if (pleat == null) return
    setPleatShift(material, shift)
    setPleatAmplitude(material, amplitude)
  }, [material, pleat, shift, amplitude])
  // A net's thread share and its gather (which a draw moves every frame), and a muslin's τ: uniform
  // writes, so this asks for the frame that shows them.
  const net = typeof part.light === 'object' && part.light.kind === 'angle' ? part.light : null
  const netR = net?.r ?? 0
  const netGather = net?.gather ?? 1
  const tau = part.finish.translucent
  const invalidate = useStageInvalidate()
  useLayoutEffect(() => {
    if (draw === 'scrim') setScrimNet(material, netR, netGather)
    if (draw === 'translucent' && tau != null) setTranslucency(material, tau)
    if (draw !== 'opaque') invalidate()
  }, [material, draw, netR, netGather, tau, invalidate])
  // The images, loaded once by the cache and bound when they arrive: a uniform write, so this asks
  // for the frame that shows it.
  const front = usePaintTexture(painted ? projectId : null, paint?.front ?? texture?.image, variant)
  const back = usePaintTexture(painted ? projectId : null, paint?.back, variant)
  useLayoutEffect(() => {
    if (!painted) return
    setPaintTextures(material, front, back)
    invalidate()
  }, [material, painted, front, back, invalidate])
  return (
    <mesh
      geometry={geometry}
      material={material}
      position={[part.at.x, part.at.z, -part.at.y]}
      // A scrim blends after the opaque surfaces and before the beams, so the haze lands on top of it.
      renderOrder={surfaceRenderOrder(draw)}
      raycast={NO_RAYCAST}
    />
  )
}

// — seats ————————————————————————————————————————————————————————————————————————

const WHITE = new Color(1, 1, 1)
/** The seat under the pointer while picking: lit up, so the operator can see which one a click takes. */
const HOVER_TINT = new Color(2.2, 2.2, 1.4)
const SCRATCH_MATRIX = new Matrix4()
const SCRATCH_POS = new Vector3()
const SCRATCH_QUAT = new Quaternion()
const UNIT_SCALE = new Vector3(1, 1, 1)
const UP = new Vector3(0, 1, 0)
/** An instanced mesh's own raycast, put back while picking — never `undefined`, which R3F would assign. */
const INSTANCED_RAYCAST = InstancedMesh.prototype.raycast

/**
 * A seating block's seats, instanced: one draw call per chair part however many rows. Each instance
 * is a seat of `lib/stageSeats.ts`'s list at its base, turned with the block; the instance index is
 * the seat's place in that list, which is what a pick reads back. The pads are the element's finish;
 * the frame is plain, glows with the element, and is the seating's `frameColour` — absent, a banquet
 * chair's gold, or a theatre seat's own finish colour. Only the pads take the pointer: one mesh per
 * pick, so crossing from a chair's cushion to its frame is not an out and an over.
 */
function SeatingMesh({
  element,
  build,
  picking,
}: {
  element: StageElementDto
  build: ElementBuild
  picking: SeatPicking | null
}) {
  const params = seatingParams(element)
  const seatPitch = params?.seatPitchM ?? 0.5
  const chair = params?.chair ?? 'THEATRE'
  const geometry = useMemo(() => chairGeometry(chair, seatPitch), [chair, seatPitch])
  useEffect(
    () => () => {
      geometry.pads.dispose()
      geometry.frame.dispose()
    },
    [geometry],
  )
  const finish = elementFinish(element, 'seat')
  const frameColour = params?.frameColour ?? (chair === 'BANQUET' ? BANQUET_FRAME_COLOUR : finish.colour)
  const padMaterial = useSurfaceMaterial(finish)
  const frameMaterial = useSurfaceMaterial({ colour: frameColour, pattern: 'PLAIN', emissive: finish.emissive, lobes: finishLobes('SEATING', null, 'frame') })
  const count = build.seats.length
  const [pads, setPads] = useState<InstancedMesh | null>(null)
  const [frame, setFrame] = useState<InstancedMesh | null>(null)
  const invalidate = useThree((s) => s.invalidate)
  const hovered = useRef(-1)

  useLayoutEffect(() => {
    if (pads == null || frame == null) return
    SCRATCH_QUAT.setFromAxisAngle(UP, MathUtils.degToRad(element.yawDeg))
    build.seats.forEach((seat, i) => {
      toThree(seat.base.x, seat.base.y, seat.base.z, SCRATCH_POS)
      SCRATCH_MATRIX.compose(SCRATCH_POS, SCRATCH_QUAT, UNIT_SCALE)
      pads.setMatrixAt(i, SCRATCH_MATRIX)
      frame.setMatrixAt(i, SCRATCH_MATRIX)
    })
    for (const mesh of [pads, frame]) {
      mesh.instanceMatrix.needsUpdate = true
      mesh.computeBoundingSphere()
    }
    hovered.current = -1
    tintSeats([pads, frame], build.seats.map((_, i) => i), WHITE)
    invalidate()
  }, [pads, frame, build, element.yawDeg, invalidate])

  const setHover = (index: number) => {
    if (index === hovered.current) return
    if (hovered.current >= 0 && hovered.current < count) tintSeats([pads, frame], [hovered.current], WHITE)
    if (index >= 0) tintSeats([pads, frame], [index], HOVER_TINT)
    hovered.current = index
    invalidate()
    picking?.onHover?.(index >= 0 ? { elementUuid: element.uuid, seatId: build.seats[index].id } : null)
  }
  // Picking switched off with a seat lit: put it back.
  useEffect(() => {
    if (picking == null && hovered.current >= 0) {
      if (hovered.current < count) tintSeats([pads, frame], [hovered.current], WHITE)
      hovered.current = -1
      invalidate()
    }
  }, [picking, pads, frame, count, invalidate])

  const onMove = (e: ThreeEvent<PointerEvent>) => {
    e.stopPropagation()
    setHover(e.instanceId ?? -1)
  }
  const onOut = () => setHover(-1)
  const onClick = (e: ThreeEvent<MouseEvent>) => {
    e.stopPropagation()
    const seat = e.instanceId != null ? build.seats[e.instanceId] : undefined
    if (seat != null) picking?.onPick(element.uuid, seat.id)
  }

  return (
    <>
      {/* A new count is a new mesh: an InstancedMesh's buffers are sized once. */}
      <instancedMesh
        key={`pads:${count}`}
        ref={setPads}
        args={[geometry.pads, padMaterial, count]}
        frustumCulled={false}
        raycast={picking ? INSTANCED_RAYCAST : NO_RAYCAST}
        onPointerMove={picking ? onMove : undefined}
        onPointerOut={picking ? onOut : undefined}
        onClick={picking ? onClick : undefined}
      />
      <instancedMesh
        key={`frame:${count}`}
        ref={setFrame}
        args={[geometry.frame, frameMaterial, count]}
        frustumCulled={false}
        raycast={NO_RAYCAST}
      />
    </>
  )
}

/** Tint [indices] of every mesh in [meshes] that has mounted. */
function tintSeats(meshes: readonly (InstancedMesh | null)[], indices: readonly number[], colour: Color) {
  for (const mesh of meshes) {
    if (mesh == null) continue
    for (const i of indices) mesh.setColorAt(i, colour)
    if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true
  }
}
