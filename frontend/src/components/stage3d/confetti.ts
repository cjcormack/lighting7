/**
 * The confetti model (stage-view plan session 9; the prototype's `stepConfetti`, ported): a fixed
 * pool of paper flakes, ~380 a tube, thrown from a cannon's muzzle, slowed by air, pulled down by
 * gravity but never faster than paper flutters, swayed, and **settled** where they land.
 *
 * Pure and allocation-free per step, so the Stage view can run it in `useFrame` and a test can run it
 * without a canvas. All coordinates are **R3F space** (Y up, −Z upstage) — the conversion from the
 * desk's lighting coordinates happens once, at the fire.
 */

/** Flakes thrown per tube: the record's "about 380". */
export const FLAKES_PER_TUBE = 380

/**
 * The pool: five tubes in the air at once before the oldest flakes are reused. Fixed, so the
 * instance buffers are allocated once per canvas and never grow (stage-vis doc §"The 3D renderer").
 */
export const MAX_FLAKES = FLAKES_PER_TUBE * 5 + 100

/** The fastest a flake falls, m/s: paper flutters down, it does not drop. */
export const FLUTTER_TERMINAL = 0.85

const GRAVITY = 9.8
const DRAG = 1.7
const SWAY = 0.6

/** Six paper colours, as the prototype throws them. */
export const CONFETTI_COLOURS: readonly (readonly [number, number, number])[] = [
  [0.949, 0.757, 0.306],
  [0.91, 0.91, 0.941],
  [1.0, 0.31, 0.639],
  [0.224, 0.776, 0.957],
  [0.702, 0.533, 1.0],
  [1.0, 0.851, 0.541],
]

export interface ConfettiPool {
  /** x, y, z per flake. */
  readonly pos: Float32Array
  readonly vel: Float32Array
  /** Where the flake settles, per flake. */
  readonly floor: Float32Array
  /** 0 idle (never thrown), 1 flying, 2 settled. */
  readonly state: Uint8Array
  /** A per-flake random, for its colour, flutter phase and spin. */
  readonly seed: Float32Array
  /** The next slot to throw into — a ring, so a sixth tube reuses the oldest flakes. */
  next: number
  /** How many flakes are flying. Zero is the signal to stop asking for frames. */
  flying: number
}

export function createConfettiPool(size = MAX_FLAKES): ConfettiPool {
  return {
    pos: new Float32Array(size * 3),
    vel: new Float32Array(size * 3),
    floor: new Float32Array(size),
    state: new Uint8Array(size),
    seed: new Float32Array(size),
    next: 0,
    flying: 0,
  }
}

/**
 * Throw one tube's worth of flakes from [at] along [dir] (unit, R3F space), each landing on
 * [floorAt]'s answer for where it is thrown over — good enough for a flake that drifts a metre.
 * [random] is injectable for tests.
 */
export function throwTube(
  pool: ConfettiPool,
  at: readonly [number, number, number],
  dir: readonly [number, number, number],
  floorAt: (x: number, z: number) => number,
  count = FLAKES_PER_TUBE,
  random: () => number = Math.random,
): void {
  const size = pool.state.length
  for (let n = 0; n < count; n++) {
    const i = pool.next
    pool.next = (pool.next + 1) % size
    if (pool.state[i] === 1) pool.flying--
    const k = i * 3
    // The muzzle's axis, scattered, at 7–13 m/s — the prototype's numbers.
    let vx = dir[0] + (random() - 0.5) * 0.34
    let vy = dir[1] + (random() - 0.5) * 0.34
    let vz = dir[2] + (random() - 0.5) * 0.34
    const len = Math.hypot(vx, vy, vz) || 1
    const speed = 7 + random() * 6
    vx = (vx / len) * speed
    vy = (vy / len) * speed
    vz = (vz / len) * speed
    pool.pos[k] = at[0]
    pool.pos[k + 1] = at[1]
    pool.pos[k + 2] = at[2]
    pool.vel[k] = vx
    pool.vel[k + 1] = vy
    pool.vel[k + 2] = vz
    pool.floor[i] = floorAt(at[0], at[2])
    pool.seed[i] = random()
    pool.state[i] = 1
    pool.flying++
  }
}

/**
 * Advance every flying flake by [dt] seconds at wall time [nowMs]: drag, gravity capped at the
 * flutter terminal, a sway, and a settle on reaching its floor. Settled flakes never move again until
 * their slot is thrown anew. Returns how many are still flying.
 */
export function stepConfetti(pool: ConfettiPool, dt: number, nowMs: number): number {
  if (pool.flying === 0) return 0
  const drag = Math.exp(-DRAG * dt)
  let flying = 0
  for (let i = 0; i < pool.state.length; i++) {
    if (pool.state[i] !== 1) continue
    const k = i * 3
    const sd = pool.seed[i]
    let vx = pool.vel[k] * drag
    let vy = (pool.vel[k + 1] - GRAVITY * dt) * drag
    let vz = pool.vel[k + 2] * drag
    if (vy < -FLUTTER_TERMINAL) vy = -FLUTTER_TERMINAL
    vx += Math.sin(nowMs / 300 + sd * 40) * SWAY * dt
    vz += Math.cos(nowMs / 370 + sd * 30) * SWAY * dt
    const x = pool.pos[k] + vx * dt
    let y = pool.pos[k + 1] + vy * dt
    const z = pool.pos[k + 2] + vz * dt
    const floor = pool.floor[i]
    if (y <= floor && vy <= 0) {
      y = floor
      pool.state[i] = 2
      vx = 0
      vy = 0
      vz = 0
    } else {
      flying++
    }
    pool.pos[k] = x
    pool.pos[k + 1] = y
    pool.pos[k + 2] = z
    pool.vel[k] = vx
    pool.vel[k + 1] = vy
    pool.vel[k + 2] = vz
  }
  pool.flying = flying
  return flying
}

/**
 * A tube's muzzle axis in R3F space for a fixture posed by [yawDeg] / [pitchDeg] (the patch's base
 * orientation): straight up for a standing cannon, down for a hung one (pitch 180), the two tubes
 * splayed ±12° either side of the head's axis, as the Twin Shot's are.
 */
export function muzzleDirection(yawDeg: number, pitchDeg: number, tubeIndex: number, tubes: number): [number, number, number] {
  const splay = tubes > 1 ? ((tubeIndex / (tubes - 1)) * 2 - 1) * 0.21 : 0
  // Local: the head's axis is +Y (up); a splay tilts it about the local Z.
  let x = -Math.sin(splay)
  let y = Math.cos(splay)
  let z = 0
  // Pitch about X, then yaw about Y (R3F's up).
  const p = (pitchDeg * Math.PI) / 180
  const y1 = y * Math.cos(p) - z * Math.sin(p)
  const z1 = y * Math.sin(p) + z * Math.cos(p)
  y = y1
  z = z1
  const w = (-yawDeg * Math.PI) / 180
  const x2 = x * Math.cos(w) + z * Math.sin(w)
  const z2 = -x * Math.sin(w) + z * Math.cos(w)
  x = x2
  z = z2
  return [x, y, z]
}
