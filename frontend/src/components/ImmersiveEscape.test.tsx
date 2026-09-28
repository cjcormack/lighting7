// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { ImmersiveEscape } from './ImmersiveEscape'
import { isImmersive, resetImmersiveStore, setImmersive } from '@/lib/immersive'

/**
 * The way back on a live route's guard arms (busk-chrome plan D11): nothing while the app is
 * drawn, the header's own glyph while immersive — because those arms render no `ShowHeader`, and
 * an immersive window on a missing project would otherwise have no control on a touch screen.
 */
afterEach(() => {
  cleanup()
  resetImmersiveStore()
  window.sessionStorage.clear()
})

describe('ImmersiveEscape', () => {
  it('renders nothing while the app is drawn — the arms are as they were', () => {
    const { container } = render(<ImmersiveEscape />)
    expect(container).toBeEmptyDOMElement()
  })

  it('draws the glyph on a chrome row while immersive, and the glyph brings the app back', () => {
    setImmersive(true)
    const { container } = render(<ImmersiveEscape />)
    const row = container.querySelector('[data-immersive-escape]')!
    expect(row.className).toContain('h-10')
    const glyph = screen.getByRole('button', { name: 'Show the app' })
    expect(glyph).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(glyph)
    expect(isImmersive()).toBe(false)
    // …and with the fact off the row is gone again.
    expect(container.querySelector('[data-immersive-escape]')).toBeNull()
  })

  it('draws just the glyph, right-aligned, for a host that already has a chrome row', () => {
    setImmersive(true)
    const { container } = render(<ImmersiveEscape bare />)
    const host = container.querySelector('[data-immersive-escape]')!
    expect(host.tagName).toBe('SPAN')
    expect(host.className).toContain('ml-auto')
    expect(screen.getByRole('button', { name: 'Show the app' })).toBeInTheDocument()
  })
})
