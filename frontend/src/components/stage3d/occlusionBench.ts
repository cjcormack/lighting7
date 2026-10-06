/**
 * The **occlusion bench** (stage-light plan session 3): what the surface pass's box test costs a
 * frame, by the length of a light's collider list, on whatever renderer opens it — served by the dev
 * server at `/occlusion-bench.html`, outside the app, so it needs no desk session (`npm run dev --
 * --host` puts it in reach of an iPad). One wall fills the canvas under [lights] wide lights, every
 * pixel lit by every one, and each light's list is filled by hand with colliders standing off to
 * the side, so nothing is ever shadowed and every entry costs what it costs:
 *
 * - `planes` is the landing-plane path the surfaces drew before session 3 (`LIST_OVERFLOW`);
 * - `list n` is n entries a light; with `?pass=1` every entry's sphere covers every fragment, so
 *   each runs the full box test, and without it none does, so each is skipped on its sphere.
 *
 * The **lobes** (session 4) are timed on the same wall with every list empty, so only the finish
 * changes: `lambert`, the whole BRDF before session 4; each lobe alone, `orenNayar`, `sheen` and
 * `ggx`; and the presets that carry two, `velour` and `paint`. `lobeCost` is each one's milliseconds
 * a frame over `lambert`, the number to set beside a list entry's.
 *
 * The answer is printed as JSON: the renderer, the canvas and the mean milliseconds a frame for
 * each, the faster of two passes. Query parameters `w`, `h`, `lights` and `frames` change the load,
 * and `only=lobes` skips the box runs. `FU-MANUAL-STAGE-LIGHT-BUDGET` says what to read off it.
 */

import { Mesh, OrthographicCamera, PlaneGeometry, Scene, WebGLRenderer, type ShaderMaterial } from 'three'
import { boxCollider } from './scene/beamReach'
import { LightTable, makeLightRow } from './scene/lightTable'
import { LAMBERT_LOBES, type FinishLobes } from './scene/lobes'
import { LIST_OVERFLOW, LIST_TEXELS, MAX_LIGHT_COLLIDERS, packColliders, packEntry } from './scene/occlusion'
import { FINISH_LOBES } from './scene/sceneParts'
import { makeLightTexture, makeOcclusionTextures, makeSurfaceMaterial, makeSurfaceUniforms } from './scene/surfaceShader'

const params = new URLSearchParams(location.search)
const width = Number(params.get('w') ?? 1024)
const height = Number(params.get('h') ?? 640)
const lights = Number(params.get('lights') ?? 12)
const frames = Number(params.get('frames') ?? 10)
const pass = params.get('pass') === '1'
const lobesOnly = params.get('only') === 'lobes'

const canvas = document.createElement('canvas')
document.body.appendChild(canvas)
const renderer = new WebGLRenderer({ canvas, antialias: false })
renderer.setPixelRatio(1)
renderer.setSize(width, height, false)
const gl = renderer.getContext()

const { texture, data } = makeLightTexture()
const occlusion = makeOcclusionTextures()
const uniforms = makeSurfaceUniforms(texture, occlusion)
const material = (lobes: FinishLobes): ShaderMaterial =>
  makeSurfaceMaterial(uniforms, { colour: '#808080', pattern: 'PLAIN', emissive: false, lobes })
const lambert = material(LAMBERT_LOBES)
const wall = new Mesh(new PlaneGeometry(20, 12.5), lambert)
const scene = new Scene()
scene.add(wall)
const camera = new OrthographicCamera(-10, 10, 6.25, -6.25, 0.1, 100)
camera.position.set(0, 0, 20)

const table = new LightTable(lights)
for (let i = 0; i < lights; i++) {
  const row = makeLightRow()
  row.ax = -8 + (16 * i) / Math.max(1, lights - 1)
  row.ay = 3
  row.az = 12
  const len = Math.hypot(row.ax, row.ay, row.az)
  row.dx = -row.ax / len
  row.dy = -row.ay / len
  row.dz = -row.az / len
  row.cosBound = Math.cos(Math.PI / 3)
  row.tanHalf = Math.tan(Math.PI / 3)
  row.r = row.g = row.b = 0.3
  row.edge = 1
  row.near = 0.2
  table.set(i, row)
}
const packed = table.pack(lights, data)
uniforms.uLightCount.value = packed
texture.needsUpdate = true

// Off to the side of everything: in every list, in no fragment's way.
packColliders(Array.from({ length: MAX_LIGHT_COLLIDERS }, (_, i) => boxCollider(200 + i, 0, 0, 0.5, 0.5, 0.5, i * 0.1)), occlusion.set)
occlusion.colliders.needsUpdate = true

function fillLists(length: number): void {
  for (let k = 0; k < packed; k++) {
    const row = k * LIST_TEXELS * 4
    occlusion.listData[row] = length
    for (let j = 0; j < length; j++) packEntry(1, 0, 0, pass ? -2 : 0.999, j, 0, occlusion.listData, row + 4 + j * 4)
  }
  occlusion.lists.needsUpdate = true
}

const pixel = new Uint8Array(4)
function frame(): void {
  renderer.render(scene, camera)
  // Waits for the frame: the GPU otherwise queues it and the clock measures nothing.
  gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, pixel)
}

/** Each run sets the scene up, then is timed: the faster of two rounds, so a compile or a hiccup is not counted. */
function measure(runs: ReadonlyArray<readonly [string, () => void]>): Record<string, number> {
  const ms: Record<string, number> = {}
  for (let round = 0; round < 2; round++) {
    for (const [name, setUp] of runs) {
      setUp()
      frame()
      frame()
      const t0 = performance.now()
      for (let f = 0; f < frames; f++) frame()
      const mean = (performance.now() - t0) / frames
      ms[name] = round === 0 ? mean : Math.min(ms[name], mean)
    }
  }
  return ms
}

const boxRuns: [string, number][] = [['planes', LIST_OVERFLOW], ['list 0', 0], ['list 8', 8], ['list 16', 16], ['list 32', 32], ['list 64', 64]]
const boxes = lobesOnly
  ? {}
  : measure(
      boxRuns.map(([name, length]) => [
        name,
        () => {
          wall.material = lambert
          fillLists(length)
        },
      ]),
    )

const only = (lobes: Partial<FinishLobes>): FinishLobes => ({ ...LAMBERT_LOBES, ...lobes })
const lobeMaterials: [string, ShaderMaterial][] = [
  ['lambert', lambert],
  ['orenNayar', material(only({ diffuseRoughness: FINISH_LOBES.VELOUR.diffuseRoughness }))],
  ['sheen', material(only({ sheen: FINISH_LOBES.VELOUR.sheen, sheenRoughness: FINISH_LOBES.VELOUR.sheenRoughness }))],
  ['ggx', material(only({ specular: FINISH_LOBES.FLOOR.specular, roughness: FINISH_LOBES.FLOOR.roughness }))],
  ['velour', material(FINISH_LOBES.VELOUR)],
  ['paint', material(FINISH_LOBES.PAINT)],
]
const lobes = measure(
  lobeMaterials.map(([name, m]) => [
    name,
    () => {
      wall.material = m
      fillLists(0)
    },
  ]),
)

const round2 = (v: number) => Number(v.toFixed(2))
const info = gl.getExtension('WEBGL_debug_renderer_info')
const result = {
  renderer: info ? String(gl.getParameter(info.UNMASKED_RENDERER_WEBGL)) : 'unknown',
  canvas: `${width} × ${height}`,
  lights: packed,
  entries: pass ? 'every one tested' : 'every one skipped',
  msPerFrame: Object.fromEntries(Object.entries({ ...boxes, ...lobes }).map(([k, v]) => [k, round2(v)])),
  lobeCost: Object.fromEntries(
    Object.entries(lobes)
      .filter(([k]) => k !== 'lambert')
      .map(([k, v]) => [k, round2(v - lobes.lambert)]),
  ),
}
const out = document.getElementById('out')
if (out) out.textContent = JSON.stringify(result, null, 1)
