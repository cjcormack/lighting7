import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AimResponse } from '../store/programmerOps'
import { JogQueue, resetJogQueues, type JogSend } from './stageJog'

/** A send whose answers the test releases by hand, so what is in flight is in the test's control. */
function harness(bases: JogSend['base'] = [], floorMs = 0) {
  const sent: JogSend[] = []
  const answers: ((answer: AimResponse) => void)[] = []
  const onAnswer = vi.fn()
  const queue = new JogQueue(
    (step) => {
      sent.push(step)
      return new Promise((resolve) => answers.push(resolve))
    },
    () => bases ?? [],
    onAnswer,
    floorMs,
  )
  const answer = async (response: AimResponse) => {
    answers.shift()!(response)
    await new Promise((resolve) => setTimeout(resolve, 0))
  }
  return { queue, sent, answer, onAnswer }
}

const spot = { type: 'fixture' as const, key: 'spot' }
const wash = { type: 'fixture' as const, key: 'wash' }
const BASE = [{ target: spot, value: '127,127', panFine: 128, tiltFine: 128 }]
const written = (panDeg: number, target = spot) => [{ target, value: '130,127', panDeg, tiltDeg: 105 }]

beforeEach(() => resetJogQueues())
afterEach(() => vi.useRealTimers())

describe('JogQueue', () => {
  it('keeps one request in flight and sends the latest offset when it lands', async () => {
    const { queue, sent, answer } = harness()
    queue.begin()
    queue.move(0.1, 0)
    queue.move(0.2, 0)
    queue.move(0.3, 0.1)
    expect(sent).toEqual([{ dx: 0.1, dy: 0 }])
    await answer({ written: written(272), base: BASE })
    // The two moves in between were replaced by the newest; it carries the anchor and the last answer.
    expect(sent).toHaveLength(2)
    expect(sent[1]).toEqual({ dx: 0.3, dy: 0.1, base: BASE, near: [{ target: spot, panDeg: 272, tiltDeg: 105 }] })
  })

  it('measures every frame of a gesture from its start, and reads the heads again for a new one', async () => {
    const { queue, sent, answer } = harness()
    queue.begin()
    queue.move(0.5, 0)
    await answer({ written: written(275), base: BASE })
    queue.move(1, 0)
    await answer({ written: written(280), base: [{ target: spot, value: '200,127' }] })
    expect(sent[1].base).toEqual(BASE)
    queue.begin()
    queue.move(0.25, 0)
    expect(sent[2]).toEqual({ dx: 0.25, dy: 0 })
  })

  it('lands the offset a drag was let go on, though a new press comes before the desk answers', async () => {
    const { queue, sent, answer } = harness()
    queue.begin()
    queue.move(0.5, 0)
    queue.move(1.5, 0)
    queue.begin()
    queue.move(0, 0.25)
    await answer({ written: written(275), base: BASE })
    // The first gesture's last offset goes first, from its own anchor; the new gesture waits for it.
    expect(sent[1]).toEqual({ dx: 1.5, dy: 0, base: BASE, near: [{ target: spot, panDeg: 275, tiltDeg: 105 }] })
    await answer({ written: written(290), base: BASE })
    expect(sent[2]).toEqual({ dx: 0, dy: 0.25 })
  })

  it('anchors a head that joins mid-gesture, and keeps a skipped head on its side', async () => {
    const { queue, sent, answer } = harness()
    queue.begin()
    queue.move(0.5, 0)
    await answer({ written: written(275), base: BASE })
    queue.move(1, 0)
    // The wash joined the selection; this frame skipped the spot.
    const washBase = { target: wash, value: '100,100' }
    await answer({ written: written(200, wash), base: [BASE[0], washBase] })
    queue.move(1.5, 0)
    expect(sent[2].base).toEqual([BASE[0], washBase])
    expect(sent[2].near).toEqual([
      { target: spot, panDeg: 275, tiltDeg: 105 },
      { target: wash, panDeg: 200, tiltDeg: 105 },
    ])
  })

  it('starts a draft host from its rows, and waits for the last gesture to land first', async () => {
    const rows = [{ target: spot, value: '100,90' }]
    const { queue, sent, answer, onAnswer } = harness(rows)
    queue.begin()
    queue.move(1, 0)
    expect(sent[0].base).toEqual(rows)
    queue.begin()
    queue.move(0, 1)
    // The new gesture's first request waits: the draft has not had the first one's answer yet.
    expect(sent).toHaveLength(1)
    await answer({ written: written(275), base: rows })
    expect(onAnswer).toHaveBeenCalledTimes(1)
    expect(sent[1]).toEqual({ dx: 0, dy: 1, base: rows })
  })

  it('holds one request in flight across pads', async () => {
    const one = harness()
    const two = harness()
    one.queue.begin()
    one.queue.move(1, 0)
    two.queue.begin()
    two.queue.move(0, 1)
    expect(two.sent).toHaveLength(0)
    await one.answer({ written: written(275), base: BASE })
    expect(two.sent).toEqual([{ dx: 0, dy: 1 }])
  })

  it('sends no faster than its floor', async () => {
    vi.useFakeTimers()
    const { queue, sent, answer } = harness([], 50)
    queue.begin()
    queue.move(0.1, 0)
    const landed = answer({ written: written(272), base: BASE })
    await vi.advanceTimersByTimeAsync(1)
    await landed
    queue.move(0.2, 0)
    expect(sent).toHaveLength(1)
    await vi.advanceTimersByTimeAsync(50)
    expect(sent).toHaveLength(2)
  })
})
