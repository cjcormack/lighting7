// @vitest-environment jsdom
import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
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
})
