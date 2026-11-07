// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import { Slider } from './slider'

class NoopResizeObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
}
vi.stubGlobal('ResizeObserver', NoopResizeObserver)

afterEach(cleanup)

/** The thumb is what a screen reader lands on, so it carries the name and the value text. */
describe('Slider', () => {
  it('puts a single thumb’s aria-label and aria-valuetext on the thumb', () => {
    render(<Slider aria-label="Speed" aria-valuetext="×½" value={[375]} min={0} max={1000} />)
    const thumb = screen.getByRole('slider', { name: 'Speed' })
    expect(thumb.getAttribute('aria-valuetext')).toBe('×½')
  })
})
