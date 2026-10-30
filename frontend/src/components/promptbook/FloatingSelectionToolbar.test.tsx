// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { FloatingSelectionToolbar } from './FloatingSelectionToolbar'

// The bar measures itself with `ResizeObserver`, which jsdom lacks.
vi.stubGlobal(
  'ResizeObserver',
  class {
    observe() {}
    unobserve() {}
    disconnect() {}
  },
)

afterEach(cleanup)

const noop = () => {}

describe('FloatingSelectionToolbar', () => {
  it('offers Record cue only while there is something to record', () => {
    render(<FloatingSelectionToolbar anchor={{ x: 100, y: 100 }} onAnchor={noop} onCut={noop} onNote={noop} />)
    expect(screen.queryByRole('button', { name: 'Record cue' })).toBeNull()
    cleanup()

    const onRecord = vi.fn()
    render(
      <FloatingSelectionToolbar
        anchor={{ x: 100, y: 100 }}
        onAnchor={noop}
        onRecord={onRecord}
        onCut={noop}
        onNote={noop}
      />,
    )
    fireEvent.click(screen.getByRole('button', { name: 'Record cue' }))
    expect(onRecord).toHaveBeenCalledOnce()
  })
})
