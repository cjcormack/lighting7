import { useEffect, useLayoutEffect, useMemo, useRef } from 'react'
import { useFrame, useThree } from '@react-three/fiber'
import * as THREE from 'three'
import { lightingApi } from '@/api/lightingApi'
import { worldPositionLighting } from '@/lib/stageCoords'
import type { FixturePatch } from '@/api/patchApi'
import type { RiggingDto } from '@/api/riggingApi'
import { triggersOf, type Fixture } from '@/store/fixtures'
import {
  CONFETTI_COLOURS,
  MAX_FLAKES,
  createConfettiPool,
  muzzleDirection,
  stepConfetti,
  throwTube,
} from './confetti'

/** One flake: a paper rectangle a few centimetres across. */
const FLAKE_W = 0.03
const FLAKE_H = 0.02

/**
 * Confetti in the Stage view (stage-view plan session 9): every `effects.fired` — a real fire or a
 * rehearsed one — throws ~380 flakes from the cannon's tube, which flutter down and settle where they
 * land (`confetti.ts` is the model). Every window draws it, so a hall screen shows the burst the desk
 * fired; a rehearsal in Blind looks the same and sent nothing.
 *
 * **One instanced mesh, one draw call**: a fixed pool of [MAX_FLAKES] quads allocated once per
 * canvas (instance matrix + instance colour, eight vertex attributes with the plane's own), unlit, so
 * it touches neither the surface shader's light table nor the haze program. The pool is a ring, so a
 * sixth tube in the air reuses the oldest flakes rather than growing a buffer.
 *
 * **The frameloop stays on demand**: a fire invalidates once, and each frame that steps a flying
 * flake asks for the next; when the last one settles nothing asks again, and the settled flakes are
 * a static draw like the rest of the stage.
 */
export function StageConfetti({
  patches,
  fixtureByKey,
  riggings,
  stageWidth,
  stageDepth,
  houseFloorZ,
}: {
  patches: readonly FixturePatch[]
  /** The fixtures by key: a tube's index and count come from its triggers, in the desk's order. */
  fixtureByKey: ReadonlyMap<string, Fixture>
  riggings: RiggingDto[]
  stageWidth: number
  stageDepth: number
  /** Lighting Z of the house floor outside the stage's footprint (a modelled room's base), else 0. */
  houseFloorZ: number
}) {
  const invalidate = useThree((s) => s.invalidate)
  const pool = useMemo(() => createConfettiPool(MAX_FLAKES), [])
  const meshRef = useRef<THREE.InstancedMesh>(null)
  const geometry = useMemo(() => new THREE.PlaneGeometry(FLAKE_W, FLAKE_H), [])
  const material = useMemo(
    () => new THREE.MeshBasicMaterial({ side: THREE.DoubleSide, toneMapped: false }),
    [],
  )
  useEffect(
    () => () => {
      geometry.dispose()
      material.dispose()
    },
    [geometry, material],
  )

  // Every slot collapsed out of sight, and the colour buffer made, before the first frame — so the
  // material compiles with instance colours once rather than on the first throw.
  useLayoutEffect(() => {
    const mesh = meshRef.current
    if (mesh == null) return
    hideAll(mesh)
    for (let i = 0; i < mesh.count; i++) mesh.setColorAt(i, COLOUR.setRGB(1, 1, 1))
    if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true
  }, [])

  // The latest props, read by the fire handler without resubscribing on every render.
  const sceneRef = useRef({ patches, fixtureByKey, riggings, stageWidth, stageDepth, houseFloorZ })
  sceneRef.current = { patches, fixtureByKey, riggings, stageWidth, stageDepth, houseFloorZ }
  const lastStep = useRef(0)

  useEffect(() => {
    const sub = lightingApi.effects.subscribeFired((fired) => {
      const { patches: ps, fixtureByKey: fx, riggings: rs, stageWidth: w, stageDepth: d, houseFloorZ: house } = sceneRef.current
      const patch = ps.find((p) => p.key === fired.fixture)
      if (patch == null) return
      const at = worldPositionLighting(patch, rs)
      if (at == null) return
      // A cannon's tubes are its triggers, in the order the desk lists them — the Twin Shot's A
      // left, B right. A fixture this window has not loaded yet throws from the head's axis.
      const triggers = triggersOf(fx.get(fired.fixture)?.properties)
      const tube = Math.max(0, triggers.findIndex((t) => t.name === fired.trigger))
      const dir = muzzleDirection(patch.baseYawDeg ?? 0, patch.basePitchDeg ?? 0, tube, Math.max(1, triggers.length))
      // A little out from the body along the tube, so the flakes leave the muzzle, not the base.
      const origin: [number, number, number] = [at.x + dir[0] * 0.2, at.z + dir[1] * 0.2, -at.y + dir[2] * 0.2]
      // The deck inside the stage's footprint, the house floor outside it (lighting Y is −Z here).
      const floorAt = (x: number, z: number) => (Math.abs(x) <= w / 2 && -z >= 0 && -z <= d ? 0.002 : house + 0.002)
      throwTube(pool, origin, dir, floorAt)
      writeColours(pool, meshRef.current)
      lastStep.current = performance.now()
      invalidate()
    })
    return () => sub.unsubscribe()
  }, [pool, invalidate])

  useFrame(() => {
    const mesh = meshRef.current
    if (mesh == null || pool.flying === 0) return
    const now = performance.now()
    const dt = Math.min(0.05, Math.max(0, (now - lastStep.current) / 1000))
    lastStep.current = now
    const flying = stepConfetti(pool, dt, now)
    writeMatrices(pool, mesh, now)
    if (flying > 0) invalidate()
  })

  return (
    <instancedMesh
      ref={meshRef}
      args={[geometry, material, MAX_FLAKES]}
      frustumCulled={false}
      raycast={NO_RAYCAST}
    />
  )
}

/** Flakes are never picked: a click on the stage reaches what is under them. */
const NO_RAYCAST = () => null

const SCRATCH = new THREE.Object3D()
const COLOUR = new THREE.Color()

function hideAll(mesh: THREE.InstancedMesh) {
  SCRATCH.position.set(0, -1000, 0)
  SCRATCH.scale.setScalar(0)
  SCRATCH.updateMatrix()
  for (let i = 0; i < mesh.count; i++) mesh.setMatrixAt(i, SCRATCH.matrix)
  mesh.instanceMatrix.needsUpdate = true
}

/** Each thrown flake's colour from its seed; written once per throw, never per frame. */
function writeColours(pool: ReturnType<typeof createConfettiPool>, mesh: THREE.InstancedMesh | null) {
  if (mesh == null) return
  for (let i = 0; i < pool.state.length; i++) {
    if (pool.state[i] !== 1) continue
    const c = CONFETTI_COLOURS[Math.floor(pool.seed[i] * CONFETTI_COLOURS.length) % CONFETTI_COLOURS.length]
    mesh.setColorAt(i, COLOUR.setRGB(c[0], c[1], c[2], THREE.SRGBColorSpace))
  }
  if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true
}

/**
 * Every thrown flake's matrix: its position, and a tumble from its seed and the clock while it flies —
 * a settled flake lies flat on the floor at a fixed turn.
 */
function writeMatrices(pool: ReturnType<typeof createConfettiPool>, mesh: THREE.InstancedMesh, nowMs: number) {
  const t = nowMs / 1000
  for (let i = 0; i < pool.state.length; i++) {
    const s = pool.state[i]
    if (s === 0) continue
    const k = i * 3
    const sd = pool.seed[i]
    SCRATCH.position.set(pool.pos[k], pool.pos[k + 1], pool.pos[k + 2])
    if (s === 1) {
      const a = sd * 6.283 + t * (4 + sd * 5)
      SCRATCH.rotation.set(a, a * 0.7 + sd * 3, a * 0.3)
    } else {
      SCRATCH.rotation.set(-Math.PI / 2, 0, sd * 6.283)
    }
    SCRATCH.scale.setScalar(1)
    SCRATCH.updateMatrix()
    mesh.setMatrixAt(i, SCRATCH.matrix)
  }
  mesh.instanceMatrix.needsUpdate = true
}
