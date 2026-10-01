import { describe, expect, it } from 'vitest'
import { FLAKES_PER_TUBE, FLUTTER_TERMINAL, MAX_FLAKES, createConfettiPool, muzzleDirection, stepConfetti, throwTube } from './confetti'

/** A deterministic random: the same sequence every run. */
function seeded(seed = 1): () => number {
  let s = seed
  return () => {
    s = (s * 16807) % 2147483647
    return (s - 1) / 2147483646
  }
}

describe('confetti', () => {
  it('throws about 380 flakes a tube into a fixed pool, reusing the oldest past five tubes', () => {
    const pool = createConfettiPool()
    expect(FLAKES_PER_TUBE).toBe(380)
    throwTube(pool, [0, 2, 0], [0, 1, 0], () => 0, FLAKES_PER_TUBE, seeded())
    expect(pool.flying).toBe(380)
    for (let i = 0; i < 6; i++) throwTube(pool, [0, 2, 0], [0, 1, 0], () => 0, FLAKES_PER_TUBE, seeded(i + 2))
    expect(pool.flying).toBe(MAX_FLAKES)
    expect(pool.state.length).toBe(MAX_FLAKES)
  })

  it('flutters down no faster than paper, and settles where it lands so it stops asking for frames', () => {
    const pool = createConfettiPool(400)
    throwTube(pool, [0, 4, 0], [0, 1, 0], () => 0, 380, seeded())
    let t = 0
    let flying = pool.flying
    let fastest = 0
    while (flying > 0 && t < 60_000) {
      t += 16
      flying = stepConfetti(pool, 0.016, t)
      for (let i = 0; i < 380; i++) if (pool.state[i] === 1) fastest = Math.min(fastest, pool.vel[i * 3 + 1])
    }
    expect(flying).toBe(0)
    expect(fastest).toBeGreaterThanOrEqual(-FLUTTER_TERMINAL - 1e-6)
    for (let i = 0; i < 380; i++) {
      expect(pool.state[i]).toBe(2)
      expect(pool.pos[i * 3 + 1]).toBe(0)
    }
    // Settled: a further step moves nothing.
    const before = Array.from(pool.pos)
    expect(stepConfetti(pool, 0.016, t + 16)).toBe(0)
    expect(Array.from(pool.pos)).toEqual(before)
  })

  it('points a standing cannon up and a hung one down, its two tubes splayed apart', () => {
    const [ax, ay] = muzzleDirection(0, 0, 0, 2)
    const [bx, by] = muzzleDirection(0, 0, 1, 2)
    expect(ay).toBeGreaterThan(0.9)
    expect(by).toBeGreaterThan(0.9)
    expect(Math.sign(ax)).toBe(-Math.sign(bx))
    expect(muzzleDirection(0, 180, 0, 2)[1]).toBeLessThan(-0.9)
  })
})
