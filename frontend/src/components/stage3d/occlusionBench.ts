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
 * **Transmittance** (scrim plan session 3): with `?scrims=1` every collider is a bobbinet scrim
 * standing between the wall and the lights, so with `pass=1` every entry is tested *and crossed*,
 * and each multiplies the share rather than ending the loop — the worst case a list of nets can be.
 * `?only=twin` draws none of that: it runs `OCCLUSION_GLSL`'s `boxTransmit` over the fixed scene in
 * `scene/occlusionTwin.ts` on this GPU and prints each case's share beside its TypeScript twin's
 * (`segmentTransmit`) — the GLSL and the twin compared on a real renderer. `?only=haze` does the
 * same for the haze's split at a cloth (scrim plan session 5): `HAZE_PLANES_GLSL`'s eye crossings and
 * sample share over `scene/hazeTwin.ts`'s fixed scene, beside `hazeSampleShare`.
 *
 * The answer is printed as JSON: the renderer, the canvas and the mean milliseconds a frame for
 * each, the faster of two passes. Query parameters `w`, `h`, `lights` and `frames` change the load,
 * and `only=lobes` skips the box runs. `FU-MANUAL-STAGE-LIGHT-BUDGET` says what to read off it.
 */

import {
  DataTexture,
  FloatType,
  Mesh,
  NearestFilter,
  OrthographicCamera,
  PlaneGeometry,
  RGBAFormat,
  Scene,
  ShaderMaterial,
  WebGLRenderer,
  WebGLRenderTarget,
} from 'three'
import { boxCollider, type Collider } from './scene/beamReach'
import { LANDING_GLSL, REACH_EPS_M } from './scene/landing'
import { LightTable, makeLightRow } from './scene/lightTable'
import { LAMBERT_LOBES, type FinishLobes } from './scene/lobes'
import { makeMaskAtlasTexture } from './scene/maskAtlas'
import {
  emptyPackedCollider,
  LIST_OVERFLOW,
  LIST_TEXELS,
  makeColliderSet,
  MAX_LIGHT_COLLIDERS,
  OCCLUSION_GLSL,
  packColliders,
  TRANSMIT_GLSL,
  packEntry,
  segmentTransmit,
  unpackCollider,
} from './scene/occlusion'
import { twinAtlas, twinCases, TWIN_MASK_LAYERS } from './scene/occlusionTwin'
import { fillHazePlanes, HAZE_PLANES_GLSL, makeHazePlaneList } from './scene/hazePlanes'
import { HAZE_TWIN_EYE, hazeTwinCases, hazeTwinScene, hazeTwinShare } from './scene/hazeTwin'
import { createSceneMaskCache } from './scene/sceneMasks'
import { FINISH_LOBES } from './scene/sceneParts'
import { makeLightTexture, makeOcclusionTextures, makeSurfaceMaterial, makeSurfaceUniforms } from './scene/surfaceShader'

const params = new URLSearchParams(location.search)
const width = Number(params.get('w') ?? 1024)
const height = Number(params.get('h') ?? 640)
const lights = Number(params.get('lights') ?? 12)
const frames = Number(params.get('frames') ?? 10)
const pass = params.get('pass') === '1'
const scrims = params.get('scrims') === '1'
const lobesOnly = params.get('only') === 'lobes'
const hazeOnly = params.get('only') === 'haze'
const twinOnly = params.get('only') === 'twin' || hazeOnly

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

// Off to the side of everything: in every list, in no fragment's way. Or, with `scrims`, a stack of
// bobbinet scrims between the wall and the lights, in every fragment's way and passing it on.
const benchColliders: Collider[] = Array.from({ length: MAX_LIGHT_COLLIDERS }, (_, i) => {
  if (!scrims) return boxCollider(200 + i, 0, 0, 0.5, 0.5, 0.5, i * 0.1)
  const net = boxCollider(0, 0, 1 + i * 0.05, 50, 50, 0.005)
  net.transmit = { kind: 'angle', r: 0.15, gather: 1 }
  return net
})
packColliders(benchColliders, occlusion.set)
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
const boxes = lobesOnly || twinOnly
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
const lobes = twinOnly ? { lambert: 0 } : measure(
  lobeMaterials.map(([name, m]) => [
    name,
    () => {
      wall.material = m
      fillLists(0)
    },
  ]),
)

/**
 * The twin: each case of the fixed scene through the GLSL's `boxTransmit` on this GPU, one pixel a
 * case into a float target, beside `segmentTransmit` on the same packed colliders and atlas.
 */
function runTwin(): Record<string, { glsl: number; ts: number; want: number }> {
  const cases = twinCases()
  const set = makeColliderSet(cases.length)
  packColliders(cases.map((c) => c.collider), set, TWIN_MASK_LAYERS)
  const collidersTexture = new DataTexture(set.data, 3, cases.length, RGBAFormat, FloatType)
  const caseData = new Float32Array(cases.length * 2 * 4)
  cases.forEach((c, i) => {
    caseData.set([...c.p, c.tMax, ...c.d, 0], i * 8)
  })
  const casesTexture = new DataTexture(caseData, 2, cases.length, RGBAFormat, FloatType)
  for (const t of [collidersTexture, casesTexture]) {
    t.magFilter = NearestFilter
    t.minFilter = NearestFilter
    t.needsUpdate = true
  }
  const atlas = twinAtlas()
  const masks = createSceneMaskCache({ load: async () => null })
  masks.atlas.set(atlas)
  const material = new ShaderMaterial({
    uniforms: {
      uColliders: { value: collidersTexture },
      uLightColliders: { value: collidersTexture },
      uMaskAtlas: { value: makeMaskAtlasTexture(masks) },
      uCases: { value: casesTexture },
    },
    vertexShader: /* glsl */ `void main() { gl_Position = vec4(position.xy * 2.0, 0.0, 1.0); }`,
    fragmentShader: /* glsl */ `
      #define REACH_EPS ${REACH_EPS_M.toFixed(3)}
      uniform sampler2D uCases;
      ${LANDING_GLSL}
      ${OCCLUSION_GLSL}
      void main() {
        int i = int(gl_FragCoord.x);
        vec4 a = texelFetch(uCases, ivec2(0, i), 0);
        vec4 b = texelFetch(uCases, ivec2(1, i), 0);
        gl_FragColor = vec4(boxTransmit(a.xyz, b.xyz, a.w, i), 0.0, 0.0, 1.0);
      }
    `,
  })
  const target = new WebGLRenderTarget(cases.length, 1, { type: FloatType })
  const quad = new Mesh(new PlaneGeometry(1, 1), material)
  const twinScene = new Scene()
  twinScene.add(quad)
  renderer.setRenderTarget(target)
  renderer.render(twinScene, camera)
  const out = new Float32Array(cases.length * 4)
  renderer.readRenderTargetPixels(target, 0, 0, cases.length, 1, out)
  renderer.setRenderTarget(null)
  const result: Record<string, { glsl: number; ts: number; want: number }> = {}
  cases.forEach((c, i) => {
    const b = unpackCollider(set.data, i, emptyPackedCollider())
    result[c.name] = {
      glsl: Number(out[i * 4].toFixed(5)),
      ts: Number(segmentTransmit(...c.p, ...c.d, c.tMax, b, atlas).toFixed(5)),
      want: Number(c.want.toFixed(5)),
    }
  })
  return result
}

/**
 * The haze's twin: each case of `scene/hazeTwin.ts` through the march's own functions on this GPU —
 * `hazeEyeCrossings` along the view ray, then `hazeSampleShare` at the sample towards the apex — one
 * pixel a case into a float target, beside `hazeTwinShare` on the same list and atlas.
 */
function runHazeTwin(): Record<string, { glsl: number; ts: number; want: number }> {
  const cases = hazeTwinCases()
  const set = makeColliderSet()
  packColliders(hazeTwinScene(), set, TWIN_MASK_LAYERS)
  const list = makeHazePlaneList()
  fillHazePlanes(set, ...HAZE_TWIN_EYE, list)
  const caseData = new Float32Array(cases.length * 4 * 4)
  cases.forEach((c, i) => {
    caseData.set([...c.o, c.t, ...c.d, c.planes, ...c.apex, 0, ...c.axis, c.near], i * 16)
  })
  const casesTexture = new DataTexture(caseData, 4, cases.length, RGBAFormat, FloatType)
  casesTexture.magFilter = NearestFilter
  casesTexture.minFilter = NearestFilter
  casesTexture.needsUpdate = true
  const atlas = twinAtlas()
  const masks = createSceneMaskCache({ load: async () => null })
  masks.atlas.set(atlas)
  const material = new ShaderMaterial({
    uniforms: {
      uMaskAtlas: { value: makeMaskAtlasTexture(masks) },
      uHazePlaneCount: { value: list.count },
      uHazePlanes: { value: list.data },
      uCases: { value: casesTexture },
    },
    vertexShader: /* glsl */ `void main() { gl_Position = vec4(position.xy * 2.0, 0.0, 1.0); }`,
    fragmentShader: /* glsl */ `
      uniform sampler2D uCases;
      ${TRANSMIT_GLSL}
      ${HAZE_PLANES_GLSL}
      void main() {
        int i = int(gl_FragCoord.x);
        vec4 a = texelFetch(uCases, ivec2(0, i), 0);
        vec4 b = texelFetch(uCases, ivec2(1, i), 0);
        vec4 c = texelFetch(uCases, ivec2(2, i), 0);
        vec4 e = texelFetch(uCases, ivec2(3, i), 0);
        vec4 eyeAt0;
        vec4 eyeAt1;
        vec4 eyeShare0;
        vec4 eyeShare1;
        hazeEyeCrossings(a.xyz, b.xyz, eyeAt0, eyeAt1, eyeShare0, eyeShare1);
        vec3 p = a.xyz + b.xyz * a.w;
        vec3 rel = p - c.xyz;
        float relLen = max(length(rel), 1e-4);
        vec3 lightDir = rel / relLen;
        float cosAngle = dot(lightDir, e.xyz);
        float keep = hazeSampleShare(a.w, p, -lightDir, relLen - e.w / max(cosAngle, 1e-4), int(b.w + 0.5), eyeAt0, eyeAt1, eyeShare0, eyeShare1);
        gl_FragColor = vec4(keep, 0.0, 0.0, 1.0);
      }
    `,
  })
  const target = new WebGLRenderTarget(cases.length, 1, { type: FloatType })
  const quad = new Mesh(new PlaneGeometry(1, 1), material)
  const hazeScene = new Scene()
  hazeScene.add(quad)
  renderer.setRenderTarget(target)
  renderer.render(hazeScene, camera)
  const out = new Float32Array(cases.length * 4)
  renderer.readRenderTargetPixels(target, 0, 0, cases.length, 1, out)
  renderer.setRenderTarget(null)
  const result: Record<string, { glsl: number; ts: number; want: number }> = {}
  cases.forEach((c, i) => {
    result[c.name] = {
      glsl: Number(out[i * 4].toFixed(5)),
      ts: Number(hazeTwinShare(list, c, atlas).toFixed(5)),
      want: Number(c.want.toFixed(5)),
    }
  })
  return result
}

const round2 = (v: number) => Number(v.toFixed(2))
const info = gl.getExtension('WEBGL_debug_renderer_info')
const twin = hazeOnly ? runHazeTwin() : twinOnly ? runTwin() : undefined
const result = {
  renderer: info ? String(gl.getParameter(info.UNMASKED_RENDERER_WEBGL)) : 'unknown',
  canvas: `${width} × ${height}`,
  lights: packed,
  entries: `${pass ? 'every one tested' : 'every one skipped'}${scrims ? ', each a scrim crossed' : ''}`,
  ...(twin && { twin, twinAgrees: Object.values(twin).every((c) => Math.abs(c.glsl - c.ts) < 1e-3) }),
  ...(!twinOnly && {
    msPerFrame: Object.fromEntries(Object.entries({ ...boxes, ...lobes }).map(([k, v]) => [k, round2(v)])),
    lobeCost: Object.fromEntries(
      Object.entries(lobes)
        .filter(([k]) => k !== 'lambert')
        .map(([k, v]) => [k, round2(v - lobes.lambert)]),
    ),
  }),
}
const out = document.getElementById('out')
if (out) out.textContent = JSON.stringify(result, null, 1)
