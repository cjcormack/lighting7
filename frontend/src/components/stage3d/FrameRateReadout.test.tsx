// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act as domAct, fireEvent, render } from '@testing-library/react'
import { act as r3fAct, createRoot, useFrame, type ReconcilerRoot, type RootState } from '@react-three/fiber'
import { FrameRateChip, FrameRateProbe, FrameRateReadout } from './FrameRateReadout'
import { createStageStats, type StageStatsStore } from './scene/stageStats'

/**
 * The readout's one unbreakable rule: it never asks for a frame. The probe
 * runs in a real R3F root on `frameloop="demand"`, with a renderer that draws nothing, and the chip is
 * mounted beside it in the DOM as `Stage3D` mounts it. Animation frames are a queue the test runs by
 * hand, so R3F's own demand loop decides whether another frame follows: one that the readout asked
 * for would keep the queue from ever emptying.
 */

/** Just enough of a `WebGLRenderer` for R3F to configure a root around; it draws nothing. */
function stubRenderer(canvas: HTMLCanvasElement) {
  return {
    domElement: canvas,
    render: () => {},
    setPixelRatio: () => {},
    setSize: () => {},
    setClearColor: () => {},
    dispose: () => {},
    forceContextLoss: () => {},
    getContext: () => ({ getExtension: () => null }),
    shadowMap: { enabled: false, type: 0, needsUpdate: false },
    xr: {
      enabled: false,
      isPresenting: false,
      addEventListener: () => {},
      removeEventListener: () => {},
      setAnimationLoop: () => {},
    },
    outputColorSpace: '',
    toneMapping: 0,
    info: { autoReset: true },
  }
}

let now = 0
let root: ReconcilerRoot<HTMLCanvasElement> | null = null
let rootState: (() => RootState) | null = null
let drawn = 0
const queue = new Map<number, FrameRequestCallback>()
let nextHandle = 1

/** Counts the frames the root draws; priority 0, so it runs in every one. */
function FrameCounter() {
  useFrame(() => {
    drawn++
  })
  return null
}

async function mountProbe(stats: StageStatsStore) {
  const canvas = document.createElement('canvas')
  root = createRoot(canvas)
  await r3fAct(async () => {
    await root!.configure({
      // The stub stands in for the renderer the root would otherwise create from a WebGL context.
      gl: stubRenderer(canvas) as unknown as NonNullable<Parameters<ReconcilerRoot<HTMLCanvasElement>['configure']>[0]>['gl'],
      frameloop: 'demand',
      size: { width: 320, height: 200, top: 0, left: 0 },
      events: undefined,
      onCreated: (state) => {
        rootState = state.get
      },
    })
    root!.render(
      <>
        <FrameCounter />
        <FrameRateProbe stats={stats} clock={() => now} />
      </>,
    )
  })
}

/** Runs the animation frames queued so far, at [t]. */
function tick(t: number) {
  now = t
  const due = [...queue.entries()]
  queue.clear()
  domAct(() => {
    for (const [, callback] of due) callback(t)
  })
}

/** Runs frames 20 ms apart until none is queued, failing if the loop never stops. */
function settle(from: number): number {
  let t = from
  for (let i = 0; i < 50; i++) {
    if (queue.size === 0) return t
    t += 20
    tick(t)
  }
  throw new Error('the canvas kept asking for frames')
}

beforeEach(() => {
  now = 0
  drawn = 0
  queue.clear()
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
  vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
    const handle = nextHandle++
    queue.set(handle, callback)
    return handle
  })
  vi.stubGlobal('cancelAnimationFrame', (handle: number) => queue.delete(handle))
})

afterEach(async () => {
  // Leave R3F's loop stopped, so the next test's first request starts it again.
  if (queue.size > 0) settle(now)
  if (root != null) await r3fAct(async () => root!.unmount())
  root = null
  rootState = null
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

describe('the frame-rate readout in a demand canvas', () => {
  it('measures the frames something else asked for, and asks for none', async () => {
    const stats = createStageStats()
    await mountProbe(stats)
    const { container } = render(<FrameRateChip stats={stats} raised={false} />)
    const chip = container.querySelector<HTMLElement>('[data-stage-frame-rate]')!
    expect(chip.textContent).toBe('idle')
    // Whatever mounting the root asked for is drawn and done before the readout is judged.
    let t = settle(0)

    // A run of thirty frames 20 ms apart: something in the scene asks for each one.
    t = 1000
    drawn = 0
    for (let i = 0; i < 30; i++) {
      rootState!().invalidate()
      t += 20
      tick(t)
    }
    expect(drawn).toBe(30)
    // Written as frames came — at most every 250 ms — and the last write saw a 20 ms run.
    expect(chip.textContent).toMatch(/fps · 20\.0 ms$/)

    // Nothing asks any more: the loop stops on the next frame, readout or no readout.
    expect(queue.size).toBeLessThanOrEqual(1)
    settle(t)
    const after = drawn
    expect(after).toBeLessThanOrEqual(31)

    // A second with nothing drawn flips it to idle from a timer, and that asks for nothing either.
    now = t + 1000
    domAct(() => {
      vi.advanceTimersByTime(1500)
    })
    expect(chip.textContent).toBe('idle')
    expect(stats.getSnapshot().frameRate).toBeNull()
    expect(queue.size).toBe(0)
    expect(drawn).toBe(after)
  })

  it('shows the end of a short burst, not its first frame', async () => {
    const stats = createStageStats()
    await mountProbe(stats)
    const { container } = render(<FrameRateChip stats={stats} raised={false} />)
    const chip = container.querySelector<HTMLElement>('[data-stage-frame-rate]')!
    settle(0)

    // Ten frames 15 ms apart after an idle spell: the first writes at once, the rest are throttled.
    const start = 5000
    for (let i = 0; i < 10; i++) {
      rootState!().invalidate()
      tick(start + i * 15)
    }
    expect(chip.textContent).toBe('1 fps')
    // The interval ends: the throttled frames land, from a timer and with no frame asked for.
    now = start + 250
    domAct(() => {
      vi.advanceTimersByTime(250)
    })
    expect(chip.textContent).toBe('10 fps · 15.0 ms')
    settle(now)
    expect(queue.size).toBe(0)
  })

  it('writes at most four times a second', async () => {
    const stats = createStageStats()
    await mountProbe(stats)
    let t = settle(0)
    const writes = vi.fn()
    stats.subscribe(writes)
    // Sixty frames over one second.
    for (let i = 0; i < 60; i++) {
      rootState!().invalidate()
      t += 1000 / 60
      tick(t)
    }
    expect(writes.mock.calls.length).toBeGreaterThan(0)
    expect(writes.mock.calls.length).toBeLessThanOrEqual(4)
  })

  it('paints slow frames amber, past the governor\'s 28 ms', () => {
    const stats = createStageStats()
    const { container } = render(<FrameRateChip stats={stats} raised={false} />)
    const chip = container.querySelector<HTMLElement>('[data-stage-frame-rate]')!
    domAct(() => stats.setFrameRate({ fps: 24, ms: 41.6 }))
    expect(chip.textContent).toBe('24 fps · 41.6 ms')
    expect(chip.dataset.slow).toBe('true')
    domAct(() => stats.setFrameRate({ fps: 58, ms: 17.2 }))
    expect(chip.dataset.slow).toBe('false')
  })
})

describe('where the chip is drawn', () => {
  const stats = createStageStats()

  it('opens Performance when clicked, one row up on a section in Edit', () => {
    const onOpen = vi.fn()
    const { container, rerender } = render(
      <FrameRateReadout stats={stats} on contextLost={false} capturing={false} raised={false} onOpen={onOpen} />,
    )
    const chip = container.querySelector<HTMLElement>('[data-stage-frame-rate]')!
    expect(chip.className).toContain('bottom-2')
    fireEvent.click(chip)
    expect(onOpen).toHaveBeenCalledOnce()
    rerender(<FrameRateReadout stats={stats} on contextLost={false} capturing={false} raised onOpen={onOpen} />)
    expect(container.querySelector<HTMLElement>('[data-stage-frame-rate]')!.className).toContain('bottom-[34px]')
  })

  it('is hidden while the context is lost', () => {
    const { container } = render(
      <FrameRateReadout stats={stats} on contextLost capturing={false} raised={false} />,
    )
    expect(container.querySelector('[data-stage-frame-rate]')).toBeNull()
  })

  it('is never drawn in a capture, nor while off', () => {
    const capture = render(<FrameRateReadout stats={stats} on contextLost={false} capturing raised={false} />)
    expect(capture.container.querySelector('[data-stage-frame-rate]')).toBeNull()
    const off = render(<FrameRateReadout stats={stats} on={false} contextLost={false} capturing={false} raised={false} />)
    expect(off.container.querySelector('[data-stage-frame-rate]')).toBeNull()
  })
})
