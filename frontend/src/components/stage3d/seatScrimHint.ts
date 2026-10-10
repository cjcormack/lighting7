import type { LiveScenery } from '../../api/sceneryApi'
import type { StageElementDto } from '../../api/stageElementApi'
import { sceneryElements, trimsOf } from '../../lib/scenery'
import type { LightingPoint3 } from '../../lib/stageSeats'
import { buildElement } from './scene/builders'
import { elementBaseZ, elementFlies, partTransmit } from './scene/sceneParts'
import { scrimShare } from './scene/scrimOpen'

/**
 * **A seat that sees a gauze as solid says so** (scrim plan D13, session 6): when a seat sees a shown
 * scrim at below half its head-on `open`, the seat's row in the viewpoint picker and its caption on
 * the canvas say so — *Forest gauze reads near-solid from F6 (open 12 %)*. That is real, and it is
 * why a reveal that works in the centre stalls can fail at the ends of a row.
 *
 * **One formula.** What a seat sees through a net is `scrimOpen.ts`'s `open(θ)^gather` — the
 * [scrimShare] the surface's blend and the haze already read — at θ between the seat's eye-to-point
 * ray and the cloth's normal, never a copy of it. "Head-on" is the same net square on through the same
 * gather, so a gathered half (its `gather` its fullness) is judged against its own head-on, not a flat
 * net's.
 *
 * **Which point, and which scrim.** Each shown net part — a dead or flown cloth, or each half of a
 * drawn one, as the builders make it at its live state — is read at its centre and its four corners,
 * and a scrim's reading is its **worst point**. A reveal is played across the whole cloth, so a gauze
 * that reads solid across its far end from a side seat fails there for that seat, which is the case
 * the hint exists for; a centre-only reading would never see it, since the centre is the point a seat
 * looks at most squarely. The hint then names the scrim whose worst point is **furthest below** its
 * head-on (the lowest ratio) and counts the others that cross the line too, rather than naming the
 * nearest one: the nearest gauze may read fine while a deeper one, seen more obliquely, is the one
 * that fails.
 *
 * **Nets only**, from the parts: a part is a scrim where its light is a `Transmit` of kind `angle`
 * (sharkstooth and bobbinet). Muslin is opaque to the eye and a cut cloth's holes are holes, so
 * neither is a scrim. **Shown only**: the live scenery is laid over the elements first (landed — a move
 * in flight is read where it lands), so a hidden gauze, one switched off by its `visible` state, and
 * one **flown out** — at its out trim, the stored trim [trimsOf] calls its out — is not read.
 *
 * Pure and three.js-free: the route reads it beside the picker, outside the canvas.
 */

/** A seat sees a scrim as near-solid below this share of the scrim's head-on open (D13). */
export const SCRIM_HINT_RATIO = 0.5

/** How close a flown cloth's live trim must be to its out trim to count as flown out, metres. */
const FLOWN_OUT_EPS_M = 1e-3

/** One net part of a shown scrim, in lighting metres: where it is read and which way it faces. */
export interface ScrimFace {
  /** Its centre, then its four corners. */
  points: readonly LightingPoint3[]
  /** Its unit normal (either side: a net passes the same both ways). */
  normal: LightingPoint3
  /** The thread share of its net (`SCRIM_THREAD_SHARE`). */
  r: number
  /** How many layers of itself it stacks (1 hanging open). */
  gather: number
}

/** A scrim the seats can see, with every net part of it. */
export interface ShownScrim {
  uuid: string
  name: string
  faces: readonly ScrimFace[]
}

/**
 * The scrims [elements] show with [scenery] landed over them: every shown, not flown-out drape whose
 * built parts include a net. [elements] are the project's stored elements, before any scenery — what
 * a flown cloth's out trim is read from.
 */
export function shownScrims(elements: readonly StageElementDto[], scenery: LiveScenery): ShownScrim[] {
  const live = sceneryElements(elements, scenery, 0, new WeakMap(), true)
  const out: ShownScrim[] = []
  elements.forEach((stored, i) => {
    const element = live[i]
    if (element.kind !== 'DRAPE' || flownOut(stored, element)) return
    // `buildElement` builds nothing for a hidden element or one its `visible` state switches off.
    const build = buildElement(element)
    const yaw = (element.yawDeg * Math.PI) / 180
    const c = Math.cos(yaw)
    const s = Math.sin(yaw)
    const baseZ = elementBaseZ(element)
    // The element frame's +Y, turned by its yaw: the normal of a cloth hung in its y = 0 plane.
    const normal: LightingPoint3 = { x: -s, y: c, z: 0 }
    const world = (lx: number, ly: number, lz: number): LightingPoint3 => ({
      x: element.positionX + c * lx - s * ly,
      y: element.positionY + s * lx + c * ly,
      z: baseZ + lz,
    })
    const faces: ScrimFace[] = []
    for (const part of build.parts) {
      const transmit = partTransmit(part)
      if (transmit == null || transmit.kind !== 'angle') continue
      const g = part.geometry
      if (g.shape !== 'sheet' && g.shape !== 'pleat') continue
      const { x, y, z } = part.at
      const hw = g.w / 2
      const hh = g.h / 2
      faces.push({
        points: [
          world(x, y, z),
          world(x - hw, y, z - hh),
          world(x + hw, y, z - hh),
          world(x - hw, y, z + hh),
          world(x + hw, y, z + hh),
        ],
        normal,
        r: transmit.r,
        gather: transmit.gather,
      })
    }
    if (faces.length > 0) out.push({ uuid: element.uuid, name: element.name, faces })
  })
  return out
}

/** Whether a flown cloth hangs at its out trim: the stored trim, read from [stored], against the live base. */
function flownOut(stored: StageElementDto, live: StageElementDto): boolean {
  if (!elementFlies(stored)) return false
  const { outM } = trimsOf(stored)
  return outM != null && Math.abs(elementBaseZ(live) - outM) < FLOWN_OUT_EPS_M
}

/** What one seat sees of one scrim at its worst point. */
export interface ScrimReading {
  uuid: string
  name: string
  /** The share the seat sees through at that point: `open(θ)^gather`. */
  open: number
  /** [open] over the same net's head-on share, through the same gather. */
  ratio: number
}

/** A seat's reading of [scrim], or null when no point of it can be read (the eye sits on one). */
export function readScrim(eye: LightingPoint3, scrim: ShownScrim): ScrimReading | null {
  let worst: ScrimReading | null = null
  for (const face of scrim.faces) {
    const headOn = scrimShare(1, face.r, face.gather)
    if (!(headOn > 0)) continue
    for (const p of face.points) {
      const dx = p.x - eye.x
      const dy = p.y - eye.y
      const dz = p.z - eye.z
      const dist = Math.hypot(dx, dy, dz)
      if (!(dist > 1e-9)) continue
      const cos = (dx * face.normal.x + dy * face.normal.y + dz * face.normal.z) / dist
      const open = scrimShare(cos, face.r, face.gather)
      const ratio = open / headOn
      if (worst == null || ratio < worst.ratio) worst = { uuid: scrim.uuid, name: scrim.name, open, ratio }
    }
  }
  return worst
}

/** The hint a seat carries: the worst scrim it sees as near-solid, and how many others cross the line. */
export interface SeatScrimHint {
  reading: ScrimReading
  /** Other shown scrims this seat also sees at below [SCRIM_HINT_RATIO] of their head-on open. */
  others: readonly ScrimReading[]
  /** The sentence: *Forest gauze reads near-solid from F6 (open 12 %)*. */
  text: string
  /** [text], and every other scrim named — a hover's whole answer. */
  detail: string
}

/** The hint for a seat [seatId] whose eye is at [eye], or null when it sees every shown scrim well enough. */
export function seatScrimHint(eye: LightingPoint3, seatId: string, scrims: readonly ShownScrim[]): SeatScrimHint | null {
  const flagged = scrims
    .map((scrim) => readScrim(eye, scrim))
    .filter((r): r is ScrimReading => r != null && r.ratio < SCRIM_HINT_RATIO)
    .sort((a, b) => a.ratio - b.ratio)
  if (flagged.length === 0) return null
  const [reading, ...others] = flagged
  const sentence = (r: ScrimReading) => `${r.name} reads near-solid from ${seatId} (open ${Math.round(r.open * 100)} %)`
  const text = sentence(reading)
  return { reading, others, text, detail: [text, ...others.map(sentence)].join('\n') }
}
