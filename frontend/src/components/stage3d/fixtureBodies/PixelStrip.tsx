import { useEffect, useImperativeHandle, useMemo } from 'react'
import type { Group, Mesh } from 'three'
import {
  AdditiveBlending,
  Color,
  ConeGeometry,
  DoubleSide,
  MathUtils,
  MeshBasicMaterial,
  ShaderMaterial,
} from 'three'
import { HAZE_LEVEL, WASH_ANGLE_DEG } from '../washConfig'
import { BODY_LENS_COLOR, housingColor } from './palette'
import type { PixelColorWriter } from './types'

interface PixelStripProps {
  active: boolean
  pixelCount: number
  lengthM: number
  heightM: number
  depthM: number
  headRef: React.RefObject<Group | null>
  colorsRef: React.RefObject<PixelColorWriter | null>
}

// Each head casts a soft, downward wash volume below the fixture — coloured
// per-head so the bar's per-pixel state reads at a glance, and scaled by the
// global haze level (uHaze) so the mid-air throw only shows when the room is
// hazy. Kept subtler than a beam cone; the per-pixel floor/region pools carry
// the on-surface effect regardless of haze.
//
// Tunables — adjust freely to taste. NB: keep this a ConeGeometry — an
// open-ended frustum here triggered a WebKit transparency artifact (black quad).
const GLOW_LEN = 0.1 // how far the wash reaches below the bar (m)
// Glow cone radius derived from the shared wash angle (radius = len × tan(½angle))
// so the mid-air volume and the floor pool are the same cone shape. Capped so a
// very wide angle can't produce an absurdly fat cone.
const GLOW_RADIUS = Math.min(
  GLOW_LEN * Math.tan(MathUtils.degToRad(WASH_ANGLE_DEG / 2)),
  GLOW_LEN * 2,
)
const GLOW_MAX_OPACITY = 0.32 // peak additive opacity at full intensity (before haze)
// Length falloff; higher concentrates light at the lens, fading to black
// sooner. Deliberately kept while the beam cones went uniform (see the
// axial-profile note in washConfig): this ~0.1 m lens bloom needs to die out
// rather than end in a visible disc.
const GLOW_FADE_POWER = 1.4
const GLOW_EDGE_SOFTNESS = 0.6 // silhouette fade exponent; lower = softer edges
// Scales the glow's haze response below HAZE_LEVEL so the wash stays subtler
// than a beam. NB: no longer a direct glow:cone brightness ratio — beam cones
// are uniform along the throw while this glow keeps its length fade, so the
// effective mid-air ratio is lower than this number; re-tune by eye.
const WASH_SUBTLETY = 0.55

// Soft additive glow: silhouette fade (abs(N·V)) × length fade (bright at the
// lens apex, fading down). Colour/opacity are per-head uniforms; the two fade
// exponents are baked from the tunables above.
const GLOW_VERTEX = /* glsl */ `
  varying vec3 vViewNormal;
  varying vec3 vViewPos;
  varying float vAlong;
  void main() {
    vAlong = uv.y;
    vec4 vp = modelViewMatrix * vec4(position, 1.0);
    vViewPos = vp.xyz;
    vViewNormal = normalMatrix * normal;
    gl_Position = projectionMatrix * vp;
  }
`
const GLOW_FRAGMENT = /* glsl */ `
  uniform vec3 uColor;
  uniform float uOpacity;
  uniform float uHaze;
  varying vec3 vViewNormal;
  varying vec3 vViewPos;
  varying float vAlong;
  void main() {
    vec3 V = normalize(-vViewPos);
    float radial = pow(abs(dot(normalize(vViewNormal), V)), ${GLOW_EDGE_SOFTNESS.toFixed(2)});
    // Bright at the lens (cone apex, uv.y≈1) fading to black at the wide far end
    // (base, uv.y≈0). Clamp the base: pow() of a negative (out-of-range uv) is
    // NaN, which WebKit renders as a black quad under additive blending.
    float fade = pow(clamp(vAlong, 0.0, 1.0), ${GLOW_FADE_POWER.toFixed(2)});
    // uHaze gates the mid-air wash on atmosphere (0 ⇒ no airborne throw).
    float a = uOpacity * radial * fade * uHaze;
    gl_FragColor = vec4(uColor, a);
  }
`

// Mid-air wash strength tracks the global haze, kept subtler than a beam.
const GLOW_HAZE = HAZE_LEVEL * WASH_SUBTLETY

function makeGlowMaterial(): ShaderMaterial {
  return new ShaderMaterial({
    uniforms: {
      uColor: { value: new Color('#ffffff') },
      uOpacity: { value: 0 },
      uHaze: { value: GLOW_HAZE },
    },
    vertexShader: GLOW_VERTEX,
    fragmentShader: GLOW_FRAGMENT,
    transparent: true,
    blending: AdditiveBlending,
    depthWrite: false,
    side: DoubleSide,
  })
}

/**
 * A strip/bar body with one emissive lens segment + one soft glow per element,
 * evenly pitched along the bar's long axis (local X). The colour sync drives
 * each head's lens and glow imperatively via the published [PixelColorWriter].
 *
 * The segment/glow arrays are built from the same `useMemo([pixelCount])`, so
 * their lengths can never diverge — a mode switch (e.g. 48→10ch) remounts them
 * and rebuilds the writer with the new count in the same commit.
 */
export function PixelStrip({
  active,
  pixelCount,
  lengthM,
  heightM,
  depthM,
  headRef,
  colorsRef,
}: PixelStripProps) {
  const lensRefs = useMemo(
    () => Array.from({ length: pixelCount }, () => ({ current: null as Mesh | null })),
    [pixelCount],
  )
  const glowMats = useMemo(
    () => Array.from({ length: pixelCount }, makeGlowMaterial),
    [pixelCount],
  )
  const glowGeo = useMemo(() => new ConeGeometry(GLOW_RADIUS, GLOW_LEN, 18, 1, true), [])

  useEffect(() => () => glowMats.forEach((m) => m.dispose()), [glowMats])
  useEffect(() => () => glowGeo.dispose(), [glowGeo])

  useImperativeHandle(
    colorsRef,
    (): PixelColorWriter => {
      const paint = (i: number, color: Color, intensity: number) => {
        const lens = lensRefs[i]?.current
        if (lens) {
          const mat = lens.material as MeshBasicMaterial
          mat.color.copy(color)
          // Lens face stays half-lit at idle (it's the lamp body, not the beam).
          mat.opacity = 0.5 + 0.5 * intensity
          mat.transparent = true
        }
        const gm = glowMats[i]
        if (gm) {
          ;(gm.uniforms.uColor.value as Color).copy(color)
          gm.uniforms.uOpacity.value = GLOW_MAX_OPACITY * intensity
        }
      }
      return {
        count: pixelCount,
        setPixel(i, color, intensity) {
          if (i < 0 || i >= pixelCount) return
          paint(i, color, intensity)
        },
        reset() {
          for (let i = 0; i < pixelCount; i++) paint(i, OFF_COLOR, 0)
        },
      }
    },
    [lensRefs, glowMats, pixelCount],
  )

  const pitch = lengthM / pixelCount
  const segLen = pitch * 0.9
  const segDepth = depthM * 0.85
  const lensY = -heightM / 2 - 0.001

  return (
    <group ref={headRef}>
      <mesh>
        <boxGeometry args={[lengthM, heightM, depthM]} />
        <meshStandardMaterial color={housingColor(active)} />
      </mesh>
      {lensRefs.map((ref, i) => {
        const x = -lengthM / 2 + pitch * (i + 0.5)
        return (
          <group key={i}>
            <mesh
              ref={(m) => {
                ref.current = m
              }}
              position={[x, lensY, 0]}
            >
              <boxGeometry args={[segLen, 0.01, segDepth]} />
              <meshBasicMaterial color={BODY_LENS_COLOR} transparent />
            </mesh>
            <mesh geometry={glowGeo} material={glowMats[i]} position={[x, lensY - GLOW_LEN / 2, 0]} />
          </group>
        )
      })}
    </group>
  )
}

const OFF_COLOR = new Color('#000000')
