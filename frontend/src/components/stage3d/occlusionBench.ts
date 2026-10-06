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
 * The answer is printed as JSON: the renderer, the canvas and the mean milliseconds a frame for
 * each, the faster of two passes. Query parameters `w`, `h`, `lights` and `frames` change the load.
 * `FU-MANUAL-STAGE-LIGHT-BUDGET` says what to read off it.
 */

import { Mesh, OrthographicCamera, PlaneGeometry, Scene, WebGLRenderer } from 'three'
import { boxCollider } from './scene/beamReach'
import { LightTable, makeLightRow } from './scene/lightTable'
import { LIST_OVERFLOW, LIST_TEXELS, MAX_LIGHT_COLLIDERS, packColliders, packEntry } from './scene/occlusion'
import { makeLightTexture, makeOcclusionTextures, makeSurfaceMaterial, makeSurfaceUniforms } from './scene/surfaceShader'

const params = new URLSearchParams(location.search)
const width = Number(params.get('w') ?? 1024)
const height = Number(params.get('h') ?? 640)
const lights = Number(params.get('lights') ?? 12)
const frames = Number(params.get('frames') ?? 10)
const pass = params.get('pass') === '1'

const canvas = document.createElement('canvas')
document.body.appendChild(canvas)
const renderer = new WebGLRenderer({ canvas, antialias: false })
renderer.setPixelRatio(1)
renderer.setSize(width, height, false)
const gl = renderer.getContext()

const { texture, data } = makeLightTexture()
const occlusion = makeOcclusionTextures()
const uniforms = makeSurfaceUniforms(texture, occlusion)
const scene = new Scene()
scene.add(new Mesh(new PlaneGeometry(20, 12.5), makeSurfaceMaterial(uniforms, { colour: '#808080', pattern: 'PLAIN', emissive: false })))
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

const runs: [string, number][] = [['planes', LIST_OVERFLOW], ['list 0', 0], ['list 8', 8], ['list 16', 16], ['list 32', 32], ['list 64', 64]]
const ms: Record<string, number> = {}
for (let round = 0; round < 2; round++) {
  for (const [name, length] of runs) {
    fillLists(length)
    frame()
    frame()
    const t0 = performance.now()
    for (let f = 0; f < frames; f++) frame()
    const mean = (performance.now() - t0) / frames
    ms[name] = round === 0 ? mean : Math.min(ms[name], mean)
  }
}

const info = gl.getExtension('WEBGL_debug_renderer_info')
const result = {
  renderer: info ? String(gl.getParameter(info.UNMASKED_RENDERER_WEBGL)) : 'unknown',
  canvas: `${width} × ${height}`,
  lights: packed,
  entries: pass ? 'every one tested' : 'every one skipped',
  msPerFrame: Object.fromEntries(Object.entries(ms).map(([k, v]) => [k, Number(v.toFixed(2))])),
}
const out = document.getElementById('out')
if (out) out.textContent = JSON.stringify(result, null, 1)
