// @vitest-environment jsdom
import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { JOG_METRES_PER_PX } from '@/lib/stageJog'
import { StageJogPad } from './StageJog'

const edges = () => ['top', 'bottom', 'left', 'right'].map((side) => document.querySelector(`[data-jog-edge="${side}"]`)?.textContent)

describe('StageJogPad', () => {
  it('names its edges as the Plan draws the stage, short on the fixture sheet’s folded pad', () => {
    const { unmount } = render(<StageJogPad size={120} refusal={null} onBegin={() => {}} onMove={() => {}} />)
    expect(edges()).toEqual(['Upstage', 'Downstage', 'SR', 'SL'])
    unmount()
    render(<StageJogPad size={64} refusal={null} onBegin={() => {}} onMove={() => {}} />)
    expect(edges()).toEqual(['US', 'DS', 'SR', 'SL'])
  })

  it('takes the arrow keys away from the grid behind it', () => {
    const behind = vi.fn()
    render(
      <div onKeyDown={behind}>
        <StageJogPad size={120} refusal={null} onBegin={() => {}} onMove={() => {}} />
      </div>,
    )
    fireEvent.keyDown(screen.getByTestId('stage-jog-pad'), { key: 'ArrowDown' })
    expect(behind).not.toHaveBeenCalled()
  })

  it('keeps moving while a drag is held at an edge, and stops when it is let go', () => {
    vi.useFakeTimers({ toFake: ['requestAnimationFrame', 'cancelAnimationFrame', 'performance'] })
    try {
      const onMove = vi.fn()
      render(<StageJogPad size={100} refusal={null} onBegin={() => {}} onMove={onMove} />)
      const pad = screen.getByTestId('stage-jog-pad')
      fireEvent.pointerDown(pad, { pointerId: 1, button: 0, clientX: 100, clientY: 100 })
      fireEvent.pointerMove(pad, { pointerId: 1, clientX: 180, clientY: 100 })
      expect(onMove).toHaveBeenLastCalledWith(50 * JOG_METRES_PER_PX, 0)
      vi.advanceTimersByTime(500)
      const held = onMove.mock.lastCall![0] as number
      expect(held).toBeGreaterThan(50 * JOG_METRES_PER_PX + 0.5)
      fireEvent.pointerUp(pad, { pointerId: 1, clientX: 180, clientY: 100 })
      const calls = onMove.mock.calls.length
      vi.advanceTimersByTime(500)
      expect(onMove).toHaveBeenCalledTimes(calls)
    } finally {
      vi.useRealTimers()
    }
  })

  it('carries a stalled frame no further than a tenth of a second, and a blur ends the drag', () => {
    const frames: FrameRequestCallback[] = []
    vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => frames.push(callback))
    vi.stubGlobal('cancelAnimationFrame', () => {})
    const now = vi.spyOn(performance, 'now').mockReturnValue(0)
    try {
      const onMove = vi.fn()
      render(<StageJogPad size={100} refusal={null} onBegin={() => {}} onMove={onMove} />)
      const pad = screen.getByTestId('stage-jog-pad')
      fireEvent.pointerDown(pad, { pointerId: 1, button: 0, clientX: 100, clientY: 100 })
      fireEvent.pointerMove(pad, { pointerId: 1, clientX: 150, clientY: 100 })
      const edge = onMove.mock.lastCall![0] as number
      // The first frame lands 2 s late: it carries the edge speed (1.5 m/s) for 0.1 s, not for 2.
      frames.shift()!(2000)
      expect((onMove.mock.lastCall![0] as number) - edge).toBeCloseTo(0.15)
      fireEvent.blur(window)
      const calls = onMove.mock.calls.length
      frames.shift()?.(2016)
      expect(onMove).toHaveBeenCalledTimes(calls)
    } finally {
      now.mockRestore()
      vi.unstubAllGlobals()
    }
  })
})
