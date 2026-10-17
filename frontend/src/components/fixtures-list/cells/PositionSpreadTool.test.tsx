// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'

/**
 * *Spread…* in the position cell's footer (venue-visit plan S12): it opens the fan through the
 * container's opener, is absent where there is none, and refuses Output, a palette layer and a
 * single head — but not a Look layer, which a fan lands in.
 */

const state = vi.hoisted(() => ({ scope: { kind: 'local' } as unknown, template: null as unknown }))
vi.mock('@/components/programmer/ProgrammerScope', () => ({ useProgrammerScope: () => state.scope }))
vi.mock('@/components/programmer/FocusedTemplateLayer', () => ({ useFocusedTemplateLayer: () => state.template }))

const { PositionFanContext, PositionSpreadTool } = await import('./PositionSpreadTool')

function draw(openFan: (() => void) | null, heads = 3, onOpened = vi.fn()) {
  render(
    <PositionFanContext.Provider value={openFan}>
      <PositionSpreadTool heads={heads} onOpened={onOpened} />
    </PositionFanContext.Provider>,
  )
  return onOpened
}

const button = () => screen.getByRole('button', { name: 'Spread…' })

afterEach(() => {
  cleanup()
  state.scope = { kind: 'local' }
  state.template = null
})

describe('PositionSpreadTool', () => {
  it('opens the fan and closes the cell’s editor', () => {
    const openFan = vi.fn()
    const onOpened = draw(openFan)
    fireEvent.click(button())
    expect(openFan).toHaveBeenCalledTimes(1)
    expect(onOpened).toHaveBeenCalledTimes(1)
  })

  it('is not drawn where nothing can open a fan', () => {
    draw(null)
    expect(screen.queryByRole('button', { name: 'Spread…' })).toBeNull()
  })

  it('refuses one head, Output and a palette layer, and stays live in a Look layer', () => {
    draw(vi.fn(), 1)
    expect(button()).toBeDisabled()
    expect(button()).toHaveAttribute('title', expect.stringContaining('two or more heads'))
    cleanup()
    state.scope = { kind: 'output' }
    draw(vi.fn())
    expect(button()).toBeDisabled()
    expect(button()).toHaveAttribute('title', expect.stringContaining('Output is a read of the cook'))
    cleanup()
    state.scope = { kind: 'layer', layerId: 3 }
    state.template = { templateId: 1 }
    draw(vi.fn())
    expect(button()).toHaveAttribute('title', expect.stringContaining('applies a palette'))
    cleanup()
    state.template = null
    draw(vi.fn())
    expect(button()).toBeEnabled()
  })
})
